package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #88) - last of the fix #82-#88 `GameObject`
 * accessor batch. See [ElfMassEffectGetRigidBodyPtrNullGuardPatcher]'s doc comment (fix #82) for
 * the full scan methodology and batch context.
 *
 * ```
 * _ZNK10GameObject12GetPlayerObjEv:
 *   ldr r3, [r0, #12]     ; <-- would crash here if r0 (this) is NULL/invalid
 *   ldr r0, [r3, #452]    ; return this->world->player
 *   bx lr
 * ```
 * This accessor is byte-for-byte structurally identical to `GameObject::GetPlayer()`, already
 * guarded by [ElfMassEffectGetPlayerNullGuardPatcher] (fix #71) - same two-level `this ->
 * field_12 -> field_452` chain, same instruction encodings, just 12 bytes further into the same
 * `.text` region. This fix matches that same, already-shipped-and-field-tested scope exactly:
 * it guards the outer `this == NULL` case (the only one this whole batch's static scan flags as
 * an unguarded-first-instruction hit), not the inner `field_12 == NULL` case, for consistency
 * with the accepted precedent rather than silently widening protection beyond it.
 *
 * Where GetPlayer's fix used a 4-fragment branchy trampoline (four separate 8-byte cave
 * addresses were the only free space available at the time), this fix reaches the same result
 * more compactly with ARM's conditional execution, entirely self-contained in a single 20-byte
 * `.rodata` gap - no separate bail fragment or resume branch needed at all:
 * ```
 * cmp r0, #0
 * ldrne r3, [r0, #12]     ; only executes if this != NULL
 * ldrne r0, [r3, #452]    ; only executes if this != NULL
 * moveq r0, #0             ; only executes if this == NULL: return NULL directly
 * bx lr
 * ```
 * The callsite (`0x1daed0`, 4 bytes) becomes a `b` into this cave at `0x99c5b0` (aligned tail
 * padding between two FMOD RTTI typeinfo names, cross-checked against every other cave address
 * already claimed by any patcher in this project, including [ElfMassEffectGetPlayerNullGuardPatcher]'s
 * four fragment addresses).
 *
 * Verified by `objdump` (branch resolves correctly, all four conditional instructions decode as
 * expected) and this project's `qemu-arm` micro-harness (both `r0 = 0` invalid - confirms `r0`
 * comes back `0` without touching `r3` or dereferencing anything - and a valid two-level pointer
 * chain, confirming the real value is still returned correctly on the valid path). Same
 * two-layer gate as every native patch here: only `libMassEffect.so`, only if the exact original
 * bytes at both regions match byte-for-byte.
 */
object ElfMassEffectGetPlayerObjNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001DAED0L
    private const val TARGET_VADDR_CAVE = 0x0099C5B0L

    // ldr r3, [r0, #12]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x0C, 0x30, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x99c5b0
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xB6.toByte(), 0x05, 0x1F, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(20)

    // cmp r0, #0 / ldrne r3, [r0, #12] / ldrne r0, [r3, #452] / moveq r0, #0 / bx lr
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x0C, 0x30, 0x90.toByte(), 0x15,
        0xC4.toByte(), 0x01, 0x93.toByte(), 0x15,
        0x00, 0x00, 0xA0.toByte(), 0x03,
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
                "GameObject::GetPlayerObj() const no longer dereferences a NULL 'this' pointer - " +
                    "proactive guard (part of the fix #82-#88 GameObject accessor batch, same " +
                    "scope as fix #71's GetPlayer()), returns NULL instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
