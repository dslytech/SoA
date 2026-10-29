package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Haegemonia: Legions of Iron only) native patch: makes EVERY file lookup try a
 * plain external folder first, before falling back to the game's proprietary encrypted `.obb`
 * package - completely unmodified from stock. In practice this is used for FMV/cutscene videos (the
 * only asset type the player has legally-owned replacement files for), but it works for any asset
 * type by construction, and is a no-op whenever the external file isn't present.
 *
 * ## Background
 *
 * Same investigation as the IG2 video patch (`ElfImperiumGalactica2DirectVideoFolderPatcher`) applied
 * to Haegemonia, which shares the same underlying engine (Digital Reality): `AssetHelper` class,
 * `StringEncoder::aes256_encdec`/LibTomCrypt present, and the SAME `":direct:"` path-prefix escape
 * hatch in `AssetHelper::OpenRawFile` that skips the encrypted package system for a plain `fopen()`.
 * Haegemonia's own `.obb` is presumed encrypted the same way (not independently re-verified here -
 * see IG2's own writeup for the entropy analysis; not needed since this patch never reads the `.obb`
 * either way). `cGuiHelper::PlayBink(CString)` confirms Haegemonia ALSO expects `.mp4` files (it
 * appends the literal `".mp4"` to the requested clip name before checking whether it exists), so the
 * same bik->mp4 conversion the user already did for IG2 is needed here too.
 *
 * Unlike IG2, Haegemonia's video path is NOT built in one clean function - the name flows through
 * `cGuiHelper::PlayBink` -> a pending-clip vector -> `hgmscreen::update()`'s per-frame poll ->
 * `videoentity::initvideo()` -> `Loader_Video_Entity` -> `AssetHelper::OpenStream`, using the engine's
 * own `CString` class along the way, with no single literal-pool reference to redirect the way IG2's
 * `command_OpenBink` allowed. Rather than reverse-engineer and correctly re-implement that whole
 * multi-function chain (real risk of a subtly wrong fix somewhere in code this project doesn't have
 * full test coverage for), this patch instead hooks the ONE function every asset lookup - video or
 * otherwise - ultimately goes through: `AssetHelper::OpenRawFile(std::string const&, char const*)`.
 *
 * ## The fix
 *
 * `AssetHelper::OpenRawFile`'s original first instruction, at `0x00378C38`
 * (`stmdb sp!, {r4,r5,r6,r7,r8,r9,sl,fp,lr}`), is replaced with an unconditional branch into a new
 * 200-byte cave at `0x0068560C`. The cave:
 *
 *  1. Replicates that same `stmdb` plus the function's next three original instructions
 *     (`sub sp,#20` / `mov r5,r1` / `mov fp,r0`, all left byte-for-byte untouched at their original
 *     addresses `0x378C3C`-`0x378C41`) - so the function's real prologue still runs exactly as before.
 *  2. Reserves its own extra 208 bytes of stack scratch (kept 8-byte aligned throughout, verified) and
 *     builds a plain C string there: the literal
 *     `"/storage/emulated/0/Android/obb/com.microids.google.HaegemoniaFull/videos/"` followed by the
 *     requested path's own existing bytes (read directly from the `std::string`'s first word, the
 *     same "data pointer is the first field" layout this project already relies on elsewhere) and a
 *     NUL terminator - a plain byte-copy loop, no engine string classes touched.
 *  3. Calls `fopen()` (the exact same libc entry point, `fopen@plt` at `0x001A2CE8`, `OpenRawFile`
 *     itself already calls elsewhere) on that combined path. If it succeeds, wraps the `FILE*` with
 *     `SDL_RWFromFP(fp, 1)` (`0x001A2CF4`) - copied verbatim from how the original function's own
 *     `":direct:"` success path already does the exact same wrap - and joins back into the ORIGINAL
 *     function's own epilogue at `0x00378D6A` (`mov r0,r4` / `add sp,#20` / pop) to return it.
 *  4. If `fopen()` fails (the external file doesn't exist - the overwhelmingly common case, since this
 *     runs for every single asset the game loads), it undoes its own extra 208 bytes of stack and
 *     rejoins the function's ORIGINAL, completely unmodified body at `0x00378C42`
 *     (`movs r4,#0`, the original code's own next instruction) with every register in exactly the
 *     state the untouched original prologue would have left them in. From there execution is
 *     byte-for-byte the original function - the encrypted-package search, the `":direct:"` handling,
 *     the network-file fallback, all of it - completely unaffected.
 *
 * ## Round 2 bug fix: this is a PIE/ASLR shared library - a naive literal pool crashed on device
 *
 * The FIRST build of this cave (shipped, then real-device tested alongside IG2's own round-2 fix -
 * see `ElfImperiumGalactica2DirectVideoFolderPatcher`'s doc comment for the full story of how this
 * bug class was found) crashed on launch: tombstone showed `SIGSEGV`/`SEGV_MAPERR` at
 * `libhaegemonia_base.so+0x68561c` (the cave's prefix-string copy loop), fault address `0x00685664`
 * - the prefix string's raw LINK-TIME address. Root cause: this `.so` is `ET_DYN`, loaded at a
 * random runtime bias like every Android native library - the cave's `ldr r6,=prefix_str`-style
 * load (and the `ldr r2,=fopen_plt; blx r2`-style PLT calls, and the `ldr r0,=orig_epilogue`/
 * `ldr r0,=orig_resume` jump-target loads) all baked in raw link-time absolute addresses with zero
 * bias correction.
 *
 * Fixed by rewriting the cave so every runtime address goes through the same `ldr rX,[pc,#N];
 * add rX,pc` idiom the original compiler-generated code already uses everywhere (bias-safe by
 * construction): `prefix_str`'s address is now resolved that way before the copy loop runs; the
 * `fopen@plt`/`SDL_RWFromFP@plt` calls became direct `blx #<link addr>` immediates (the same form
 * the original function already uses for these same calls elsewhere - no register load needed);
 * and the two exit jump targets (`orig_epilogue`, `orig_resume`) use the same offset-based idiom,
 * computed after linking and patched into a placeholder literal-pool word by the build tooling
 * (GNU `as`'s `.equ` can't fold a cross-object absolute address against an unresolved same-section
 * label in one assembler pass).
 *
 * Verified two ways: capstone-decoded every instruction in both the entry-point branch and the full
 * cave (clean decode, no illegal encodings, every branch target resolves to the intended address -
 * including the two stack-restoring exits and the shared success epilogue/original resume points);
 * and, since this fix calls real libc (`fopen`) unlike any earlier fix in this project, the string-
 * building + `fopen()` logic was additionally extracted into a standalone ARM binary (same
 * instruction sequence, real `fopen`, no synthetic stubbing) and run under `qemu-arm` against both a
 * missing and a present test file: the built path matched byte-for-byte, `fopen()` correctly returned
 * NULL for the missing case, and a real file at an equivalent path opened successfully. NEW for
 * round 2, specifically to catch the ASLR bug above (the `qemu-arm` test never would have, since it
 * never executes the cave's OWN address-computing instructions at a real, deployed address):
 * applied the exact patch to a full copy of the real, extracted `libhaegemonia_base.so` and ran it
 * under the `unicorn` CPU emulator with the WHOLE module mapped at a deliberately nonzero simulated
 * load bias (`0x50000000`), starting from the function's REAL entry point - hooking only the two
 * PLT call targets and both possible landing addresses (the untouched original resume point, and
 * the untouched original epilogue). Confirmed, for both the "file found" and "file not found"
 * scenarios: execution reaches the correct landing address with no fault, the path-object pointer
 * (`fp`) and mode-string pointer (`r5`) both survive unclobbered, the stack pointer lands exactly
 * where the REAL original prologue alone would have left it, and (for the "found" case) `r4` holds
 * the fake `SDL_RWops*` correctly threaded through from the `SDL_RWFromFP` call.
 *
 * Cave lives at `0x0068560C`, a 2048-byte all-zero, non-relocated gap inside `.rodata` (confirmed zero
 * `.rel.dyn` relocations land in it; only 184 bytes claimed as of round 2, down from round 1's 200 -
 * the direct `blx` immediates for the PLT calls are more compact than loading the address first).
 *
 * ## Why this is safe for every OTHER game (IG2, Mass Effect: Infiltrator, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libhaegemonia_base.so`, and even then only if the exact original bytes at both target regions (the
 * entry-point instruction, and the cave) match byte-for-byte what was recorded here from the real
 * Haegemonia 1.00 build. Every OTHER asset the game already loads successfully takes one extra,
 * always-failing `fopen()` call (negligible cost) and is otherwise completely unaffected - the guard
 * only ever changes behavior for a path that has a matching file under the external `videos` folder,
 * which today is nothing at all until the player adds some.
 */
object ElfHaegemoniaDirectVideoFolderPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_ENTRY = 0x00378C38L
    private const val TARGET_VADDR_CAVE = 0x0068560CL

    // stmdb sp!, {r4,r5,r6,r7,r8,r9,sl,fp,lr} - AssetHelper::OpenRawFile's original first instruction.
    private val EXPECTED_ORIGINAL_ENTRY = byteArrayOf(0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x4F)

    // b.w 0x68560c
    private val PATCHED_BYTES_ENTRY = byteArrayOf(0x0C, 0xF3.toByte(), 0xE8.toByte(), 0xBC.toByte())

    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x2D, 0xE9.toByte(), 0xF0.toByte(), 0x4F, 0x85.toByte(), 0xB0.toByte(), 0x0D, 0x46, 0x83.toByte(), 0x46, 0xB4.toByte(), 0xB0.toByte(),
        0x29, 0x4E, 0x7E, 0x44, 0x6F, 0x46, 0x16, 0xF8.toByte(), 0x01, 0x8B.toByte(), 0x07, 0xF8.toByte(),
        0x01, 0x8B.toByte(), 0xB8.toByte(), 0xF1.toByte(), 0x00, 0x0F, 0xF8.toByte(), 0xD1.toByte(), 0xA7.toByte(), 0xF1.toByte(), 0x01, 0x07,
        0xDB.toByte(), 0xF8.toByte(), 0x00, 0x90.toByte(), 0x19, 0xF8.toByte(), 0x01, 0x8B.toByte(), 0x07, 0xF8.toByte(), 0x01, 0x8B.toByte(),
        0xB8.toByte(), 0xF1.toByte(), 0x00, 0x0F, 0xF8.toByte(), 0xD1.toByte(), 0x68, 0x46, 0x29, 0x46, 0x1D, 0xF7.toByte(),
        0x50, 0xE3.toByte(), 0x00, 0x28, 0x09, 0xD0.toByte(), 0x04, 0x46, 0x20, 0x46, 0x01, 0x21,
        0x1D, 0xF7.toByte(), 0x4E, 0xE3.toByte(), 0x04, 0x46, 0x34, 0xB0.toByte(), 0x16, 0x48, 0x78, 0x44,
        0x00, 0x47, 0x34, 0xB0.toByte(), 0x15, 0x48, 0x78, 0x44, 0x00, 0x47, 0x00, 0xBF.toByte(),
        // "/storage/emulated/0/Android/obb/com.microids.google.HaegemoniaFull/videos/\0"
        0x2F, 0x73, 0x74, 0x6F, 0x72, 0x61, 0x67, 0x65, 0x2F, 0x65, 0x6D, 0x75,
        0x6C, 0x61, 0x74, 0x65, 0x64, 0x2F, 0x30, 0x2F, 0x41, 0x6E, 0x64, 0x72,
        0x6F, 0x69, 0x64, 0x2F, 0x6F, 0x62, 0x62, 0x2F, 0x63, 0x6F, 0x6D, 0x2E,
        0x6D, 0x69, 0x63, 0x72, 0x6F, 0x69, 0x64, 0x73, 0x2E, 0x67, 0x6F, 0x6F,
        0x67, 0x6C, 0x65, 0x2E, 0x48, 0x61, 0x65, 0x67, 0x65, 0x6D, 0x6F, 0x6E,
        0x69, 0x61, 0x46, 0x75, 0x6C, 0x6C, 0x2F, 0x76, 0x69, 0x64, 0x65, 0x6F,
        0x73, 0x2F, 0x00, 0x00,
        // literal pool: epilogue-resume PC-relative offset word, not-found-resume PC-relative
        // offset word (both post-link-patched, see doc comment above - PLT calls themselves are
        // direct "blx #imm" immediates baked into the instruction stream above, not looked up here).
        // The cave's own logic ends after these two words (180 bytes in); the final 4 bytes below
        // are unused trailing space within the cave's fixed 184-byte capacity in the .so - never
        // branched to or read by the cave (every path exits via "bx" before reaching them) - kept
        // here only because they're part of the exact byte sequence that was unicorn-verified
        // against the real deployed .so; the original (unpatched) bytes at this whole 184-byte
        // range are all zero, so these trailing 4 bytes could equally be zero, but matching the
        // verified artifact exactly takes priority over cosmetic cleanup.
        0x09, 0x37, 0xCF.toByte(), 0xFF.toByte(),
        0xD9.toByte(), 0x35, 0xCF.toByte(), 0xFF.toByte(),
        0x4E, 0x00, 0x00, 0x00
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(PATCHED_BYTES_CAVE.size)

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

            val entryOff = fileOffsetFor(TARGET_VADDR_ENTRY)
            val caveOff = fileOffsetFor(TARGET_VADDR_CAVE)

            if (entryOff == null || caveOff == null ||
                entryOff + 4 > bytes.size || caveOff + PATCHED_BYTES_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(entryOff, EXPECTED_ORIGINAL_ENTRY) || !matches(caveOff, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version?) - left completely untouched")
            }

            fun write(off: Int, patch: ByteArray) { for (i in patch.indices) bytes[off + i] = patch[i] }
            write(entryOff, PATCHED_BYTES_ENTRY)
            write(caveOff, PATCHED_BYTES_CAVE)

            return Result(
                true,
                "AssetHelper::OpenRawFile now tries a plain external file first - " +
                    "/storage/emulated/0/Android/obb/com.microids.google.HaegemoniaFull/videos/<the " +
                    "same relative name the game already requests> - via a real fopen(), before falling " +
                    "back to the completely untouched original encrypted-.obb lookup. Drop .mp4 files " +
                    "there (named after the game's requested clip names, e.g. 'intro.mp4') to play them; " +
                    "any asset without a matching file there behaves exactly as before, .obb untouched."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
