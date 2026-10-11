package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REACTIVE fix for Mass Effect: Infiltrator (fix #90) - the SECOND consequence of fix #81 found
 * on real-device test (the first was fix #89, `ParticleSystem::CacheSystem()`'s own field-12
 * dereference - already fixed and confirmed gone in the 0.85 test, which is what surfaced THIS
 * crash instead, one call deeper).
 *
 * Fix #81 builds a safe "empty/inert" `Deserializer` when its copy source is invalid, by zeroing
 * fields at offsets 0, 4 and 12. Fix #89 then stopped `CacheSystem()` itself from crashing on that
 * empty object's field-12 pointer, correctly bailing into `CacheSystem`'s own existing cleanup
 * block at `0x1b7bf0`. That cleanup block's very next instruction, though, destructs an
 * `eastl::shared_ptr<im::serialization_old::DeserializationEngine>` sub-object embedded at offset 0
 * of the same now-empty `Deserializer` - and THAT destructor was never written to expect an empty
 * shared_ptr either:
 * ```
 * eastl::shared_ptr<...DeserializationEngine...>::~shared_ptr():
 *   e78a0: push {r4,r5,r6,lr}
 *   e78a4: ldr r3, [r0, #4]    ; r3 = this->mpRefCount (control block) - zeroed by fix #81's guard
 *                              ; when the Deserializer this shared_ptr lives inside was built empty
 *   e78a8: mov r4, r0
 *   e78ac: ldr r2, [r3, #4]    ; <-- CRASHES here (fault addr 0x4) when r3 == 0 - confirmed by the
 *                              ;     0.85 real-device crash log, backtrace frame #00, exactly this
 *                              ;     function +12
 *   ...
 * ```
 * Under normal EASTL usage a `shared_ptr`'s control-block pointer is apparently never null even for
 * a "default-constructed" instance (a real control block gets allocated regardless) - so this
 * destructor was never written with a null check. Fix #81's approach of building an "empty" object
 * by zeroing raw memory (rather than running each embedded member's real constructor) produces
 * exactly the one shared_ptr state this destructor doesn't handle. Reworking fix #81 itself would
 * touch all 16 of its already-validated call sites for the sake of one; guarding this destructor's
 * entry directly is the smaller, more targeted fix - and, as a bonus, covers every OTHER caller of
 * this same destructor project-wide against the same latent assumption, not just this one call site.
 *
 * The callsite (`0xe78ac`, 4 bytes - just the single `ldr r2, [r3, #4]` instruction) becomes a `b`
 * into a free, 4-byte-aligned `.rodata` gap at `0x98dbf8` (296 bytes available, only 16 used here -
 * manually confirmed genuine: an unrelated integer table for `m3g::Group` ends cleanly before it,
 * and completely unrelated RTTI type-name strings for `m3g::Image2D`/`m3g::IndexBuffer`/
 * `m3g::KeyframeSequence` begin fresh well after it, not a table interior):
 * ```
 * cmp r3, #0
 * beq 0xe78d0              ; control block == NULL: nothing to release - jump straight to this
 *                          ; function's own existing return sequence (r4 already holds 'this',
 *                          ; set by the unconditional 'mov r4, r0' at 0xe78a8 just before)
 * ldr r2, [r3, #4]          ; relocated original load
 * b 0xe78b0                 ; control block real: resume the original refcount-decrement logic
 * ```
 * Verified by `objdump` (both branches resolve correctly, patch bytes match the assembled output
 * exactly) and this project's `qemu-arm` micro-harness (an invalid `r3 = 0` path confirms it lands
 * on the function's own return sequence without dereferencing anything further; a valid path with
 * `r3` pointing at a small test buffer confirms the relocated `ldr r2, [r3, #4]` still loads the
 * correct value and resumes the normal refcount decrement). Same two-layer gate as every native
 * patch here: only `libMassEffect.so`, only if the exact original bytes at both regions match
 * byte-for-byte.
 */
object ElfMassEffectDeserializationEngineSharedPtrDtorNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x000E78ACL
    private const val TARGET_VADDR_CAVE = 0x0098DBF8L

    // ldr r2, [r3, #4]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x04, 0x20, 0x93.toByte(), 0xE5.toByte()
    )
    // b 0x98dbf8
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xD1.toByte(), 0x98.toByte(), 0x22, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3, #0 / beq 0xe78d0 / ldr r2, [r3, #4] / b 0xe78b0
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0x33, 0x67, 0xDD.toByte(), 0x0A,
        0x04, 0x20, 0x93.toByte(), 0xE5.toByte(),
        0x29, 0x67, 0xDD.toByte(), 0xEA.toByte()
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
                "eastl::shared_ptr<im::serialization_old::DeserializationEngine>::~shared_ptr() no " +
                    "longer dereferences a NULL control-block pointer (a second downstream consequence " +
                    "of fix #81's empty-Deserializer guard, found one call deeper than fix #89) - " +
                    "silently treats it as nothing-to-release instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
