package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #55, found in the 0.59 test (every fix through
 * #54 present, including the touch-flag guard that fix directly precedes) - the game got past
 * fixes #51/#52/#54's call sites inside `Playable::EndTapToAim(bool)` cleanly and crashed a few
 * instructions later, still in the SAME function, at its own "type-check" virtual-dispatch block -
 * the exact same idiom fix #50 already fixed once, in a DIFFERENT function.
 *
 * ## How this was found
 *
 * ```
 * signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x000000de
 * #00 pc 001fc668  libMassEffect.so (Playable::EndTapToAim(bool)+172)
 * #01 pc 001147e8  libMassEffect.so (Hud::StateTransition(Hud::State)+1164)
 * #02 pc 00114cd0  libMassEffect.so (Hud::InitWorld(GameWorld*)+712)
 * ```
 * `fault addr 0xde - 0xb8 (184) = 0x26` - a small, unaligned garbage value (the classic
 * never-fully-initialized-field pattern this whole project keeps finding), this time sitting
 * where a vtable pointer should be, so the CPU's own alignment check on the vtable-slot read
 * faults before it can even try to fetch unmapped memory.
 *
 * ## Root cause
 *
 * ```
 * ldr  r3, [r4]                ; r3 = this->vtable  (r4 = this, valid - used successfully many
 *                               ;   times earlier in this same function call)
 * mov  r0, r4
 * mov  lr, pc
 * ldr  pc, [r3, #184]          ; <-- virtual call #1 ("get type name"-style), dispatched through
 *                               ;     this->vtable with NO validity check on the vtable pointer
 * ldr  r1, [pc, #...]          ; literal string
 * bl   strcmp@plt              ; compares the returned name against a literal
 * cmp  r0, #0
 * bne  <skip second dispatch>
 * ldr  r3, [r4]                ; re-reads this->vtable (defensively - same value either way)
 * mov  r0, r4
 * mov  lr, pc
 * ldr  pc, [r3, #180]          ; <-- virtual call #2, same vtable pointer, same lack of check
 * <epilogue: pop {r4,r5,r6,pc}>
 * ```
 * This is the identical "two type-check virtual dispatches through one vtable pointer, with a
 * `strcmp()` between them" idiom fix #50 already fixed inside
 * `Playable::OnBioticSelectBegin()` - just occurring a second time, independently, inside
 * `EndTapToAim()`, dispatching through `this`'s OWN vtable rather than a separate object's. `this`
 * itself is a valid, mapped object (every other field access on it in this function already
 * succeeded) - only the vtable pointer field specifically reads back as leftover/uninitialized
 * garbage here, consistent with everything else this project has found about this game's object
 * lifecycle.
 *
 * ## The fix: reuse fix #50's exact structural bounds check, applied to this second call site
 *
 * Same technique as fix #50: before dispatching, check the vtable pointer actually falls inside
 * this library's real `.data.rel.ro` section (`~0xA30000`-`0xA90000`, the same bounds already
 * proven correct there). If it doesn't, skip the WHOLE type-check block - both dispatches and the
 * `strcmp()`/branch between them - straight to the block's own natural merge point (the function's
 * epilogue), exactly like fix #50 does. The second dispatch reads the exact same (invalid) vtable
 * pointer as the first with nothing in between able to change it, so guarding only the first
 * dispatch and skipping past both is sufficient and safe.
 *
 * - **Callsite** (`0x1fc664`, 4 bytes): the PC-relative `mov lr,pc` before the first dispatch (same
 *   as fix #50, this instruction can't be relocated into a cave verbatim) becomes a `bl` into a
 *   7-fragment trampoline chain (no contiguous free region left, same situation as fixes #51-#54):
 *   - `0x951DE0` (G1): `add lr,lr,#4` (computes the equivalent of `mov lr,pc`'s value - `bl`'s
 *     automatic `lr` already equals the address of the still-untouched original dispatch
 *     instruction itself, `0x1fc668`, so `+4` gives the correct return address, `0x1fc66c`) /
 *     `b 0x951F08`
 *   - `0x951F08` (G2): `cmp r3,#0xA30000` / `b 0x951F28`
 *   - `0x951F28` (G3): `blo 0x952718` (invalid: jump to G7) / `b 0x952020`
 *   - `0x952020` (G4): `cmp r3,#0xA90000` / `b 0x9520C0`
 *   - `0x9520C0` (G5): `bhs 0x952718` (invalid: jump to G7) / `b 0x952438`
 *   - `0x952438` (G6, terminal-valid): `b 0x1fc668` (re-executes the untouched original
 *     `ldr pc,[r3,#184]`, now with the correctly-fixed-up `lr`)
 *   - `0x952718` (G7, terminal-invalid): `b 0x1fc698` (the function's epilogue, `add sp,sp,#8` /
 *     `pop {r4,r5,r6,pc}` - skipping both dispatches and the `strcmp()` between them entirely)
 *   All seven fragments are fresh verified-safe zero-byte gaps between adjacent string literals in
 *   `.rodata`, each individually re-scanned to exclude every region every prior fix (including
 *   fix #54's seven) already claims.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking every one of the eight branches' resolved target addresses individually - and then
 * verified again with this project's `qemu-arm` micro-harness: both the exact real crash register
 * value (`r3 = 0x26`) and a valid in-bounds value (`r3 = 0xA50000`) resolve to the correct outcome,
 * with `lr` landing on exactly `0x1fc66c` in both cases - confirming the arithmetic `lr` fix-up is
 * correct, the same way it was confirmed for fix #50.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL EIGHT regions
 * (callsite + 7 cave fragments) match byte-for-byte what this was built against - any single
 * mismatch leaves the file completely untouched; all eight regions are applied together or not at
 * all. For a real vtable pointer (always inside `.data.rel.ro` on every device this project has
 * seen data from), behavior is provably identical to the original: same two dispatches, same
 * `strcmp()`, same eventual return.
 */
object ElfMassEffectEndTapToAimVtableGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001FC664L
    private const val TARGET_VADDR_CAVE_G1 = 0x00951DE0L
    private const val TARGET_VADDR_CAVE_G2 = 0x00951F08L
    private const val TARGET_VADDR_CAVE_G3 = 0x00951F28L
    private const val TARGET_VADDR_CAVE_G4 = 0x00952020L
    private const val TARGET_VADDR_CAVE_G5 = 0x009520C0L
    private const val TARGET_VADDR_CAVE_G6 = 0x00952438L
    private const val TARGET_VADDR_CAVE_G7 = 0x00952718L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x0F, 0xE0.toByte(), 0xA0.toByte(), 0xE1.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xDD.toByte(), 0x55, 0x1D, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_G1 = byteArrayOf(
        0x04, 0xE0.toByte(), 0x8E.toByte(), 0xE2.toByte(),
        0x47, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G2 = byteArrayOf(
        0xA3.toByte(), 0x08, 0x53, 0xE3.toByte(),
        0x05, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G3 = byteArrayOf(
        0xFA.toByte(), 0x01, 0x00, 0x3A,
        0x3B, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G4 = byteArrayOf(
        0xA9.toByte(), 0x08, 0x53, 0xE3.toByte(),
        0x25, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G5 = byteArrayOf(
        0x94.toByte(), 0x01, 0x00, 0x2A,
        0xDB.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G6 = byteArrayOf(
        0x8A.toByte(), 0xA8.toByte(), 0xE2.toByte(), 0xEA.toByte(),
        0x00, 0x00, 0x00, 0x00
    )
    private val PATCHED_BYTES_CAVE_G7 = byteArrayOf(
        0xDE.toByte(), 0xA7.toByte(), 0xE2.toByte(), 0xEA.toByte(),
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
            val offG1 = fileOffsetFor(TARGET_VADDR_CAVE_G1)
            val offG2 = fileOffsetFor(TARGET_VADDR_CAVE_G2)
            val offG3 = fileOffsetFor(TARGET_VADDR_CAVE_G3)
            val offG4 = fileOffsetFor(TARGET_VADDR_CAVE_G4)
            val offG5 = fileOffsetFor(TARGET_VADDR_CAVE_G5)
            val offG6 = fileOffsetFor(TARGET_VADDR_CAVE_G6)
            val offG7 = fileOffsetFor(TARGET_VADDR_CAVE_G7)
            val caveOffs = listOf(offG1, offG2, offG3, offG4, offG5, offG6, offG7)
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
            for (i in PATCHED_BYTES_CAVE_G1.indices) bytes[offG1!! + i] = PATCHED_BYTES_CAVE_G1[i]
            for (i in PATCHED_BYTES_CAVE_G2.indices) bytes[offG2!! + i] = PATCHED_BYTES_CAVE_G2[i]
            for (i in PATCHED_BYTES_CAVE_G3.indices) bytes[offG3!! + i] = PATCHED_BYTES_CAVE_G3[i]
            for (i in PATCHED_BYTES_CAVE_G4.indices) bytes[offG4!! + i] = PATCHED_BYTES_CAVE_G4[i]
            for (i in PATCHED_BYTES_CAVE_G5.indices) bytes[offG5!! + i] = PATCHED_BYTES_CAVE_G5[i]
            for (i in PATCHED_BYTES_CAVE_G6.indices) bytes[offG6!! + i] = PATCHED_BYTES_CAVE_G6[i]
            for (i in PATCHED_BYTES_CAVE_G7.indices) bytes[offG7!! + i] = PATCHED_BYTES_CAVE_G7[i]
            return Result(
                true,
                "Playable::EndTapToAim() no longer performs its two type-check virtual dispatches " +
                    "through a vtable pointer that doesn't fall inside libMassEffect.so's actual " +
                    ".data.rel.ro bounds (~0xA30000-0xA90000) - both dispatches and the strcmp() " +
                    "check between them are skipped together, straight to the function's own " +
                    "epilogue, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
