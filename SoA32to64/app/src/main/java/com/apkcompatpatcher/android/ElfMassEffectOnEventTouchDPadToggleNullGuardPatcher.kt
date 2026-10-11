package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PROACTIVE fix (found by static analysis, not yet real-device confirmed): a SEVENTEENTH crash
 * site in Mass Effect: Infiltrator, sharing the exact same root cause as fixes #42/#43 but reached
 * through a completely different call path that hasn't been hit by real-device testing yet.
 *
 * ## How this was found
 *
 * After fix #43 shipped, the user asked how many more test/fix cycles this could take ("mennyi
 * ilyen lesz meg?") and, after being told honestly that the number can't be predicted, pushed for
 * a broader proactive scan instead of continuing purely one-crash-per-test ("kitepem a maradek
 * hajam ha meg 200 teszt lesz" - I'll pull out what's left of my hair if there's 200 more tests).
 * That prompted a static sweep of every `InputSchemeBasic` method for the same bug pattern fixes
 * #42/#43 already fixed once. `InputSchemeBasic::Init(GameWorld*)` was disassembled in full: it
 * turns out the two touch D-pad forwarder fields fix #42/#43 guard (`this->[80]`, `this->[92]`)
 * are constructed there, but only inside an `if (touchCapable)`-style block gated on a flag read
 * once near the top of `Init()` (`Tier::GetInstance()`'s field+4, bit 1) - on this gamepad-only
 * device that flag is false, so those two fields are never constructed and keep whatever garbage
 * bit pattern the allocator handed back (`1` and `0x1000`, matching the real crash logs exactly).
 * (The sibling checks this same sweep ran - `IsMovement()`'s and `ResetPointer()`'s reads of the
 * `InputRegionBox`/`InputForwarderGroup` fields, and the third touch D-pad forwarder field at
 * `this->[68]` - all turned out to be safe: `InputSchemeBasic::UpdateRegions()` and `Init()`
 * construct those fields completely unconditionally, with no such gate, so they were ruled out.)
 *
 * Searching for every OTHER direct read of `this->[80]`/`this->[92]` (not just the two already
 * fixed) turned up `InputSchemeBasic::OnEvent(im::Event const&)`, which reads both of these SAME
 * still-empty fields completely unguarded, at `0x124360` and `0x124374`:
 * ```
 * ldrb r3, [r0, #80]           ; r3 = Settings::GetInstance()->someFlag ^ 1
 * eor  r3, r3, #1
 * strb r3, [r6, #4]            ; this->[80]->[4] = r3   <-- r6 is garbage (1) -> write to 0x5
 * ldr  r6, [r5, #92]           ; r6 = this->[92]  (0x1000 on this device)
 * bl   Settings::GetInstance()
 * ldrb r3, [r0, #80]
 * strb r3, [r6, #4]            ; this->[92]->[4] = r3   <-- write to 0x1004
 * ```
 * This is reached when `OnEvent()` handles a specific event that ends up toggling a "sticky fire"
 * style setting on both touch D-pad forwarders (the code also touches `Playable::IsInCover()` and
 * `UpdateRegions()` right after, consistent with a cover/aim-related input event) - something the
 * user's playthrough simply hasn't triggered yet on the builds tested so far. On a real touch
 * device (or once fix #42/#43-style construction actually ran) both fields hold a real heap
 * pointer and this is a completely ordinary field write; on this gamepad-only device it's a write
 * through address `0x5` or `0x1004` - a guaranteed SIGSEGV the moment that event fires.
 *
 * ## The fix: guard each write, skip it for an absent forwarder
 *
 * Both call sites patch the exact same 4-byte instruction (`strb r3, [r6, #4]`) and, since the
 * instruction bytes are byte-for-byte identical at both sites, share ONE cave: `cmp r6, #0x1000`
 * / `bls skip` / `strb r3, [r6, #4]` / `skip: bx lr` - the same "small address = never-built
 * forwarder, treat the update as a no-op" threshold fixes #42/#43 already established (every real
 * heap pointer seen anywhere in this whole crash log is many kilobytes in; nothing legitimate is
 * ever this small). For a genuinely-present forwarder (any device where these fields hold a real
 * pointer) behavior is unchanged; for an absent one the toggle is silently skipped instead of
 * crashing, exactly mirroring what fixes #42/#43 already do for the same fields' other call site.
 *
 * - **Callsite 1** (`0x124360`, 4 bytes, guards `this->[80]`): `strb r3,[r6,#4]` becomes
 *   `bl 0x99597C`.
 * - **Callsite 2** (`0x124374`, 4 bytes, guards `this->[92]`): the same `strb r3,[r6,#4]` becomes
 *   `bl 0x99597C` too - same cave, since the replaced instruction is byte-identical at both sites.
 * - **Cave** (`0x99597C`, 16 bytes, in previously-unused zero `.rodata` padding - the next free
 *   address right after fix #43's own 24-byte cave): `cmp r6,#0x1000` / `bls skip` / `strb
 *   r3,[r6,#4]` / `skip: bx lr`.
 *
 * Assembled and linked at all three real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` plus a whole-file byte-diff (21 bytes differ: 4+4 at the two call sites, 13 of the
 * cave's 16 bytes - 3 stay zero) - and re-verified together with fixes #38-#43's own adjacent
 * trampolines confirming all seven coexist with no interference (132 total bytes differ across all
 * seven fixes combined, exactly matching each fix's own independently-verified count, with zero
 * overlap in the shared `.rodata` cave block).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other event
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at all three regions
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched; all three regions are applied together or not at all. Not yet real-device tested (it
 * was found by proactively reading the code, not by a crash log) - shipped alongside fixes #38-#43
 * in the same build specifically so it's already in place if/when the user's playthrough does
 * reach this event, instead of costing them another full test/fix/re-test round trip.
 */
object ElfMassEffectOnEventTouchDPadToggleNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE1 = 0x00124360L
    private const val TARGET_VADDR_CALLSITE2 = 0x00124374L
    private const val TARGET_VADDR_CAVE = 0x0099597CL

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x04, 0x30, 0xC6.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE1 = byteArrayOf(
        0x85.toByte(), 0xC5.toByte(), 0x21, 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE2 = byteArrayOf(
        0x80.toByte(), 0xC5.toByte(), 0x21, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x01, 0x0A, 0x56, 0xE3.toByte(),
        0x00, 0x00, 0x00, 0x9A.toByte(),
        0x04, 0x30, 0xC6.toByte(), 0xE5.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
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

            val offC1 = fileOffsetFor(TARGET_VADDR_CALLSITE1)
            val offC2 = fileOffsetFor(TARGET_VADDR_CALLSITE2)
            val offCave = fileOffsetFor(TARGET_VADDR_CAVE)
            if (offC1 == null || offC2 == null || offCave == null ||
                offC1 < 0 || offC1 + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offC2 < 0 || offC2 + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offCave < 0 || offCave + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offC1, EXPECTED_ORIGINAL_CALLSITE) || !matches(offC2, EXPECTED_ORIGINAL_CALLSITE) ||
                !matches(offCave, EXPECTED_ORIGINAL_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE1.indices) bytes[offC1 + i] = PATCHED_BYTES_CALLSITE1[i]
            for (i in PATCHED_BYTES_CALLSITE2.indices) bytes[offC2 + i] = PATCHED_BYTES_CALLSITE2[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            return Result(
                true,
                "InputSchemeBasic::OnEvent() no longer writes through its two touch D-pad forwarder " +
                    "fields (the same ones fixes #42/#43 already guard at their SetFireEnabled() call " +
                    "site) when toggling a fire-related setting on them - when a forwarder was never " +
                    "constructed (gamepad-only setup) that write is now skipped entirely instead of " +
                    "writing through a small garbage address and crashing. Found proactively by static " +
                    "analysis, not yet confirmed by a real-device crash."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
