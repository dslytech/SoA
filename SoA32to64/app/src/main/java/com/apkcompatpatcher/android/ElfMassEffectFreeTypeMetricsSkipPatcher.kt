package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's THIRD native SIGSEGV in the FreeType
 * font-driver chain. Real-device tested twice now with the exact same crash both times ("Round
 * 26" and "Round 27", the latter with [ElfMassEffectFreeTypeSizeAllocPatcher] - fix #28 - active):
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr = face->size + 0x14
 * #00 FT_Request_Metrics+68
 * #01 FT_Request_Size+124
 * #02 FT_Set_Char_Size+104
 * #03 im::FreeTypeFont::FreeTypeFont(char const*, float)+260
 * ... (CreateDefaultFont <- PlainSkin <- UILayer <- DebugMenuLayer <- GetDebugMenuLayer <-
 * M3GApplication::OnCreate, same chain both times)
 * ```
 *
 * ## What fix #28 ruled out
 *
 * The crash is `str r2, [r4, #20]` where `r4 = face->size` (loaded from `face+88`), inside
 * `FT_Request_Metrics()`'s "font is not scalable" fallback branch, which unconditionally zeroes
 * 7 metrics fields at offsets 12-40 into `face->size`. Fix #28's working theory was that
 * `face->size` was allocated too small by `FT_New_Size()`, so it forced that allocation to always
 * be a fixed 64 bytes. Real-device tested: **the crash was byte-for-byte identical** with that
 * fix active - same offset, same fault pattern - proving allocation SIZE isn't the problem.
 * Confirming this from another angle: dumping the real, static `tt_driver_class` structure
 * straight out of this binary's `.data.rel.ro` section (the actual, always-valid, compile-time-
 * constant TrueType driver table - not something that can become corrupted, since it's never
 * written after the linker's relocations run) shows its true `size_object_size` is 304 bytes, not
 * some tiny/garbage value - so even the *original, unpatched* allocation should already have been
 * comfortably large enough. Whatever's wrong, it isn't the allocation size.
 *
 * The same `tt_driver_class` dump also shows a fully valid, non-NULL `request_size` function
 * pointer (`tt_size_request`, the TrueType-specific hook fix #27 currently bypasses) and sane
 * pointers throughout every other field - the driver's own static data is completely intact. The
 * defect is purely in some *runtime* piece of state (most plausibly the live `driver->clazz` field
 * read at several points in this call chain, and/or `face->size` itself) not reliably holding the
 * value it should by the time this code runs - something this project's tooling (static
 * disassembly plus post-mortem crash dumps) cannot fully explain without a live debugger attached
 * on-device.
 *
 * ## The fix: stop trying to write into a proven-unreliable buffer
 *
 * Given two independent real-device tests now agree that writing into `face->size` here reliably
 * faults regardless of how it was allocated, the safest move is to stop trying: skip the entire
 * "zero out 7 metrics fields" branch by jumping straight to the function's own epilogue. This is
 * provably safe to skip - `FT_Request_Metrics()` is declared to return nothing meaningful here (its
 * only caller, inside `FT_Request_Size()`, unconditionally overwrites r0 right after the call with
 * an unrelated value, so whatever this function returns is discarded), and every write this branch
 * performs targets the exact struct this project's testing has shown is not reliably writable in
 * this scenario - so removing them removes the only way this specific code path can fault, without
 * needing to know why `face->size` is unreliable in the first place.
 * ```
 * str r2, [r4, #20]      ; <- replaced with: b <epilogue>   (skips this and all 6 writes after it)
 * str r3, [r4, #36]
 * str r3, [r4, #12]
 * str r3, [r4, #24]
 * str r3, [r4, #28]
 * str r3, [r4, #32]
 * str r2, [r4, #16]
 * pop {r4,r5,r6,r7,r8,r9,sl,pc}   ; <- branch target
 * ```
 * Assembled + disassembled via `arm-linux-gnueabi-as`/`objdump` to confirm the branch encoding
 * byte-for-byte, same discipline as every other native patch here.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and other fonts in this game
 *
 * Same two-layer gate as every other native patch in this project: only looks at a zip entry
 * named `libMassEffect.so`, and even then only patches if the exact original 4 bytes at this
 * address match exactly what this was built against - any mismatch leaves the file untouched.
 * This specific branch is only reached for a face FreeType currently considers to have neither
 * the SCALABLE nor FIXED_SIZES flag set - an unusual, effectively-broken state that a normally-
 * loading font shouldn't be in; a font that loads correctly (scalable bit set, as any real TTF/OTF
 * should be) takes a completely different code path elsewhere in this same function, untouched by
 * this patch.
 *
 * ## Status
 *
 * `ElfMassEffectFreeTypeSizeAllocPatcher` (fix #28) is unwired from the build after this - its
 * hypothesis was tested and disproven, and forcing a wrong, arbitrary allocation size for every
 * font in the binary is unnecessary risk for zero benefit. Not yet real-device tested.
 */
object ElfMassEffectFreeTypeMetricsSkipPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The `str r2, [r4, #20]` inside FreeType's FT_Request_Metrics() - the first of 7 writes into
    // face->size that this project's real-device testing has shown reliably faults.
    private const val TARGET_VADDR = 0x005f7cd0L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: str r2, [r4, #20]
    private val EXPECTED_ORIGINAL = byteArrayOf(0x14, 0x20, 0x84.toByte(), 0xE5.toByte())

    // Assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte:
    // b 0x5f7cec (the function's `pop {r4,r5,r6,r7,r8,r9,sl,pc}` epilogue, 7 instructions ahead -
    // skips all 7 metrics-zeroing writes, landing exactly on the existing, unmodified return).
    private val PATCHED_BYTES = byteArrayOf(0x05, 0x00, 0x00, 0xEA.toByte())

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
                "FreeType's FT_Request_Metrics() no longer writes its 'not scalable' fallback " +
                    "metrics (7 fields) into face->size - two independent real-device tests showed " +
                    "that write reliably faults for this game's debug-menu font regardless of how " +
                    "face->size was allocated, so this branch now skips straight to the function's " +
                    "own return instead, avoiding the SIGSEGV while creating the game's debug menu " +
                    "UI font."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
