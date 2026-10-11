package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's SIXTH native SIGSEGV. Real-device tested
 * after fix #32 (the level-index null-guard) shipped: that crash is gone, and the game gets much
 * further - starting to actually load a level, on a background loader thread this time (not the
 * main GL thread) - before hitting a new SIGSEGV:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 * Cause: null pointer dereference
 *     r3 00000000
 * backtrace:
 *     #00 pc 003aa8b8  libMassEffect.so (im::m3gext::ModelCache::LoadNodeUncached(...)+212)
 *     #01 pc 003abcdc  libMassEffect.so (im::m3gext::ModelCache::CachePreloadNode(...)+324)
 *     #02 pc 001287e0  libMassEffect.so (LoadTaskCache::Run()+324)
 *     #03 pc 0040bc5c  libMassEffect.so (im::RunFunction(void*)+12)
 *     #04 pc 0037a80c  libMassEffect.so (RunnableFunctionInternal(void*)+452)
 *     ... (a background asset-loading thread, __pthread_start)
 * ```
 *
 * ## Root cause
 *
 * `LoadNodeUncached()` calls `m3g::Loader::Load(name, flag)`, which fills a local
 * `eastl::vector<intrusive_ptr<m3g::Object3D>>` with whatever objects it managed to load from the
 * named model/node file. The very next thing the function does is unconditionally dereference that
 * vector's first element (`ldr r3, [sp, #36]` then `ldr r7, [r3]`) with no check for whether the
 * vector actually has any elements in it at all - if `Loader::Load()` couldn't load anything for
 * this name (an empty, default-constructed `eastl::vector` has a NULL `begin` pointer), this reads
 * address 0 and crashes, matching the real-device register dump exactly (`r3 = 0x00000000`, fault
 * addr `0x0`). Same general shape of bug as fix #32 (a "not found" result silently ending up empty
 * instead of being checked before use) - a different function, a different asset, but the same
 * pattern.
 *
 * ## The fix: reuse the function's own existing "no node produced" fallback
 *
 * A few instructions later, this same function already has a clean, well-defined path for "no node
 * was produced" (`0x3aa8e0`: sets the output node pointer to NULL and runs the same cleanup/unlock/
 * return sequence as every other exit from this function) - reached today only when the *first
 * loaded element itself* is a null pointer. This patch makes it additionally reached whenever the
 * vector is empty to begin with, using the same ARM condition-code approach as fix #31/#32:
 * ```
 * ldr   r3, [sp, #36]      ; UNCHANGED - r3 := the loaded-objects vector's begin pointer
 * movs  r7, r3             ; was: ldr r7, [r3]   - r7 := r3; also sets Z=1 iff r3 (begin) is NULL
 * ldrne r7, [r3]           ; was: cmp r7, #0     - only dereference the first element if r3 isn't NULL
 * beq   <no-node fallback> ; UNCHANGED bytes (same condition, same target) - now fires whenever the
 *                            vector was empty (r3 NULL, from movs), in addition to its original
 *                            "first element itself is a null pointer" case
 * ```
 * On a non-empty result (the normal case for every model/node that actually loads) this is
 * instruction-for-instruction equivalent to the original: `r7` ends up holding the exact same
 * dereferenced value, computed via `ldrne` instead of a plain `ldr`, one instruction earlier. The
 * trade-off made here: this patch's `beq` no longer separately re-checks whether that dereferenced
 * value is itself a null pointer (the original real `cmp r7, #0` this replaces did) - only whether
 * the vector was empty to begin with, since a same-size in-place replacement leaves no room for
 * both checks in this instruction budget (this project never grows a function in place; doing so
 * would shift every subsequent address in this 17MB binary). The scenario this narrows away - a
 * *non-empty* result vector whose first entry is itself a literal null pointer - is not what this
 * project's crash logs have ever shown (every real-device test hit the empty-vector case,
 * `r3 == 0`), and is a narrower, more hypothetical situation than the one actually observed;
 * documented here plainly rather than silently. Assembled and linked at the real address with
 * `arm-linux-gnueabi-as`/`ld -Ttext=0x3aa8b4` (the unmodified virtual-call sequence and the
 * `beq <success>` right after it were reproduced from the reference binary as linker context only,
 * not part of the patched bytes, to let the real toolchain confirm every following address and
 * branch target is completely undisturbed), then verified via the usual kotlinc harness + `objdump`
 * + whole-file byte-diff.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other model/node
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 16 bytes at this address
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched. For a model/node that loads successfully (the normal case), behavior is provably
 * identical to the original, instruction for instruction.
 */
object ElfMassEffectModelCacheNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The 4-instruction (16-byte) block inside im::m3gext::ModelCache::LoadNodeUncached(...) that
    // dereferences the loaded-objects vector's first element, up to (and including, unchanged) the
    // existing `beq <no-node fallback>`.
    private const val TARGET_VADDR = 0x003aa8b4L

    // Real original 16 bytes, confirmed via arm-linux-gnueabi-objdump and a raw file-offset dump
    // against the real extracted 1.0.58 libMassEffect.so.
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x24, 0x30, 0x9D.toByte(), 0xE5.toByte(), 0x00, 0x70, 0x93.toByte(), 0xE5.toByte(),
        0x00, 0x00, 0x57, 0xE3.toByte(), 0x06, 0x00, 0x00, 0x0A
    )

    // Assembled + linked at the real address + disassembled to confirm byte-for-byte (see doc
    // comment above for the annotated instruction-by-instruction meaning of each changed word).
    private val PATCHED_BYTES = byteArrayOf(
        0x24, 0x30, 0x9D.toByte(), 0xE5.toByte(), 0x03, 0x70, 0xB0.toByte(), 0xE1.toByte(),
        0x00, 0x70, 0x93.toByte(), 0x15, 0x06, 0x00, 0x00, 0x0A
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at the target address match. Anything else (wrong file, mismatched bytes) is left completely
     * untouched.
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

            val off = fileOffsetFor(TARGET_VADDR)
            if (off == null || off < 0 || off + EXPECTED_ORIGINAL.size > bytes.size) {
                return Result(false, "target address doesn't map into this file - not touching")
            }
            for (i in EXPECTED_ORIGINAL.indices) {
                if (bytes[off + i] != EXPECTED_ORIGINAL[i]) {
                    return Result(false, "original bytes at the target address don't match expected " +
                        "(different build/version?) - left completely untouched")
                }
            }
            for (i in PATCHED_BYTES.indices) bytes[off + i] = PATCHED_BYTES[i]
            return Result(
                true,
                "im::m3gext::ModelCache::LoadNodeUncached() no longer dereferences a NULL pointer " +
                    "when a model/node file couldn't be loaded (an empty result) - it now falls into " +
                    "the same function's own existing 'no node produced' fallback path instead of " +
                    "crashing, exactly as it already does when a loaded element is itself a null " +
                    "pointer."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
