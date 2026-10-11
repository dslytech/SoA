package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Imperium Galactica 2 only) native patch: makes the FMV/cutscene video loader
 * check for a plain external "videos" folder FIRST, and only fall back to the game's original
 * (silent, "no video plays") behavior if the requested clip isn't there - so a player who already
 * legally owns dubbed/localized video files (e.g. the Hungarian dub) can drop them into that
 * folder and have them play, without ever touching the encrypted `.obb`.
 *
 * ## Background (round 1: Round 97's 0.99-ig2DirectVideoFolder)
 *
 * The Android port starts and runs fine, but no cutscene/intro videos ever play - the classic
 * Google Play "expansion file" (`.obb`) video-delivery mechanism these old ports relied on is
 * effectively dead today. Investigation found:
 *
 * - `command_OpenBink(ig2_command_t&)` (`0x0009A358`) translates a requested clip name (e.g.
 *   `"foo.bik"`) by stripping the extension and rebuilding it as `<prefix> + basename + ".mp4"`,
 *   then hands that to `ig2screen::openAnim` -> ... -> `AssetHelper::OpenRawFile`, the exact same
 *   generic asset-loading pipeline used for every other game asset - i.e. actual playback is
 *   standard MP4/H.264 via `libSDL_ffmpeg.so`, not raw Bink.
 * - The game's `.obb` is genuinely AES-256 encrypted end-to-end (confirmed via entropy analysis:
 *   ~8.0 bits/byte essentially everywhere), so cracking it wasn't attempted.
 * - `AssetHelper::OpenRawFile` has a BUILT-IN escape hatch the game's own package system already
 *   uses: if the requested path starts with the literal 8-character prefix `":direct:"`, the
 *   function skips the encrypted-package search entirely and calls plain `fopen()` on the
 *   remainder - confirmed by disassembly (the plain `fopen()` + `SDL_RWFromFP()` path at
 *   `0x2230c6`-`0x2230e6`, no decryption involved).
 *
 * Round 97 exploited this by permanently swapping the ONE literal-pool word `command_OpenBink`
 * uses for its prefix, so every video request unconditionally redirected to
 * `:direct:/storage/emulated/0/Android/obb/com.digitalreality.ig2/videos/<name>.mp4`. That
 * worked, but had a real gap: if a given clip's `.mp4` wasn't present in that external folder
 * (not yet supplied by the player, or simply doesn't apply to a particular clip), `fopen()` just
 * failed and the video silently didn't play - there was no fallback to the ORIGINAL
 * `"anim/mpeg4/" + basename + ".mp4"` package-relative path, even though that path might have
 * worked (e.g. if that particular clip's data does exist inside the `.obb`/package tree via some
 * other loader route). The player asked for this to be fixed.
 *
 * ## The fix (this round)
 *
 * Same idea already built for Haegemonia: Legions of Iron - "try the external override first,
 * else fall through to fully untouched original behavior" - but implemented at the exact point
 * where it's cheapest here: right where `command_OpenBink` loads its prefix pointer.
 *
 * `command_OpenBink` builds the final video path from two `std::operator+` calls: first
 * `<prefix> + basename` (prefix loaded via `ldr r1,[pc,#180]` at `0x9a3b6` + `add r1,pc` at
 * `0x9a3bc`, basename already computed into `r7` by `stringhelper::remove_extension` a few
 * instructions earlier), then `<that> + ".mp4"`. This patch replaces the FIRST TWO instructions
 * of that sequence - `ldr r1,[pc,#180]` (`0x9a3b6`) and the following `mov r2,r7` (`0x9a3b8`, 4
 * bytes total, exactly matching a `b.w`) - with a branch into a small cave that:
 *
 *  1. Builds a plain C string on the stack: `/storage/emulated/0/Android/obb/` +
 *     `com.digitalreality.ig2/videos/` + `<basename>` + `.mp4` (reading `basename`'s `char*` data
 *     straight out of the `std::string` object `r7` already points to - no C++ calls needed).
 *  2. Probes it with a real `fopen(path, "rb")` (the same `fopen@plt` address `OpenRawFile`
 *     already calls elsewhere in this binary). If it opens, immediately `fclose()`s it again -
 *     this is only an existence check, the REAL open happens later through the pipeline below.
 *  3. If found: sets `r1` to the same `:direct:/storage/emulated/0/Android/obb/`
 *     `com.digitalreality.ig2/videos/` string Round 97 used (so the existing, completely
 *     untouched `:direct:` mechanism in `AssetHelper::OpenRawFile` does the real, final open).
 *  4. If NOT found: sets `r1` to the address of the ORIGINAL, unmodified `"anim/mpeg4/"` literal
 *     (`0x2d1de7`) - i.e. exactly what stock `command_OpenBink` would have used, so the game
 *     falls back to its completely original package-relative lookup.
 *  5. Either way, turns that absolute address into the same PC-relative offset the very next
 *     (untouched!) instruction at `0x9a3bc` (`add r1,pc`) already expects, replicates the `mov
 *     r2,r7` this patch's own branch overwrote, undoes its own scratch stack allocation, and jumps
 *     back into `command_OpenBink` right at `0x9a3ba` (`mov r0,r8`) - i.e. everything from that
 *     point on, including both `operator+` calls, `stringhelper::fixup`, `openAnim`, and the whole
 *     `AssetHelper` pipeline, is exactly the original code, untouched, just fed a different
 *     prefix constant depending on whether the override file exists.
 *
 * Net effect for a clip requested as `"intro_human.bik"`: if the player has supplied
 * `Android/obb/com.digitalreality.ig2/videos/intro_human.mp4`, that exact file plays (via the
 * unmodified `:direct:` fopen path). If not, the game behaves EXACTLY as it did before Round 97
 * ever existed - same `"anim/mpeg4/intro_human.mp4"` package-relative lookup, same outcome.
 *
 * Cave lives at `0x002F41EC`, inside a 498-byte all-zero, non-relocated gap in `.rodata`
 * (confirmed via the same relocation-aware zero-run scan used throughout this project) - only 272
 * of those bytes are claimed. `.rodata` here shares the SAME single `R E` (readable + executable)
 * `PT_LOAD` program header as `.text` (confirmed via `readelf -l`), so code placed there is
 * actually executable at runtime, not just readable.
 *
 * ## Round 2 bug fix: this is a PIE/ASLR shared library - a naive literal pool crashed on device
 *
 * The FIRST build of this cave (shipped, then real-device tested) crashed on launch: tombstone
 * showed `SIGSEGV`/`SEGV_MAPERR` at `libig2ps.so+0x2f41f2` (the cave's very first string-copy
 * loop), fault address `0x002f4248` - suspiciously identical to that string's LINK-TIME address.
 * Root cause: this `.so` is `ET_DYN` (a shared object), loaded at a random runtime bias like every
 * other Android native library - the cave's `ldr r5, =probe_prefix`-style loads (and, more subtly,
 * `ldr r2, =fopen_plt_addr; blx r2`-style PLT calls that went through a NAMED literal instead of a
 * numeric constant, and jump-target loads for the resume address) all baked in raw *link-time*
 * absolute addresses with no bias correction - correct only if the module happened to load at
 * address 0, which it never does. Every other patcher in this project was safe from this because
 * it only ever repointed an EXISTING, compiler-generated `ldr rX,[pc,#N]` + `add rX,pc` pair (which
 * IS bias-correct by construction) rather than writing brand-new address-loading code from scratch.
 *
 * Fixed by rewriting the cave so every runtime address goes through that same idiom: any string
 * this cave actually dereferences (`probe_prefix`/`probe_suffix`/`probe_mode`) is loaded via an
 * explicit `ldr rX,[pc,#N]; add rX,pc` pair (the literal word holds a precomputed *offset*, not an
 * address); the `fopen@plt`/`fclose@plt` calls became direct `blx #<link addr>` immediates (the
 * same inherently position-independent form the original compiled code already uses for these same
 * calls elsewhere in the function - no register load needed at all); and the final resume jump
 * target uses the same offset-based idiom, computed after linking (GNU `as`'s `.equ` can't fold an
 * absolute cross-object address against an unresolved same-section label in one step, so that one
 * offset is patched into a placeholder word post-link by the same Kotlin build tooling that already
 * assembles/verifies every cave here). The `anim/mpeg4/` and `:direct:...` address values fed into
 * the ORIGINAL, untouched `add r1,pc` at `0x9a3bc` are the one deliberate exception - since that
 * subtraction's result feeds a real, already bias-correct instruction, both operands are kept as
 * raw link-time constants (the bias cancels out algebraically); bias-adjusting them too would have
 * been the same class of mistake in the opposite direction.
 *
 * ## Verification
 *
 * - Disassembled with both `arm-linux-gnueabi-objdump -d` (via a proper `-Ttext=<cave addr>`
 *   link) and `capstone` (`CS_MODE_THUMB`) independently, confirming clean instruction decoding
 *   with no illegal encodings.
 * - The probe-and-decide logic (string concatenation + real `fopen()`/`fclose()`) was extracted
 *   into a standalone, real ARM Thumb function and exercised under `qemu-arm` against both a
 *   missing file and a real one it created on disk - confirming the built path string is correct
 *   (prefix + basename + `.mp4`, no leftover NUL truncation) and that the found/not-found decision
 *   and the `fclose()` cleanup both behave correctly. (This first caught and fixed a real bug: an
 *   earlier draft forgot to back up over the basename's NUL terminator before appending `.mp4`.)
 * - NEW for round 2, specifically to catch the ASLR bug above (the `qemu-arm` test above never
 *   would have, since it never actually executes the cave's OWN address-computing instructions at
 *   a real, deployed address): applied the exact patch to a full copy of the real, extracted
 *   `libig2ps.so` and ran it under the `unicorn` CPU emulator with the WHOLE module mapped at a
 *   deliberately nonzero, arbitrary simulated load bias (`0x40000000`) - real Thumb execution, not
 *   hand-traced arithmetic - hooking only the two PLT call targets (to fake `fopen()`/`fclose()`
 *   without a real filesystem) and the resume landing address. Confirmed, for both the "file
 *   found" and "file not found" scenarios: execution reaches the resume address with no fault, the
 *   scratch stack allocation is perfectly unwound, `r2`/`r7`/`r8`/`r6` all still hold their correct
 *   entry values, and `r1` holds exactly the expected bias-independent offset - i.e. the same test
 *   that would have caught round 1's crash before it ever reached a real device now passes cleanly
 *   at a bias that is NOT the link-time address, which round 1 never was tested against.
 *
 * ## Why this is safe for every OTHER game (Mass Effect: Infiltrator, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libig2ps.so`, and even then only if the exact original bytes at both target regions (the entry
 * instructions, and the cave) match byte-for-byte what was recorded here from the real IG2 1.41
 * build.
 */
object ElfImperiumGalactica2DirectVideoFolderPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // command_OpenBink+0x5e: "ldr r1,[pc,#180]" (0x9a3b6) + "mov r2,r7" (0x9a3b8), replaced with
    // a single b.w into the cave below.
    private const val TARGET_VADDR_ENTRY = 0x0009A3B6L
    private const val TARGET_VADDR_CAVE = 0x002F41ECL

    private val EXPECTED_ORIGINAL_ENTRY = byteArrayOf(0x2D, 0x49, 0x3A, 0x46)
    private val PATCHED_BYTES_ENTRY = byteArrayOf(0x59, 0xF2.toByte(), 0x19, 0xBF.toByte())

    // 272-byte cave (round 2, ASLR-safe): probe-and-decide code + the probe prefix / ".mp4" suffix
    // / "rb" mode / ":direct:..." redirect strings + its own literal pool. Every address the cave
    // actually dereferences or jumps to is resolved via the same "ldr rX,[pc,#N]; add rX,pc" idiom
    // the original compiler-generated code uses everywhere (bias-safe under ASLR); the two PLT
    // calls (fopen/fclose) are direct `blx #<addr>` immediates; the two values feeding the
    // ORIGINAL, untouched "add r1,pc" at 0x9a3bc (the "anim/mpeg4/" and ":direct:..." addresses,
    // and the pc-relative base) are deliberately left as raw, un-adjusted link-time constants,
    // since that subtraction's result is consumed by an already bias-correct real instruction. See
    // the class doc comment above for the full round-2 bug/fix writeup and verification.
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0xC0.toByte(), 0xB0.toByte(), 0x6C, 0x46, 0x3C, 0x4D, 0x7D, 0x44, 0x15, 0xF8.toByte(), 0x01, 0x3B,
        0x04, 0xF8.toByte(), 0x01, 0x3B, 0x00, 0x2B, 0xF9.toByte(), 0xD1.toByte(), 0xA4.toByte(), 0xF1.toByte(), 0x01, 0x04,
        0x3D, 0x68, 0x15, 0xF8.toByte(), 0x01, 0x3B, 0x04, 0xF8.toByte(), 0x01, 0x3B, 0x00, 0x2B,
        0xF9.toByte(), 0xD1.toByte(), 0xA4.toByte(), 0xF1.toByte(), 0x01, 0x04, 0x34, 0x4D, 0x7D, 0x44, 0x15, 0xF8.toByte(),
        0x01, 0x3B, 0x04, 0xF8.toByte(), 0x01, 0x3B, 0x00, 0x2B, 0xF9.toByte(), 0xD1.toByte(), 0x68, 0x46,
        0x30, 0x49, 0x79, 0x44, 0x9B.toByte(), 0xF5.toByte(), 0xFC.toByte(), 0xE9.toByte(), 0x00, 0x28, 0x03, 0xD0.toByte(),
        0x9B.toByte(), 0xF5.toByte(), 0x0A, 0xEA.toByte(), 0x2D, 0x49, 0x00, 0xE0.toByte(), 0x2D, 0x49, 0x2E, 0x4B,
        0xA1.toByte(), 0xEB.toByte(), 0x03, 0x01, 0x3A, 0x46, 0x40, 0xB0.toByte(), 0x25, 0x4B, 0x7B, 0x44,
        0x18, 0x47, 0x00, 0xBF.toByte(), 0x2F, 0x73, 0x74, 0x6F, 0x72, 0x61, 0x67, 0x65,
        0x2F, 0x65, 0x6D, 0x75, 0x6C, 0x61, 0x74, 0x65, 0x64, 0x2F, 0x30, 0x2F,
        0x41, 0x6E, 0x64, 0x72, 0x6F, 0x69, 0x64, 0x2F, 0x6F, 0x62, 0x62, 0x2F,
        0x63, 0x6F, 0x6D, 0x2E, 0x64, 0x69, 0x67, 0x69, 0x74, 0x61, 0x6C, 0x72,
        0x65, 0x61, 0x6C, 0x69, 0x74, 0x79, 0x2E, 0x69, 0x67, 0x32, 0x2F, 0x76,
        0x69, 0x64, 0x65, 0x6F, 0x73, 0x2F, 0x00, 0x2E, 0x6D, 0x70, 0x34, 0x00,
        0x72, 0x62, 0x00, 0x3A, 0x64, 0x69, 0x72, 0x65, 0x63, 0x74, 0x3A, 0x2F,
        0x73, 0x74, 0x6F, 0x72, 0x61, 0x67, 0x65, 0x2F, 0x65, 0x6D, 0x75, 0x6C,
        0x61, 0x74, 0x65, 0x64, 0x2F, 0x30, 0x2F, 0x41, 0x6E, 0x64, 0x72, 0x6F,
        0x69, 0x64, 0x2F, 0x6F, 0x62, 0x62, 0x2F, 0x63, 0x6F, 0x6D, 0x2E, 0x64,
        0x69, 0x67, 0x69, 0x74, 0x61, 0x6C, 0x72, 0x65, 0x61, 0x6C, 0x69, 0x74,
        0x79, 0x2E, 0x69, 0x67, 0x32, 0x2F, 0x76, 0x69, 0x64, 0x65, 0x6F, 0x73,
        0x2F, 0x00, 0x00, 0xBF.toByte(), 0x6D, 0x61, 0xDA.toByte(), 0xFF.toByte(), 0x5A, 0x00, 0x00, 0x00,
        0x73, 0x00, 0x00, 0x00, 0x66, 0x00, 0x00, 0x00, 0x97.toByte(), 0x42, 0x2F, 0x00,
        0xE7.toByte(), 0x1D, 0x2D, 0x00, 0xC0.toByte(), 0xA3.toByte(), 0x09, 0x00
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(PATCHED_BYTES_CAVE.size)

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

            val entryOff = fileOffsetFor(TARGET_VADDR_ENTRY)
            val caveOff = fileOffsetFor(TARGET_VADDR_CAVE)

            if (entryOff == null || caveOff == null ||
                entryOff + PATCHED_BYTES_ENTRY.size > bytes.size || caveOff + PATCHED_BYTES_CAVE.size > bytes.size
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
                "command_OpenBink now probes 'Android/obb/com.digitalreality.ig2/videos/<name>.mp4' with a " +
                    "real fopen() before building its video path: if that file exists it's played (via the " +
                    "game's own untouched ':direct:' fopen escape hatch), and if it doesn't, the game falls " +
                    "back to its completely original 'anim/mpeg4/<name>.mp4' package-relative lookup - so " +
                    "clips the player hasn't supplied behave exactly as they did before this patch existed, " +
                    "instead of silently failing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
