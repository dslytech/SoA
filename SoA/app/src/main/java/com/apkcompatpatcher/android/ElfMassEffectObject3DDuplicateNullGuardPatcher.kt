package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's SEVENTH native SIGSEGV. Real-device
 * tested after fix #33 (the model-cache null-guard) shipped: that crash (`LoadNodeUncached`) is
 * gone, and the game gets further still into the same background loader thread before hitting a
 * new SIGSEGV, one call deeper in the same general "populate a cached model node" call chain:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 * Cause: null pointer dereference
 *     r0 00000000  r1 00000000  r4 00000000  r5 00000000
 * backtrace:
 *     #00 pc 003ffed4  libMassEffect.so (m3g::Object3D::Duplicate(m3g::Object3D::DuplicateContext*)+356)
 *     #01 pc 003aa8b8  libMassEffect.so (im::m3gext::ModelCache::CacheCreateNode(...)+64)
 *     #02 pc 003abcdc  libMassEffect.so (im::m3gext::ModelCache::CachePreloadNode(...)+964)
 *     #03 pc 001287e0  libMassEffect.so (LoadTaskCache::Run()+324)
 *     ... (the same background asset-loading thread as fix #33)
 * ```
 *
 * ## Root cause
 *
 * `CacheCreateNode()` reads a "source node to clone" pointer out of a small chain of pointers
 * (`ldr r3,[r4]` / `ldr r3,[r3]` / `ldr r0,[r3,#4]` - `r4` here is the `shared_ptr<CacheNodeEntry>
 * const&` argument) and, without ever checking whether that pointer came back NULL, unconditionally
 * calls `m3g::Object3D::Duplicate(r0, /*context=*/0)` on it (`bl 3ffd70`). `Duplicate()` itself,
 * when called with a null `context` (always the case at this call site), immediately does
 * `ldr r3, [r0]` to fetch `this`'s vtable before doing anything else - so when `r0` (the "source
 * node") is itself NULL, this is a straight null-pointer dereference at the very first instruction
 * of that path, matching the real-device register dump exactly (`r0 = r1 = r4 = r5 = 0`, fault addr
 * `0x0`). This is the same general shape of bug as fixes #32 and #33 (a "not found"/empty result
 * silently used without a null-check) recurring a third time, in a third function, one call frame
 * deeper into the same model/node-loading subsystem - consistent with this game's asset pipeline
 * on this device regularly failing to produce a usable object for some model/node names, with
 * essentially every layer of this engine's cache code trusting its result unconditionally.
 *
 * ## The fix: reuse the function's own existing "duplicate failed/unavailable" fallback
 *
 * A few instructions later, `CacheCreateNode()` already has a well-defined path for "the duplicate
 * came back NULL" - it checks the call's return value (`subs r4, r0, #0`) and, if NULL, skips a
 * virtual call it would otherwise make on the duplicated node and falls straight through into
 * building a fresh cache entry anyway (`0x3aa290` onward: `operator new`, etc. - the same "create an
 * entry even without a source" continuation exercised whenever duplication doesn't produce anything
 * usable). This patch makes that same fallback also reached whenever the *source* pointer was NULL
 * to begin with, by never calling `Duplicate()` at all in that case (which is what avoids the crash,
 * since calling it with a NULL "this" is exactly what previously crashed) while still ending up in
 * exactly the same "r4 == 0" state the original code already handles:
 * ```
 * ldr   r0, [r3, #4]        ; UNCHANGED - r0 := the "source node to duplicate" pointer (candidate NULL)
 * cmp   r0, #0               ; was: bl Duplicate            - test the source pointer first
 * blne  Duplicate             ; was: subs r4, r0, #0         - only call Duplicate() if the source isn't NULL
 * movs  r4, r0                ; was: beq <no-duplicate path>  - r4 := r0 (call result, or still 0 if skipped);
 *                                also sets flags fresh from r0 (a `bl`/`blne` does not preserve flags
 *                                across the call, so this re-tests the value directly afterwards,
 *                                exactly like the original `subs` did with the real return value)
 * ldrne r3, [r4]               ; was: ldr r3, [r4]     ; only reached (via the NE condition, not a
 * movne  lr, pc                ; was: mov lr, pc       ; branch) when r4 != 0 - conditionally skipped
 * ldrne  pc, [r3, #12]         ; was: ldr pc, [r3, #12]; instructions fall through cleanly to the
 *                                unchanged `mov r0, #8` right after, exactly where the original
 *                                `beq` would have landed when the duplicate came back NULL
 * ```
 * On a non-NULL source whose `Duplicate()` call succeeds (the normal case for every model/node that
 * actually has something to clone) this is instruction-for-instruction equivalent to the original:
 * the same virtual call is made on the same duplicated pointer, reached this time via three NE-
 * conditional instructions instead of an unconditional block plus a separate `beq`, which is what
 * makes room for the new pre-call NULL check without growing the function (this project never grows
 * a function in place; doing so would shift every subsequent address in this 17MB binary). Assembled
 * and linked at the real address with `arm-linux-gnueabi-as`/`ld -Ttext=0x3aa274` (the unmodified
 * `ldr r0,[r3,#4]` before this block and the unmodified `mov r0,#8` right after were reproduced from
 * the reference binary as linker context only, not part of the patched bytes, to let the real
 * toolchain confirm both the call's branch offset and every following address are completely
 * undisturbed), then verified via the usual kotlinc harness + `objdump` + whole-file byte-diff.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call site
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 24 bytes at this address
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched. For a source node that duplicates successfully (the normal case), behavior is provably
 * identical to the original, instruction for instruction.
 */
object ElfMassEffectObject3DDuplicateNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The 6-instruction (24-byte) block inside im::m3gext::ModelCache::CacheCreateNode(...) that
    // unconditionally calls m3g::Object3D::Duplicate() on a possibly-NULL source pointer, then
    // conditionally makes a virtual call on the (possibly-NULL) result.
    private const val TARGET_VADDR = 0x003aa278L

    // Real original 24 bytes, confirmed via arm-linux-gnueabi-objdump and a raw file-offset dump
    // against the real extracted 1.0.58 libMassEffect.so.
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0xBC.toByte(), 0x56, 0x01, 0xEB.toByte(),
        0x00, 0x40, 0x50, 0xE2.toByte(),
        0x02, 0x00, 0x00, 0x0A,
        0x00, 0x30, 0x94.toByte(), 0xE5.toByte(),
        0x0F, 0xE0.toByte(), 0xA0.toByte(), 0xE1.toByte(),
        0x0C, 0xF0.toByte(), 0x93.toByte(), 0xE5.toByte()
    )

    // Assembled + linked at the real address + disassembled to confirm byte-for-byte (see doc
    // comment above for the annotated instruction-by-instruction meaning of each changed word).
    private val PATCHED_BYTES = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0xBB.toByte(), 0x56, 0x01, 0x1B,
        0x00, 0x40, 0xB0.toByte(), 0xE1.toByte(),
        0x00, 0x30, 0x94.toByte(), 0x15,
        0x0F, 0xE0.toByte(), 0xA0.toByte(), 0x11,
        0x0C, 0xF0.toByte(), 0x93.toByte(), 0x15
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
                "im::m3gext::ModelCache::CacheCreateNode() no longer calls m3g::Object3D::Duplicate() " +
                    "with a NULL source pointer - it now falls into the same function's own existing " +
                    "'duplicate unavailable' fallback path instead of crashing, exactly as it already " +
                    "does when Duplicate() itself returns NULL."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
