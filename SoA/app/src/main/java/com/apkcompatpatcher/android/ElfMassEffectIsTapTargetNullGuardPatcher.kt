package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #84) - part of the fix #82-#88 `GameObject`
 * accessor batch. See [ElfMassEffectGetRigidBodyPtrNullGuardPatcher]'s doc comment (fix #82) for
 * the full scan methodology and batch context.
 *
 * ```
 * _ZNK10GameObject11IsTapTargetEv:
 *   ldr r0, [r0, #504]   ; <-- would crash here if r0 (this) is NULL/invalid
 *   subs r0, r0, #0
 *   movne r0, #1
 *   bx lr
 * ```
 * The downstream `subs r0, r0, #0` / `movne r0, #1` pair is a "is this pointer non-null"
 * idiom - with `r0 = 0` injected by the guard, `0 - 0 = 0` sets the Z flag, `movne` doesn't fire,
 * and `r0` correctly stays `0` (false), same safe "let a false/zero result propagate through
 * downstream ops" property already used for [ElfMassEffectIsVisibleNullGuardPatcher] (fix #83),
 * extended here to a trivial always-executes subtract instead of pure bitwise ops - `subs r0,
 * r0, #0` never changes `r0`'s value, only sets flags from it, so it's safe by the same logic.
 *
 * The callsite (`0xa0fb4`, 4 bytes) becomes a `b` into a free 12-byte `.rodata` gap at `0x9c30f8`
 * (aligned tail padding after a C++ typeinfo name, cross-checked against every cave address
 * already claimed by any patcher in this project):
 * ```
 * cmp r0, #0
 * ldrne r0, [r0, #504]   ; only dereference if r0 (this) isn't NULL
 * b 0xa0fb8               ; resume at the original subs/movne/bx lr either way
 * ```
 * Verified by `objdump` (branch resolves correctly) and this project's `qemu-arm` micro-harness
 * (both `r0 = 0` invalid, and a valid heap-range `r0` confirming the flag is extracted correctly
 * on the valid path). Same two-layer gate as every native patch here: only `libMassEffect.so`,
 * only if the exact original bytes at both regions match byte-for-byte.
 */
object ElfMassEffectIsTapTargetNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x000A0FB4L
    private const val TARGET_VADDR_CAVE = 0x009C30F8L

    // ldr r0, [r0, #504]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xF8.toByte(), 0x01, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x9c30f8
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x4F, 0x88.toByte(), 0x24, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(12)

    // cmp r0, #0 / ldrne r0, [r0, #504] / b 0xa0fb8
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0xF8.toByte(), 0x01, 0x90.toByte(), 0x15,
        0xAC.toByte(), 0x77, 0xDB.toByte(), 0xEA.toByte()
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
                "GameObject::IsTapTarget() const no longer dereferences a NULL 'this' pointer - " +
                    "proactive guard (part of the fix #82-#88 GameObject accessor batch), returns " +
                    "false instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
