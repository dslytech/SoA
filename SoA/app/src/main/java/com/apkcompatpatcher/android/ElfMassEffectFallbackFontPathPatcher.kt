package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: redirects the game's hardcoded
 * CJK/fallback font path away from a system font file that no longer exists on modern Android,
 * likely the actual ROOT CAUSE of this whole project's long-running FreeType crash saga (fixes
 * #27-31, #80, #91) - all of which have so far only guarded individual downstream NULL-pointer
 * *consequences* of this one upstream failure, never the failure itself.
 *
 * ## Background
 *
 * `libMassEffect.so`'s own .rodata hardcodes two absolute system font paths for its FreeType
 * font driver (`im::FreeTypeFont`) to open directly, found right next to each other:
 *
 *   0x98E7C0: "/system/fonts/DroidSans.ttf"           (primary font)
 *   0x98E7D8: "/system/fonts/DroidSansFallback.ttf"   (CJK/fallback font)
 *
 * Both are real paths from the Android 2.x/4.x era this 2012 game shipped for. Stock/AOSP-based
 * Android dropped `DroidSansFallback.ttf` entirely starting around Android 4.3/5.0 (replaced by
 * the Noto CJK family) - so on any modern device, `FT_New_Face()` for the fallback font fails.
 * This project already root-caused (fixes #27-31) that the game's own `im::FreeTypeFont`
 * constructor never checks `FT_New_Face()`'s return value - it proceeds with whatever (possibly
 * uninitialized) `FT_Face` resulted regardless, and every downstream crash this project has
 * guarded in the FreeType family (`FT_Request_Size`, `FT_Request_Metrics`, `FT_Done_Glyph`'s
 * `clazz` field in fix #80, its `library` field in fix #91) is consistent with exactly that:
 * operating on a face object that was never actually populated, because the file it names was
 * never there to open.
 *
 * A real-device check (2026-09-15, Hihonor tablet, Android 16) confirms `/system/fonts/` on
 * *this* device (a Magic UI/Honor build, not stock AOSP) still happens to carry `DroidSans.ttf`,
 * `DroidSansChinese.ttf`, and `DroidSansMono.ttf` alongside the full modern Noto/Roboto set - but
 * *not* `DroidSansFallback.ttf` specifically. This matches a real APK repack ("Fix RU Adreno",
 * sourced from xdaforums.com/t/game-4-0-mass-effect-infiltrator-v1-0-58-fix.4769388/) already
 * carrying exactly this same byte-for-byte string substitution in its own `libMassEffect.so`,
 * confirming both that the missing file is a real, previously-hit problem and that this specific
 * replacement path is a real device's confirmed-present file, not a guess.
 *
 * ## The fix
 *
 * Straight string replacement, no branch/cave needed (unlike this project's usual native
 * patches) - the fallback path's own 36-byte slot (35 chars + NUL) has ample room for the
 * 29-byte replacement, zero-padded same as upstream C string handling already expects:
 *
 *   "/system/fonts/DroidSansFallback.ttf\0"  (36 bytes)
 *        -> "/system/fonts/DroidSans.ttf\0\0\0\0\0\0\0\0"  (36 bytes, same length)
 *
 * The primary font path (`/system/fonts/DroidSans.ttf`, at 0x98E7C0) is left untouched - it's
 * confirmed present on the real device already, and re-pointing the fallback at the *same* file
 * the primary already successfully opens is the same "no known-good file, so just don't ever ask
 * for the missing one" logic as the reference repack's own fix, not a new guess.
 *
 * ## Why this doesn't replace fixes #27-31/#80/#91
 *
 * Those guards stay in place. This patch only makes the *common* case (both font opens succeed)
 * no longer hit the failure path those guards exist for - it doesn't prove no other path can ever
 * produce an uninitialized FT_Face (a corrupt/short font file on some other device's `/system/
 * fonts/DroidSans.ttf`, an out-of-memory FT_New_Face failure, etc.), so removing any of the
 * existing null-guards on the strength of this fix alone would be unjustified. Defense in depth:
 * this fix addresses the specific failure this project actually diagnosed as the trigger; the
 * guards remain the safety net if any other path still produces the same broken-face state.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally
 * named `libMassEffect.so`, and even then only if the exact original bytes at the target region
 * match byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectFallbackFontPathPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR = 0x0098E7D8L
    private const val SLOT_SIZE = 36 // "/system/fonts/DroidSansFallback.ttf" (35 chars) + NUL

    // "/system/fonts/DroidSansFallback.ttf\0"
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x2F, 0x73, 0x79, 0x73, 0x74, 0x65, 0x6D, 0x2F, 0x66, 0x6F, 0x6E, 0x74, 0x73, 0x2F,
        0x44, 0x72, 0x6F, 0x69, 0x64, 0x53, 0x61, 0x6E, 0x73, 0x46, 0x61, 0x6C, 0x6C, 0x62,
        0x61, 0x63, 0x6B, 0x2E, 0x74, 0x74, 0x66, 0x00
    )

    // "/system/fonts/DroidSans.ttf\0" + zero padding to fill the same 36-byte slot
    private val PATCHED = run {
        val path = "/system/fonts/DroidSans.ttf\u0000".toByteArray(Charsets.US_ASCII)
        require(path.size <= SLOT_SIZE) { "replacement path too long for the fixed slot" }
        path + ByteArray(SLOT_SIZE - path.size)
    }

    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libMassEffect.so")) {
            return Result(false, "not libMassEffect.so - not applicable")
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

            val targetOff = fileOffsetFor(TARGET_VADDR)
            if (targetOff == null || targetOff + SLOT_SIZE > bytes.size) {
                return Result(false, "target address doesn't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(targetOff, EXPECTED_ORIGINAL)) {
                return Result(false, "original bytes at the target region don't match expected (different build/version?) - left completely untouched")
            }

            for (i in PATCHED.indices) bytes[targetOff + i] = PATCHED[i]

            return Result(
                true,
                "FreeType fallback font path: \"/system/fonts/DroidSansFallback.ttf\" -> " +
                    "\"/system/fonts/DroidSans.ttf\" (the fallback file doesn't exist on modern " +
                    "Android - likely the real root cause behind this project's whole FT_New_Face " +
                    "-> FT_Done_Glyph null-guard saga; the replacement path is confirmed present on " +
                    "the real test device, and is the same one the primary font already uses)."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
