package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #56, found in the 0.60 test (every fix through
 * #55 present) - a brand new crash, in a brand new function, on the very first level load:
 * `LayerGameWorld::LoadLevel(LoadTransition*)`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3e4cccd1
 *     r2 d8da9430  r3 3e4ccccd
 * #00 pc 00316d28  libMassEffect.so (LayerGameWorld::LoadLevel(LoadTransition*)+124)
 * #01 pc 0012d8dc  libMassEffect.so (LoadTransitionTask::Run()+564)
 * ```
 * `fault addr = r3 + 4 = 0x3e4ccccd + 4`, so the crash is a plain `ldr rX, [r3, #4]` where `r3`
 * itself is garbage - not a small offset like the earlier vtable-guard fixes, just an
 * uninitialized/leftover value that happens to look nothing like a real heap pointer.
 *
 * ## Root cause
 *
 * ```
 * ldr  r0, [r4, #24]
 * bl   GameWorld::GetSubtitleWidget()   ; writes a 2-word eastl::shared_ptr {ptr, ctrl} into the
 *                                       ; buffer at sp+12/sp+16 (hidden-pointer return, r0=sp+12)
 * ldr  r2, [sp, #12]                   ; r2 = new ptr
 * ldr  r3, [r4, #0x90c]                ; r3 = this->m_subtitleWidget's CURRENT ptr (member)
 * cmp  r3, r2
 * beq  <do nothing - same object already assigned>
 * ldr  r3, [sp, #16]                   ; r3 = new ctrl/refcount-block ptr        <- becomes garbage
 * str  r2, [sp]
 * mov  r0, sp
 * str  r3, [sp, #4]
 * ldr  r2, [r3, #4]                    ; <-- CRASH: increment new object's strong refcount
 * add  r2, r2, #1
 * str  r2, [r3, #4]
 * ...                                  ; (increment a second count at [r3,#8], then swap the
 *                                       ;  member with the new {ptr,ctrl} and destruct the old one)
 * ```
 * This is `Hud::InitWorld()` assigning its subtitle widget shared_ptr for the very first time on
 * level load. `GetSubtitleWidget()`'s underlying member hasn't been fully constructed yet at this
 * point in the load sequence, so the "ctrl/refcount" half of the shared_ptr it returns comes back
 * as leftover garbage (`0x3e4ccccd` in the crash log) even though the "ptr" half looks like a
 * perfectly plausible heap pointer (`r2 = 0xd8da9430`, consistent with every other real pointer
 * seen elsewhere in this same crash dump) - the same "only one field of an otherwise-valid-looking
 * struct is still uninitialized" pattern this whole project keeps finding, just hitting a
 * shared_ptr's control block this time instead of a vtable pointer.
 *
 * ## The fix: reuse this project's proven heap-floor pointer check
 *
 * Real heap pointers in this app always come back `>= 0xC0000000` (the same floor already used to
 * gate fixes #51/#52's dereferences); `0x3e4ccccd` is nowhere close. Guard the reload of `r3` (the
 * new ctrl pointer) with that floor check: if it's below the floor, skip the ENTIRE
 * increment/swap/destruct block and jump straight to the function's own "nothing to do" merge
 * point - exactly the same place the existing `beq` (self-assignment) shortcut already jumps to.
 * This treats an unusable ctrl pointer exactly like "no assignment needed": the member keeps
 * whatever it already had, nothing is incremented, nothing is freed, and no later code is left
 * holding half of a torn shared_ptr.
 *
 * - **Callsite** (`0x316d18`, 4 bytes): the `ldr r3, [sp, #16]` that loads the new ctrl pointer
 *   becomes a `bl` into a 3-fragment trampoline chain (fresh free `.rodata` gaps, re-scanned to
 *   exclude every region every prior fix - including fixes #54 and #55's fourteen - already
 *   claims):
 *   - `0x952730` (H1): `ldr r3, [sp, #16]` (re-executes the original instruction) / `b 0x9533A8`
 *   - `0x9533A8` (H2): `cmp r3, #0xC0000000` / `b 0x953418`
 *   - `0x953418` (H3, terminal): `blo 0x316d60` (invalid: jump straight to the "nothing to do"
 *     merge point, same target the original `beq` uses) / `b 0x316d1c` (valid: resume the
 *     original code completely unmodified)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all four branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness: both the exact real crash register value (`r3 = 0x3e4ccccd`, loaded via the
 * harness's `MEM` directive at the stack slot the cave itself reads from) and a valid heap-range
 * value (`r3 = 0xd8da9430`, the actual "ptr" half seen in this very crash dump) resolve to the
 * correct outcome - `pc = 0x316d60` for the invalid case, `pc = 0x316d1c` for the valid case.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions match byte-for-byte what this was built against - any single mismatch leaves the file
 * completely untouched; all four regions are applied together or not at all. For a real ctrl
 * pointer (always `>= 0xC0000000` on every device this project has seen data from), behavior is
 * provably identical to the original: same increment, same swap, same destructor call.
 */
object ElfMassEffectLoadLevelSubtitleWidgetGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00316D18L
    private const val TARGET_VADDR_CAVE_H1 = 0x00952730L
    private const val TARGET_VADDR_CAVE_H2 = 0x009533A8L
    private const val TARGET_VADDR_CAVE_H3 = 0x00953418L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x10, 0x30, 0x9D.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x84.toByte(), 0xEE.toByte(), 0x18, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_H1 = byteArrayOf(
        0x10, 0x30, 0x9D.toByte(), 0xE5.toByte(),
        0x1B, 0x03, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_H2 = byteArrayOf(
        0x03, 0x01, 0x53, 0xE3.toByte(),
        0x19, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_H3 = byteArrayOf(
        0x50, 0x0E.toByte(), 0xE7.toByte(), 0x3A,
        0x3E, 0x0E.toByte(), 0xE7.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL FOUR target regions match. Anything else (wrong file, any mismatch) is left
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
            val offH1 = fileOffsetFor(TARGET_VADDR_CAVE_H1)
            val offH2 = fileOffsetFor(TARGET_VADDR_CAVE_H2)
            val offH3 = fileOffsetFor(TARGET_VADDR_CAVE_H3)
            val caveOffs = listOf(offH1, offH2, offH3)
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
            for (i in PATCHED_BYTES_CAVE_H1.indices) bytes[offH1!! + i] = PATCHED_BYTES_CAVE_H1[i]
            for (i in PATCHED_BYTES_CAVE_H2.indices) bytes[offH2!! + i] = PATCHED_BYTES_CAVE_H2[i]
            for (i in PATCHED_BYTES_CAVE_H3.indices) bytes[offH3!! + i] = PATCHED_BYTES_CAVE_H3[i]
            return Result(
                true,
                "LayerGameWorld::LoadLevel() no longer increments/swaps its subtitle widget " +
                    "shared_ptr through a control-block pointer below the real heap floor " +
                    "(0xC0000000) - the whole assignment is skipped, exactly like the existing " +
                    "self-assignment shortcut, instead of crashing on the very first level load."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
