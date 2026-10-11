package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #76 - found in the 0.77 real-device test. Fix #75
 * (this exact same round's earlier guard) worked: `GameWorld::OnUpdate()` no longer crashes
 * reading its player object's one-shot flag byte - the game now proceeds one call deeper into
 * `GameWorld::UpdateSoundLevels(int)`, which hits the SAME underlying "player hasn't spawned yet"
 * situation a second, independent way.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 *     r0  00000000  r1  00000000  r2  00000009  r3  00000000
 * #00 pc 00300b60  (GameWorld::UpdateSoundLevels(int)+76)
 * #01 pc 0030dac4  (GameWorld::OnUpdate(int)+140)
 * #02 pc 0031cd00  (LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180)
 * #03 pc 00315e10  (LayerGameWorld::OnEvent(im::Event const&)+1564)
 * #04 pc 003d99c4  (im::LayerStack::OnEvent(im::Event const&)+124)
 * ```
 * `r0 == 0`, `r3 == 0`, `fault addr = 0x0`.
 *
 * ## Root cause
 *
 * ```
 * _ZN9GameWorld17UpdateSoundLevelsEi:
 *   ldr  r3, [r4, #452]    ; r3 = this->player (the exact same field fix #71's GetPlayer() reads
 *                          ;   and fix #75 already guards ONE OTHER unconditional read of - this
 *                          ;   is a THIRD, independent call site reading the same field)
 *   mov  r0, r3            ; r0 = player (about to become "this" for a virtual call)
 *   ldr  r3, [r3]          ; <-- CRASH: r3 (the player pointer) is NULL, reading its vtable
 *   mov  lr, pc            ; (manual virtual-call setup: precompute the return address, since the
 *                          ;   next instruction is a direct tail-branch through the vtable, not a
 *                          ;   real `bl`)
 *   ldr  pc, [r3, #0x178]  ; tail-call through the player's own vtable slot 376
 * ```
 * Same root situation as fix #75 (the player `GameObject` doesn't exist yet this early in a
 * level), just a different accessor: here `GameWorld::UpdateSoundLevels()` dispatches a virtual
 * method directly on the player object (almost certainly something like "apply player-position-
 * relative sound settings") with no NULL check on `this` before reading its vtable.
 *
 * ## The fix: skip the whole virtual call, default its result to `0`/`false`
 *
 * The call's return value (`r0`) is checked immediately afterward (`cmp r0,#0` / `beq ...`), so
 * skipping the entire dispatch and defaulting `r0 = 0` is exactly the same "as if nothing special
 * happened" outcome the function already handles as a normal case - not a new behavior. Unlike
 * every guard in this project so far, the ORIGINAL `mov lr, pc` / `ldr pc, [r3, #0x178]`
 * instructions are never touched or relocated at all: only the vtable-read instruction right
 * before them is replaced, and on the valid path the cave branches straight back into that
 * untouched original code, which computes its own correct return address exactly as it always
 * did. This sidesteps the "PC-relative `mov lr,pc` can't be relocated into a cave verbatim"
 * problem fix #50 first ran into - there's simply no need to relocate it here.
 *
 * - **Callsite** (`0x300b60`, 4 bytes): the `ldr r3, [r3]` becomes a `b` into a 3-fragment chain
 *   plus a bail fragment (fresh free `.rodata` gaps, cross-checked against every cave address
 *   every prior fix in this project already claims):
 *   - `0x9541c8` (F1): `cmp r3, #0` / `b 0x9541d8`
 *   - `0x9541d8` (F2, two-branch fragment): `beq 0x954278` (invalid: bail) / `b 0x954268`
 *     (valid: continue to F3)
 *   - `0x954268` (F3, terminal): `ldr r3, [r3]` (the original instruction, now relocated and only
 *     reached when `r3` is confirmed non-NULL) / `b 0x300b64` - resumes the UNTOUCHED original
 *     `mov lr, pc` / `ldr pc, [r3, #0x178]` tail-call exactly as before
 *   - `0x954278` (BAIL, terminal): `mov r0, #0` / `b 0x300b6c` - skips the entire virtual call and
 *     resumes right after it, with the same "nothing happened" return value (`0`) the function's
 *     own next check already treats as a normal case
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld` against the real target addresses,
 * objdump-verified (every branch resolves to exactly the intended address, and the relocated
 * `ldr` reassembles to the exact original 4 bytes byte-for-byte), then harness-verified with this
 * project's `qemu-arm` micro-harness: the exact real crash condition (`r3 = 0`) skips straight to
 * `0x300b6c` with `r0 = 0` and no dereference, and a valid pointer performs the exact original
 * dereference and resumes into the untouched original tail-call code at `0x300b64`, unchanged
 * from stock.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions (the callsite plus the three cave fragments, which must still be untouched/zero-filled)
 * match byte-for-byte what this was built against - any single mismatch leaves the file completely
 * untouched; all four regions are applied together or not at all. For a real, non-NULL player
 * `GameObject`, behavior is provably identical to the original.
 */
object ElfMassEffectUpdateSoundLevelsPlayerVtableGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00300B60L
    private const val TARGET_VADDR_CAVE_F1 = 0x009541C8L
    private const val TARGET_VADDR_CAVE_F2 = 0x009541D8L
    private const val TARGET_VADDR_CAVE_F3 = 0x00954268L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00954278L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr r3, [r3]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // b 0x9541c8
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x98.toByte(), 0x4D, 0x19, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r3, #0 / b 0x9541d8
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0x01, 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x954278 / b 0x954268
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x26, 0x00, 0x00, 0x0A,
        0x21, 0x00, 0x00, 0xEA.toByte()
    )
    // ldr r3, [r3] (relocated original) / b 0x300b64
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0x3C, 0xB2.toByte(), 0xE6.toByte(), 0xEA.toByte()
    )
    // mov r0, #0 / b 0x300b6c
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x00, 0x00, 0xA0.toByte(), 0xE3.toByte(),
        0x3A, 0xB2.toByte(), 0xE6.toByte(), 0xEA.toByte()
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
            val offF1 = fileOffsetFor(TARGET_VADDR_CAVE_F1)
            val offF2 = fileOffsetFor(TARGET_VADDR_CAVE_F2)
            val offF3 = fileOffsetFor(TARGET_VADDR_CAVE_F3)
            val offBail = fileOffsetFor(TARGET_VADDR_CAVE_BAIL)
            val caveOffs = listOf(offF1, offF2, offF3, offBail)
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
            for (i in PATCHED_BYTES_CAVE_BAIL.indices) bytes[offBail!! + i] = PATCHED_BYTES_CAVE_BAIL[i]
            return Result(
                true,
                "GameWorld::UpdateSoundLevels() no longer dereferences a NULL player GameObject's " +
                    "vtable before dispatching a virtual call on it (the player hasn't spawned yet " +
                    "at this point) - it now skips the call and defaults the result to false, the " +
                    "exact value the very next check already treats as normal, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
