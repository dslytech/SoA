package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix for Mass Effect: Infiltrator (fix #82) - first of a new batch (fixes #82-#88)
 * of `GameObject::` accessor guards, found by a whole-binary static scan rather than a specific
 * crash log. After fixes #66-#81 (16 crashes in a row, several of them the same
 * "dereferences `this` with no NULL check" shape already proven real by fixes #67/#69/#70-74/#77-80),
 * the user asked to cut down on one-crash-at-a-time real-device test rounds again. This time the
 * scan was scripted end-to-end: disassemble the whole `.text` section once, parse every function
 * belonging to `GameObject` (the class with by far the most confirmed real crashes of this shape),
 * keep only small (<=64 byte) leaf-style functions whose very first real instruction is an
 * unguarded `ldr/str [r0, #N]` with no preceding `cmp/cmn/tst/teq` on r0, and cross-reference
 * against every address already claimed by an existing patcher. That produced a handful of
 * finalists, hand-triaged by return-type/semantics for which are safe to default to NULL/false
 * (see [ElfMassEffectGetPlayerObjNullGuardPatcher]'s doc comment for the two candidates that were
 * excluded as unsafe: `GetPos()` and `GetRatings()`).
 *
 * ## How this was found
 *
 * ```
 * _ZNK10GameObject15GetRigidBodyPtrEv:
 *   ldr r0, [r0, #88]   ; <-- would crash here if r0 (this) is NULL/invalid
 *   bx lr
 * ```
 * No real-device crash log has hit this specific accessor yet - proactive guard, verified by
 * `objdump` (every branch resolves correctly) and this project's `qemu-arm` micro-harness (both
 * `r0 = 0` invalid and a valid heap-range `r0`, confirming the returned pointer value is correct
 * on the valid path too). If `this` is in fact never null in practice for this accessor, the
 * guard is a permanent no-op - same accepted trade-off as fixes #70-74.
 *
 * ## The fix
 *
 * The callsite (`0xa0e78`, 4 bytes) becomes a `b` into a free 12-byte `.rodata` gap at `0x9f2324`
 * (the tail padding right after a C++ typeinfo name, before the next RTTI string - the same kind
 * of gap this project has used since fix #54's free-space search, cross-checked against every
 * other cave address already claimed by any patcher in this project):
 * ```
 * cmp r0, #0
 * ldrne r0, [r0, #88]   ; only dereference if r0 (this) isn't NULL
 * b 0xa0e7c              ; resume at the original bx lr either way
 * ```
 * When `this` is NULL, `r0` simply stays `0` (the value `cmp` just compared it to) and the
 * function returns NULL instead of crashing - a legitimate value for a pointer-returning
 * accessor with no further indirection inside this function.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only `libMassEffect.so`, only if the
 * exact original bytes at BOTH target regions match byte-for-byte; any mismatch leaves the file
 * completely untouched, both regions applied together or not at all.
 */
object ElfMassEffectGetRigidBodyPtrNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x000A0E78L
    private const val TARGET_VADDR_CAVE = 0x009F2324L

    // ldr r0, [r0, #88]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x58, 0x00, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x9f2324
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x29, 0x45, 0x25, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(12)

    // cmp r0, #0 / ldrne r0, [r0, #88] / b 0xa0e7c
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x58, 0x00, 0x90.toByte(), 0x15,
        0xD2.toByte(), 0xBA.toByte(), 0xDA.toByte(), 0xEA.toByte()
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
                "GameObject::GetRigidBodyPtr() const no longer dereferences a NULL 'this' pointer - " +
                    "proactive guard (part of the fix #82-#88 GameObject accessor batch), returns " +
                    "NULL instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
