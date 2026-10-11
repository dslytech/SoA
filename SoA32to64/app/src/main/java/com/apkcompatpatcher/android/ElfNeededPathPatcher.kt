package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fixes a different, unrelated ELF problem from [ElfTextRelPatcher]: a `DT_NEEDED` entry (a
 * "this library needs XYZ.so to be loaded first" dependency, stored in the ELF's `.dynamic`
 * section) whose value isn't a plain library name at all, but a dead absolute build-machine
 * path baked in by whatever linked the library - something like:
 * ```
 * D:/EAMT/masseffectinfiltrator_eamt/Packages/EAOS/Framework2/RL/AndroidSDK/14-ndk-r7-1/
 * installed/android-ndk-r7/platforms/android-9/arch-arm/usr/lib/libc.so
 * ```
 * Confirmed via a real device `adb logcat` for Mass Effect Infiltrator's `libMassEffect.so`:
 * ```
 * java.lang.UnsatisfiedLinkError: dlopen failed: library "D:/EAMT/.../libc.so" not found:
 * needed by .../libMassEffect.so in namespace clns-9
 * ```
 * Whatever this game's original (~2012) Android build/test setup was, it evidently never
 * caught this - maybe an older bionic was more forgiving, or it was simply never re-tested
 * after whatever build-system bug produced this. Modern bionic takes the `DT_NEEDED` string
 * completely literally as a library name to search for, and a Windows build-machine path
 * obviously doesn't exist as a file on an Android device, so it just fails outright.
 *
 * ## The fix
 *
 * `DT_NEEDED`'s value is an offset into the `.dynstr` string table (found via the `DT_STRTAB`
 * entry), pointing at a normal null-terminated C string. For every `DT_NEEDED` entry whose
 * string contains a path separator (`/` or `\`) - which a well-formed library name never does,
 * it's always just e.g. `"libc.so"` - this rewrites that string in place to just its basename
 * (the part after the last separator), null-terminates it there, and zero-fills the leftover
 * bytes out to the original string's length. The dynamic array itself, every offset, and every
 * other string in the table are completely untouched - only the bytes of the broken string
 * itself change, and it only ever gets shorter, never longer, so nothing needs to move.
 *
 * Zero-filling the leftover tail (rather than, say, leaving the old bytes in place after the new
 * terminator) means anything that happened to have an offset pointing further into the *middle*
 * of this exact string would now see an empty string instead of whatever tail substring used to
 * be there. This is a real, theoretical trade-off - ELF string tables sometimes deliberately
 * overlap entries that share a common suffix to save space - but for it to matter here, some
 * *other* symbol/table entry would have to specifically alias into the tail of this one broken,
 * extremely distinctive, one-off absolute Windows build path, which is not a realistic risk in
 * practice.
 *
 * This has been checked against a synthetic ELF constructed with the exact byte-for-byte broken
 * string from the real crash log above (parsing, basename extraction, in-place rewrite, and
 * zero-fill all verified offline), but not yet against the real `libMassEffect.so` file itself -
 * unlike [ElfTextRelPatcher]'s trampoline fix, which was checked against the real extracted
 * library. Confirm with a fresh `adb logcat` after this ships.
 */
object ElfNeededPathPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2

    private const val DT_NULL = 0
    private const val DT_NEEDED = 1
    private const val DT_STRTAB = 5
    private const val DT_STRSZ = 0xa

    private class Phdr(
        val pType: Int,
        val pOffset: Long,
        val pVaddr: Long,
        val pFilesz: Long
    )

    private class DynEntry(val fileOffset: Int, val tag: Int, val value: Long)

    /**
     * Patches [bytes] IN PLACE (it's a mutable-backing-array wrap, not a copy) if it finds at
     * least one `DT_NEEDED` entry whose string looks like a broken absolute path rather than a
     * plain library name. Anything else (not an ELF, no such entries, malformed/truncated) is
     * left completely untouched and reported as not patched - callers should keep the original
     * bytes whenever [Result.patched] is false.
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
                    pType = buf.getInt(off),
                    pOffset = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL,
                    pVaddr = buf.getInt(off + 8).toLong() and 0xFFFFFFFFL,
                    pFilesz = buf.getInt(off + 16).toLong() and 0xFFFFFFFFL
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

            // vaddr -> file offset, via whichever PT_LOAD segment's mapped range contains it
            // (true for every normal, non-prelinked .so, where p_offset == p_vaddr per segment).
            fun fileOffsetFor(vaddr: Long): Long? =
                phdrs.firstOrNull { it.pType == PT_LOAD && vaddr >= it.pVaddr && vaddr < it.pVaddr + it.pFilesz }
                    ?.let { vaddr - it.pVaddr + it.pOffset }

            val strtabVaddr = dynEntries.firstOrNull { it.tag == DT_STRTAB }?.value
                ?: return Result(false, "no DT_STRTAB - malformed dynamic section, not touching this file")
            val strtabFileOff = fileOffsetFor(strtabVaddr)
                ?: return Result(false, "DT_STRTAB vaddr doesn't map to any PT_LOAD segment - not touching this file")
            val strtabSize = dynEntries.firstOrNull { it.tag == DT_STRSZ }?.value
                ?: (bytes.size - strtabFileOff)

            val neededEntries = dynEntries.filter { it.tag == DT_NEEDED }
            if (neededEntries.isEmpty()) {
                return Result(false, "no DT_NEEDED entries - nothing to check")
            }

            var fixedCount = 0
            val fixedNames = mutableListOf<String>()
            val maxEnd = minOf(bytes.size.toLong(), strtabFileOff + strtabSize).toInt()

            for (entry in neededEntries) {
                val strOff = (strtabFileOff + entry.value).toInt()
                if (strOff < 0 || strOff >= bytes.size) continue

                var end = strOff
                while (end < maxEnd && bytes[end] != 0.toByte()) end++
                if (end >= maxEnd) continue // no terminator found within bounds - leave alone

                val original = String(bytes, strOff, end - strOff, Charsets.US_ASCII)
                val lastSep = maxOf(original.lastIndexOf('/'), original.lastIndexOf('\\'))
                if (lastSep < 0) continue // already a plain name, nothing to do

                val basename = original.substring(lastSep + 1)
                if (basename.isEmpty() || basename.length >= original.length) continue

                val basenameBytes = basename.toByteArray(Charsets.US_ASCII)
                for (i in basenameBytes.indices) bytes[strOff + i] = basenameBytes[i]
                bytes[strOff + basenameBytes.size] = 0
                for (i in (strOff + basenameBytes.size + 1) until end) bytes[i] = 0

                fixedCount++
                fixedNames.add("\"$original\" -> \"$basename\"")
            }

            if (fixedCount == 0) {
                return Result(false, "DT_NEEDED entries present but none look like a broken absolute path")
            }

            return Result(
                true,
                "fixed $fixedCount broken DT_NEEDED path(s): ${fixedNames.joinToString("; ")}"
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
