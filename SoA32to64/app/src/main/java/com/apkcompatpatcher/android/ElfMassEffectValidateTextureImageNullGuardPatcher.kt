package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #60, found in the 0.64 test (every fix through
 * #58 plus the StateTransition Round 7 update present) - a brand new crash, deep in the actual
 * texture-upload/rendering subsystem, never reached before: `m3g::Image2D::IsInvalid() const`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000da
 * Cause: null pointer dereference
 * #00 pc 003f897c  (m3g::Image2D::IsInvalid() const)
 * #01 pc 004239b0  (m3g::OpenGLESRenderer::ValidateTexture(eastl::intrusive_ptr<m3g::Texture2D>
 *                    const&, bool)+44)
 * #02 pc ...        (LoadTransitionTask::UploadTextures(...)+156)
 * ```
 * `IsInvalid()`'s very first instruction is `ldr r3, [r0, #68]` - fault addr `0xda` minus field
 * offset `0x44` (68) is `0x96`, a small garbage/sentinel `this` pointer, not a real NULL - Android's
 * crash reporter labels any low-address fault "null pointer dereference" generically, but this is
 * the project's familiar "uninitialized small value" pattern again.
 *
 * ## Root cause
 *
 * `ValidateTexture()` calls `Texture2D::GetImage()` TWICE on the same texture object:
 * ```
 * cmp  r0, #0
 * beq  0x423a40                  ; first GetImage() result checked...
 * ldr  r0, [r4]
 * bl   Texture2D::GetImage        ; ...but then called AGAIN, redundantly
 * bl   Image2D::IsInvalid         ; <-- CRASH: this second result is never null/sentinel-checked
 * cmp  r0, #0
 * beq  0x423a40
 * ```
 * The first call's result is properly guarded (`cmp r0,#0` / `beq 0x423a40`, the function's own
 * "texture invalid, bail" convention). The second, redundant call to the exact same getter has no
 * such check before its result is fed straight into `IsInvalid()`.
 *
 * ## The fix: gate the second `GetImage()` result before calling `IsInvalid()`
 *
 * - **Callsite** (`0x4239b0`, 4 bytes): the `bl Image2D::IsInvalid` becomes a `bl` into a
 *   3-fragment trampoline chain (fresh free `.rodata` gaps, re-scanned to exclude every region
 *   every prior fix - including the StateTransition Round 7 update's five - already claims):
 *   - `0x953B28` (F1): `cmp r0, #0xC0000000` / `b 0x953B80`
 *   - `0x953B80` (F2): `blo 0x423a40` (invalid: reuse the function's own existing "texture
 *     invalid" bail target - same convention as fix #58 and every guard before it) /
 *     `b 0x953C10` (valid: fall through to F3)
 *   - `0x953C10` (F3, terminal-valid): `bl 0x3f897c` (the REAL `Image2D::IsInvalid()`, called
 *     explicitly since this fragment - unlike a plain relocated instruction - stands in for the
 *     original `bl` itself) / `b 0x4239b4` (resumes the untouched original code right after the
 *     callsite, with `r0` now holding `IsInvalid()`'s real return value exactly as before)
 *
 * The nested `bl` inside F3 clobbers `lr`, but that's safe here: F3 resumes via a plain `b`, not
 * `bx lr`, so the callsite's own `lr` (already consumed by the outer `bl F1` at the callsite) is
 * never needed again by this cave.
 *
 * ## Round 2 (shipped as 0.66): the floor was too low
 *
 * Shipped as 0.65 with F1/F2 checking `r0 <= 0x1000` (mirroring fix #58's `GetSunFlare` guard,
 * whose one observed crash value happened to be a low sentinel). The 0.66 test's crash log showed
 * `IsInvalid()` faulting again - same call site (`#01 pc 00953c10`, i.e. reached through this very
 * cave's F3, proving the guard let a bad pointer through) - but this time with fault addr `0x307ba`,
 * i.e. `r0 = 0x30776`: comfortably above `0x1000`, so Round 1's check waved it through, yet nowhere
 * near a real heap pointer either (every real object this project has seen on this device is
 * `>= 0xC0000000`). A mid-range garbage value, not a low sentinel - the same category of value every
 * OTHER guard in this project already screens for with the shared `< 0xC0000000` real-heap-floor
 * check (see e.g. `ElfMassEffectLoadLevelSubtitleWidgetGuardPatcher`). Round 2 simply widens F1/F2 to
 * that same, already-proven floor: `cmp r0, #0xC0000000` / `blo` instead of `cmp r0, #0x1000` /
 * `bls` - same two instructions, same 8-byte slots, only the threshold and branch condition change.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all three branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness: both the exact real Round 2 crash value (`r0 = 0x30776`) and a valid heap-range
 * pointer (`r0 = 0xd8da9430`, with synthetic `Image2D` fields at `+0x44`/`+0x48` so the real
 * `IsInvalid()` body runs to completion) resolve to the correct outcome - landing on the function's
 * own "texture invalid" bail target for the invalid case, and resuming exactly at `0x4239b4` (right
 * after the original callsite) with a real `IsInvalid()` return value in `r0` for the valid case.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original (pristine, unpatched) bytes
 * at ALL FOUR target regions match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all four regions are applied together or not at all. For a
 * real `Image2D*` (always well above the `0xC0000000` floor on every device this project has seen
 * data from), behavior is provably identical to the original - the real `IsInvalid()` still runs and
 * its real return value is still used exactly as before.
 */
object ElfMassEffectValidateTextureImageNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x004239B0L
    private const val TARGET_VADDR_CAVE_F1 = 0x00953B28L
    private const val TARGET_VADDR_CAVE_F2 = 0x00953B80L
    private const val TARGET_VADDR_CAVE_F3 = 0x00953C10L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xF1.toByte(), 0x53, 0xFF.toByte(), 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x5C, 0xC0.toByte(), 0x14, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // Round 2 (0.66): widened from "r0 <= 0x1000" to "r0 < 0xC0000000" (the same real-heap-floor
    // check used throughout this project) - the 0.65 fix's low-sentinel-only check missed a
    // mid-range garbage value (0x30776) that still isn't remotely close to a real heap pointer.
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x03, 0x01, 0x50, 0xE3.toByte(),
        0x13, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0xAE.toByte(), 0x3F, 0xEB.toByte(), 0x3A,
        0x21, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x59, 0x93.toByte(), 0xEA.toByte(), 0xEB.toByte(),
        0x66, 0x3F, 0xEB.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL FOUR target regions match. Anything else (wrong file, any mismatch) is left
     * completely untouched - this is all-or-nothing, never a partial application.
     */
    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libMassEffect.so")) {
            return Result(false, "not libMassEffect.so - not applicable")
        }
        try {
            if (bytes.size < 52) return Result(false, "too small to be an ELF file - not touching")
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.toByte() ||
                bytes[2] != 'L'.toByte() || bytes[3] != 'F'.toByte()
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

            val offCallsite = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val offF1 = fileOffsetFor(TARGET_VADDR_CAVE_F1)
            val offF2 = fileOffsetFor(TARGET_VADDR_CAVE_F2)
            val offF3 = fileOffsetFor(TARGET_VADDR_CAVE_F3)
            val caveOffs = listOf(offF1, offF2, offF3)
            if (offCallsite == null || caveOffs.any { it == null } ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                caveOffs.any { it!! < 0 || it + EXPECTED_ORIGINAL_CAVE.size > bytes.size }
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                caveOffs.any { !matches(it!!, EXPECTED_ORIGINAL_CAVE) }
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE_F1.indices) bytes[offF1!! + i] = PATCHED_BYTES_CAVE_F1[i]
            for (i in PATCHED_BYTES_CAVE_F2.indices) bytes[offF2!! + i] = PATCHED_BYTES_CAVE_F2[i]
            for (i in PATCHED_BYTES_CAVE_F3.indices) bytes[offF3!! + i] = PATCHED_BYTES_CAVE_F3[i]
            return Result(
                true,
                "OpenGLESRenderer::ValidateTexture() no longer calls Image2D::IsInvalid() on the " +
                    "redundant, unchecked second Texture2D::GetImage() result - a small " +
                    "garbage/sentinel pointer now bails to this function's own existing " +
                    "'texture invalid' path instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
