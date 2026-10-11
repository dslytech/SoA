package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #75 - found in the 0.76 real-device test (every
 * fix through #74 already in place, all five of them correctly applying since the game had never
 * reached this state before - see their own doc comments). This is a BRAND NEW call chain, not
 * the render/HUD path fixes #66-#74 all guard - it's the game's per-frame UPDATE (logic) tick
 * instead, the first crash ever seen in this project on that side of the engine.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000007fc
 *     r0  e1e64d30  r1  00000014  r2  000001f4  r3  00000000
 * #00 pc 0030da6c  (GameWorld::OnUpdate(int)+52)
 * #01 pc 0031cd00  (LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180)
 * #02 pc 00315e10  (LayerGameWorld::OnEvent(im::Event const&)+1564)
 * #03 pc 003d99c4  (im::LayerStack::OnEvent(im::Event const&)+124)
 * #04 pc 00329250  (Application::OnUpdate()+972)
 * ```
 * `r3 == 0` and `fault addr = 0x7fc` (2044) match exactly.
 *
 * ## Root cause
 *
 * ```
 * _ZN9GameWorld8OnUpdateEi:
 *   ldr  r3, [r4, #452]    ; r3 = this->player (the same field GameObject::GetPlayer()/
 *                          ;   GetPlayerObj() read - see fixes #71's doc comment; NOT itself
 *                          ;   guarded here, this is GameWorld reading its OWN player field
 *                          ;   directly, not through that accessor)
 *   ldrb r3, [r3, #2044]   ; <-- CRASH: r3 (the player pointer) is NULL
 *   cmp  r3, #0
 *   bne  ...               ; a one-shot flag on the player object - the sibling code path at
 *                          ; +0xe4 both reads and clears this same byte, gated by a Settings
 *                          ; flag, consistent with some kind of "pending/just-spawned" toggle
 * ```
 * The player `GameObject` doesn't exist yet at this point (e.g. very early in a level's first
 * update tick, before the player has actually spawned), and `GameWorld::OnUpdate()` reads a flag
 * byte off of it completely unconditionally, with no NULL check - the exact same "player object
 * not yet available" situation already behind several earlier fixes (`Playable::`-prefixed fixes
 * #48-#57, and fix #69's `AllowIndicators()`), just hitting a different accessor this time.
 *
 * ## The fix: default the missing flag to "not set" (`false`/`0`)
 *
 * The loaded byte is used immediately afterward purely as a boolean gate (`cmp r3,#0` / `bne ...`),
 * so treating a NULL player the same as "flag not currently set" is exactly correct, not just a
 * safe placeholder - and since the checked register (`r3`, the player pointer) and the loaded
 * register (`r3`, the flag byte) are the SAME register here, the NULL case doesn't even need to
 * write anything: it's already `0`, exactly the value the rest of the function expects for "flag
 * off". This makes the cave unusually small for this project - only three fragments, no separate
 * bail fragment needed at all:
 *
 * - **Callsite** (`0x30da6c`, 4 bytes): the `ldrb r3, [r3, #2044]` becomes a `b` into a 2-fragment
 *   check chain plus a relocated-original terminal (fresh free `.rodata` gaps, cross-checked
 *   against every cave address every prior fix in this project already claims):
 *   - `0x954108` (F1): `cmp r3, #0` / `b 0x954118`
 *   - `0x954118` (F2, two-branch fragment): `beq 0x30da70` (invalid: `r3` is already `0`, the
 *     exact "flag off" value needed - resume DIRECTLY, no bail fragment required) / `b 0x9541b8`
 *     (valid: continue to F3)
 *   - `0x9541b8` (F3, terminal): `ldrb r3, [r3, #2044]` (the original instruction, now relocated
 *     and only reached when `r3` is confirmed non-NULL) / `b 0x30da70` (resumes the untouched
 *     original code right after the callsite)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld` against the real target addresses,
 * objdump-verified (every branch resolves to exactly the intended address, and the relocated
 * `ldrb` reassembles to the exact original 4 bytes byte-for-byte), then harness-verified with this
 * project's `qemu-arm` micro-harness: the exact real crash condition (`r3 = 0`) resumes immediately
 * with `r3` still `0` and no dereference, and a valid pointer performs the exact original
 * dereference, returning the exact original byte value, unchanged from stock.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL THREE target
 * regions (the callsite plus the two cave fragments, which must still be untouched/zero-filled)
 * match byte-for-byte what this was built against - any single mismatch leaves the file completely
 * untouched; all three regions are applied together or not at all. For a real, non-NULL player
 * `GameObject`, behavior is provably identical to the original.
 */
object ElfMassEffectGameWorldOnUpdatePlayerFlagNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0030DA6CL
    private const val TARGET_VADDR_CAVE_F1 = 0x00954108L
    private const val TARGET_VADDR_CAVE_F2 = 0x00954118L
    private const val TARGET_VADDR_CAVE_F3 = 0x009541B8L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldrb r3, [r3, #2044]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xFC.toByte(), 0x37, 0xD3.toByte(), 0xE5.toByte()
    )
    // b 0x954108
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xA5.toByte(), 0x19, 0x19, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r3, #0 / b 0x954118
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0x01, 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x30da70 / b 0x9541b8
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x54, 0xE6.toByte(), 0xE6.toByte(), 0x0A,
        0x25, 0x00, 0x00, 0xEA.toByte()
    )
    // ldrb r3, [r3, #2044] (relocated original) / b 0x30da70
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0xFC.toByte(), 0x37, 0xD3.toByte(), 0xE5.toByte(),
        0x2B, 0xE6.toByte(), 0xE6.toByte(), 0xEA.toByte()
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
            val caveOffs = listOf(offF1, offF2, offF3)
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
            return Result(
                true,
                "GameWorld::OnUpdate() no longer dereferences a NULL player GameObject pointer " +
                    "when reading its one-shot per-frame flag byte (the player hasn't spawned yet " +
                    "at this point) - it now defaults the flag to 'not set', the exact value the " +
                    "very next check already treats as normal, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
