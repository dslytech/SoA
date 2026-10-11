package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #57, found in the 0.61 test (every fix through
 * #56 present) - the SAME fault address as fix #56's crash (`0x3e4cccd1`), one call further down
 * in the exact same function: `LayerGameWorld::LoadLevel(LoadTransition*)`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3e4cccd1
 * #00 pc 000b2930  (eastl::shared_ptr<...Subtitles...>::~shared_ptr()+12)
 * #01 pc 00316d64  (LayerGameWorld::LoadLevel(LoadTransition*)+184)
 * #02 pc 0012d8dc  (LoadTransitionTask::Run()+564)
 * ```
 * Fix #56 already guards the FIRST use of the new subtitle widget shared_ptr's garbage
 * control-block pointer (the refcount increment at `0x316d28`) - and that guard works: the crash
 * no longer happens there. But the exact same garbage pointer (`0x3e4ccccd`, still sitting
 * untouched at `[sp,#16]` - nothing in this function overwrites that stack slot) gets used a
 * SECOND time, later in the same function, when the local temporary `eastl::shared_ptr` that
 * `GameWorld::GetSubtitleWidget()` originally returned goes out of scope and its destructor runs:
 * ```
 * mov  r0, r5              ; r5 = sp+12, the temp {ptr, ctrl} pair from GetSubtitleWidget()
 * bl   ~shared_ptr()        ; <-- CRASH (12 bytes into the destructor): dereferences the SAME
 *                           ;     garbage ctrl pointer at [r5,#4] == [sp,#16]
 * ```
 * This destructor call happens unconditionally - on every path through the function, including
 * the self-assignment shortcut fix #56's guard also uses (`beq`/the new `blo` both land just
 * before it) - because the temporary's own C++ scope ends here regardless of whether its value was
 * actually adopted by the member.
 *
 * ## The fix: the same heap-floor check, guarding this second use of the same pointer
 *
 * Reload the ctrl pointer from `[sp,#16]` one more time right before this destructor call and
 * apply the exact same `>= 0xC0000000` floor check fix #56 already uses. If it's invalid, skip the
 * destructor call entirely (there is nothing real to free - a garbage ctrl pointer never
 * represented an actual refcounted allocation, consistent with fix #56's own reasoning) and jump
 * straight past both this and the earlier (untouched, real) destructor call for the OLD member
 * value to the function's own stack-teardown. If valid, set up `r0` exactly as the original code
 * did and let the untouched original `bl` execute normally.
 *
 * - **Callsite** (`0x316d60`, 4 bytes): the `mov r0, r5` immediately before the destructor call
 *   becomes a `bl` into a 4-fragment trampoline chain (fresh free `.rodata` gaps, re-scanned to
 *   exclude every region every prior fix - including fix #56's three - already claims):
 *   - `0x953458` (I1): `ldr r3, [sp, #16]` (reloads the same ctrl pointer fix #56 already checked
 *     once) / `b 0x953550`
 *   - `0x953550` (I2): `cmp r3, #0xC0000000` / `b 0x953580`
 *   - `0x953580` (I3): `blo 0x316d68` (invalid: skip straight past both the destructor call at
 *     `0x316d64` AND the untouched earlier one at `0x316d5c` was already behind us by this point -
 *     lands exactly on the function's `add sp,sp,#24` stack teardown) / `b 0x9535E8`
 *   - `0x9535E8` (I4, terminal-valid): `mov r0, r5` (the original instruction, now relocated) /
 *     `b 0x316d64` (resumes the untouched original `bl` to the real destructor)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all four branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness: both the exact real crash register value (`r3 = 0x3e4ccccd`) and a valid
 * heap-range value (`r3 = 0xd8da9430`) resolve to the correct outcome - `pc = 0x316d68` for the
 * invalid case, `pc = 0x316d64` (with `r0` correctly set to `r5`) for the valid case.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR target
 * regions match byte-for-byte what this was built against - any single mismatch leaves the file
 * completely untouched; all four regions are applied together or not at all. For a real ctrl
 * pointer (always `>= 0xC0000000` on every device this project has seen data from), behavior is
 * provably identical to the original: the destructor still runs exactly as before.
 */
object ElfMassEffectLoadLevelSubtitleDtorGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00316D60L
    private const val TARGET_VADDR_CAVE_I1 = 0x00953458L
    private const val TARGET_VADDR_CAVE_I2 = 0x00953550L
    private const val TARGET_VADDR_CAVE_I3 = 0x00953580L
    private const val TARGET_VADDR_CAVE_I4 = 0x009535E8L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x05, 0x00, 0xA0.toByte(), 0xE1.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xBC.toByte(), 0xF1.toByte(), 0x18, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_I1 = byteArrayOf(
        0x10, 0x30, 0x9D.toByte(), 0xE5.toByte(),
        0x3B, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_I2 = byteArrayOf(
        0x03, 0x01, 0x53, 0xE3.toByte(),
        0x09, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_I3 = byteArrayOf(
        0xF8.toByte(), 0x0D, 0xE7.toByte(), 0x3A,
        0x17, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_I4 = byteArrayOf(
        0x05, 0x00, 0xA0.toByte(), 0xE1.toByte(),
        0xDC.toByte(), 0x0D, 0xE7.toByte(), 0xEA.toByte()
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
            val offI1 = fileOffsetFor(TARGET_VADDR_CAVE_I1)
            val offI2 = fileOffsetFor(TARGET_VADDR_CAVE_I2)
            val offI3 = fileOffsetFor(TARGET_VADDR_CAVE_I3)
            val offI4 = fileOffsetFor(TARGET_VADDR_CAVE_I4)
            val caveOffs = listOf(offI1, offI2, offI3, offI4)
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
            for (i in PATCHED_BYTES_CAVE_I1.indices) bytes[offI1!! + i] = PATCHED_BYTES_CAVE_I1[i]
            for (i in PATCHED_BYTES_CAVE_I2.indices) bytes[offI2!! + i] = PATCHED_BYTES_CAVE_I2[i]
            for (i in PATCHED_BYTES_CAVE_I3.indices) bytes[offI3!! + i] = PATCHED_BYTES_CAVE_I3[i]
            for (i in PATCHED_BYTES_CAVE_I4.indices) bytes[offI4!! + i] = PATCHED_BYTES_CAVE_I4[i]
            return Result(
                true,
                "LayerGameWorld::LoadLevel() no longer destructs its subtitle widget shared_ptr " +
                    "temporary through a control-block pointer below the real heap floor " +
                    "(0xC0000000) - the destructor call is now skipped for that (never actually " +
                    "refcounted) value, instead of crashing on the very first level load."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
