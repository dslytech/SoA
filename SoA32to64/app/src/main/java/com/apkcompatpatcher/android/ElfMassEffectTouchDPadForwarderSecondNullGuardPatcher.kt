package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's SIXTEENTH native SIGSEGV. Real-device
 * tested after fix #42 (the first touch D-pad forwarder null-guard) shipped: the crash log's OWN
 * shape confirms fix #42 worked - `InputSchemeBasic::SetFireEnabled(bool)+48`, the frame for the
 * call fix #42 guards, no longer appears at all, and the exact same crash site is hit one call
 * later, straight from `Hud::StateTransition()`:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000108c
 * Cause: null pointer dereference
 * #00 InputForwarderTouchDPad::SetAutoFireEnabled(bool)+4
 * #01 Hud::StateTransition(Hud::State)+672
 * ```
 *
 * ## Root cause
 *
 * `InputSchemeBasic::SetFireEnabled()` doesn't call `SetAutoFireEnabled()` just once - it calls it
 * on TWO separate forwarder fields, back to back: `this->[80]` (fixed by fix #42) and, immediately
 * after via a tail-call (`pop {...,lr}; b SetAutoFireEnabled`, not a `bl`, so this frame never shows
 * up in the backtrace on its own), `this->[92]`. Both are meant to be `InputForwarderTouchDPad*`,
 * and both go unconditionally into the same unguarded call. On this device `this->[80]` held the
 * sentinel value `1` (fixed by #42); `this->[92]` holds a *different* small, equally-bogus value:
 * `0x1000` (`fault addr 0x108c` is exactly `0x1000 + 140`, the same write offset as every previous
 * crash in this same function). Two different small "garbage" values at two neighboring fields of
 * the same object, in the same run, aren't a fixed sentinel constant a hand-guard could special-case
 * - they're consistent with genuinely *uninitialized* memory (this build's touch-D-pad forwarders
 * are apparently never constructed at all on a gamepad-only setup, so both fields simply keep
 * whatever bit pattern the allocator handed back) rather than one designed "empty" value. Every real
 * heap object pointer seen throughout this entire crash log is many kilobytes into the address space
 * at minimum (`0xd8......`-class addresses) - nothing legitimate is ever this small - so guarding on
 * "the loaded value is at or below one page (`0x1000`)" catches both observed garbage values without
 * needing to guess every possible one, while staying nowhere near any value a real pointer could take.
 *
 * ## The fix: an out-of-line trampoline that also finishes the caller's own epilogue on the guard path
 *
 * Unlike fix #42's call site, this one is a genuine tail-call: `SetFireEnabled()` restores its own
 * saved registers (`pop {r4,r5,r6,lr}`) and then plain-branches (not `bl`) into `SetAutoFireEnabled`,
 * so that function's own `bx lr` returns straight to `Hud::StateTransition()` without an extra stack
 * frame. That means simply testing-and-skipping the branch isn't enough - the guard path also has to
 * perform that same `pop`/return itself, or the stack would end up unbalanced. This cave does exactly
 * that: it reproduces the original load (`ldr r0,[r4,#92]`), tests it, and on a valid pointer returns
 * normally (continuing into the original `mov r1,r5; pop; b SetAutoFireEnabled` unmodified); on an
 * invalid one, it performs the equivalent `pop {r4,r5,r6,lr}; bx lr` itself, exactly reproducing "the
 * call never happened, the function just returned" instead of crashing.
 *
 * - **Callsite** (`0x123c48`, 4 bytes): `ldr r0,[r4,#92]` becomes `bl 0x995964`.
 * - **Cave** (`0x995964`, 24 bytes, in previously-unused zero `.rodata` padding): `ldr r0,[r4,#92]`
 *   / `cmp r0,#0x1000` / `bls skip` / `bx lr` / `skip: pop {r4,r5,r6,lr}` / `bx lr`.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` (confirming the guard path's own pop/return is correctly encoded, matching what the
 * caller's own popped epilogue would have done) plus a whole-file byte-diff (24 bytes differ: 4 at
 * the call site, 20 of the cave's 24 bytes) - and re-verified together with fixes #38-#42's own
 * adjacent trampolines confirming all six coexist with no interference (111 total bytes differ
 * across all six fixes combined, exactly matching each fix's own independently-verified count).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other forwarder
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions match
 * byte-for-byte what this was built against - any mismatch leaves the file completely untouched;
 * both regions are applied together or not at all. For a touch D-pad forwarder that's actually
 * present (any value above one page), behavior is provably identical to the original.
 */
object ElfMassEffectTouchDPadForwarderSecondNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00123C48L
    private const val TARGET_VADDR_CAVE = 0x00995964L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x5C, 0x00, 0x94.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x45, 0xC7.toByte(), 0x21, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(24)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x5C, 0x00, 0x94.toByte(), 0xE5.toByte(),
        0x01, 0x0A, 0x50, 0xE3.toByte(),
        0x00, 0x00, 0x00, 0x9A.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte(),
        0x70, 0x40, 0xBD.toByte(), 0xE8.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at BOTH target regions match. Anything else (wrong file, either mismatch) is left completely
     * untouched - this is all-or-nothing, never a partial application.
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
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) || !matches(offCave, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            return Result(
                true,
                "InputSchemeBasic::SetFireEnabled() no longer calls " +
                    "InputForwarderTouchDPad::SetAutoFireEnabled() through its SECOND touch D-pad " +
                    "forwarder field either (a sibling of the one fix #42 already guards, left at a " +
                    "different small garbage/'not built' value - 0x1000 rather than 1 - consistent " +
                    "with genuinely uninitialized memory rather than one fixed sentinel) - that call " +
                    "is now skipped entirely (via a small out-of-line trampoline that also performs " +
                    "the caller's own register-restore/return) instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
