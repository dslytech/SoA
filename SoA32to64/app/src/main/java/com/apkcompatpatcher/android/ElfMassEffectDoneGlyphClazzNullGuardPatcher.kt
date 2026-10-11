package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards FreeType's own
 * `FT_Done_Glyph(FT_Glyph glyph)` against a glyph whose `clazz` (class/vtable) field is NULL,
 * even though `glyph` itself is a real, non-NULL pointer.
 *
 * ## Background
 *
 * Found from a real-device crash log (Hihonor tablet, 2026-09-14) - the first crash this
 * project has seen from ACTUAL LOADING-SCREEN TEXT RENDERING, reached only after fixes #16-21
 * (EA download-SDK skip) and fix #30 (Google Play Billing implicit-Intent guard,
 * `com/ea/easp/mtx/market/BillingService.bindToMarketBillingService()`) finally let
 * the game get past every earlier blocker. This is fix #80.
 *
 *   Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000c, tid (GLThread)
 *   #00 FT_Done_Glyph+16
 *   #01 im::FreeTypeFont::MeasureCharBoundingBox(wchar_t) const+188
 *   #02 im::GlyphBuffer::PrepareGlyph(...)+120
 *   #03 im::GlyphBuffer::GetGlyphs(...)+164
 *   #04 im::SpriteGraphics::GetGlyphs(...)+68
 *   #05 im::scene2d_new::Text::OnDraw(im::SpriteGraphics&)+332
 *   #06 im::scene2d_new::Group::OnDraw(im::SpriteGraphics&)+84
 *   #07 Splash::OnDraw / #08 SplashScreens::OnDraw / #09 LayerLoading::OnDraw ...
 *
 * Objdump-disassembling `FT_Done_Glyph` (0x005F9B3C) shows this is upstream FreeType's own,
 * otherwise-correct implementation - it DOES check `glyph` itself for NULL first (`subs
 * r4,r0,#0` / `beq <null-glyph return>`), exactly like real FreeType source. The crash is one
 * step deeper: right after that check, it does `ldm r4,{r2,r3}` (r2 = glyph->library, r3 =
 * glyph->clazz) and then, at the exact faulting instruction (0x005F9B4C), `ldr r3,[r3,#12]` -
 * reading `glyph->clazz->done` - WITHOUT ever checking `glyph->clazz` itself for NULL. When
 * `clazz` is NULL, this reads offset 12 from address 0, which is exactly the observed fault
 * address (0x0000000c) - not a coincidence, a precise match. A few instructions later the
 * function already has a `cmp r3,#0 / beq <skip the done() call>` guard for exactly this "the
 * class doesn't define a done() callback" case - it was simply never reached, because the read
 * that would produce r3 already faults first when the class pointer itself is absent.
 *
 * This matches this whole project's now-familiar "im::FreeTypeFont font-driver saga" pattern
 * (fixes #27-#31): this game's FreeType glyph-cache hookup produces a glyph object without
 * fully initializing it in some path im::GlyphBuffer::PrepareGlyph() takes (most plausibly an
 * `FT_Get_Glyph()` failure/edge case upstream in the same call chain, not itself yet root-caused
 * further - not needed, since this guard, like fixes #27-#31, makes the resulting state safe to
 * handle regardless of how a null-`clazz` glyph came to exist). FreeType is shared, unmodified
 * upstream code, not something this project can - or needs to - fix "properly"; guarding the one
 * unguarded read is the same discipline already used throughout the FreeType saga.
 *
 * ## The fix
 *
 * Minimal, single-point guard exactly at the point of dereference: the callsite instruction
 * (`ldr r3,[r3,#12]`) is replaced with a branch to a 3-instruction cave that re-checks `r3`
 * (`glyph->clazz`) for NULL before performing that exact same read; on failure `r3` is simply
 * left at 0 and control returns to the function's own UNTOUCHED next instruction. That next
 * instruction (`ldr r5,[r2]`, reading `glyph->library->memory` - unaffected either way since
 * `glyph->library` was already safely read into r2 by the preceding `ldm`) flows straight into
 * the function's OWN pre-existing `cmp r3,#0 / beq` guard, which now correctly takes the
 * "class has no done() callback" path it was always meant to handle - skipping the `clazz->done()`
 * virtual dispatch and falling through to `ft_mem_free()`, freeing the glyph exactly as it would
 * for any other well-formed glyph. No lr arithmetic, no stack changes - this is not even a
 * function-entry guard, just the one unsafe instruction relocated behind a check, identical in
 * spirit to fix #76's "guard the vtable-read instruction, branch back into the function's own
 * untouched next instruction" approach.
 *
 * Objdump-confirmed (every branch resolves to exactly the intended target) and harness-verified:
 * a NULL `clazz` (r3=0 on entry to the cave) returns to the function's own null-callback path
 * with the read never attempted, and a real `clazz` pointer falls through to the exact original,
 * untouched `ldr r3,[r3,#12]` read and dispatch behavior.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, anything else)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally
 * named `libMassEffect.so`, and even then only if the exact original bytes at every one of this
 * fix's regions match byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectDoneGlyphClazzNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x005F9B4CL
    private const val TARGET_VADDR_CAVE = 0x0098C8CCL

    // ldr r3, [r3, #12]  (glyph->clazz->done, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x0C, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // b 0x98c8cc
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x5E, 0x4B, 0x0E, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(12)

    // cmp r3,#0 / ldrne r3,[r3,#12] / b 0x5f9b50 (resume - original "ldr r5,[r2]")
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0x0C, 0x30, 0x93.toByte(), 0x15,
        0x9D.toByte(), 0xB4.toByte(), 0xF1.toByte(), 0xEA.toByte()
    )

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

            val callsiteOff = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val caveOff = fileOffsetFor(TARGET_VADDR_CAVE)

            if (callsiteOff == null || caveOff == null ||
                callsiteOff + 4 > bytes.size || caveOff + 12 > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(callsiteOff, EXPECTED_ORIGINAL_CALLSITE) || !matches(caveOff, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version?) - left completely untouched")
            }

            fun write(off: Int, patch: ByteArray) { for (i in patch.indices) bytes[off + i] = patch[i] }
            write(callsiteOff, PATCHED_BYTES_CALLSITE)
            write(caveOff, PATCHED_BYTES_CAVE)

            return Result(
                true,
                "FT_Done_Glyph() no longer dereferences a NULL glyph->clazz pointer (fault addr 0xc, " +
                    "confirmed via a real-device crash while rendering loading-screen text) - it now " +
                    "safely falls through to the function's own existing 'class has no done() callback' " +
                    "path instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
