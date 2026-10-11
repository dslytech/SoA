package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REACTIVE fix for Mass Effect: Infiltrator (fix #89) - a direct, traceable consequence of fix #81
 * ([ElfMassEffectDeserializerCopyCtorNullGuardPatcher], shipped in 0.83). Found from a real-device
 * crash log against a 0.84 build that already had fix #81 applied.
 *
 * Fix #81 guards `Deserializer`'s copy constructor so that, when the source object being copied is
 * NULL/invalid, it builds a safe "empty/inert" `Deserializer` instead of crashing - zeroing fields
 * at offsets 0, 4 and 12 of the new object rather than dereferencing garbage.
 *
 * `ParticleSystem::CacheSystem(...)` calls that guarded copy constructor at `0x1b7b04` (this call
 * itself is untouched by this fix) and then, a few instructions later, unconditionally trusts the
 * result's field at offset 12 to be a valid pointer:
 * ```
 * 1b7b94: ldr r3, [sp, #256]   ; r3 = (just-constructed Deserializer)[12] - zeroed by fix #81's
 *                              ; guard when the copy source was invalid
 * 1b7b98: ldr r3, [r3, #16]    ; <-- CRASHES here (fault addr 0x10) when r3 == 0 - fix #81 made the
 *                              ;     object "safely empty", but this caller was never written to
 *                              ;     expect an empty one; it shifted the crash one level downstream
 *                              ;     instead of eliminating it
 * 1b7b9c: ldr r2, [r3, #24]    ; immediately re-dereferences the SAME register again - the "let
 *                              ;     zero propagate" trick (used for the fix #82-#88 batch) does
 *                              ;     NOT apply here, a real guard is required
 * ...
 * 1b7bf0: ldr r0, [sp, #24]    ; the function's OWN existing cleanup/early-return path (destructs
 *                              ;     four shared_ptrs, then jumps to the function's shared epilogue)
 * ```
 * Same "reuse an existing downstream instruction as a bail target" technique as
 * [ElfMassEffectSelectNullGuardPatcher] (fix #87): when `r3` (the Deserializer's field-12 pointer)
 * is NULL, this guard branches straight into `CacheSystem`'s own pre-existing bail-out block at
 * `0x1b7bf0` instead of allocating a dedicated bail fragment - that block already does exactly the
 * right cleanup for "give up on this cache entry", no new code needed for the invalid path.
 *
 * The callsite (`0x1b7b98`, 4 bytes - just the single `ldr r3, [r3, #16]` instruction) becomes a
 * `b` into a free, 4-byte-aligned, exactly-16-byte `.rodata` gap at `0x991c30`: manually inspected
 * with wide hex context and confirmed to be genuine NUL padding between two unrelated Bullet
 * Physics RTTI type-name strings ("...SphereTriangleDetector\0" immediately before, an unrelated
 * small integer dispatch table immediately after) - not a table interior, not a float/constant
 * table, not a hash table, matching this project's established "narrow, NUL-flanked, string-padding
 * gap" safe pattern exactly:
 * ```
 * cmp r3, #0
 * beq 0x1b7bf0            ; (this[12]) == NULL: skip straight to CacheSystem's own existing
 *                          ; cleanup/bail block, don't touch r3 further
 * ldr r3, [r3, #16]        ; relocated original load
 * b 0x1b7b9c               ; != NULL: resume the original code unmodified
 * ```
 * Verified by `objdump` (both branches resolve correctly, patch bytes match the assembled output
 * exactly) and this project's `qemu-arm` micro-harness (an invalid `r3 = 0` path confirms it lands
 * on the bail block without dereferencing anything further; a valid path with `r3` pointing at a
 * small test buffer confirms the relocated `ldr r3, [r3, #16]` still loads the correct value and
 * resumes normal execution). Same two-layer gate as every native patch here: only
 * `libMassEffect.so`, only if the exact original bytes at both regions match byte-for-byte.
 */
object ElfMassEffectCacheSystemEmptyDeserializerNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001B7B98L
    private const val TARGET_VADDR_CAVE = 0x00991C30L

    // ldr r3, [r3, #16]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x10, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // b 0x991c30
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x24, 0x68, 0x1F, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3, #0 / beq 0x1b7bf0 / ldr r3, [r3, #16] / b 0x1b7b9c
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0xED.toByte(), 0x97.toByte(), 0xE0.toByte(), 0x0A,
        0x10, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0xD6.toByte(), 0x97.toByte(), 0xE0.toByte(), 0xEA.toByte()
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
                "ParticleSystem::CacheSystem() no longer dereferences the empty/inert Deserializer's " +
                    "NULL field-12 pointer (a downstream consequence of fix #81's guard) - silently " +
                    "bails into the function's own existing cleanup path instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
