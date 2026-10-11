package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #87) - part of the fix #82-#88 `GameObject`
 * accessor batch. See [ElfMassEffectGetRigidBodyPtrNullGuardPatcher]'s doc comment (fix #82) for
 * the full scan methodology and batch context.
 *
 * ```
 * _ZN10GameObject6SelectEv:
 *   ldr r3, [r0, #512]   ; <-- would crash here if r0 (this) is NULL/invalid
 *   add r3, r3, #1
 *   str r3, [r0, #512]   ; <-- would ALSO crash here on a NULL this
 *   bx lr
 * ```
 * Unlike the other five accessors in this batch, `Select()` is a `void` mutator, not a
 * value-returning accessor, and it dereferences `this` TWICE (once to read the counter, once to
 * write it back) - so the "let zero propagate through downstream ops" trick used for
 * [ElfMassEffectIsVisibleNullGuardPatcher] and friends doesn't apply here: injecting `r3 = 0`
 * would still crash on the `str` a moment later. This needs a real "skip the whole body when
 * `this` is NULL" guard instead - the same category of guard [ElfMassEffectSelectNullGuardPatcher]'s
 * sibling fixes use a bail fragment for, except here the guard reuses the function's OWN existing
 * final `bx lr` (at `0xa1008`, otherwise untouched) as the bail target directly, instead of
 * allocating a separate bail fragment - a natural target since `Select()` returns nothing, so
 * there's no register to zero out before returning.
 *
 * The callsite (`0xa0ffc`, 4 bytes, just the first instruction - `0xa1000`/`0xa1004`/`0xa1008`
 * are all left completely untouched) becomes a `b` into a free 16-byte `.rodata` gap at
 * `0x9edbf0` (aligned tail padding after an RTTI/serializer-related name, cross-checked against
 * every other cave address already claimed by any patcher in this project):
 * ```
 * cmp r0, #0
 * beq 0xa1008            ; this == NULL: skip straight to the original bx lr, don't touch r0-r3
 * ldr r3, [r0, #512]      ; relocated original load
 * b 0xa1000               ; this != NULL: resume the original add/str/bx lr unmodified
 * ```
 * Verified by `objdump` (both branches resolve correctly) and this project's `qemu-arm`
 * micro-harness (both `r0 = 0` invalid - confirms it bails straight to the final `bx lr` without
 * touching the counter at all - and a valid heap-range `r0` with a real counter value, confirming
 * the increment still happens correctly on the valid path). Same two-layer gate as every native
 * patch here: only `libMassEffect.so`, only if the exact original bytes at both regions match
 * byte-for-byte.
 */
object ElfMassEffectSelectNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x000A0FFCL
    private const val TARGET_VADDR_CAVE = 0x009EDBF0L

    // ldr r3, [r0, #512]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x32, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x9edbf0
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xFB.toByte(), 0x32, 0x25, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r0, #0 / beq 0xa1008 / ldr r3, [r0, #512] / b 0xa1000
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x03, 0xCD.toByte(), 0xDA.toByte(), 0x0A,
        0x00, 0x32, 0x90.toByte(), 0xE5.toByte(),
        0xFF.toByte(), 0xCC.toByte(), 0xDA.toByte(), 0xEA.toByte()
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
                "GameObject::Select() no longer dereferences a NULL 'this' pointer - proactive " +
                    "guard (part of the fix #82-#88 GameObject accessor batch), silently does " +
                    "nothing instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
