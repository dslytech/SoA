package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #86) - part of the fix #82-#88 `GameObject`
 * accessor batch. See [ElfMassEffectGetRigidBodyPtrNullGuardPatcher]'s doc comment (fix #82) for
 * the full scan methodology and batch context.
 *
 * ```
 * _ZNK10GameObject8IsHiddenEv:
 *   ldr r0, [r0, #56]   ; <-- would crash here if r0 (this) is NULL/invalid
 *   and r0, r0, #1
 *   bx lr
 * ```
 * Same "zero propagates through a downstream bitwise-only op" guard already used for
 * [ElfMassEffectIsVisibleNullGuardPatcher] (fix #83) and [ElfMassEffectIsCloakedNullGuardPatcher]
 * (fix #85) - here with just a single `and`, no `lsr` first.
 *
 * The callsite (`0x1daf1c`, 4 bytes) becomes a `b` into a free 12-byte `.rodata` gap at
 * `0x98bb20` (aligned tail padding right after a C++ typeinfo name, before a small table of
 * `vec4`/quaternion identity constants - cross-checked against every other cave address already
 * claimed by any patcher in this project, and against the constants table itself so the guard
 * only touches the genuinely-unused padding bytes before it, never any of the table's real zero
 * components):
 * ```
 * cmp r0, #0
 * ldrne r0, [r0, #56]   ; only dereference if r0 (this) isn't NULL
 * b 0x1daf20              ; resume at the original and/bx lr either way
 * ```
 * Verified by `objdump` (branch resolves correctly) and this project's `qemu-arm` micro-harness
 * (both `r0 = 0` invalid, and a valid heap-range `r0` confirming the bit is extracted correctly
 * on the valid path). Same two-layer gate as every native patch here: only `libMassEffect.so`,
 * only if the exact original bytes at both regions match byte-for-byte.
 */
object ElfMassEffectIsHiddenNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001DAF1CL
    private const val TARGET_VADDR_CAVE = 0x0098BB20L

    // ldr r0, [r0, #56]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x38, 0x00, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x98bb20
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xFF.toByte(), 0xC2.toByte(), 0x1E, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(12)

    // cmp r0, #0 / ldrne r0, [r0, #56] / b 0x1daf20
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x38, 0x00, 0x90.toByte(), 0x15,
        0xFC.toByte(), 0x3C, 0xE1.toByte(), 0xEA.toByte()
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
                "GameObject::IsHidden() const no longer dereferences a NULL 'this' pointer - " +
                    "proactive guard (part of the fix #82-#88 GameObject accessor batch), returns " +
                    "false instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
