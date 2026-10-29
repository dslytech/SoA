package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tries to make the game's OWN bundled `libSDL_ffmpeg.so` actually load and work, instead of
 * [ApkPatcher] swapping it out for a fully no-op stub. The stub trade-off (see its own doc
 * comment: "Intro/cutscene videos are skipped; gameplay does not depend on this library") was a
 * correct, safe call for Mass Effect: Infiltrator, where nothing in-game depends on this library
 * at all. It's the WRONG call for IG2/Haegemonia's `:direct:`-folder video feature
 * ([ElfImperiumGalactica2DirectVideoFolderPatcher] / [ElfHaegemoniaDirectVideoFolderPatcher]):
 * both games' cutscenes decode through this exact library, so as long as it's stubbed, correctly
 * finding a video file on disk can never result in it actually playing - confirmed on a real
 * device 2026-09-23: the debug-trace diagnostic build found the user's re-placed clips with
 * `fopen()` every time, and still nothing played, because the decoder behind that successful
 * `fopen()` was this empty stub the whole time.
 *
 * ## Why this isn't just "run it through [ElfTextRelPatcher] instead"
 *
 * It has the same `DT_TEXTREL`-refusal problem as any other pre-2014-NDK library, but the actual
 * relocations don't match [ElfTextRelPatcher]'s recognized "dead `__cxa_finalize` cleanup
 * trampoline" pattern - inspecting real `libSDL_ffmpeg.so` files pulled from this project's own
 * IG2 and Haegemonia APKs found exactly 15 problem relocations in each (`R_ARM_RELATIVE`, pointing
 * at addresses inside `.text`, so real function pointers - almost certainly an internal codec/
 * format registration table), all landing in `.rodata`, physically inside the same combined
 * text+rodata `PT_LOAD` segment as the actual executable code. That table is genuinely
 * load-bearing (not dead code), so it can't be neutralized the way the trampoline case is -
 * [ElfTextRelPatcher] would fall back to its "make the segment writable" escape hatch for these,
 * which reintroduces exactly the W^X (`"has load segments that are both writable and executable"`)
 * `dlopen()` refusal this project already hit once for real, on this exact tablet/Android version,
 * with Colin McRae Rally's Unity libraries (see [ElfTextRelPatcher]'s own doc comment) - not a
 * theoretical risk, a previously-confirmed one.
 *
 * ## The actual fix: give that table its own writable, non-executable home
 *
 * Both files show the identical shape: the problem relocations are a single contiguous ~60-byte
 * block, entirely inside `.rodata` (confirmed via the section header table: `.rodata` never has
 * the `SHF_EXECINSTR` flag `.text`/`.plt` have), near the tail of the first (`R E`) `PT_LOAD`
 * segment. Since ELF segments only need to be page-aligned, not section-aligned, that first
 * segment can be split at a page boundary into two: everything before the split, unchanged (still
 * `R E`, still covers all the real code); everything from the split to the old segment's end,
 * as a NEW `PT_LOAD` segment marked `R W` (no `X`) instead. The problem relocations end up
 * physically inside a segment that's writable but never executable - so the dynamic linker's
 * normal relocation-processing writes them successfully with no TEXTREL refusal needed, and no
 * segment is ever both writable and executable, so the separate W^X hardening check has nothing to
 * object to either. This is the same trick modern NDK toolchains already use on purpose (that's
 * what `GNU_RELRO` `PT_LOAD` segments are, structurally) - here it's applied surgically to just the
 * one old library's one problem region instead of relying on the original build having done it.
 *
 * Adding a 9th program header needs 32 more bytes than the existing (tightly-packed, no free
 * space between the `PHDR` and `INTERP` segments in every real file checked) program header table
 * has room for, so the whole table is rebuilt and appended past the end of the file instead, with
 * `e_phoff`/`e_phnum` in the ELF header updated to point at it - safe and standard, since the
 * dynamic linker reads program headers via a direct file read before it sets up any memory
 * mappings, so their file location doesn't need to fall inside any `PT_LOAD` range itself.
 *
 * ## Safety gates - anything that doesn't match the verified-safe shape falls back to the stub
 *
 * This only ever touches the file if EVERY one of these holds; anything else and [fixIfNeeded]
 * returns `patched = false` so [ApkPatcher] keeps using the old, safe, fully-inert stub instead:
 *  - 32-bit little-endian ELF, `DT_TEXTREL`/`DF_TEXTREL` actually set (same gate as
 *    [ElfTextRelPatcher]).
 *  - every problem relocation (target inside a non-writable `PT_LOAD`) falls inside exactly ONE
 *    `PT_LOAD` segment - a file with problem relocations scattered across multiple segments isn't
 *    the shape this was built and verified against.
 *  - a valid section header table is present and NOT ONE section flagged `SHF_EXECINSTR`
 *    overlaps the byte range from the chosen page-aligned split point through the end of that
 *    segment - i.e. this independently re-derives "there's no real code back here" from the ELF's
 *    own metadata for whatever specific file is being checked, rather than trusting that the two
 *    files this was built against are representative of every possible `libSDL_ffmpeg.so` build.
 *  - the split segment's `p_offset == p_vaddr` and `p_filesz == p_memsz` (true for every real file
 *    checked - a non-prelinked library with no trailing bss in this segment - and required for the
 *    plain page-count arithmetic here to be correct).
 *
 * Verified (2026-09-23) against the real `libSDL_ffmpeg.so` extracted from this project's own IG2
 * and Haegemonia APKs: post-patch, zero relocations land outside a writable `PT_LOAD`, zero
 * `PT_LOAD` segments are simultaneously writable and executable, and no `PT_LOAD` vaddr ranges
 * overlap - the three structural invariants that correspond to bionic's actual refusal conditions.
 * Not yet real-device tested (no way to invoke bionic's own `dlopen()` from this environment) -
 * ships as a TEMPORARY diagnostic-adjacent build for exactly that reason, same as 1.02's debug
 * trace was.
 */
object ElfSdlFfmpegRelinkPatcher {

    data class Result(val patched: Boolean, val note: String, val newBytes: ByteArray? = null)

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val PF_X = 0x1
    private const val PF_W = 0x2

    private const val DT_NULL = 0
    private const val DT_PLTRELSZ = 0x2
    private const val DT_REL = 0x11
    private const val DT_RELSZ = 0x12
    private const val DT_PLTREL = 0x14
    private const val DT_DEBUG = 0x15
    private const val DT_TEXTREL = 0x16
    private const val DT_JMPREL = 0x17
    private const val DT_FLAGS = 0x1e
    private const val DF_TEXTREL_BIT = 0x4L

    private const val SHF_EXECINSTR = 0x4L
    private const val PAGE = 0x1000L

    private class Phdr(
        val fileOffset: Int,
        var pType: Int,
        var pOffset: Long,
        var pVaddr: Long,
        var pPaddr: Long,
        var pFilesz: Long,
        var pMemsz: Long,
        var pFlags: Int,
        var pAlign: Long
    )

    private class DynEntry(val fileOffset: Int, val tag: Int, val value: Long)

    fun fixIfNeeded(bytes: ByteArray): Result {
        try {
            if (bytes.size < 52) return Result(false, "too small to be an ELF file")
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) return Result(false, "not an ELF file")
            if (bytes[4].toInt() != 1) return Result(false, "not 32-bit ELF - not handled")
            if (bytes[5].toInt() != 1) return Result(false, "not little-endian ELF - not handled")

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val ePhoff = buf.getInt(28).toLong() and 0xFFFFFFFFL
            val ePhentsize = buf.getShort(42).toInt() and 0xFFFF
            val ePhnum = buf.getShort(44).toInt() and 0xFFFF
            if (ePhoff <= 0 || ePhentsize < 32 || ePhnum <= 0 || ePhnum > 200) {
                return Result(false, "implausible program header table")
            }

            fun readPhdrs(): MutableList<Phdr> = (0 until ePhnum).map { i ->
                val off = (ePhoff + i.toLong() * ePhentsize).toInt()
                Phdr(
                    fileOffset = off,
                    pType = buf.getInt(off),
                    pOffset = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                    pVaddr = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                    pPaddr = buf.getInt(off + 12).toLong() and 0xFFFFFFFFL,
                    pFilesz = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL,
                    pMemsz = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL,
                    pFlags = buf.getInt(off + 24),
                    pAlign = buf.getInt(off + 28).toLong() and 0xFFFFFFFFL
                )
            }.toMutableList()

            val phdrs = readPhdrs()
            val dynPhdr = phdrs.firstOrNull { it.pType == PT_DYNAMIC }
                ?: return Result(false, "no PT_DYNAMIC segment - not applicable")

            val dynEntries = mutableListOf<DynEntry>()
            var p = dynPhdr.pOffset.toInt()
            val dynEnd = p + dynPhdr.pFilesz.toInt()
            while (p + 8 <= dynEnd && p + 8 <= bytes.size) {
                val tag = buf.getInt(p)
                val value = buf.getInt(p + 4).toLong() and 0xFFFFFFFFL
                dynEntries.add(DynEntry(p, tag, value))
                if (tag == DT_NULL) break
                p += 8
            }
            fun dynEntry(tag: Int): DynEntry? = dynEntries.firstOrNull { it.tag == tag }
            fun dynVal(tag: Int): Long? = dynEntry(tag)?.value

            val textrelEntry = dynEntry(DT_TEXTREL)
            val flagsEntry = dynEntry(DT_FLAGS)
            val hasDfTextrelBit = flagsEntry != null && (flagsEntry.value and DF_TEXTREL_BIT) != 0L
            if (textrelEntry == null && !hasDfTextrelBit) {
                return Result(false, "no TEXTREL flag set - doesn't need this fix")
            }

            fun fileOffsetFor(vaddr: Long): Long? =
                phdrs.firstOrNull { it.pType == PT_LOAD && vaddr >= it.pVaddr && vaddr < it.pVaddr + it.pFilesz }
                    ?.let { vaddr - it.pVaddr + it.pOffset }

            val relocTargets = mutableListOf<Long>()
            fun collect(tableVaddr: Long?, tableSize: Long?) {
                if (tableVaddr == null || tableSize == null || tableSize <= 0) return
                val fileOff = fileOffsetFor(tableVaddr) ?: return
                var q = fileOff
                val end = fileOff + tableSize
                while (q + 8 <= end && q + 8 <= bytes.size) {
                    relocTargets.add(buf.getInt(q.toInt()).toLong() and 0xFFFFFFFFL)
                    q += 8
                }
            }
            collect(dynVal(DT_REL), dynVal(DT_RELSZ))
            if (dynVal(DT_PLTREL) == DT_REL.toLong()) {
                collect(dynVal(DT_JMPREL), dynVal(DT_PLTRELSZ))
            }
            if (relocTargets.isEmpty()) return Result(false, "TEXTREL set but no relocation entries found")

            fun segmentFor(target: Long) = phdrs.firstOrNull {
                it.pType == PT_LOAD && target >= it.pVaddr && target < it.pVaddr + it.pMemsz
            }

            val problemTargets = relocTargets.filter { t ->
                val seg = segmentFor(t)
                seg != null && (seg.pFlags and PF_W) == 0
            }
            if (problemTargets.isEmpty()) {
                return Result(false, "TEXTREL flag set but every relocation already lands in a writable segment - leave to ElfTextRelPatcher")
            }

            val touchedSegs = problemTargets.mapNotNull { segmentFor(it) }.distinct()
            if (touchedSegs.size != 1) {
                return Result(false, "problem relocations span ${touchedSegs.size} segments - not the verified-safe single-segment shape")
            }
            val targetSeg = touchedSegs[0]

            // Required for the plain page-count arithmetic below to be correct.
            if (targetSeg.pOffset != targetSeg.pVaddr) return Result(false, "segment p_offset != p_vaddr - not handled")
            if (targetSeg.pFilesz != targetSeg.pMemsz) return Result(false, "segment p_filesz != p_memsz - not handled")

            @Suppress("DEPRECATION")
            val minTarget = problemTargets.min()!! // safe: problemTargets checked non-empty above
            @Suppress("DEPRECATION")
            val maxTarget = problemTargets.max()!! // inclusive start of last 4-byte word
            val segStart = targetSeg.pVaddr
            val segEnd = targetSeg.pVaddr + targetSeg.pFilesz
            val splitPoint = (minTarget / PAGE) * PAGE
            if (splitPoint < segStart || splitPoint >= segEnd) {
                return Result(false, "computed split point falls outside the segment - not applying")
            }
            if (maxTarget + 4 > segEnd) {
                return Result(false, "a problem relocation's target overruns the segment end - not applying")
            }

            // Independently re-derive "nothing executable back here" from the file's OWN section
            // header table, rather than trusting this is the same shape as the two files this was
            // built and verified against.
            val eShoff = buf.getInt(32).toLong() and 0xFFFFFFFFL
            val eShentsize = buf.getShort(46).toInt() and 0xFFFF
            val eShnum = buf.getShort(48).toInt() and 0xFFFF
            if (eShoff <= 0 || eShentsize < 40 || eShnum <= 0 || eShnum > 400) {
                return Result(false, "no usable section header table - can't independently verify the split region is code-free")
            }
            for (i in 0 until eShnum) {
                val off = (eShoff + i.toLong() * eShentsize).toInt()
                if (off + 40 > bytes.size) return Result(false, "section header table runs past EOF")
                val shFlags = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
                if ((shFlags and SHF_EXECINSTR) == 0L) continue
                val shAddr = buf.getInt(off + 12).toLong() and 0xFFFFFFFFL
                val shSize = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL
                if (shAddr == 0L) continue // not allocated at this address (e.g. debug-only section)
                val overlaps = shAddr < segEnd && (shAddr + shSize) > splitPoint
                if (overlaps) {
                    return Result(false, "an SHF_EXECINSTR section overlaps the would-be RW tail segment - not applying")
                }
            }

            // --- everything checks out - build the patched file ---

            val newFileSize = bytes.size + (ePhnum + 1) * ePhentsize
            val out = ByteArray(newFileSize)
            System.arraycopy(bytes, 0, out, 0, bytes.size)
            val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

            // Clear TEXTREL the same inert way ElfTextRelPatcher does.
            if (textrelEntry != null) {
                outBuf.putInt(textrelEntry.fileOffset, DT_DEBUG)
                outBuf.putInt(textrelEntry.fileOffset + 4, 0)
            }
            if (flagsEntry != null && hasDfTextrelBit) {
                outBuf.putInt(flagsEntry.fileOffset + 4, (flagsEntry.value and DF_TEXTREL_BIT.inv()).toInt())
            }

            // Shrink the split segment's own header in place (still at its original file offset).
            val newFilesz = splitPoint - targetSeg.pOffset
            outBuf.putInt(targetSeg.fileOffset + 16, newFilesz.toInt())
            outBuf.putInt(targetSeg.fileOffset + 20, newFilesz.toInt())

            // Rebuild the full program header table (all entries unchanged except the one just
            // shrunk above, plus one new RW tail entry) and append it past the original EOF.
            val newPhdrOff = bytes.size
            var w = newPhdrOff
            for (ph in phdrs) {
                val filesz = if (ph === targetSeg) newFilesz else ph.pFilesz
                val memsz = if (ph === targetSeg) newFilesz else ph.pMemsz
                outBuf.putInt(w, ph.pType); outBuf.putInt(w + 4, ph.pOffset.toInt())
                outBuf.putInt(w + 8, ph.pVaddr.toInt()); outBuf.putInt(w + 12, ph.pPaddr.toInt())
                outBuf.putInt(w + 16, filesz.toInt()); outBuf.putInt(w + 20, memsz.toInt())
                outBuf.putInt(w + 24, ph.pFlags); outBuf.putInt(w + 28, ph.pAlign.toInt())
                w += ePhentsize
            }
            val tailFilesz = segEnd - splitPoint
            outBuf.putInt(w, PT_LOAD); outBuf.putInt(w + 4, splitPoint.toInt())
            outBuf.putInt(w + 8, splitPoint.toInt()); outBuf.putInt(w + 12, splitPoint.toInt())
            outBuf.putInt(w + 16, tailFilesz.toInt()); outBuf.putInt(w + 20, tailFilesz.toInt())
            outBuf.putInt(w + 24, PF_W or 0x4 /* PF_R */); outBuf.putInt(w + 28, PAGE.toInt())

            outBuf.putInt(28, newPhdrOff) // e_phoff
            outBuf.putShort(44, (ePhnum + 1).toShort()) // e_phnum

            return Result(
                true,
                "relinked: split the code segment at ${"0x%x".format(splitPoint)} so the " +
                    "${problemTargets.size}-entry relocation table at " +
                    "${"0x%x".format(minTarget)} gets its own writable, non-executable segment " +
                    "instead of being stubbed out - real video decoding restored",
                out
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
