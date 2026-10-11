package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #69 - found in the 0.74 real-device test (every
 * fix through #68 already in place): a brand new crash, a different branch of the same per-frame
 * HUD/render call chain fixes #66-#68 already guard three crashes in.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000584
 *     r0  00000000  r1  e10d1740  r2  e10e6d20  r3  00000000
 * #00 pc 001f536c  (Playable::AllowIndicators() const+0)
 * #01 pc 00115d30  (Hud::DrawIndicators(im::SpriteGraphics&)+32)
 * #02 pc 001177a8  (Hud::OnDraw(...)+908)
 * #03 pc 00314ae0  (LayerGameWorld::OnDraw(...)+696)
 * ```
 * `r0 == 0` (the `this` pointer itself) and `fault addr = 0x584` (1412) - `AllowIndicators()`
 * dereferences its OWN `this` pointer completely unconditionally, on its VERY FIRST instruction,
 * with no NULL check anywhere. `Hud::DrawIndicators()` calls this on whatever `Playable` it has a
 * reference to without checking it first - the same "player object not yet/no longer available"
 * situation this project has already seen cause several other crashes (fixes #48-#57's
 * `Playable::`-prefixed guards).
 *
 * ## Root cause
 *
 * ```
 * _ZNK8Playable15AllowIndicatorsEv:
 *   ldr r0, [r0, #1412]   ; <-- CRASH: r0 (this) is NULL
 *   rsbs r0, r0, #1       ; r0 = 1 - (loaded field); sets carry per unsigned subtraction
 *   movcc r0, #0          ; if the field was > 1, force r0 = 0
 *   bx lr                 ; return r0 (boolean: "indicators allowed")
 * ```
 * This is a pure leaf accessor (no stack frame - no `push`/`pop` at all) that returns `true` only
 * when a per-instance suppression counter/flag field is `0`, and `false` for any other value -
 * effectively "indicators are allowed exactly when nothing is currently suppressing them."
 *
 * ## The fix: guard `this`, return the function's own "not allowed" outcome directly
 *
 * Since the function has no stack frame to unwind, the correct bail is exactly as simple as the
 * crash: a NULL `Playable` has nothing to check, so treat it the same as "suppressed" - return
 * `false` (`0`) directly via `bx lr`, matching this function's own existing outcome for the
 * ordinary "currently suppressed" case, and every real caller of a boolean "allowed?" query already
 * has to handle a `false` result as a normal, expected case.
 *
 * - **Callsite** (`0x1f536c`, 4 bytes): the `ldr r0, [r0, #1412]` becomes a `b` into a 3-fragment
 *   trampoline chain plus a one-instruction-pair bail (fresh free `.rodata` gaps, cross-checked
 *   against every cave address every prior fix in this project already claims - including fixes
 *   #66-#68's - so there is no collision):
 *   - `0x9512c0` (F1): `cmp r0, #0` / `b 0x951590`
 *   - `0x951590` (F2, two-branch fragment): `beq 0x951740` (invalid: bail) / `b 0x951620`
 *     (valid: continue to F3)
 *   - `0x951620` (F3, terminal): `ldr r0, [r0, #1412]` (the original instruction, now relocated
 *     and only reached when `r0` is confirmed non-NULL) / `b 0x1f5370` (resumes the untouched
 *     original code right after the callsite)
 *   - `0x951740` (BAIL, terminal): `mov r0, #0` / `bx lr` - returns `false` directly, exactly
 *     mirroring the function's own "no stack frame, just return" shape
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld` against the real target addresses,
 * verified with `objdump` (every branch target resolves to exactly the intended address, and the
 * relocated `ldr` reassembles to the exact original 4 bytes byte-for-byte), then verified again
 * with this project's `qemu-arm` micro-harness across three cases: the exact real crash condition
 * (`r0 = 0`) takes the bail path and returns `0` with no dereference; a valid object whose
 * suppression field is `0` returns `1` (allowed); and a valid object whose field is `1` returns `0`
 * (not allowed) - proving the guard changes nothing about the function's real logic for a real
 * `Playable`, only adds the missing NULL check.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions (the callsite plus the three cave fragments and the bail slot, which must still be
 * untouched/zero-filled) match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all four regions are applied together or not at all. For a
 * real, non-NULL `Playable`, behavior is provably identical to the original.
 */
object ElfMassEffectAllowIndicatorsNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001F536CL
    private const val TARGET_VADDR_CAVE_F1 = 0x009512C0L
    private const val TARGET_VADDR_CAVE_F2 = 0x00951590L
    private const val TARGET_VADDR_CAVE_F3 = 0x00951620L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00951740L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr r0, [r0, #1412]
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x84.toByte(), 0x05, 0x90.toByte(), 0xE5.toByte()
    )
    // b 0x9512c0
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xD3.toByte(), 0x6F, 0x1D, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0, #0 / b 0x951590
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0xB1.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x951740 / b 0x951620
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x6A, 0x00, 0x00, 0x0A,
        0x21, 0x00, 0x00, 0xEA.toByte()
    )
    // ldr r0, [r0, #1412] (relocated original) / b 0x1f5370
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x84.toByte(), 0x05, 0x90.toByte(), 0xE5.toByte(),
        0x51, 0x8F.toByte(), 0xE2.toByte(), 0xEA.toByte()
    )
    // mov r0, #0 / bx lr
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x00, 0x00, 0xA0.toByte(), 0xE3.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
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
                "Playable::AllowIndicators() no longer dereferences a NULL 'this' pointer when " +
                    "Hud::DrawIndicators() queries it on a player object that isn't (or is no " +
                    "longer) available - it now returns false (the same 'indicators not allowed' " +
                    "outcome this function already produces for the ordinary suppressed case) " +
                    "instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
