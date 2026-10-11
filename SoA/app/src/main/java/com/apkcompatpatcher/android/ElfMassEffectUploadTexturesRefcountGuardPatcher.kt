package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #62, found in the 0.66 test (every fix through
 * #60's Round 2 present) - a brand new crash, one level deeper in the same texture-upload
 * subsystem the previous two fixes worked through: a corrupt refcount write inside
 * `eastl::shared_ptr<im::TextureImage, ...>::~shared_ptr()`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xe5845014
 *     r0  e12e3158  r1  e12e3150  r2  4b152351  r3  e5845010
 * #00 pc 0010c7c0  (eastl::shared_ptr<im::TextureImage,...>::~shared_ptr()+24)
 * #01 pc 0010c800  (eastl::shared_ptr<im::TextureImage,...>::~shared_ptr()+88)
 * #02 pc 0012d210  (LoadTransitionTask::UploadTextures(eastl::intrusive_ptr<m3g::Renderer> const&)+164)
 * ```
 * Note `code 2 (SEGV_ACCERR)`, not the usual `SEGV_MAPERR` - the faulting address IS mapped, just
 * not writable (this project's other fixes fault on completely unmapped addresses instead). Frame
 * #01 is this SAME destructor calling itself recursively for an embedded `shared_ptr` member of
 * the `TextureImage` payload (`add r0, r5, #8` / `bl` self); the crash is in that INNER call.
 *
 * ## Root cause
 *
 * ```
 * push {r4,r5,r6,lr}
 * ldr  r3, [r0, #4]      ; r3 = this shared_ptr's control-block pointer
 * mov  r4, r0
 * ldr  r2, [r3, #4]      ; r2 = strong refcount - this READ succeeds (the page is mapped)
 * sub  r2, r2, #1
 * cmp  r2, #0
 * str  r2, [r3, #4]      ; <-- CRASH: writing back to a READ-ONLY mapped page
 * ble  ...
 * ```
 * The register dump shows `r2 = 0x4b152351` - AFTER the decrement, so the value actually read from
 * `[r3, #4]` was `0x4b152352`: not a null, not a small sentinel, not even an obviously "wild" upper
 * address - just a number nowhere near a plausible strong-reference count (real counts for a
 * texture are always small, single/double-digit values). `r3` itself (`0xe5845010`) also isn't
 * caught by this project's usual `< 0xC0000000` real-heap-floor check (it's numerically ABOVE that
 * floor), so a pointer-magnitude guard alone would have missed this. The actual bug: this
 * `TextureImage`'s embedded `shared_ptr` member was never properly initialized before being
 * destructed (a partially-constructed object on some error path) - its control-block field holds
 * whatever was previously in that memory, and in this case that happens to be a real, mapped,
 * read-only address elsewhere in the process (hence `SEGV_ACCERR`, not `SEGV_MAPERR`).
 *
 * ## The fix: validate the refcount VALUE itself, not the pointer
 *
 * Since the pointer's magnitude isn't a reliable signal here, the guard instead checks the thing
 * that's actually being trusted: is the strong-refcount value that was just read a plausible small
 * positive integer? A real object's refcount is always tiny; a value like `0x4b152352` (or, on the
 * flip side, `0` - which would underflow to `0xFFFFFFFF` on the very next `sub`, an existing latent
 * bug this same check also happens to catch) is never legitimate. If the decremented value doesn't
 * fit in `[0, 0x10000)`, the destructor now bails straight to its own existing "nothing more to do"
 * epilogue (`0x10c7d8`, the same `mov r0,r4 / pop {r4,r5,r6,pc}` path this function already takes
 * when a real object's refcount legitimately reaches zero with no weak references left) instead of
 * writing through the corrupt control-block pointer.
 *
 * - **Callsite** (`0x10c7b8`, 4 bytes): the `sub r2, r2, #1` becomes a `bl` into a 4-fragment
 *   trampoline chain (fresh free `.rodata` gaps, re-scanned to exclude every region every prior fix
 *   - including this fix's own earlier siblings #56-#60 - already claims):
 *   - `0x953C80` (K1): `sub r1, r2, #1` (the original instruction, relocated - computed into a
 *     scratch register so the real `r2` is left untouched until the value is validated) /
 *     `b 0x953E88`
 *   - `0x953E88` (K2): `cmp r1, #0x10000` / `b 0x953FF0`
 *   - `0x953FF0` (K3): `bhs 0x10c7d8` (invalid - including the `r2 == 0` underflow-to-huge case,
 *     caught by the same unsigned comparison - bail to the function's own existing "nothing more to
 *     do" path) / `b 0x954068` (valid: fall through to K4)
 *   - `0x954068` (K4, terminal-valid): `mov r2, r1` (commit the real decrement, now validated) /
 *     `b 0x10c7bc` (resumes the untouched original code right after the callsite - `cmp r2, #0`)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all four branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness: both the exact real crash's pre-decrement value (`r2 = 0x4b152352`) and a plausible
 * real refcount (`r2 = 2`) resolve to the correct outcome - landing on the function's own "nothing
 * more to do" bail for the invalid case, and resuming exactly at `0x10c7bc` with the correctly
 * decremented value (`r2 = 1`) for the valid case.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original (pristine, unpatched) bytes
 * at ALL FIVE target regions match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all five regions are applied together or not at all. This
 * destructor is a template instantiation specific to `im::TextureImage` (its own distinct function
 * in the binary, per its mangled name), so this change cannot affect any other `shared_ptr<T>` type
 * used elsewhere in the game. For a real refcount (always a small positive integer, never anywhere
 * close to `0x10000`), behavior is provably identical to the original.
 */
object ElfMassEffectUploadTexturesRefcountGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0010C7B8L
    private const val TARGET_VADDR_CAVE_K1 = 0x00953C80L
    private const val TARGET_VADDR_CAVE_K2 = 0x00953E88L
    private const val TARGET_VADDR_CAVE_K3 = 0x00953FF0L
    private const val TARGET_VADDR_CAVE_K4 = 0x00954068L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x01, 0x20, 0x42, 0xE2.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x30, 0x1D, 0x21, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_K1 = byteArrayOf(
        0x01, 0x10, 0x42, 0xE2.toByte(),
        0x7F, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K2 = byteArrayOf(
        0x01, 0x08, 0x51, 0xE3.toByte(),
        0x57, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K3 = byteArrayOf(
        0xF8.toByte(), 0xE1.toByte(), 0xDE.toByte(), 0x2A,
        0x1B, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K4 = byteArrayOf(
        0x01, 0x20, 0xA0.toByte(), 0xE1.toByte(),
        0xD2.toByte(), 0xE1.toByte(), 0xDE.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL FIVE target regions match. Anything else (wrong file, any mismatch) is left completely
     * untouched - this is all-or-nothing, never a partial application.
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
            val offK1 = fileOffsetFor(TARGET_VADDR_CAVE_K1)
            val offK2 = fileOffsetFor(TARGET_VADDR_CAVE_K2)
            val offK3 = fileOffsetFor(TARGET_VADDR_CAVE_K3)
            val offK4 = fileOffsetFor(TARGET_VADDR_CAVE_K4)
            val caveOffs = listOf(offK1, offK2, offK3, offK4)
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
            for (i in PATCHED_BYTES_CAVE_K1.indices) bytes[offK1!! + i] = PATCHED_BYTES_CAVE_K1[i]
            for (i in PATCHED_BYTES_CAVE_K2.indices) bytes[offK2!! + i] = PATCHED_BYTES_CAVE_K2[i]
            for (i in PATCHED_BYTES_CAVE_K3.indices) bytes[offK3!! + i] = PATCHED_BYTES_CAVE_K3[i]
            for (i in PATCHED_BYTES_CAVE_K4.indices) bytes[offK4!! + i] = PATCHED_BYTES_CAVE_K4[i]
            return Result(
                true,
                "eastl::shared_ptr<im::TextureImage>::~shared_ptr() no longer writes a decremented " +
                    "refcount back through a control-block pointer whose stored refcount value isn't " +
                    "a plausible small integer - it now takes this function's own existing 'nothing " +
                    "more to do' path instead of crashing on a read-only mapped page."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
