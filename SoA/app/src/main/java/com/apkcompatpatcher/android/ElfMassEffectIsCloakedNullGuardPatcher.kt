package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #85) - part of the fix #82-#88 `GameObject`
 * accessor batch. See [ElfMassEffectGetRigidBodyPtrNullGuardPatcher]'s doc comment (fix #82) for
 * the full scan methodology and batch context.
 *
 * ```
 * _ZNK10GameObject9IsCloakedEv:
 *   ldr r0, [r0, #52]   ; <-- would crash here if r0 (this) is NULL/invalid
 *   lsr r0, r0, #17
 *   and r0, r0, #1
 *   bx lr
 * ```
 * Same shape and same safe "zero propagates through downstream bitwise ops" guard as
 * [ElfMassEffectIsVisibleNullGuardPatcher] (fix #83) - just a different bit index and field
 * offset.
 *
 * The callsite (`0xa100c`, 4 bytes) becomes a `b` into a free 12-byte `.rodata` gap at
 * `0x94f684` (the tail padding right at the `.text`/`.rodata` section boundary itself - the
 * last remaining free slot in the same `.rodata` cave farm fixes #70-74/#77-#81 already mined,
 * cross-checked against every other cave address already claimed by any patcher in this
 * project):
 * ```
 * cmp r0, #0
 * ldrne r0, [r0, #52]   ; only dereference if r0 (this) isn't NULL
 * b 0xa1010              ; resume at the original lsr/and/bx lr either way
 * ```
 * Verified by `objdump` (branch resolves correctly) and this project's `qemu-arm` micro-harness
 * (both `r0 = 0` invalid, and a valid heap-range `r0` confirming the bit is extracted correctly
 * on the valid path). Same two-layer gate as every native patch here: only `libMassEffect.so`,
 * only if the exact original bytes at both regions match byte-for-byte.
 */
object ElfMassEffectIsCloakedNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x000A100CL
    private const val TARGET_VADDR_CAVE = 0x0094F684L

    // ldr r0, [r0, #52]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x34, 0x00, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x94f684
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x9C.toByte(), 0xB9.toByte(), 0x22, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(12)

    // cmp r0, #0 / ldrne r0, [r0, #52] / b 0xa1010
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x34, 0x00, 0x90.toByte(), 0x15,
        0x5F, 0x46, 0xDD.toByte(), 0xEA.toByte()
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
            val offCave = fileOffsetFor(TARGET_VADDR_CAVE)
            if (offCallsite == null || offCave == null ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offCave < 0 || offCave + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                !matches(offCave, EXPECTED_ORIGINAL_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            return Result(
                true,
                "GameObject::IsCloaked() const no longer dereferences a NULL 'this' pointer - " +
                    "proactive guard (part of the fix #82-#88 GameObject accessor batch), returns " +
                    "false instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
