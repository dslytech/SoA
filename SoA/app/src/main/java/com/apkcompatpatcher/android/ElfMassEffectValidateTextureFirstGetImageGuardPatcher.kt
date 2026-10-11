package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #64, found in the 0.70 test - a new crash inside
 * `OpenGLESRenderer::ValidateTexture()`, the SAME function fix #60 already guards, but at the
 * FIRST `Texture2D::GetImage()` call this time, not the second (redundant) one fix #60 targets -
 * and a fundamentally different failure shape: the crashing pointer isn't an obviously-wild
 * magnitude, it's a dangling reference that merely happens to be unmapped.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xe3a03130
 *     r0  e3a03000  r1  e8363150  r2  00000001  r3  e8959b90
 * #00 pc 00404348  (m3g::Texture2D::GetImage() const+0)
 * #01 pc 0042399c  (m3g::OpenGLESRenderer::ValidateTexture(...)+24)
 * #02 pc 0012d208  (LoadTransitionTask::UploadTextures(...)+156)
 * ```
 * `GetImage()` is a two-instruction accessor - `ldr r0,[r0,#0x130]` / `bx lr` - and it faults on
 * its very FIRST instruction, meaning the crash isn't about what `GetImage()` returns (fix #60's
 * territory); it's about the `this` pointer passed INTO it. `ValidateTexture()` reads the raw
 * `Texture2D*` out of its `eastl::intrusive_ptr` parameter TWICE (`ldr r0,[r1]` for this first call,
 * `ldr r0,[r4]` - same address, `r4` having just been set to `r1` - for the second), and calls
 * `GetImage()` on it before ever checking it's non-null/plausible. Fault addr `0xe3a03130` minus
 * `GetImage()`'s field offset (`0x130`) is exactly `r0`'s value (`0xe3a03000`) - the object pointer
 * itself is what's unmapped.
 *
 * ## Root cause - and why this one doesn't fit this project's usual pointer-magnitude check
 *
 * Every prior "garbage pointer" fix in this project (fixes #48 onward) has relied on the corrupted
 * value's MAGNITUDE looking obviously wrong - a small sentinel, an address with a `0xff......` or
 * `0x80......`-style wild prefix, or simply below the `0xC0000000` real-heap floor this device's
 * allocator has consistently used everywhere else. `0xe3a03000` does NOT fit that pattern - it's
 * squarely inside the normal `0xC0000000`-`0xF0000000` heap range every other valid pointer in this
 * project's crash logs has occupied. A pure floor/ceiling magnitude check, as used by every other
 * guard here, would wave this value straight through. This is a plain dangling-pointer bug (the
 * referenced `Texture2D` was freed/unmapped while this `intrusive_ptr` still held a raw reference to
 * it) rather than an uninitialized-memory pattern, and it can't be distinguished from a live object
 * by value alone.
 *
 * What IS unusual about `0xe3a03000`: it is exactly 4096-byte (page) aligned. A real, live
 * `Texture2D` allocated by this game's own small-object heap has no reason to ever start on a page
 * boundary - that shape is far more consistent with a pointer into memory that has since been
 * unmapped (freed back to the OS) than with any legitimately-live small object this project has
 * seen elsewhere. This is a genuinely different, LOWER-CONFIDENCE signal than this project's usual
 * magnitude checks (based on a single observed occurrence, not several), so it's combined with -
 * not substituted for - the existing floor/ceiling check, and shipped as a best-effort improvement
 * pending a second real-device data point.
 *
 * ## The fix: reject a page-aligned OR out-of-range pointer before calling `GetImage()`
 *
 * - **Callsite** (`0x423990`, 4 bytes): the `ldr r0,[r1]` becomes a `b` into a 7-fragment
 *   trampoline chain (fresh free `.rodata` gaps, re-scanned to exclude every region every prior fix
 *   already claims):
 *   - `0x954290` (F1): `ldr r0,[r1]` (the original instruction, relocated) / `b 0x954848`
 *   - `0x954848` (F2): `lsls ip, r0, #20` (shifts the low 12 bits of `r0` to the top and sets the
 *     zero flag iff they were all zero - i.e. iff `r0` is page-aligned; uses `ip`, a genuinely free
 *     scratch register here, so `r2`/`r6` - needed a few instructions later by the untouched
 *     original code - are never disturbed) / `b 0x954DE8`
 *   - `0x954DE8` (F3): `beq 0x423a40` (invalid: page-aligned, reuse this function's own existing
 *     "texture invalid" bail target - same convention fix #60 uses) / `b 0x954EF0`
 *   - `0x954EF0` (F4): `cmp r0, #0xC0000000` / `b 0x954F20`
 *   - `0x954F20` (F5): `blo 0x423a40` (invalid: below the real-heap floor) / `b 0x954F38`
 *   - `0x954F38` (F6): `cmp r0, #0xF0000000` / `b 0x954F80`
 *   - `0x954F80` (F7, terminal): `bhs 0x423a40` (invalid: at/above the wild-pointer ceiling) /
 *     `b 0x423994` (valid: resumes the untouched original code right after the callsite - `mov
 *     r4,r1`, then the second, already-fix-#60-guarded `GetImage()` call)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all seven branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness: the exact real crash value (`r0 = 0xe3a03000`, page-aligned) now resolves to the
 * function's "texture invalid" bail without ever calling `GetImage()`; an ordinary sub-floor garbage
 * value (`r0 = 0x30776`, this project's earlier `fix #60` crash value, kept as a regression check)
 * still resolves to the same bail; and a plausible, non-page-aligned real pointer (`r0 = 0xddf10cd4`)
 * resolves to resuming exactly at `0x423994` with `r2`/`r6` unaffected.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original (pristine, unpatched) bytes
 * at ALL EIGHT target regions match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all eight regions are applied together or not at all. For a
 * real, non-page-aligned, in-range `Texture2D*` (every legitimately-live object this project has
 * observed), behavior is provably identical to the original - same load, same call, same
 * continuation.
 */
object ElfMassEffectValidateTextureFirstGetImageGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00423990L
    private const val TARGET_VADDR_CAVE_F1 = 0x00954290L
    private const val TARGET_VADDR_CAVE_F2 = 0x00954848L
    private const val TARGET_VADDR_CAVE_F3 = 0x00954DE8L
    private const val TARGET_VADDR_CAVE_F4 = 0x00954EF0L
    private const val TARGET_VADDR_CAVE_F5 = 0x00954F20L
    private const val TARGET_VADDR_CAVE_F6 = 0x00954F38L
    private const val TARGET_VADDR_CAVE_F7 = 0x00954F80L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x00, 0x91.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x3E, 0xC2.toByte(), 0x14, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x91.toByte(), 0xE5.toByte(),
        0x6B, 0x01, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x00, 0xCA.toByte(), 0xB0.toByte(), 0xE1.toByte(),
        0x65, 0x01, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x14, 0x3B, 0xEB.toByte(), 0x0A,
        0x3F, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F4 = byteArrayOf(
        0x03, 0x01, 0x50, 0xE3.toByte(),
        0x09, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F5 = byteArrayOf(
        0xC6.toByte(), 0x3A, 0xEB.toByte(), 0x3A,
        0x03, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F6 = byteArrayOf(
        0x0F, 0x02, 0x50, 0xE3.toByte(),
        0x0F, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F7 = byteArrayOf(
        0xAE.toByte(), 0x3A, 0xEB.toByte(), 0x2A,
        0x82.toByte(), 0x3A, 0xEB.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL EIGHT target regions match. Anything else (wrong file, any mismatch) is left completely
     * untouched - this is all-or-nothing, never a partial application.
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
            val offF4 = fileOffsetFor(TARGET_VADDR_CAVE_F4)
            val offF5 = fileOffsetFor(TARGET_VADDR_CAVE_F5)
            val offF6 = fileOffsetFor(TARGET_VADDR_CAVE_F6)
            val offF7 = fileOffsetFor(TARGET_VADDR_CAVE_F7)
            val caveOffs = listOf(offF1, offF2, offF3, offF4, offF5, offF6, offF7)
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
            for (i in PATCHED_BYTES_CAVE_F4.indices) bytes[offF4!! + i] = PATCHED_BYTES_CAVE_F4[i]
            for (i in PATCHED_BYTES_CAVE_F5.indices) bytes[offF5!! + i] = PATCHED_BYTES_CAVE_F5[i]
            for (i in PATCHED_BYTES_CAVE_F6.indices) bytes[offF6!! + i] = PATCHED_BYTES_CAVE_F6[i]
            for (i in PATCHED_BYTES_CAVE_F7.indices) bytes[offF7!! + i] = PATCHED_BYTES_CAVE_F7[i]
            return Result(
                true,
                "OpenGLESRenderer::ValidateTexture() no longer calls Texture2D::GetImage() on its " +
                    "FIRST, redundant read of the texture's raw pointer when that pointer is page-aligned " +
                    "(a dangling-pointer signature) or outside this project's established real-heap " +
                    "range - it now bails to this function's own existing 'texture invalid' path instead " +
                    "of crashing inside GetImage() itself."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
