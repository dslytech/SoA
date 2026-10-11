package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #70) - NOT triggered by a real crash log this
 * round. The user asked (2026-09-12, after fix #69) to cut down the number of remaining
 * one-crash-at-a-time real-device test rounds, since fixes #66-#69 were four crashes in a row in
 * the same per-frame render/HUD call chain, each only found by testing the previous one. Agreed
 * approach: proactively scan the SAME `GameObject::` accessor family that fixes #67
 * (`GetNode()`) and #69 (`Playable::AllowIndicators()`) already proved buggy - a tiny "leaf"
 * const accessor that dereferences its own `this` pointer completely unconditionally, on its very
 * first instruction, with no NULL check - and guard every other accessor sharing that exact
 * shape, even without a crash log for each one individually.
 *
 * ## How this was found
 *
 * Static disassembly of every small `GameObject::Get*() const` accessor in `libMassEffect.so`
 * (via `arm-linux-gnueabi-objdump`) turned up several 3-instruction leaf functions of the form
 * `ldr r3, [r0, #12]` / `ldr r0, [r3, #N]` / `bx lr` - i.e. read a secondary object pointer out of
 * `this` at a fixed offset, then return a field from THAT object, with `this` (`r0`) itself never
 * checked. This is structurally identical to fix #67's `GameObject::GetNode() const` bug and fix
 * #69's `Playable::AllowIndicators() const` bug, just in a sibling accessor:
 *
 * ```
 * _ZNK10GameObject19GetCameraControllerEv:
 *   ldr r3, [r0, #12]     ; <-- would crash here if r0 (this) is NULL/invalid
 *   ldr r0, [r3, #52]     ; return this->world->cameraController
 *   bx lr
 * ```
 *
 * No real-device crash log has hit this specific accessor yet - this is a preventive guard, not a
 * confirmed-crash fix, exactly as agreed with the user as the trade-off for fewer test rounds:
 * verified by `objdump` (every branch resolves to the intended address) and by this project's
 * `qemu-arm` micro-harness (both an `r0 = 0` invalid case and a valid heap-range `r0` case), but
 * NOT by an observed real crash, since none has occurred here. If this accessor's `this` is in
 * fact never null in practice, the guard is a permanent no-op with zero behavioral effect.
 *
 * ## The fix: guard `this`, return NULL directly (same outcome the function already produces
 * whenever the underlying world/camera-controller object doesn't exist)
 *
 * - **Callsite** (`0x1daeb8`, 4 bytes): the `ldr r3, [r0, #12]` becomes a `b` into a 3-fragment
 *   trampoline chain plus a one-instruction-pair bail (fresh free `.rodata` gaps, cross-checked
 *   against every cave address every prior fix in this project already claims):
 *   - `0x952388` (F1): `cmp r0, #0` / `b 0x9523f8`
 *   - `0x9523f8` (F2, two-branch fragment): `beq 0x952ad8` (invalid: bail) / `b 0x952830`
 *     (valid: continue to F3)
 *   - `0x952830` (F3, terminal): `ldr r3, [r0, #12]` (the original instruction, now relocated and
 *     only reached when `r0` is confirmed non-NULL) / `b 0x1daebc` (resumes the untouched
 *     original code right after the callsite)
 *   - `0x952ad8` (BAIL, terminal): `mov r0, #0` / `bx lr` - returns NULL directly, matching this
 *     leaf function's own "no stack frame, just return" shape
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions match byte-for-byte what this was built against - any single mismatch leaves the file
 * completely untouched; all four regions are applied together or not at all. For a real, non-NULL
 * `GameObject`, behavior is provably identical to the original.
 */
object ElfMassEffectGetCameraControllerNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001DAEB8L
    private const val TARGET_VADDR_CAVE_F1 = 0x00952388L
    private const val TARGET_VADDR_CAVE_F2 = 0x009523F8L
    private const val TARGET_VADDR_CAVE_F3 = 0x00952830L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00952AD8L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr r3, [r0, #12]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x0C, 0x30, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x952388
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x32, 0xDD.toByte(), 0x1D, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0, #0 / b 0x9523f8
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x19, 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x952ad8 / b 0x952830
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0xB6.toByte(), 0x01, 0x00, 0x0A,
        0x0B, 0x01, 0x00, 0xEA.toByte()
    )
    // ldr r3, [r0, #12] (relocated original) / b 0x1daebc
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x0C, 0x30, 0x90.toByte(), 0xE5.toByte(),
        0xA0.toByte(), 0x21, 0xE2.toByte(), 0xEA.toByte()
    )
    // mov r0, #0 / bx lr
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x00, 0x00, 0xA0.toByte(), 0xE3.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
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
            val offBail = fileOffsetFor(TARGET_VADDR_CAVE_BAIL)
            val caveOffs = listOf(offF1, offF2, offF3, offBail)
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
            for (i in PATCHED_BYTES_CAVE_BAIL.indices) bytes[offBail!! + i] = PATCHED_BYTES_CAVE_BAIL[i]
            return Result(
                true,
                "GameObject::GetCameraController() const no longer dereferences a NULL 'this' " +
                    "pointer - proactive guard (fixes #67/#69's same accessor-family bug shape, " +
                    "no crash log yet for this specific one), returns NULL instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
