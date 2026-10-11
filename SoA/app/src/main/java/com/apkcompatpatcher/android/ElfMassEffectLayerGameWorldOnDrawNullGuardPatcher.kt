package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's landscape-orientation crash - a brand new crash, found
 * only after fix #65 (see the other `ElfMassEffect*` patchers) made the game reach real, ongoing
 * gameplay rendering: `LayerGameWorld::OnDraw()` SIGSEGVs a few frames in, but ONLY in landscape
 * orientation - portrait never triggers it. Confirmed (by disabling `am compat`'s
 * `OVERRIDE_ANY_ORIENTATION`/`OVERRIDE_ANY_ORIENTATION_TO_USER`/`FORCE_RESIZE_APP` flags and
 * retesting) to be a genuine native engine bug, not an artifact of Android's orientation-override
 * compat framework - the identical crash reproduces either way.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000188
 * Cause: null pointer dereference
 * #00 pc 0031491c  (LayerGameWorld::OnDraw(eastl::intrusive_ptr<m3g::Renderer> const&)+244)
 * ```
 *
 * ## Root cause
 *
 * Disassembling `OnDraw()` around the fault site:
 * ```
 * 314908: ldr r3, [r4, #24]        ; r3 = some per-frame camera/lens-flare-related object
 * 314910: ldr r2, [r3, #0x1c4]     ; r2 = a sub-object pointer read from it - NULL under some
 *                                  ;      orientation/aspect-ratio configuration this tablet's
 *                                  ;      unusual 1920x1200 landscape mode hits every time
 * 314914: ldr r0, [r6, r1]         ; (unrelated - different registers, untouched by this fix)
 * 314918: ldr r1, [pc, #980]       ; (unrelated - different registers, untouched by this fix)
 * 31491c: ldr r2, [r2, #0x188]     ; <-- CRASH: r2 is NULL, fault addr = 0 + 0x188 = 0x188,
 *                                  ;     matching the real device crash log exactly
 * ```
 * followed by a chain of `vdiv.f32`/`vsub.f32`/`vcmpe.f32` on the loaded values - this is a
 * camera/lens-flare aspect-ratio computation, and whatever normally supplies this sub-object is
 * either never set up for this orientation/resolution combination, or is torn down and not
 * recreated across an orientation change - either way, `OnDraw()` gets a NULL pointer here and
 * dereferences it completely unconditionally.
 *
 * ## The fix: guard the NULL, reuse the function's OWN existing fallback path
 *
 * `OnDraw()` already contains a ready-made "lens flare data unavailable, use a neutral default"
 * path a few instructions later: its own `bmi 0x314c18` (taken when the aspect-ratio comparison
 * at `0x31494c` goes the other way) lands at `0x314c18`, which does exactly
 * `vldr s16, [pc, #180]` (loads the constant `0.0`) then `b 0x314960` - rejoining the function's
 * normal flow with a safe, neutral flare-intensity default. This is not code written for this fix;
 * it is the function's own pre-existing bail-out for "no usable flare data this frame", reused
 * as-is for our new NULL-pointer case too.
 *
 * - **Callsite** (`0x31491c`, 4 bytes): `ldr r2, [r2, #0x188]` becomes a `b` into a 3-fragment
 *   trampoline chain (fresh free `.rodata` gaps, cross-checked against every cave address every
 *   prior fix in this project already claims, so there is no collision):
 *   - `0x94fd60` (F1): `cmp r2, #0` / `b 0x94fd78`
 *   - `0x94fd78` (F2, two-branch fragment): `beq 0x314c18` (invalid: reuse the function's own
 *     safe fallback) / `b 0x94fdd0` (valid: continue to F3)
 *   - `0x94fdd0` (F3, terminal): `ldr r2, [r2, #0x188]` (the original instruction, now relocated
 *     and only reached when `r2` is confirmed non-NULL) / `b 0x314920` (resumes the untouched
 *     original code right after the callsite)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld` against the real target addresses,
 * verified with `objdump` (every branch target resolves to exactly the intended address, and the
 * relocated `ldr r2, [r2, #0x188]` reassembles to the exact original 4 bytes byte-for-byte), then
 * verified again with this project's `qemu-arm` micro-harness simulating both the invalid case
 * (`r2 = 0`, the exact real crash condition) and a valid case (`r2` pointing at a real word):
 * the invalid case takes the bail path with `r2` left completely untouched (proving the
 * dereference never runs and nothing crashes), and the valid case performs the exact original
 * dereference and produces the exact original result (`0x12345678` from the test's control word),
 * proving behavior for a real, valid pointer is completely unchanged.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions (the callsite plus all three cave fragments, which must still be untouched/zero-filled)
 * match byte-for-byte what this was built against - any single mismatch leaves the file
 * completely untouched; all four regions are applied together or not at all. For a real, non-NULL
 * flare sub-object, behavior is provably identical to the original code.
 */
object ElfMassEffectLayerGameWorldOnDrawNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0031491CL
    private const val TARGET_VADDR_CAVE_F1 = 0x0094FD60L
    private const val TARGET_VADDR_CAVE_F2 = 0x0094FD78L
    private const val TARGET_VADDR_CAVE_F3 = 0x0094FDD0L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr r2, [r2, #0x188]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x88.toByte(), 0x21, 0x92.toByte(), 0xE5.toByte()
    )
    // b 0x94fd60
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x0F, 0xED.toByte(), 0x18, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r2, #0 / b 0x94fd78
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x52, 0xE3.toByte(),
        0x03, 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x314c18 / b 0x94fdd0
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0xA6.toByte(), 0x13, 0xE7.toByte(), 0x0A,
        0x13, 0x00, 0x00, 0xEA.toByte()
    )
    // ldr r2, [r2, #0x188] (relocated original) / b 0x314920
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x88.toByte(), 0x21, 0x92.toByte(), 0xE5.toByte(),
        0xD1.toByte(), 0x12, 0xE7.toByte(), 0xEA.toByte()
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
                "LayerGameWorld::OnDraw() no longer dereferences a NULL lens-flare/camera sub-object " +
                    "pointer that this device's landscape orientation leaves unset - it now falls back " +
                    "to the function's own existing neutral default (the same path it already uses for " +
                    "'no usable flare data this frame') instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
