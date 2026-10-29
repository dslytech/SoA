package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Haegemonia: Legions of Iron only) TEMPORARY DIAGNOSTIC patch - not a fix. Added to
 * investigate the "save vanishes after a full force-close + reopen" bug reported on the real tablet
 * (reproduces every time: save, fully close the app - not just background it -, reopen, the save
 * slot is completely empty). Logcat shows nothing useful during a save (this project's established
 * finding: native file I/O in these old games never logs anything), so this adds a side-channel debug
 * file instead, the same technique already used successfully for the earlier video-folder investigation
 * (`soa_debug.txt`).
 *
 * ## Background
 *
 * Disassembling `DataManager::GetDataDir(DataManager::StorageTypeType)` (the function both
 * `cSaveGame::saveData` and `cSaveGame::loadData` call, with `StorageType=1`, to find out where to
 * read/write) shows it resolves its directory via `Environment.getExternalStorageDirectory()` +
 * `File.getPath()` (both confirmed by literal class/method-name strings in the binary), then appends
 * `"/Android/data/" + <package name> + "/files"` - i.e. the correct, scoped-storage-safe, no-permission
 * -needed app-private directory, NOT some risky raw shared-storage path. That directory is normally as
 * persistent as internal storage, so a code-level explanation for saves vanishing on a cold restart is
 * not obvious from reading `GetDataDir` alone - it needs a real on-device trace of what the game
 * actually does on a genuine cold start vs. within a continuing session.
 *
 * ## What this build does
 *
 * Hooks the very first instruction of `cSaveGame::saveData(std::string const&, void const*, int)`
 * (`0x0029E6A0`) and of `cSaveGame::loadData(std::string const&, void**, vector<char>*)`
 * (`0x0029E880`). Each hook: replicates the function's real original first instruction (its `stmdb`
 * prologue) unchanged; preserves all four incoming argument registers (`r0`-`r3`) across its own work
 * via a simple balanced `push`/`pop`; calls the completely untouched, already-working
 * `DataManager::GetDataDir(1)` itself (same call the real save/load code already makes, just one extra
 * time - side-effect-free beyond its own one-time-per-process caching, which it already does); opens
 * `/storage/emulated/0/Android/obb/com.microids.google.HaegemoniaFull/soa_save_debug.txt` in append
 * mode (that `Android/obb/<pkg>` location is already confirmed visible/writable by the player's own
 * file manager from the earlier video-folder work, unlike `Android/data/<pkg>`, which Android hides
 * from everything except the owning app from Android 11 on - also a `soa_save_debug.txt` finding worth
 * recording: this may be WHY earlier attempts to find the save folder via Device Explorer/adb turned up
 * "No such file or directory" even though the owning game can read/write it fine); writes one line
 * (`"SAVE dir=<resolved path>\n"` or `"LOAD dir=<resolved path>\n"`); closes the file; restores
 * `r0`-`r3` exactly; and rejoins the function's own completely untouched second instruction. If the
 * debug file can't be opened, the hook is a pure no-op beyond the wasted `fopen()` call - every path
 * falls straight through to the real, unmodified save/load logic either way.
 *
 * Goal: comparing the logged directory string across a save (within a session) and a load (after a
 * full force-close/reopen) tells us whether `GetDataDir` ever resolves to a DIFFERENT path between the
 * two (a real path-resolution bug), or whether it's consistently the same path both times (pointing
 * instead at something else - e.g. a separate listing/cache mechanism not exercised by this trace, or
 * genuine OS/OEM-level data clearing).
 *
 * ## Verification
 *
 * Every instruction in both caves decodes cleanly (capstone, no illegal encodings). Both hooks were
 * run end-to-end under `unicorn`, with the WHOLE patched module mapped at a deliberately nonzero
 * simulated load bias, entering at the real redirected entry point, for both an "debug file opens"
 * and an "debug file fails to open" scenario: in every case execution lands exactly on the function's
 * own untouched second instruction with `r0`-`r3` and the stack pointer in exactly the state the real
 * original single `stmdb` instruction alone would have left them (confirmed against the actual
 * push/pop register-list sizes: 6 registers / 24 bytes for both `saveData` and `loadData`, since both
 * share the identical `stmdb sp!, {r4,r5,r6,r7,fp,lr}` prologue). `DataManager::GetDataDir` itself is
 * NOT re-verified here (it's completely unmodified, already proven to work by every save that has
 * ever succeeded within a session) - the emulation stubs its entry to isolate and verify only the NEW
 * code this patch adds.
 *
 * Caves live in the same 2048-byte all-zero `.rodata` gap at `0x0068560C` already vetted by
 * `ElfHaegemoniaDirectVideoFolderPatcher` (184 bytes) and `ElfHaegemoniaSaveGameNullGuardPatcher`
 * (18 bytes) - this patch claims the next 182 bytes (`saveData` hook, at `0x006856D6`) and the 180
 * bytes after that (`loadData` hook, at `0x0068578C`), confirmed non-overlapping with both existing
 * claims and leaving the gap's remaining ~1480 bytes free. Both caves apply together or not at all.
 *
 * ## Reverting
 *
 * This is diagnostic-only. Once `soa_save_debug.txt` (pulled, not visible in `logcat`) has answered
 * the question above, this patcher gets removed/reverted and superseded by whatever real fix (or
 * non-fix, if this points at a device setting) the log reveals - same lifecycle as the earlier
 * `videoDebugTrace-TEMP` round.
 */
object ElfHaegemoniaSaveDebugTracePatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_SAVE_ENTRY = 0x0029E6A0L
    private const val TARGET_VADDR_SAVE_CAVE = 0x006856D6L
    private const val TARGET_VADDR_LOAD_ENTRY = 0x0029E880L
    private const val TARGET_VADDR_LOAD_CAVE = 0x0068578CL

    // stmdb sp!, {r4,r5,r6,r7,fp,lr} - cSaveGame::saveData's original first instruction.
    private val EXPECTED_ORIGINAL_SAVE_ENTRY = byteArrayOf(0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x48)
    // stmdb sp!, {r4,r5,r6,r7,fp,lr} - cSaveGame::loadData's original first instruction (identical).
    private val EXPECTED_ORIGINAL_LOAD_ENTRY = byteArrayOf(0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x48)

    // b.w 0x6856d8 (branches 2 bytes into the cave region - the assembler/linker placed the real
    // cave code starting there, with 2 leading pad bytes at 0x6856d6-0x6856d7 that are never
    // executed and stay at their original zero value).
    private val PATCHED_BYTES_SAVE_ENTRY = byteArrayOf(0xE7.toByte(), 0xF3.toByte(), 0x1A, 0xB8.toByte())
    // b.w 0x68578c
    private val PATCHED_BYTES_LOAD_ENTRY = byteArrayOf(0xE6.toByte(), 0xF3.toByte(), 0x84.toByte(), 0xBF.toByte())

    private val PATCHED_BYTES_SAVE_CAVE = byteArrayOf(
        0x00, 0x00, 0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x48, 0x0F, 0xB4.toByte(), 0x01, 0x20, 0x00, 0xF5.toByte(),
        0x1A, 0xFB.toByte(), 0x04, 0x68, 0x0C, 0x48, 0x0C, 0x49, 0x1D, 0xF7.toByte(), 0xFE.toByte(), 0xE2.toByte(),
        0x78, 0xB1.toByte(), 0x05, 0x46, 0x0B, 0x48, 0x29, 0x46, 0x1D, 0xF7.toByte(), 0x20, 0xE5.toByte(),
        0x20, 0x46, 0x29, 0x46, 0x1D, 0xF7.toByte(), 0x1C, 0xE5.toByte(), 0x08, 0x48, 0x29, 0x46,
        0x1D, 0xF7.toByte(), 0x18, 0xE5.toByte(), 0x28, 0x46, 0x1D, 0xF7.toByte(), 0xD6.toByte(), 0xE3.toByte(), 0x0F, 0xBC.toByte(),
        0x18, 0xF4.toByte(), 0xC7.toByte(), 0xBF.toByte(), 0x00, 0x00, 0x36, 0x57, 0x68, 0x00, 0x34, 0x57,
        0x68, 0x00, 0x28, 0x57, 0x68, 0x00, 0x32, 0x57, 0x68, 0x00, 0x53, 0x41,
        0x56, 0x45, 0x20, 0x64, 0x69, 0x72, 0x3D, 0x00, 0x0A, 0x00, 0x61, 0x00,
        0x2F, 0x73, 0x74, 0x6F, 0x72, 0x61, 0x67, 0x65, 0x2F, 0x65, 0x6D, 0x75,
        0x6C, 0x61, 0x74, 0x65, 0x64, 0x2F, 0x30, 0x2F, 0x41, 0x6E, 0x64, 0x72,
        0x6F, 0x69, 0x64, 0x2F, 0x6F, 0x62, 0x62, 0x2F, 0x63, 0x6F, 0x6D, 0x2E,
        0x6D, 0x69, 0x63, 0x72, 0x6F, 0x69, 0x64, 0x73, 0x2E, 0x67, 0x6F, 0x6F,
        0x67, 0x6C, 0x65, 0x2E, 0x48, 0x61, 0x65, 0x67, 0x65, 0x6D, 0x6F, 0x6E,
        0x69, 0x61, 0x46, 0x75, 0x6C, 0x6C, 0x2F, 0x73, 0x6F, 0x61, 0x5F, 0x73,
        0x61, 0x76, 0x65, 0x5F, 0x64, 0x65, 0x62, 0x75, 0x67, 0x2E, 0x74, 0x78,
        0x74, 0x00
    )

    private val PATCHED_BYTES_LOAD_CAVE = byteArrayOf(
        0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x48, 0x0F, 0xB4.toByte(), 0x01, 0x20, 0x00, 0xF5.toByte(), 0xC0.toByte(), 0xFA.toByte(),
        0x04, 0x68, 0x0C, 0x48, 0x0C, 0x49, 0x1D, 0xF7.toByte(), 0xA4.toByte(), 0xE2.toByte(), 0x78, 0xB1.toByte(),
        0x05, 0x46, 0x0B, 0x48, 0x29, 0x46, 0x1D, 0xF7.toByte(), 0xC6.toByte(), 0xE4.toByte(), 0x20, 0x46,
        0x29, 0x46, 0x1D, 0xF7.toByte(), 0xC2.toByte(), 0xE4.toByte(), 0x08, 0x48, 0x29, 0x46, 0x1D, 0xF7.toByte(),
        0xBE.toByte(), 0xE4.toByte(), 0x28, 0x46, 0x1D, 0xF7.toByte(), 0x7C, 0xE3.toByte(), 0x0F, 0xBC.toByte(), 0x19, 0xF4.toByte(),
        0x5D, 0xB8.toByte(), 0x00, 0x00, 0xEA.toByte(), 0x57, 0x68, 0x00, 0xE8.toByte(), 0x57, 0x68, 0x00,
        0xDC.toByte(), 0x57, 0x68, 0x00, 0xE6.toByte(), 0x57, 0x68, 0x00, 0x4C, 0x4F, 0x41, 0x44,
        0x20, 0x64, 0x69, 0x72, 0x3D, 0x00, 0x0A, 0x00, 0x61, 0x00, 0x2F, 0x73,
        0x74, 0x6F, 0x72, 0x61, 0x67, 0x65, 0x2F, 0x65, 0x6D, 0x75, 0x6C, 0x61,
        0x74, 0x65, 0x64, 0x2F, 0x30, 0x2F, 0x41, 0x6E, 0x64, 0x72, 0x6F, 0x69,
        0x64, 0x2F, 0x6F, 0x62, 0x62, 0x2F, 0x63, 0x6F, 0x6D, 0x2E, 0x6D, 0x69,
        0x63, 0x72, 0x6F, 0x69, 0x64, 0x73, 0x2E, 0x67, 0x6F, 0x6F, 0x67, 0x6C,
        0x65, 0x2E, 0x48, 0x61, 0x65, 0x67, 0x65, 0x6D, 0x6F, 0x6E, 0x69, 0x61,
        0x46, 0x75, 0x6C, 0x6C, 0x2F, 0x73, 0x6F, 0x61, 0x5F, 0x73, 0x61, 0x76,
        0x65, 0x5F, 0x64, 0x65, 0x62, 0x75, 0x67, 0x2E, 0x74, 0x78, 0x74, 0x00
    )

    private val EXPECTED_ORIGINAL_SAVE_CAVE = ByteArray(PATCHED_BYTES_SAVE_CAVE.size)
    private val EXPECTED_ORIGINAL_LOAD_CAVE = ByteArray(PATCHED_BYTES_LOAD_CAVE.size)

    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libhaegemonia_base.so")) {
            return Result(false, "not libhaegemonia_base.so - not applicable")
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
                "DIAGNOSTIC ONLY: cSaveGame::saveData/loadData now also log the directory " +
                    "DataManager::GetDataDir(1) resolves to, appending one line per call to " +
                    "/storage/emulated/0/Android/obb/com.microids.google.HaegemoniaFull/soa_save_debug.txt " +
                    "(pull it with adb, it won't show in logcat). Save/load behavior itself is completely " +
                    "unaffected either way."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
