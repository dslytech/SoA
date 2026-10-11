package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #68 - found in the 0.73 real-device test (every
 * fix through #67, including the `GameObject::GetNode()` null guard, already in place): a brand
 * new crash one call further UP the same chain fix #67 just made safe - `GetNode()` now correctly
 * returns NULL instead of crashing, but its caller's caller wasn't ready for that NULL either.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000134
 *     r0  00000000  r1  00000000  r2  00000000  r3  007951b8
 * #00 pc 0030374c  (GameWorld::OnRender(...)+168)
 * #01 pc 002ea320  (Composer::OnDraw(...)+2384)
 * #02 pc 00314a1c  (LayerGameWorld::OnDraw(...)+500)
 * ```
 * `r0 == 0`, `fault addr = 0x134` (308) - a full, symbolized backtrace this time, three levels
 * deep into the same per-frame render call chain fix #66 already guards one crash in.
 *
 * ## Root cause
 *
 * ```
 * 303740: ldr r0, [r4, #452]         ; r0 = this GameObject's actor/game-object field
 * 303744: bl GameObject::GetGroup()  ; internally calls GetNode() (fix #67), returns NULL when
 *                                    ; GetNode() does - GetGroup() ALREADY null-checks this itself
 *                                    ; (`subs r4,r0,#0; beq <return NULL>`), so it correctly hands
 *                                    ; back NULL rather than crashing
 * 30374c: ldrb r8, [r0, #308]        ; <-- CRASH: r0 (GetGroup()'s result) is NULL, and THIS call
 *                                    ;     site never checks it
 * ```
 * `GameWorld::OnRender()` calls `GameObject::GetGroup()` and immediately dereferences its result
 * completely unconditionally, one instruction later, with no NULL check - a group-less game object
 * (exactly the case fix #67 made survivable one level down) reaches here and crashes anyway.
 *
 * ## The fix: guard the pointer, default the flag it feeds to "off"
 *
 * The loaded byte (`r8`) is immediately used two instructions later purely as a boolean gate:
 * `cmp r8,#0` / `ldrbne r8,[r0,#309]` - a second byte is only read (through the SAME pointer) when
 * the first one was non-zero. Setting `r8 = 0` on the NULL-pointer path is therefore not just "a
 * safe default" - it's exactly what's needed to make the very next instruction skip its own use of
 * the same NULL pointer too (the `ne`-conditional load is naturally never taken when r8 is 0),
 * with no second guard required. Nothing outside this immediate two-instruction window reads `r0`
 * again before it's reassigned a few lines later, so this one guard fully covers the crash.
 *
 * - **Callsite** (`0x30374c`, 4 bytes): the `ldrb r8, [r0, #308]` becomes a `b` into a 3-fragment
 *   trampoline chain plus a "default to false" bail fragment (fresh free `.rodata` gaps,
 *   cross-checked against every cave address every prior fix in this project already claims -
 *   including fixes #66 and #67's - so there is no collision):
 *   - `0x950ce8` (F1): `cmp r0, #0` / `b 0x950d40`
 *   - `0x950d40` (F2, two-branch fragment): `beq 0x950f98` (invalid: bail) / `b 0x950f68`
 *     (valid: continue to F3)
 *   - `0x950f68` (F3, terminal): `ldrb r8, [r0, #308]` (the original instruction, now relocated
 *     and only reached when `r0` is confirmed non-NULL) / `b 0x303750` (resumes the untouched
 *     original code right after the callsite)
 *   - `0x950f98` (BAIL, terminal): `mov r8, #0` (the "no group" default, which also makes the
 *     following `ldrbne r8,[r0,#309]` a no-op) / `b 0x303750` (resumes normally, same as F3)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld` against the real target addresses,
 * verified with `objdump` (every branch target resolves to exactly the intended address, and the
 * relocated `ldrb r8, [r0, #308]` reassembles to the exact original 4 bytes byte-for-byte), then
 * verified again with this project's `qemu-arm` micro-harness: the exact real crash condition
 * (`r0 = 0`) takes the bail path and leaves `r8 = 0` with no dereference (matching what the
 * following conditional load already treats as "nothing more to read"), and a valid pointer
 * performs the exact original dereference and produces the exact original byte value, proving
 * behavior for a real `Group*` is completely unchanged.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions (the callsite plus the three cave fragments and the bail slot, which must still be
 * untouched/zero-filled) match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all four regions are applied together or not at all. For a
 * real, non-NULL `Group*`, behavior is provably identical to the original.
 */
object ElfMassEffectGameWorldOnRenderGroupNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0030374CL
    private const val TARGET_VADDR_CAVE_F1 = 0x00950CE8L
    private const val TARGET_VADDR_CAVE_F2 = 0x00950D40L
    private const val TARGET_VADDR_CAVE_F3 = 0x00950F68L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00950F98L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldrb r8, [r0, #308]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x34, 0x81.toByte(), 0xD0.toByte(), 0xE5.toByte()
    )
    // b 0x950ce8
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x65, 0x35, 0x19, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0, #0 / b 0x950d40
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x13, 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x950f98 / b 0x950f68
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x94.toByte(), 0x00, 0x00, 0x0A,
        0x87.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    // ldrb r8, [r0, #308] (relocated original) / b 0x303750
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x34, 0x81.toByte(), 0xD0.toByte(), 0xE5.toByte(),
        0xF7.toByte(), 0xC9.toByte(), 0xE6.toByte(), 0xEA.toByte()
    )
    // mov r8, #0 / b 0x303750
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x00, 0x80.toByte(), 0xA0.toByte(), 0xE3.toByte(),
        0xEB.toByte(), 0xC9.toByte(), 0xE6.toByte(), 0xEA.toByte()
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
                "GameWorld::OnRender() no longer dereferences a NULL GameObject::GetGroup() result " +
                    "(the exact 'no group' case fix #67 already made GetNode()/GetGroup() return " +
                    "safely instead of crashing) - it now defaults the group-flag byte this call " +
                    "site reads to false (which also makes the very next conditional read of the " +
                    "same pointer a no-op) instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
