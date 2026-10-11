package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #54 - a THIRD unguarded pointer dereference
 * inside `Playable::EndTapToAim(bool)`, between the two call sites fixes #51 and #52 already
 * guard. Found in the 0.57 test (every fix through #53 present, including a BlueStacks/PC
 * emulator smoke-test pass before the real tablet run - see README's "Testing on an emulator"
 * section): both `EndTapToAim()` guards held (no recurrence at either), and the game crashed a
 * few instructions later in the SAME function instead.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xff000082
 * #00 pc 001fc648  libMassEffect.so (Playable::EndTapToAim(bool)+140)
 * #01 pc 001147e8  libMassEffect.so (Hud::StateTransition(Hud::State)+1164)
 * #02 pc 00114cd0  libMassEffect.so (Hud::InitWorld(GameWorld*)+712)
 * ```
 * `0xff000082` is a classic instance of this project's recurring "0xff......-prefixed wild
 * pointer" pattern (first seen in the `Hud::StateTransition()` listener loop, fix #45 Round 4) -
 * clearly not a real heap pointer (every real one seen anywhere in this project sits in
 * `0xd8000000`-`0xdf000000`), but also not caught by the plain "`< 0xC0000000` is invalid"
 * whitelist floor most other fixes use here, since `0xff000000 > 0xC0000000`.
 *
 * ## Root cause
 *
 * ```
 * ldr  r3, [r4, #412]      ; r3 = this->someTouchStateObject (optional, not always constructed)
 * mov  r2, #0
 * strb r2, [r3, #130]      ; <-- unconditionally clears a flag byte through it, no null/validity check
 * ```
 * `fault addr 0xff000082 - 0x82 (130) = 0xff000000` confirms `r3` itself was the garbage value.
 * This field is a leftover/never-constructed touch-input-state pointer (this game is being run
 * with a gamepad, matching the touch-D-pad-forwarder-absent pattern fixes #42-#44 already cover
 * elsewhere in this same input-handling area) - `0xff000000` looks like a poisoned/uninitialized
 * fill pattern rather than a small sentinel or NULL.
 *
 * ## The fix: guard the write with the same two-sided range fix #45 Round 4 already validated
 *
 * A plain `< 0xC0000000` floor doesn't reject `0xff000000` (it's numerically above that floor).
 * Reusing the exact two-sided range already proven correct for this same wild-pointer pattern
 * elsewhere in this project (fix #45 Round 4, `0.50-stateTransitionListenerNullGuard`): reject if
 * `r3 <= 0x1000` (covers NULL and every small never-constructed sentinel seen in this project) OR
 * `r3 >= 0xF0000000` (covers this `0xff000000`-class wild/poisoned value, comfortably above every
 * real heap pointer `0xd8000000`-`0xdf000000`). If either check fails, the byte-clear is silently
 * skipped; either way execution then continues normally with the very next original instruction.
 *
 * - **Callsite** (`0x1fc644`, 4 bytes): the `mov r2,#0` immediately before the unguarded `strb`
 *   becomes a `bl` into a 7-fragment trampoline chain (no single free `.rodata` region anywhere in
 *   this library is big enough for a contiguous 28-byte cave - confirmed by a full symbol-aware
 *   `.rodata` scan; the one seemingly-free 66-byte run found, `0x9dfd0e`, turned out to be the
 *   unused tail of `EA::Json::JsonInternal::gCharStateMap`'s OWN declared size, i.e. a real data
 *   table, not padding - rejected the same way `0x99e252` was rejected while building fix #45
 *   Round 3, same reasoning). Each fragment holds one real instruction plus a `b` to the next
 *   (CPU condition flags survive an unconditional `b`, so the `cmp`/conditional-branch pairs don't
 *   need to be adjacent in memory):
 *   - `0x9510D8` (F1): `mov r2,#0` / `b 0x9513D0`
 *   - `0x9513D0` (F2): `cmp r3,#0x1000` / `b 0x951510`
 *   - `0x951510` (F3): `bls 0x951DA8` (invalid: jump straight to F7) / `b 0x951548`
 *   - `0x951548` (F4): `cmp r3,#0xF0000000` / `b 0x951A18`
 *   - `0x951A18` (F5): `bhs 0x951DA8` (invalid: jump straight to F7) / `b 0x951BE8`
 *   - `0x951BE8` (F6, only reached if both checks passed): `strb r2,[r3,#130]` / `b 0x951DA8`
 *   - `0x951DA8` (F7, terminal - both the valid and invalid paths converge here): `b 0x1fc64c`
 *     (the original next instruction, `mov r3,#4`), leaving its trailing 4 bytes as the
 *     already-zero, never-executed original padding.
 *   All seven fragments are verified-safe zero-byte gaps between adjacent string literals in
 *   `.rodata`, each OUTSIDE every symbol's own declared size (re-scanned fresh for this fix,
 *   excluding every region every prior fix already claims).
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking every one of the eight branches' resolved target addresses individually - and then
 * verified again with this project's `qemu-arm` micro-harness (see `/home/claude/arm-harness/`):
 * both an invalid-pointer test vector (`r3 = 0xff000000`, the exact real crash value) and a
 * valid-pointer test vector (`r3 = 0xd8000010`, a plausible real heap pointer, with the `strb`
 * write actually landing in mapped memory) reach the correct resume address with no fault.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL EIGHT regions
 * (callsite + 7 cave fragments) match byte-for-byte what this was built against - any single
 * mismatch leaves the file completely untouched; all eight regions are applied together or not at
 * all. For a real touch-state pointer (always `0xd8000000`-`0xdf000000` on every device this
 * project has seen data from), behavior is provably identical to the original: same write, same
 * continuation.
 */
object ElfMassEffectEndTapToAimTouchFlagGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001FC644L
    private const val TARGET_VADDR_CAVE_F1 = 0x009510D8L
    private const val TARGET_VADDR_CAVE_F2 = 0x009513D0L
    private const val TARGET_VADDR_CAVE_F3 = 0x00951510L
    private const val TARGET_VADDR_CAVE_F4 = 0x00951548L
    private const val TARGET_VADDR_CAVE_F5 = 0x00951A18L
    private const val TARGET_VADDR_CAVE_F6 = 0x00951BE8L
    private const val TARGET_VADDR_CAVE_F7 = 0x00951DA8L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x20, 0xA0.toByte(), 0xE3.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xA3.toByte(), 0x52, 0x1D, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x20, 0xA0.toByte(), 0xE3.toByte(),
        0xBB.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x01, 0x0A, 0x53, 0xE3.toByte(),
        0x4D, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x24, 0x02, 0x00, 0x9A.toByte(),
        0x0B, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F4 = byteArrayOf(
        0x0F, 0x02, 0x53, 0xE3.toByte(),
        0x31, 0x01, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F5 = byteArrayOf(
        0xE2.toByte(), 0x00, 0x00, 0x2A,
        0x71, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F6 = byteArrayOf(
        0x82.toByte(), 0x20, 0xC3.toByte(), 0xE5.toByte(),
        0x6D, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F7 = byteArrayOf(
        0x27, 0xAA.toByte(), 0xE2.toByte(), 0xEA.toByte(),
        0x00, 0x00, 0x00, 0x00
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL EIGHT target regions match. Anything else (wrong file, any mismatch) is left
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
            val offF1 = fileOffsetFor(TARGET_VADDR_CAVE_F1)
            val offF2 = fileOffsetFor(TARGET_VADDR_CAVE_F2)
            val offF3 = fileOffsetFor(TARGET_VADDR_CAVE_F3)
            val offF4 = fileOffsetFor(TARGET_VADDR_CAVE_F4)
            val offF5 = fileOffsetFor(TARGET_VADDR_CAVE_F5)
            val offF6 = fileOffsetFor(TARGET_VADDR_CAVE_F6)
            val offF7 = fileOffsetFor(TARGET_VADDR_CAVE_F7)
            val caveOffs = listOf(offF1, offF2, offF3, offF4, offF5, offF6, offF7)
            if (offCallsite == null || caveOffs.any { it == null } ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                caveOffs.any { it!! < 0 || it + EXPECTED_ORIGINAL_CAVE.size > bytes.size }
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                caveOffs.any { !matches(it!!, EXPECTED_ORIGINAL_CAVE) }
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE_F1.indices) bytes[offF1!! + i] = PATCHED_BYTES_CAVE_F1[i]
            for (i in PATCHED_BYTES_CAVE_F2.indices) bytes[offF2!! + i] = PATCHED_BYTES_CAVE_F2[i]
            for (i in PATCHED_BYTES_CAVE_F3.indices) bytes[offF3!! + i] = PATCHED_BYTES_CAVE_F3[i]
            for (i in PATCHED_BYTES_CAVE_F4.indices) bytes[offF4!! + i] = PATCHED_BYTES_CAVE_F4[i]
            for (i in PATCHED_BYTES_CAVE_F5.indices) bytes[offF5!! + i] = PATCHED_BYTES_CAVE_F5[i]
            for (i in PATCHED_BYTES_CAVE_F6.indices) bytes[offF6!! + i] = PATCHED_BYTES_CAVE_F6[i]
            for (i in PATCHED_BYTES_CAVE_F7.indices) bytes[offF7!! + i] = PATCHED_BYTES_CAVE_F7[i]
            return Result(
                true,
                "Playable::EndTapToAim() no longer clears a touch-input-state flag byte through a " +
                    "pointer that doesn't look like a real heap object (<= 0x1000, covering NULL/never" +
                    "-constructed, OR >= 0xF0000000, covering this project's recurring 0xff......-" +
                    "prefixed wild-pointer pattern) - the write is now silently skipped instead of " +
                    "crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
