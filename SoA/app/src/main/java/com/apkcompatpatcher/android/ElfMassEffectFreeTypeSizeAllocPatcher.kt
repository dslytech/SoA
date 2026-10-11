package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's THIRD native SIGSEGV in the FreeType
 * font-driver chain - the one that showed up only after [ElfMassEffectFreeTypeRequestSizePatcher]
 * (fix #27) successfully routed execution into FreeType's own generic fallback sizing path
 * ("Round 26"). Real-device tested crash log:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00dd8814
 * #00 FT_Request_Metrics+68
 * #01 FT_Request_Size+124
 * #02 FT_Set_Char_Size+104
 * #03 im::FreeTypeFont::FreeTypeFont(char const*, float)+260
 * ... (same #04-#08 chain as "Round 25": CreateDefaultFont <- PlainSkin <- UILayer <-
 * DebugMenuLayer <- DebugMenu::GetDebugMenuLayer <- M3GApplication::OnCreate)
 * ```
 * `FT_Request_Size+124` is exactly the `bl FT_Request_Metrics` call that fix #27's `mov r5, #0`
 * routes into - proof fix #27 works as designed: the crash genuinely moved to a new, deeper
 * location, not a coincidence. Inside `FT_Request_Metrics`, the crash is `str r2, [r4, #20]`
 * where `r4 = face->size` (the same `face+88` field `FT_Request_Size` reads to pass to a driver
 * hook) - i.e. the `FT_Size` object itself, not just the driver's optional hook, turns out to be
 * bad. Register dump: `r4 = 0x00dd8800` (a plausible-looking, non-null address), but writing 20
 * bytes past it (`0x00dd8814`) faults with `SEGV_MAPERR` (no mapping at all - not a permission or
 * read-only-page issue). So `face->size` does not point at a real, fully-usable allocation big
 * enough for `FT_Request_Metrics` to write its 8 metrics fields (spanning offsets 12-40) into.
 *
 * ## Root cause hypothesis: an undersized `FT_Size` allocation
 *
 * `face->size` is allocated inside FreeType's own `FT_New_Size()` (disassembled from the pristine
 * reference binary, real function, not custom engine code):
 * ```
 * ldr r3, [r4, #96]     ; r3 = face->driver
 * ldr r5, [r4, #100]    ; r5 = face->memory
 * ldr r7, [r3, #20]     ; r7 = driver->clazz
 * ldr sl, [r7, #40]     ; sl = clazz->size_object_size (how many bytes to allocate)  <- TARGET
 * cmp sl, #0
 * ble <error/edge-case path>
 * mov r0, r5
 * mov r1, sl            ; r1 = alloc size, passed straight through to the allocator
 * ...
 * blx <memory->alloc>   ; face->size ends up being exactly `sl` bytes
 * ```
 * `clazz->size_object_size` is meant to be a small positive constant a font driver reports once,
 * at build/registration time (FreeType's own base `FT_SizeRec` needs ~40 bytes; a driver can ask
 * for more if it needs extra per-size state). If this driver's registration is even partially
 * broken (consistent with fix #27's finding that the very same `driver->clazz` reads NULL a few
 * instructions later, in `FT_Request_Size`), a wrong/too-small `size_object_size` would produce
 * an undersized `face->size` allocation - exactly matching the observed crash (a write to offset
 * 20 faulting on an otherwise plausible pointer, not a NULL one).
 *
 * Notably, `FT_New_Size()` and `FT_New_GlyphSlot()` (the sibling function that sets up a face's
 * glyph slot) both dereference `driver->clazz` with **no null check at all** - if `clazz` were
 * already NULL at the point either of them runs, the crash would happen immediately inside
 * `FT_New_Face()`, long before `FT_Set_Char_Size()` is ever reached. Since both real-device crash
 * logs so far ("Round 25" and "Round 26") clearly show `FT_New_Face()` already returned
 * successfully - `FT_Set_Char_Size()` runs only ~8 pure floating-point instructions later in the
 * same constructor, no function calls or syscalls in between - `driver->clazz` was demonstrably
 * valid when `FT_New_Size()` ran for this exact face. Whatever is wrong with this driver's
 * registration, it's something more specific than "the whole clazz pointer is missing" by the
 * time size allocation happens; a wrong `size_object_size` value is consistent with that.
 *
 * ## The fix
 *
 * Replace the `size_object_size` READ (`ldr sl, [r7, #40]`) with a hardcoded, generous constant
 * (`mov sl, #64`, i.e. 64 bytes - comfortably more than the ~40 bytes `FT_Request_Metrics`'s
 * "not scalable" fallback path writes into, offsets 12 through 40). This has two effects: it
 * guarantees `face->size` is always allocated with enough room regardless of what this specific
 * driver's (possibly broken) registration reports, AND - like fix #27 - it removes any dependency
 * on reading through `driver->clazz` at this exact spot, so it's harmless even if `clazz` turns
 * out to be NULL here on some other device/build. A larger-than-strictly-needed allocation is a
 * standard, low-risk defensive pattern: the extra bytes are simply unused padding for any driver
 * that already reports a correct (smaller) size, and FreeType's own `memset`-based zero-fill for
 * the object (already existing code, immediately after the allocation, untouched by this patch)
 * covers the full allocated block either way.
 *
 * This does not touch `driver->clazz` itself, the driver's `init_size` hook call (a few
 * instructions later in this same function, which real evidence suggests already runs
 * successfully and may do something legitimate for a working driver), or `FT_New_GlyphSlot()` -
 * only the one allocation-size read this crash chain has actual evidence for.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else)
 *
 * Same two-layer gate as every other native patch in this project: only looks at a zip entry
 * named `libMassEffect.so`, and even then only patches if the exact original 4 bytes at this
 * address match exactly what this was built against - any mismatch leaves the file untouched.
 *
 * ## Status
 *
 * Best-supported working hypothesis given the evidence gathered so far, not a certainty - this
 * function allocates `face->size` for every font this binary loads, not just the one that
 * crashed, so if this hypothesis is wrong (e.g. the real problem is `face->size` being
 * overwritten/corrupted after a correctly-sized allocation, rather than being allocated too small
 * in the first place), this patch alone may not fully resolve "Round 26"'s crash - but it is a
 * safe, low-risk, easily-verified next experiment either way. Not yet real-device tested.
 */
object ElfMassEffectFreeTypeSizeAllocPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The `ldr sl, [r7, #40]` inside FreeType's FT_New_Size() that reads a font driver's
    // reported FT_Size allocation size (clazz->size_object_size) from its class/function table.
    private const val TARGET_VADDR = 0x005f844cL

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr sl, [r7, #40]
    private val EXPECTED_ORIGINAL = byteArrayOf(0x28, 0xA0.toByte(), 0x97.toByte(), 0xE5.toByte())

    // Assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte: mov sl, #64
    private val PATCHED_BYTES = byteArrayOf(0x40, 0xA0.toByte(), 0xA0.toByte(), 0xE3.toByte())

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
                "FreeType's FT_New_Size() no longer trusts a font driver's (possibly broken) " +
                    "reported allocation size for a face's FT_Size object - it now always " +
                    "allocates a generous, fixed-size buffer (64 bytes, comfortably more than the " +
                    "~40 bytes actually needed) instead, guarding against an undersized " +
                    "allocation that was crashing FT_Request_Metrics() while creating the game's " +
                    "debug menu UI font."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
