package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #58, found in the 0.62 test (every fix through
 * #57 present) - a brand new crash, PAST level loading entirely, during actual rendering:
 * `Environment::GetSunFlare(int, Hud const*)`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000a4
 * Cause: null pointer dereference
 * #00 pc 002fc20c  (Environment::GetSunFlare(int, Hud const*)+4)
 * #01 pc 0000004c  <unknown>
 * ```
 * `fault addr = 0xa4` - a plain NULL-plus-small-offset dereference, this time of the function's
 * OWN second argument (the `Hud const*`), not a member field. The caller is an `<unknown>` frame
 * (an unresolvable/generated address), consistent with this being invoked through some render
 * callback table rather than a normal direct call - the exact identity of the caller doesn't
 * matter here, only that it's passing a NULL `Hud*` into this function at least once.
 *
 * ## Root cause
 *
 * ```
 * push {r4,r5,r6,r7,r8,r9,sl,fp,lr}
 * ldrb ip, [r1, #164]          ; <-- CRASH: r1 (the Hud* parameter) is NULL
 * ldr  r6, [pc, #...]
 * sub  sp, sp, #244
 * cmp  ip, #0
 * str  r0, [sp, #12]           ; (saves the int arg for later - this is the function's OWN
 *                               ;  "return the same int unchanged" early-out convention, already
 *                               ;  used by several of its many other internal bail-out paths)
 * ...
 * ```
 * `GetSunFlare()` dereferences its `Hud const*` parameter completely unconditionally, on its very
 * second instruction, with no NULL check anywhere before it - whoever calls this (apparently some
 * render-callback dispatch this project's static analysis can't fully trace) sometimes does so
 * with no valid `Hud` yet available.
 *
 * ## The fix: guard the parameter, return the function's OWN existing "unchanged" sentinel
 *
 * This function has exactly ONE shared epilogue in its entire body (`add sp,sp,#244` /
 * `pop {r4,r5,r6,r7,r8,r9,sl,fp,pc}`), reached by many different internal early-out conditions,
 * always returning whatever integer was originally passed in via `r0` completely unchanged - a
 * "nothing to do, return the input flare index as-is" convention this function's own logic already
 * relies on repeatedly. Since our guard sits BEFORE the stack is even adjusted (`sub sp,#244`
 * hasn't executed yet) and before `r0` is saved anywhere, the simplest and most exactly-equivalent
 * bail-out is to skip straight past that entire adjustment and pop the very same registers the
 * prologue just pushed, directly - `r0` is still sitting in the register exactly as the caller
 * passed it, so this is byte-for-byte the same observable outcome the function's own "return
 * unchanged" paths already produce elsewhere.
 *
 * - **Callsite** (`0x2fc20c`, 4 bytes): the `ldrb ip, [r1, #164]` becomes a `bl` into a 4-fragment
 *   trampoline chain (fresh free `.rodata` gaps, re-scanned to exclude every region every prior
 *   fix - including fix #57's four - already claims):
 *   - `0x953678` (J1): `cmp r1, #0xC0000000` / `b 0x953690`
 *   - `0x953690` (J2): `blo 0x953760` (invalid: jump to J4) / `b 0x9536D0` (valid: jump to J3)
 *   - `0x9536D0` (J3, terminal-valid): `ldrb ip, [r1, #164]` (the original instruction, now
 *     relocated) / `b 0x2fc210` (resumes the untouched original code right after the callsite)
 *   - `0x953760` (J4, terminal-invalid): `pop {r4,r5,r6,r7,r8,r9,sl,fp,pc}` - returns immediately
 *     with `r0` unchanged, exactly like this function's own other early-out paths
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all three branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness, this time simulating a real call into the function (a synthetic stack frame with
 * a marker return address, since the guard sits before the function's own register push executes):
 * both a NULL `Hud*` (the exact real crash condition) and a valid heap-range `Hud*`
 * (`r1 = 0xd8da9430`) resolve to the correct outcome - the marker return address for the invalid
 * case (proving `r0` was left untouched and the real prologue/dereference never ran), `pc =
 * 0x2fc210` for the valid case (proving the original dereference and control flow are completely
 * unchanged).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions match byte-for-byte what this was built against - any single mismatch leaves the file
 * completely untouched; all four regions are applied together or not at all. For a real `Hud*`
 * (always `>= 0xC0000000` on every device this project has seen data from), behavior is provably
 * identical to the original.
 */
object ElfMassEffectGetSunFlareNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x002FC20CL
    private const val TARGET_VADDR_CAVE_J1 = 0x00953678L
    private const val TARGET_VADDR_CAVE_J2 = 0x00953690L
    private const val TARGET_VADDR_CAVE_J3 = 0x009536D0L
    private const val TARGET_VADDR_CAVE_J4 = 0x00953760L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xA4.toByte(), 0xC0.toByte(), 0xD1.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x19, 0x5D, 0x19, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_J1 = byteArrayOf(
        0x03, 0x01, 0x51, 0xE3.toByte(),
        0x03, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_J2 = byteArrayOf(
        0x32, 0x00, 0x00, 0x3A,
        0x0D, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_J3 = byteArrayOf(
        0xA4.toByte(), 0xC0.toByte(), 0xD1.toByte(), 0xE5.toByte(),
        0xCD.toByte(), 0xA2.toByte(), 0xE6.toByte(), 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_J4 = byteArrayOf(
        0xF0.toByte(), 0x8F.toByte(), 0xBD.toByte(), 0xE8.toByte(),
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
            val offJ1 = fileOffsetFor(TARGET_VADDR_CAVE_J1)
            val offJ2 = fileOffsetFor(TARGET_VADDR_CAVE_J2)
            val offJ3 = fileOffsetFor(TARGET_VADDR_CAVE_J3)
            val offJ4 = fileOffsetFor(TARGET_VADDR_CAVE_J4)
            val caveOffs = listOf(offJ1, offJ2, offJ3, offJ4)
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
            for (i in PATCHED_BYTES_CAVE_J1.indices) bytes[offJ1!! + i] = PATCHED_BYTES_CAVE_J1[i]
            for (i in PATCHED_BYTES_CAVE_J2.indices) bytes[offJ2!! + i] = PATCHED_BYTES_CAVE_J2[i]
            for (i in PATCHED_BYTES_CAVE_J3.indices) bytes[offJ3!! + i] = PATCHED_BYTES_CAVE_J3[i]
            for (i in PATCHED_BYTES_CAVE_J4.indices) bytes[offJ4!! + i] = PATCHED_BYTES_CAVE_J4[i]
            return Result(
                true,
                "Environment::GetSunFlare() no longer dereferences a NULL Hud pointer passed in by " +
                    "its caller - it now returns the input flare index unchanged (this function's " +
                    "own existing convention for its many other 'nothing to do' early-out paths) " +
                    "instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
