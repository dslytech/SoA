package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Imperium Galactica 2 only) TEMPORARY DIAGNOSTIC patch - not a fix. Companion to
 * `ElfHaegemoniaSaveDebugTracePatcher` - see that file's doc comment for the full background on the
 * "save vanishes after a full force-close + reopen" investigation this supports. IG2 shares the same
 * underlying engine as Haegemonia (both from Digital Reality): `DataManager::GetDataDir` in
 * `libig2ps.so` is structurally byte-identical in its logic to Haegemonia's copy - same
 * `Environment.getExternalStorageDirectory()` + `File.getPath()` JNI calls, same
 * `"/Android/data/<pkg>/files"` suffix - which is what first suggested this might be ONE shared root
 * cause rather than two separate bugs.
 *
 * ## What this build does
 *
 * IG2's save system is plain C-style, not the `cSaveGame` class Haegemonia uses: `SaveScenario(char
 * const*, char const*)` (`0x00160F0C`) and `LoadSavedGame(char const*)` (`0x001578A8`) are the real
 * player-triggered save/load entry points, and `GetSavePath()` (`0x00155CE8`) is a tiny existing
 * wrapper that just tail-calls `SG_GetDataDir(1)` -> `DataManager::GetDataDir(1)` - calling it is
 * exactly equivalent to calling `DataManager::GetDataDir` directly, just using the game's own existing
 * wrapper.
 *
 * Each hook replicates the target function's real original first instruction (`stmdb` prologue, which
 * differs slightly between the two functions - `SaveScenario` pushes `{r4,r5,r6,r7,r8,r9,sl,fp,lr}`,
 * `LoadSavedGame` pushes `{r3,r4,r5,r6,r7,r8,r9,lr}` - each cave replicates its own target's exact
 * original bytes); preserves incoming argument registers `r0`-`r3` across its own work via a balanced
 * `push`/`pop`; calls the untouched `GetSavePath()`; opens
 * `/storage/emulated/0/Android/obb/com.digitalreality.ig2/soa_save_debug.txt` in append mode (same
 * already-confirmed-visible location family as IG2's own working video-folder feature); writes
 * `"SAVE dir=<path>\n"` or `"LOAD dir=<path>\n"`; closes the file; restores `r0`-`r3`; and rejoins the
 * function's own untouched next instruction. A debug file that can't be opened is a pure no-op beyond
 * one wasted `fopen()` call either way - real save/load logic is completely unaffected.
 *
 * ## Verification
 *
 * Same discipline as the Haegemonia companion patch: every instruction in both caves decodes cleanly
 * (capstone), and both hooks were run end-to-end under `unicorn` with the whole patched module mapped
 * at a deliberately nonzero simulated load bias, entering at the real redirected entry point, for both
 * a "debug file opens" and "debug file fails to open" scenario. In every case execution lands exactly
 * on the target function's own untouched next instruction with `r0`-`r3` and the stack pointer in
 * exactly the state the real original single `stmdb` alone would have left them (confirmed against
 * each function's own actual push list: 9 registers / 36 bytes for `SaveScenario`, 8 registers / 32
 * bytes for `LoadSavedGame`). `GetSavePath`/`DataManager::GetDataDir` themselves are NOT re-verified
 * here (completely unmodified, already proven to work by every save that has ever succeeded within a
 * session) - the emulation stubs `GetSavePath`'s entry to isolate and verify only the new code this
 * patch adds.
 *
 * Caves live in a fresh, independently-confirmed all-zero, non-relocated 471-byte `.rodata` gap at
 * `0x002EB843` (distinct from the gap `ElfImperiumGalactica2DirectVideoFolderPatcher` already uses at
 * `0x002F41EC` - zero overlap with that or anything else): the `SaveScenario` hook claims the first
 * 165 bytes (real code starts 1 byte in, at `0x002EB844`, after a single alignment pad byte the
 * assembler inserted), the `LoadSavedGame` hook claims the next 164 bytes immediately after (at
 * `0x002EB8E8`), leaving most of the gap still free. Both caves apply together or not at all.
 *
 * ## Reverting
 *
 * This is diagnostic-only, same lifecycle as the earlier `videoDebugTrace-TEMP` round: once
 * `soa_save_debug.txt` (pulled, not visible in `logcat`) has answered what `GetDataDir` actually
 * resolves to across a save and a post-reopen load, this patcher gets removed/reverted and superseded
 * by whatever real fix (or non-fix) the combined IG2 + Haegemonia logs reveal.
 */
object ElfImperiumGalactica2SaveDebugTracePatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_SAVE_ENTRY = 0x00160F0CL
    private const val TARGET_VADDR_SAVE_CAVE = 0x002EB843L
    private const val TARGET_VADDR_LOAD_ENTRY = 0x001578A8L
    private const val TARGET_VADDR_LOAD_CAVE = 0x002EB8E8L

    // stmdb sp!, {r4,r5,r6,r7,r8,r9,sl,fp,lr} - SaveScenario's original first instruction.
    private val EXPECTED_ORIGINAL_SAVE_ENTRY = byteArrayOf(0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x4F)
    // stmdb sp!, {r3,r4,r5,r6,r7,r8,r9,lr} - LoadSavedGame's original first instruction.
    private val EXPECTED_ORIGINAL_LOAD_ENTRY = byteArrayOf(0x2D, 0xE9.toByte(), 0xF8.toByte(), 0x43)

    // b.w 0x2eb844 (branches 1 byte into the cave region - a single alignment pad byte at 0x2eb843
    // that is never executed and stays at its original zero value).
    private val PATCHED_BYTES_SAVE_ENTRY = byteArrayOf(0x8A.toByte(), 0xF1.toByte(), 0x9A.toByte(), 0xBC.toByte())
    // b.w 0x2eb8e8
    private val PATCHED_BYTES_LOAD_ENTRY = byteArrayOf(0x94.toByte(), 0xF1.toByte(), 0x1E, 0xB8.toByte())

    private val PATCHED_BYTES_SAVE_CAVE = byteArrayOf(
        0x00, 0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x4F, 0x0F, 0xB4.toByte(), 0x6A, 0xF6.toByte(), 0x4D, 0xFA.toByte(), 0x04,
        0x68, 0x0B, 0x48, 0x0C, 0x49, 0xA3.toByte(), 0xF5.toByte(), 0xE8.toByte(), 0xEE.toByte(), 0x78, 0xB1.toByte(), 0x05,
        0x46, 0x0A, 0x48, 0x29, 0x46, 0xA3.toByte(), 0xF5.toByte(), 0x8A.toByte(), 0xEF.toByte(), 0x20, 0x46, 0x29,
        0x46, 0xA3.toByte(), 0xF5.toByte(), 0x86.toByte(), 0xEF.toByte(), 0x07, 0x48, 0x29, 0x46, 0xA3.toByte(), 0xF5.toByte(), 0x82.toByte(),
        0xEF.toByte(), 0x28, 0x46, 0xA3.toByte(), 0xF5.toByte(), 0xEA.toByte(), 0xEE.toByte(), 0x0F, 0xBC.toByte(), 0x75, 0xF6.toByte(), 0x48,
        0xBB.toByte(), 0x9E.toByte(), 0xB8.toByte(), 0x2E, 0x00, 0x9C.toByte(), 0xB8.toByte(), 0x2E, 0x00, 0x90.toByte(), 0xB8.toByte(), 0x2E,
        0x00, 0x9A.toByte(), 0xB8.toByte(), 0x2E, 0x00, 0x53, 0x41, 0x56, 0x45, 0x20, 0x64, 0x69,
        0x72, 0x3D, 0x00, 0x0A, 0x00, 0x61, 0x00, 0x2F, 0x73, 0x74, 0x6F, 0x72,
        0x61, 0x67, 0x65, 0x2F, 0x65, 0x6D, 0x75, 0x6C, 0x61, 0x74, 0x65, 0x64,
        0x2F, 0x30, 0x2F, 0x41, 0x6E, 0x64, 0x72, 0x6F, 0x69, 0x64, 0x2F, 0x6F,
        0x62, 0x62, 0x2F, 0x63, 0x6F, 0x6D, 0x2E, 0x64, 0x69, 0x67, 0x69, 0x74,
        0x61, 0x6C, 0x72, 0x65, 0x61, 0x6C, 0x69, 0x74, 0x79, 0x2E, 0x69, 0x67,
        0x32, 0x2F, 0x73, 0x6F, 0x61, 0x5F, 0x73, 0x61, 0x76, 0x65, 0x5F, 0x64,
        0x65, 0x62, 0x75, 0x67, 0x2E, 0x74, 0x78, 0x74, 0x00
    )

    private val PATCHED_BYTES_LOAD_CAVE = byteArrayOf(
        0x2D, 0xE9.toByte(), 0xF8.toByte(), 0x43, 0x0F, 0xB4.toByte(), 0x6A, 0xF6.toByte(), 0xFB.toByte(), 0xF9.toByte(), 0x04, 0x68,
        0x0B, 0x48, 0x0C, 0x49, 0xA3.toByte(), 0xF5.toByte(), 0x96.toByte(), 0xEE.toByte(), 0x78, 0xB1.toByte(), 0x05, 0x46,
        0x0A, 0x48, 0x29, 0x46, 0xA3.toByte(), 0xF5.toByte(), 0x38, 0xEF.toByte(), 0x20, 0x46, 0x29, 0x46,
        0xA3.toByte(), 0xF5.toByte(), 0x34, 0xEF.toByte(), 0x07, 0x48, 0x29, 0x46, 0xA3.toByte(), 0xF5.toByte(), 0x30, 0xEF.toByte(),
        0x28, 0x46, 0xA3.toByte(), 0xF5.toByte(), 0x98.toByte(), 0xEE.toByte(), 0x0F, 0xBC.toByte(), 0x6B, 0xF6.toByte(), 0xC4.toByte(), 0xBF.toByte(),
        0x42, 0xB9.toByte(), 0x2E, 0x00, 0x40, 0xB9.toByte(), 0x2E, 0x00, 0x34, 0xB9.toByte(), 0x2E, 0x00,
        0x3E, 0xB9.toByte(), 0x2E, 0x00, 0x4C, 0x4F, 0x41, 0x44, 0x20, 0x64, 0x69, 0x72,
        0x3D, 0x00, 0x0A, 0x00, 0x61, 0x00, 0x2F, 0x73, 0x74, 0x6F, 0x72, 0x61,
        0x67, 0x65, 0x2F, 0x65, 0x6D, 0x75, 0x6C, 0x61, 0x74, 0x65, 0x64, 0x2F,
        0x30, 0x2F, 0x41, 0x6E, 0x64, 0x72, 0x6F, 0x69, 0x64, 0x2F, 0x6F, 0x62,
        0x62, 0x2F, 0x63, 0x6F, 0x6D, 0x2E, 0x64, 0x69, 0x67, 0x69, 0x74, 0x61,
        0x6C, 0x72, 0x65, 0x61, 0x6C, 0x69, 0x74, 0x79, 0x2E, 0x69, 0x67, 0x32,
        0x2F, 0x73, 0x6F, 0x61, 0x5F, 0x73, 0x61, 0x76, 0x65, 0x5F, 0x64, 0x65,
        0x62, 0x75, 0x67, 0x2E, 0x74, 0x78, 0x74, 0x00
    )

    private val EXPECTED_ORIGINAL_SAVE_CAVE = ByteArray(PATCHED_BYTES_SAVE_CAVE.size)
    private val EXPECTED_ORIGINAL_LOAD_CAVE = ByteArray(PATCHED_BYTES_LOAD_CAVE.size)

    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libig2ps.so")) {
            return Result(false, "not libig2ps.so - not applicable")
        }
        try {
            if (bytes.size < 52) return Result(false, "too small to be an ELF file - not touching")
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) {
                return Result(false, "not an ELF file - not touching")
            }
            if (bytes[4].toInt() != 1) return Result(false, "not 32-bit ELF - not handled")
            if (bytes[5].toInt() != 1) return Result(false, "not little-endian ELF - not handled")

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
            fun fileOffsetFor(vaddr: Long): Int? =
                phdrs.firstOrNull { it.pType == PT_LOAD && vaddr >= it.pVaddr && vaddr < it.pVaddr + it.pFilesz }
                    ?.let { (vaddr - it.pVaddr + it.pOffset).toInt() }

            val saveEntryOff = fileOffsetFor(TARGET_VADDR_SAVE_ENTRY)
            val saveCaveOff = fileOffsetFor(TARGET_VADDR_SAVE_CAVE)
            val loadEntryOff = fileOffsetFor(TARGET_VADDR_LOAD_ENTRY)
            val loadCaveOff = fileOffsetFor(TARGET_VADDR_LOAD_CAVE)

            if (saveEntryOff == null || saveCaveOff == null || loadEntryOff == null || loadCaveOff == null ||
                saveEntryOff + 4 > bytes.size || saveCaveOff + PATCHED_BYTES_SAVE_CAVE.size > bytes.size ||
                loadEntryOff + 4 > bytes.size || loadCaveOff + PATCHED_BYTES_LOAD_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(saveEntryOff, EXPECTED_ORIGINAL_SAVE_ENTRY) ||
                !matches(saveCaveOff, EXPECTED_ORIGINAL_SAVE_CAVE) ||
                !matches(loadEntryOff, EXPECTED_ORIGINAL_LOAD_ENTRY) ||
                !matches(loadCaveOff, EXPECTED_ORIGINAL_LOAD_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version?) - left completely untouched")
            }

            fun write(off: Int, patch: ByteArray) { for (i in patch.indices) bytes[off + i] = patch[i] }
            write(saveEntryOff, PATCHED_BYTES_SAVE_ENTRY)
            write(saveCaveOff, PATCHED_BYTES_SAVE_CAVE)
            write(loadEntryOff, PATCHED_BYTES_LOAD_ENTRY)
            write(loadCaveOff, PATCHED_BYTES_LOAD_CAVE)

            return Result(
                true,
                "DIAGNOSTIC ONLY: SaveScenario/LoadSavedGame now also log the directory " +
                    "GetSavePath()/DataManager::GetDataDir(1) resolves to, appending one line per call to " +
                    "/storage/emulated/0/Android/obb/com.digitalreality.ig2/soa_save_debug.txt " +
                    "(pull it with adb, it won't show in logcat). Save/load behavior itself is completely " +
                    "unaffected either way."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
