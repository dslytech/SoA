package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #72) - part of the same batch as fix #70
 * (`GetCameraController`), #71 (`GetPlayer`), #73 (`GetDynamicsWorld`) and #74 (`GetGravity`).
 * See [ElfMassEffectGetCameraControllerNullGuardPatcher]'s doc comment for the full context and
 * rationale for guarding this whole accessor family proactively, without a crash log for each one.
 *
 * ## How this was found
 *
 * ```
 * _ZNK10GameObject11GetM3GWorldEv:
 *   ldr r3, [r0, #12]     ; <-- would crash here if r0 (this) is NULL/invalid
 *   ldr r0, [r3, #24]     ; return this->world->m3gWorld
 *   bx lr
 * ```
 * No real-device crash log has hit this specific accessor yet - proactive guard, verified by
 * `objdump` and this project's `qemu-arm` micro-harness (both invalid and valid `r0`), not by an
 * observed crash. If `this` is in fact never null here in practice, the guard is a permanent no-op.
 *
 * ## The fix
 *
 * - **Callsite** (`0x1daea0`, 4 bytes): `ldr r3, [r0, #12]` becomes a `b` into a 3-fragment
 *   trampoline chain plus bail (fresh free `.rodata` gaps, cross-checked against every cave
 *   address every prior fix in this project already claims):
 *   - `0x953660` (F1): `cmp r0, #0` / `b 0x953a00`
 *   - `0x953a00` (F2, two-branch fragment): `beq 0x953bf0` (invalid: bail) / `b 0x953b58`
 *     (valid: continue to F3)
 *   - `0x953b58` (F3, terminal): `ldr r3, [r0, #12]` (relocated original) / `b 0x1daea4`
 *     (resumes the untouched original code)
 *   - `0x953bf0` (BAIL, terminal): `mov r0, #0` / `bx lr` - returns NULL directly
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only `libMassEffect.so`, only if the
 * exact original bytes at ALL FOUR target regions match byte-for-byte; any mismatch leaves the
 * file completely untouched, all four regions applied together or not at all.
 */
object ElfMassEffectGetM3GWorldNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001DAEA0L
    private const val TARGET_VADDR_CAVE_F1 = 0x00953660L
    private const val TARGET_VADDR_CAVE_F2 = 0x00953A00L
    private const val TARGET_VADDR_CAVE_F3 = 0x00953B58L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00953BF0L

    // ldr r3, [r0, #12]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x0C, 0x30, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x953660
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xEE.toByte(), 0xE1.toByte(), 0x1D, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0, #0 / b 0x953a00
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0xE5.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x953bf0 / b 0x953b58
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x7A, 0x00, 0x00, 0x0A,
        0x53, 0x00, 0x00, 0xEA.toByte()
    )
    // ldr r3, [r0, #12] (relocated original) / b 0x1daea4
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x0C, 0x30, 0x90.toByte(), 0xE5.toByte(),
        0xD0.toByte(), 0x1C, 0xE2.toByte(), 0xEA.toByte()
    )
    // mov r0, #0 / bx lr
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x00, 0x00, 0xA0.toByte(), 0xE3.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )

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
                "GameObject::GetM3GWorld() const no longer dereferences a NULL 'this' pointer - " +
                    "proactive guard (same accessor-family bug shape as fixes #67/#69), returns " +
                    "NULL instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
