package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #67 - found in the 0.72 real-device test (every
 * fix through #66, including the landscape-orientation `LayerGameWorld::OnDraw()` null guard,
 * already in place): a brand new crash, one call earlier than any previous fix has reached.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000028
 * Cause: null pointer dereference
 *     r0  00000000  r1  00000000  r2  00000000  r3  00000000
 * #00 pc 001de274  (GameObject::GetNode() const+4)
 * #01 pc 00102ee6  [anon:stack_and_tls:...]
 * ```
 * `r0 == 0` (the `this` pointer itself) and `fault addr = 0x28` (40 decimal) - `GetNode()`
 * dereferences its OWN `this` pointer completely unconditionally, on its very second instruction,
 * with no NULL check anywhere before it. The caller is an `<unknown>`/anonymous stack frame
 * (consistent with `GetSunFlare()`'s crash, fix #58 - some render/game-object-list callback
 * dispatch this project's static analysis can't fully trace), so whoever calls this sometimes does
 * so on a NULL or not-yet-constructed `GameObject`.
 *
 * ## Root cause
 *
 * ```
 * push {r4,r5,lr}
 * ldr  r4, [r0, #40]     ; <-- CRASH: r0 (this) is NULL
 * sub  sp, sp, #12
 * mov  r5, r0
 * cmp  r4, #0
 * beq  <fallback: recompute via weak_ptr lock, ultimately may still return NULL>
 * ...
 * ```
 * The crash happens BEFORE the function's own stack frame adjustment (`sub sp,sp,#12`) - only the
 * initial `push {r4,r5,lr}` has executed. `GetNode()` already has multiple internal "nothing
 * available" paths later in its body that end up returning NULL through its normal
 * `add sp,sp,#12 / pop {r4,r5,pc}` epilogue (e.g. when its internal `ActorData` weak pointer or
 * node pointer isn't set) - a NULL `Node*` is already a normal, expected result callers of this
 * function have to handle. A NULL `this` is just one more case of "nothing to give back."
 *
 * ## The fix: guard `this`, return NULL via the exact stack state already in play
 *
 * Since the crash sits before `sub sp,sp,#12` executes, the correct bail is not the function's
 * normal epilogue (which expects that adjustment to have happened) but a direct
 * `pop {r4,r5,pc}` - unwinding exactly the `push {r4,r5,lr}` that already ran, nothing more,
 * nothing less. `r0` is already `0` at the guard (that's the condition being tested), so no
 * explicit "set return value" step is even needed - the bail is a single instruction.
 *
 * - **Callsite** (`0x1de274`, 4 bytes): the `ldr r4, [r0, #40]` becomes a `b` into a 3-fragment
 *   trampoline chain plus a one-instruction bail target (fresh free `.rodata` gaps, cross-checked
 *   against every cave address every prior fix in this project already claims - including fix
 *   #66's three - so there is no collision):
 *   - `0x94ffc0` (F1): `cmp r0, #0` / `b 0x9502a8`
 *   - `0x9502a8` (F2, two-branch fragment): `beq 0x950bb8` (invalid: bail) / `b 0x950948`
 *     (valid: continue to F3)
 *   - `0x950948` (F3, terminal): `ldr r4, [r0, #40]` (the original instruction, now relocated and
 *     only reached when `r0` is confirmed non-NULL) / `b 0x1de278` (resumes the untouched original
 *     code right after the callsite)
 *   - `0x950bb8` (BAIL, terminal, single real instruction): `pop {r4, r5, pc}` - unwinds exactly
 *     the function's own already-executed prologue and returns to the caller with `r0` left at `0`
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld` against the real target addresses,
 * verified with `objdump` (every branch target resolves to exactly the intended address, and the
 * relocated `ldr r4, [r0, #40]` reassembles to the exact original 4 bytes byte-for-byte), then
 * verified again with this project's `qemu-arm` micro-harness simulating a real call into the
 * function (the function's own real `push {r4,r5,lr}` prologue, then jumping into the guard exactly
 * where the real callsite sits): the invalid case (`r0 = 0`, the exact real crash condition) takes
 * the bail path and returns with `r0` still `0` - the same "no node" result callers already handle
 * - and the valid case (a real struct pointer) performs the exact original dereference and returns
 * the exact original value read from `[this+40]`, proving behavior for a real `GameObject` is
 * completely unchanged.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions (the callsite plus the three cave fragments and the bail slot, which must still be
 * untouched/zero-filled) match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all four regions are applied together or not at all. For a
 * real, non-NULL `GameObject`, behavior is provably identical to the original.
 */
object ElfMassEffectGameObjectGetNodeNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001DE274L
    private const val TARGET_VADDR_CAVE_F1 = 0x0094FFC0L
    private const val TARGET_VADDR_CAVE_F2 = 0x009502A8L
    private const val TARGET_VADDR_CAVE_F3 = 0x00950948L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00950BB8L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr r4, [r0, #40]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x28, 0x40, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x94ffc0
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x51, 0xC7.toByte(), 0x1D, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0, #0 / b 0x9502a8
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0xB7.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x950bb8 / b 0x950948
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x42, 0x02, 0x00, 0x0A,
        0xA5.toByte(), 0x01, 0x00, 0xEA.toByte()
    )
    // ldr r4, [r0, #40] (relocated original) / b 0x1de278
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x28, 0x40, 0x90.toByte(), 0xE5.toByte(),
        0x49, 0x36, 0xE2.toByte(), 0xEA.toByte()
    )
    // pop {r4, r5, pc} - single real instruction, rest of the 8-byte slot unused/zero
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x30, 0x80.toByte(), 0xBD.toByte(), 0xE8.toByte(),
        0x00, 0x00, 0x00, 0x00
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
                "GameObject::GetNode() no longer dereferences a NULL 'this' pointer passed in by " +
                    "some untraceable render/game-object-list caller - it now returns NULL (the " +
                    "exact same 'no node available' result this function's own other internal " +
                    "fallback paths already produce for callers to handle) instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
