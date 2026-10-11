package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixes the "has text relocations" `dlopen()` refusal (`UnsatisfiedLinkError`) that modern
 * Android's dynamic linker throws for any native library built the old way (pre ~2014 NDK,
 * without `-fPIC`/position-independent code) once the app's `targetSdkVersion` is 23+.
 *
 * This is a DIFFERENT problem from the `libSDL_ffmpeg.so` fix in [ApkPatcher] (which just
 * swaps in a clean stub, since that library is a non-essential video helper): here the library
 * in question can be the game's *own* engine code (e.g. a Unity `libmain.so`/`libunity.so`),
 * which obviously can't be stubbed out - it has to actually keep working. So instead of
 * replacing the file, this rewrites a handful of bytes in its ELF headers/code in place.
 *
 * ## Background: why bionic refuses these libraries at all
 *
 * "Text relocation" means: this `.so` has load-time fixups (`R_ARM_RELATIVE` / `R_ARM_ABS32`
 * entries in `.rel.dyn`) whose target address falls inside a segment that's mapped **read-only
 * + executable** (the code segment), rather than the normal read-write data segment. Applying
 * those fixups means writing into supposedly-read-only executable memory - which used to be
 * allowed (the linker would temporarily `mprotect()` the segment writable, patch it, then set
 * it back to read-only), but Android 6.0+ refuses to do that at all once `targetSdkVersion` is
 * 23 or higher, and just aborts the whole library load instead (`DT_TEXTREL` / `DF_TEXTREL`).
 *
 * ## First attempt (superseded) and why it wasn't enough
 *
 * The obvious fix is: mark the offending segment writable (`PF_W`) and clear the TEXTREL flag,
 * so bionic no longer refuses and the relocation writes themselves are safe. That version of
 * this file shipped and was tested on a real device against Colin McRae Rally - it DID clear the
 * original "has text relocations" error, but bionic on that device (Android 16) turned out to
 * have a SECOND, independent, unconditional hardening check that isn't gated by
 * `targetSdkVersion` at all: it refuses to `dlopen()` any ELF that has even one `PT_LOAD` segment
 * that is simultaneously writable AND executable ("W^X"), full stop. Confirmed via real
 * `adb logcat`: `dlopen failed: "...libmain.so" has load segments that are both writable and
 * executable`. So permanently marking the code segment writable is fundamentally incompatible
 * with this newer check - a real dead end, not just a tuning problem.
 *
 * ## Current approach: don't make anything writable - remove the need for the relocation
 *
 * Disassembling the actual bytes at every remaining problem-relocation's target (across
 * `libmain.so`, `libmono.so`, `libunity.so` from a real Colin McRae Rally APK, using
 * `arm-linux-gnueabi-objdump`) showed the same tiny, universal pattern every time, with no
 * exceptions: a pre-2014-NDK-toolchain-generated C++ runtime cleanup trampoline, byte-identical
 * across all three files:
 * ```
 * add r0, pc, #4        ; E2 8F 00 04 -> LE bytes 04 00 8F E2
 * ldr r0, [r0]          ; E5 90 00 00 -> LE bytes 00 00 90 E5
 * b   __cxa_finalize@plt ; unconditional branch, top (MSB/last) byte of the LE word = 0xEA
 * <literal: DSO handle pointer>   <- this word IS the relocation target
 * [<literal: &__cxa_atexit>]      <- sometimes a second, ABS32 relocation right after
 * ```
 * This loads a per-library "DSO handle" pointer and tail-calls `__cxa_finalize(handle)` - the
 * standard toolchain boilerplate for running static C++ destructors when a shared library is
 * unloaded via `dlclose()`. An Android `NativeActivity` game process essentially never
 * `dlclose()`s its own engine library - the process just gets killed by the OS on exit - so this
 * trampoline never actually runs in practice, and is safe to disable outright.
 *
 * The fix, for every problem relocation whose target is immediately preceded by this exact
 * pattern:
 *  1. Overwrite the trampoline's first instruction with `bx lr` (`1E FF 2F E1`) - an immediate
 *     return. The CPU now returns to the caller instantly and never reaches the literal-pool
 *     word(s) that follow, so what those words contain no longer matters at all.
 *  2. "Void" every relocation entry that targets one of those now-dead literal words, by zeroing
 *     its `r_info` field - `Elf32_Rel { r_offset, r_info }`, and `r_info = 0` decodes to
 *     relocation type `R_ARM_NONE` (symbol 0), a standard, well-defined "do nothing" entry. The
 *     loader still walks over it, just performs no write.
 *  3. Once every problem relocation has been neutralized this way, clear `DT_TEXTREL`/
 *     `DF_TEXTREL` as before - bionic no longer sees any reason to refuse, and no `PT_LOAD`
 *     segment ever needs `PF_W`, so the newer W^X check has nothing to object to either.
 *
 * If a problem relocation is ever found whose preceding bytes do NOT match this recognized
 * trampoline (not seen in any of the three real files this was built against, but a different
 * game could plausibly ship something else old and exotic), this falls back to the old
 * make-the-segment-writable approach for just that one segment - which fixes the original
 * TEXTREL refusal but reintroduces the W^X risk for that specific file, so [Result.note] calls
 * that case out explicitly as a "please retest this one" flag rather than silently trusting it.
 */
object ElfTextRelPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val PF_W = 0x2

    private const val DT_NULL = 0
    private const val DT_PLTRELSZ = 0x2
    private const val DT_REL = 0x11
    private const val DT_RELSZ = 0x12
    private const val DT_RELENT = 0x13
    private const val DT_PLTREL = 0x14
    private const val DT_DEBUG = 0x15
    private const val DT_TEXTREL = 0x16
    private const val DT_JMPREL = 0x17
    private const val DT_FLAGS = 0x1e
    private const val DF_TEXTREL_BIT = 0x4L

    // "add r0, pc, #4" then "ldr r0, [r0]" - the first 8 bytes of the recognized __cxa_finalize
    // cleanup trampoline, little-endian, byte-identical across every affected library seen.
    private val TRAMPOLINE_PROLOGUE = byteArrayOf(
        0x04, 0x00, 0x8F.toByte(), 0xE2.toByte(),
        0x00, 0x00, 0x90.toByte(), 0xE5.toByte()
    )
    private val TRAMPOLINE_BRANCH_TOP_BYTE = 0xEA.toByte() // AL-condition unconditional B
    private val BX_LR = byteArrayOf(0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte())

    // Trampoline body: head+0..+11 = the 3 instructions above; head+12 onward = literal pool
    // (the DSO handle pointer, sometimes followed by a second literal). head+40 is a generous,
    // confirmed-safe upper bound for "still inside this trampoline's dead literal pool" across
    // every real file this was checked against - real code never starts before head+16, and the
    // furthest observed relocation lands at head+36.
    private const val TRAMPOLINE_DEAD_BYTES = 40

    private class Phdr(
        val fileOffset: Int,
        val pType: Int,
        val pOffset: Long,
        val pVaddr: Long,
        val pFilesz: Long,
        val pMemsz: Long,
        val pFlags: Int
    )

    private class DynEntry(val fileOffset: Int, val tag: Int, val value: Long)

    // One Elf32_Rel table entry: fileOffset is where the 8-byte { r_offset, r_info } struct
    // itself lives (so we can zero r_info to void it later), target is its r_offset value (the
    // vaddr it would write to).
    private class RelEntry(val fileOffset: Long, val target: Long)

    /**
     * Patches [bytes] IN PLACE (it's a mutable-backing-array wrap, not a copy) if it's a 32-bit
     * little-endian ELF with a text-relocation flag AND at least one relocation that actually
     * targets a non-writable segment. Anything else (not an ELF, already fine, 64-bit/big-endian
     * ELF, malformed/truncated) is left completely untouched and reported as not patched -
     * callers should keep the original bytes whenever [Result.patched] is false.
     */
    fun fixIfNeeded(bytes: ByteArray): Result {
        try {
            if (bytes.size < 52) return Result(false, "too small to be an ELF file")
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) {
                return Result(false, "not an ELF file")
            }
            if (bytes[4].toInt() != 1) return Result(false, "not 32-bit ELF (ELFCLASS != 1) - not handled")
            if (bytes[5].toInt() != 1) return Result(false, "not little-endian ELF (ELFDATA != 1) - not handled")

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val ePhoff = buf.getInt(28).toLong() and 0xFFFFFFFFL
            val ePhentsize = buf.getShort(42).toInt() and 0xFFFF
            val ePhnum = buf.getShort(44).toInt() and 0xFFFF
            if (ePhoff <= 0 || ePhentsize < 32 || ePhnum <= 0 || ePhnum > 200) {
                return Result(false, "implausible program header table - not touching this file")
            }

            val phdrs = (0 until ePhnum).map { i ->
                val off = (ePhoff + i.toLong() * ePhentsize).toInt()
                Phdr(
                    fileOffset = off,
                    pType = buf.getInt(off),
                    pOffset = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                    pVaddr = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                    pFilesz = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL,
                    pMemsz = buf.getInt(off + 20).toLong() and 0xFFFFFFFFL,
                    pFlags = buf.getInt(off + 24)
                )
            }

            val dynPhdr = phdrs.firstOrNull { it.pType == PT_DYNAMIC }
                ?: return Result(false, "no PT_DYNAMIC segment (static executable?) - not applicable")

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
            fun dynVal(tag: Int): Long? = dynEntries.firstOrNull { it.tag == tag }?.value

            val textrelEntry = dynEntries.firstOrNull { it.tag == DT_TEXTREL }
            val flagsEntry = dynEntries.firstOrNull { it.tag == DT_FLAGS }
            val hasDfTextrelBit = flagsEntry != null && (flagsEntry.value and DF_TEXTREL_BIT) != 0L
            if (textrelEntry == null && !hasDfTextrelBit) {
                return Result(false, "no TEXTREL flag set - doesn't need this fix")
            }

            // vaddr -> file offset, via whichever PT_LOAD segment's mapped range contains it
            // (true for every normal, non-prelinked .so, where p_offset == p_vaddr per segment).
            fun fileOffsetFor(vaddr: Long): Long? =
                phdrs.firstOrNull { it.pType == PT_LOAD && vaddr >= it.pVaddr && vaddr < it.pVaddr + it.pFilesz }
                    ?.let { vaddr - it.pVaddr + it.pOffset }

            val relocEntries = mutableListOf<RelEntry>()
            fun collectRelTable(tableVaddr: Long?, tableSize: Long?) {
                if (tableVaddr == null || tableSize == null || tableSize <= 0) return
                val fileOff = fileOffsetFor(tableVaddr) ?: return
                var q = fileOff
                val end = fileOff + tableSize
                while (q + 8 <= end && q + 8 <= bytes.size) {
                    val target = buf.getInt(q.toInt()).toLong() and 0xFFFFFFFFL
                    relocEntries.add(RelEntry(q, target))
                    q += 8
                }
            }
            // Elf32_Rel is { r_offset: u32, r_info: u32 } = 8 bytes/entry - true for DT_REL/
            // DT_JMPREL when DT_PLTREL says REL (0x11), which is what ARM's ABI always uses
            // (as opposed to x86's Elf32_Rela with an extra addend field - not handled here,
            // this whole class of old-NDK textrel library is ARM-only in practice anyway).
            collectRelTable(dynVal(DT_REL), dynVal(DT_RELSZ))
            if (dynVal(DT_PLTREL) == DT_REL.toLong()) {
                collectRelTable(dynVal(DT_JMPREL), dynVal(DT_PLTRELSZ))
            }
            if (relocEntries.isEmpty()) {
                return Result(false, "TEXTREL flag set but no relocation entries found - leaving as-is")
            }

            fun segmentFor(target: Long) = phdrs.firstOrNull {
                it.pType == PT_LOAD && target >= it.pVaddr && target < it.pVaddr + it.pMemsz
            }

            val problemEntries = relocEntries.filter { entry ->
                val seg = segmentFor(entry.target)
                seg != null && (seg.pFlags and PF_W) == 0
            }

            fun matchesTrampoline(head: Long): Boolean {
                val h = head.toInt()
                if (head < 0 || h + 12 > bytes.size) return false
                for (i in TRAMPOLINE_PROLOGUE.indices) {
                    if (bytes[h + i] != TRAMPOLINE_PROLOGUE[i]) return false
                }
                return bytes[h + 11] == TRAMPOLINE_BRANCH_TOP_BYTE
            }

            // Recognize + dedupe trampoline heads among the problem relocations.
            val neutralizedHeads = problemEntries
                .map { it.target - 12 }
                .distinct()
                .filter { matchesTrampoline(it) }
                .toSet()

            fun isVoidedByTrampoline(target: Long): Boolean =
                neutralizedHeads.any { h -> target >= h + 12 && target < h + TRAMPOLINE_DEAD_BYTES }

            // --- apply, in place ---

            // 1. Kill each recognized trampoline at its head with an immediate `bx lr`.
            for (head in neutralizedHeads) {
                val h = head.toInt()
                for (i in BX_LR.indices) buf.put(h + i, BX_LR[i])
            }

            // 2. Void every relocation entry (not just the one that led us to a given head -
            // a trampoline can have a second, ABS32 `__cxa_atexit` relocation right after the
            // DSO-handle one, both now inside dead code) whose target falls inside a neutralized
            // trampoline's now-unreachable literal pool.
            var voidedCount = 0
            for (entry in relocEntries) {
                if (isVoidedByTrampoline(entry.target)) {
                    buf.putInt(entry.fileOffset.toInt() + 4, 0) // r_info = 0 -> R_ARM_NONE
                    voidedCount++
                }
            }

            // 3. Anything left over wasn't a recognized trampoline - fall back to the older,
            // less-certain "make the segment writable" approach just for those, so the file at
            // least loads (fixing the original TEXTREL refusal), while flagging in the result
            // that this specific file reintroduces the W^X risk and needs a real retest.
            val remainingProblems = problemEntries.filterNot { isVoidedByTrampoline(it.target) }
            val fallbackSegs = remainingProblems.mapNotNull { segmentFor(it.target) }.distinct()
            for (seg in fallbackSegs) {
                buf.putInt(seg.fileOffset + 24, seg.pFlags or PF_W)
            }

            if (textrelEntry != null) {
                // DT_DEBUG: a real, well-known tag the loader only ever WRITES to (a slot for
                // gdb's benefit) and never reads as configuration - a safe, inert placeholder
                // that keeps the dynamic array the same length and shape, unlike overwriting it
                // with DT_NULL, which would truncate parsing right there and silently drop every
                // entry that comes after it (this array's terminator IS a DT_NULL entry).
                buf.putInt(textrelEntry.fileOffset, DT_DEBUG)
                buf.putInt(textrelEntry.fileOffset + 4, 0)
            }
            if (flagsEntry != null && hasDfTextrelBit) {
                buf.putInt(flagsEntry.fileOffset + 4, (flagsEntry.value and DF_TEXTREL_BIT.inv()).toInt())
            }

            val note = buildString {
                if (neutralizedHeads.isEmpty() && fallbackSegs.isEmpty()) {
                    append("TEXTREL flag was set but every relocation already lands in a writable segment - just cleared the flag")
                } else {
                    append("neutralized ${neutralizedHeads.size} dead __cxa_finalize cleanup trampoline(s) " +
                        "by voiding $voidedCount relocation entr${if (voidedCount == 1) "y" else "ies"} " +
                        "(no writable+executable segment needed)")
                    if (fallbackSegs.isNotEmpty()) {
                        append("; PLUS made ${fallbackSegs.size} code segment(s) writable as a fallback for " +
                            "${remainingProblems.size} unrecognized relocation(s) not matching the known " +
                            "trampoline pattern - unverified, please retest this file specifically")
                    }
                    append("; cleared the TEXTREL flag")
                }
            }
            return Result(true, note)
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
