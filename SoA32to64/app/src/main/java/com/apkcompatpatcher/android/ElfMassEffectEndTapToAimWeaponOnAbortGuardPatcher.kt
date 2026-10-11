package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE FIX for Mass Effect: Infiltrator, shipped alongside fix #51 without waiting for its
 * own real-device crash report - found by inspection while disassembling `Playable::EndTapToAim(
 * bool)` for fix #51 (same technique fix #44 used: a duplicate of an already-fixed pattern, fixed
 * ahead of time to cut down the number of real-device test rounds still needed).
 *
 * ## What this is
 *
 * `Playable::EndTapToAim(bool)` contains its own unconditional call to `Weapon::OnAbort()`:
 * ```
 * ldr  r0, [r4, #1192]                ; r0 = this->[1192], the "current weapon" field
 * bl   Weapon::OnAbort()              ; <-- called unconditionally, same as fix #49's site
 * ```
 * This is the EXACT SAME field (`this + 1192`) and the exact same call fix #49
 * (`ElfMassEffectWeaponOnAbortCallGuardPatcher`) already guards - just at a SECOND, independent
 * call site inside a different function (`EndTapToAim()` rather than `OnBioticSelectBegin()`).
 * Fix #49 only patched its own call site; this one was never reached by that patch and remains
 * exactly as vulnerable to the same "current weapon" field not always being populated.
 *
 * ## The fix: identical technique and threshold to fix #49
 *
 * - **Callsite** (`0x1FC658`, 4 bytes): `bl 0x2f8810` (`Weapon::OnAbort()`) becomes a `bl` into a
 *   2-part trampoline chain (`.rodata` is out of single-region free space large enough for one
 *   contiguous cave after fixes #50/#51, so this reuses the same "chained trampolines" technique):
 *   - `0x950E78` (8 bytes): `cmp r0,#0xC0000000` / `b 0x951018`
 *   - `0x951018` (8 bytes, terminal): `bxlo lr` (invalid: skip the call, tail-return - `lr` is
 *     already the correct return address) / `b 0x2f8810` (valid: tail-call the real
 *     `Weapon::OnAbort()`, same `lr`).
 *   Both fragments are verified-safe zero-byte gaps between adjacent string literals in
 *   `.rodata`, cross-checked against `readelf -r` for no relocations pointing in.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking every branch's resolved target address individually, plus a combined re-verification
 * alongside every other fix's trampoline.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL THREE regions
 * (callsite + 2 cave fragments) match byte-for-byte what this was built against - any single
 * mismatch leaves the file completely untouched; all three regions are applied together or not at
 * all. For a real `Weapon*` (which, on every device this project has seen data from, is always
 * `>= 0xC0000000`), behavior is provably identical to the original: same tail-call, same eventual
 * return address.
 */
object ElfMassEffectEndTapToAimWeaponOnAbortGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001FC658L
    private const val TARGET_VADDR_CAVE_G1 = 0x00950E78L
    private const val TARGET_VADDR_CAVE_G2 = 0x00951018L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x6C, 0xF0.toByte(), 0x03, 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x06, 0x52, 0x1D, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_G1 = byteArrayOf(
        0x03, 0x01, 0x50, 0xE3.toByte(),
        0x65, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G2 = byteArrayOf(
        0x1E, 0xFF.toByte(), 0x2F, 0x31,
        0xFB.toByte(), 0x9D.toByte(), 0xE6.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL THREE target regions match. Anything else (wrong file, any mismatch) is left
     * completely untouched - this is all-or-nothing, never a partial application.
     */
    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libMassEffect.so")) {
            return Result(false, "not libMassEffect.so - not applicable")
        }
        try {
            if (bytes.size < 52) return Result(false, "too small to be an ELF file - not touching")
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.toByte() ||
                bytes[2] != 'L'.toByte() || bytes[3] != 'F'.toByte()
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
            val offG1 = fileOffsetFor(TARGET_VADDR_CAVE_G1)
            val offG2 = fileOffsetFor(TARGET_VADDR_CAVE_G2)
            if (offCallsite == null || offG1 == null || offG2 == null ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offG1 < 0 || offG1 + EXPECTED_ORIGINAL_CAVE.size > bytes.size ||
                offG2 < 0 || offG2 + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                !matches(offG1, EXPECTED_ORIGINAL_CAVE) ||
                !matches(offG2, EXPECTED_ORIGINAL_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE_G1.indices) bytes[offG1 + i] = PATCHED_BYTES_CAVE_G1[i]
            for (i in PATCHED_BYTES_CAVE_G2.indices) bytes[offG2 + i] = PATCHED_BYTES_CAVE_G2[i]
            return Result(
                true,
                "Playable::EndTapToAim() no longer calls Weapon::OnAbort() with a 'current weapon' " +
                    "pointer that doesn't look like a real heap object (< 0xC0000000) - same fix as " +
                    "#49, applied proactively to this function's own independent copy of the same call."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
