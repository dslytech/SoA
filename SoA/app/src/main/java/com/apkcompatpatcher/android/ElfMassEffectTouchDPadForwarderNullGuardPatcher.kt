package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's FIFTEENTH native SIGSEGV. Real-device
 * tested after fix #41 (the `Hud::InitWorld()` level-title-color null-guard) shipped: that read is
 * gone, `Hud::InitWorld()` runs to completion and calls `Hud::StateTransition()`, which itself runs
 * for hundreds of bytes before hitting this - a new function entirely:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000008d
 * Cause: null pointer dereference
 *     r0 00000001  r1 00000000
 * #00 InputForwarderTouchDPad::SetAutoFireEnabled(bool)+4
 * #01 InputSchemeBasic::SetFireEnabled(bool)+48
 * #02 Hud::StateTransition(Hud::State)+672
 * #03 Hud::InitWorld(GameWorld*)+712
 * ```
 *
 * ## Root cause
 *
 * `Hud::StateTransition()` calls `InputSchemeBasic::SetFireEnabled(false)` on an `InputSchemeBasic`
 * embedded inside the `Hud` object. `SetFireEnabled()` already explicitly null-checks one of its two
 * dependent fields (`this->[56]`, an `InputRegionBoolean*` - if that's NULL, the whole function
 * bails out early) but then reads a *second*, sibling field - `this->[80]`, meant to be an
 * `InputForwarderTouchDPad*` - and passes it straight into `SetAutoFireEnabled()` as `this`, with no
 * equivalent check. On this real device that field's actual stored value is the literal integer `1`
 * (not `0`) - `fault addr 0x8d` is exactly `1 + 140`, the offset `SetAutoFireEnabled()` writes to.
 * `1` isn't a plausible real pointer; it reads as an explicit "not yet assigned" sentinel this field
 * can apparently hold (distinct from - but just as invalid as - a plain NULL), most likely because
 * this build runs with a physical gamepad (`meinfiltrator_gamepad`), so the on-screen-touch-D-pad
 * forwarder this field would normally point to is never constructed at all. Either way: the exact
 * same "one dependency checked, a sibling dependency of the very same kind isn't" gap already fixed
 * a dozen times over in this project, just with the empty-value sentinel observed here being `1`
 * rather than `0`.
 *
 * ## The fix: an out-of-line trampoline guarding the call on both sentinel values
 *
 * The call site (`ldr r0,[r0,#80]` immediately followed by `bl SetAutoFireEnabled`) has no spare
 * instruction to fold a test into, so - as with fixes #38-#41 - this replaces the `bl` itself (same
 * 4 bytes) with a `bl` to a cave in the same free `.rodata` block. Because the call site is reached
 * via `bl` (not inlined), `lr` already holds the correct post-call return address on cave entry, so
 * the cave can use it directly: it treats any loaded value `<= 1` (unsigned) - covering both a
 * plain NULL and the specific `1` sentinel observed here - as "not present" and returns immediately
 * via `bx lr` without calling anything; otherwise it tail-branches (plain `b`, not `bl`) into the
 * real `SetAutoFireEnabled()`, which returns directly to the original caller via its own `bx lr`
 * exactly as if this trampoline had never been inserted.
 *
 * - **Callsite** (`0x123c44`, 4 bytes): `bl 0x122458` (`SetAutoFireEnabled`) becomes `bl 0x995954`.
 * - **Cave** (`0x995954`, 16 bytes, in previously-unused zero `.rodata` padding): `cmp r0,#1` /
 *   `bls skip` / `b 0x122458` / `skip: bx lr`.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` (confirming the tail-branch resolves to the real `SetAutoFireEnabled` and the `bls`
 * target is the local `bx lr`) plus a whole-file byte-diff (15 bytes differ from the pristine
 * original - fewer than the usual 18 because this cave's own bytes happen to overlap more zero
 * padding than fixes #38-#41's) - and re-verified together with fixes #38-#41's own adjacent
 * trampolines confirming all five coexist with no interference (87 total bytes differ across all
 * five fixes combined, exactly matching each fix's own independently-verified count).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other forwarder
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions match
 * byte-for-byte what this was built against - any mismatch leaves the file completely untouched;
 * both regions are applied together or not at all. For a touch D-pad forwarder that's actually
 * present (a value greater than `1`), behavior is provably identical to the original: the real
 * function still gets called with the exact same arguments.
 */
object ElfMassEffectTouchDPadForwarderNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00123C44L
    private const val TARGET_VADDR_CAVE = 0x00995954L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x03, 0xFA.toByte(), 0xFF.toByte(), 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x42, 0xC7.toByte(), 0x21, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x01, 0x00, 0x50, 0xE3.toByte(),
        0x00, 0x00, 0x00, 0x9A.toByte(),
        0xBD.toByte(), 0x32, 0xDE.toByte(), 0xEA.toByte(),
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
                    "InputForwarderTouchDPad::SetAutoFireEnabled() through an absent touch D-pad " +
                    "forwarder (a raw pointer field left at a NULL-or-'not assigned' sentinel value " +
                    "when the touch D-pad forwarder was never constructed, e.g. on a gamepad-only " +
                    "setup) - that call is now skipped entirely (via a small out-of-line trampoline) " +
                    "instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
