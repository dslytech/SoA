package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards FreeType's own
 * `FT_Done_Glyph(FT_Glyph glyph)` against a glyph whose `library` field is NULL, even though
 * `glyph` itself is a real, non-NULL pointer. This is fix #91, and it's the direct sequel to
 * fix #80 (`ElfMassEffectDoneGlyphClazzNullGuardPatcher`) - same function, one instruction later,
 * a second and independent NULL field on the very same glyph object.
 *
 * ## Background
 *
 * Found from a real-device crash log (Hihonor tablet, Android 16, 2026-09-15), on a build already
 * carrying fix #80's `clazz`-null guard:
 *
 *   Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000, tid (GLThread)
 *   #00 FT_Done_Glyph+20
 *   #01 im::FreeTypeFont::MeasureCharBoundingBox(wchar_t) const+188
 *   #02 im::GlyphBuffer::PrepareGlyph(...)+120
 *   #03 im::GlyphBuffer::GetGlyphs(...)+164
 *   ... (identical remainder of the stack to fix #80's crash - same call chain, same
 *   loading-screen text rendering path)
 *
 * Objdump-disassembling `FT_Done_Glyph` (0x005F9B3C) shows fix #80's own doc comment already
 * anticipated exactly this instruction but assumed it was safe - it wasn't:
 *
 *   5f9b3c: push {r4, r5, r6, lr}
 *   5f9b40: subs r4, r0, #0            ; r4 = glyph; NULL-glyph already checked/guarded
 *   5f9b44: beq  5f9b70                ; -> safe "nothing to free" return
 *   5f9b48: ldm  r4, {r2, r3}          ; r2 = glyph->library, r3 = glyph->clazz
 *   5f9b4c: ldr  r3, [r3, #12]         ; glyph->clazz->done - guarded by fix #80's cave
 *   5f9b50: ldr  r5, [r2]              ; glyph->library->memory - THIS instruction, unguarded
 *   5f9b54: cmp  r3, #0
 *   5f9b58: beq  5f9b60
 *   5f9b5c: blx  r3                    ; clazz->done(glyph)
 *   5f9b60: mov  r0, r5
 *   5f9b64: mov  r1, r4
 *   5f9b68: pop  {r4, r5, r6, lr}
 *   5f9b6c: b    ft_mem_free            ; tail call: ft_mem_free(memory=r5, block=r4)
 *   5f9b70: pop  {r4, r5, r6, pc}       ; "nothing to free" early return (glyph == NULL case)
 *
 * Fix #80's own doc comment describes the instruction at 0x005F9B50 (`ldr r5,[r2]`) as "unaffected
 * either way since `glyph->library` was already safely read into r2 by the preceding `ldm`" - that
 * reasoning covered r2 itself being *readable* (it's a register, always "safe" to read), but missed
 * that the VALUE now sitting in r2 - `glyph->library` - can itself be NULL, exactly like `clazz`
 * one field over. When it is, `ldr r5,[r2]` reads offset 0 from address 0, which is exactly the
 * observed fault address (0x00000000) - not a coincidence, a precise match, the same "precise fault
 * address as struct-field-offset" signature fix #80 already relied on for `clazz`.
 *
 * This is the same "incompletely initialized glyph object" root cause fix #80 already documented
 * (this game's FreeType glyph-cache hookup, in some `im::GlyphBuffer::PrepareGlyph()` edge case,
 * produces a glyph whose `library`/`clazz` fields aren't both populated) - not re-investigated
 * further here for the same reason fix #80 didn't chase it further: this guard, like fix #80's,
 * makes the resulting state safe to handle regardless of how it came to exist.
 *
 * ## Why not just let it fall through with a garbage r5?
 *
 * If `library` is NULL, there is no way to know which memory manager owns this glyph, so the
 * function cannot safely call `ft_mem_free()` at all (calling it with a garbage/uninitialized r5
 * as the `FT_Memory` argument would itself crash, or worse, corrupt unrelated memory, inside
 * `ft_mem_free` instead of here). The correct behavior is to skip freeing entirely for this glyph
 * and just return - which the function already does, verbatim, for the "glyph itself is NULL"
 * case at 0x005F9B70 (`pop {r4,r5,r6,pc}`, no free). This fix reuses that exact existing exit.
 *
 * ## The fix
 *
 * The callsite instruction (`ldr r5,[r2]` at 0x005F9B50) is replaced with a branch into a 4-word
 * cave living in unused `.rodata` padding (immediately after the null-terminated UPnP string
 * `"root.URLBase"` inside DirtySDK's already-neutered network module - see the online/network stub
 * fix's doc comment; this string table is never walked once that module's socket/SSL entry points
 * are stubbed out, and the cave's exact bytes were independently confirmed to be zero, unreferenced
 * by any relocation, and byte-identical across two independently-extracted copies of this same
 * build's `libMassEffect.so` before being claimed here):
 *
 *   cmp   r2, #0
 *   ldrne r5, [r2]      ; only performed when r2 (glyph->library) is actually non-NULL
 *   bne   0x005F9B54     ; non-NULL: resume the function's own untouched original flow
 *   b     0x005F9B70     ; NULL: skip straight to the existing "nothing to free" return
 *
 * No lr arithmetic, no stack changes - identical in spirit to fix #80's "guard the one unsafe
 * read, branch back into the function's own untouched next instruction (or its own existing safe
 * exit)" approach, just with two exits instead of one since this field's absence can't be silently
 * papered over the way a null `done` callback can.
 *
 * Objdump/capstone-confirmed (every branch resolves to exactly the intended target) before being
 * wired in here: a NULL `library` (r2=0 on entry to the cave) returns via the function's own
 * "nothing to free" path with neither the read nor the free ever attempted, and a real `library`
 * pointer falls through to the exact original, untouched `ldr r5,[r2]` read and the rest of the
 * function's own logic (including fix #80's own `clazz` guard, entirely unaffected by this fix).
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, anything else)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at every one of this fix's
 * regions match byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectDoneGlyphLibraryNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x005F9B50L
    private const val TARGET_VADDR_CAVE = 0x009FADE0L

    // ldr r5, [r2]  (glyph->library->memory, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x50, 0x92.toByte(), 0xE5.toByte()
    )
    // b 0x9fade0
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xA2.toByte(), 0x04, 0x10, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r2,#0 / ldrne r5,[r2] / bne 0x5f9b54 (resume) / b 0x5f9b70 (skip free, glyph-null exit)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x52, 0xE3.toByte(),
        0x00, 0x50, 0x92.toByte(), 0x15,
        0x59, 0xFB.toByte(), 0xEF.toByte(), 0x1A,
        0x5F, 0xFB.toByte(), 0xEF.toByte(), 0xEA.toByte()
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
                callsiteOff + 4 > bytes.size || caveOff + 16 > bytes.size
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
                "FT_Done_Glyph() no longer dereferences a NULL glyph->library pointer (fault addr 0x0, " +
                    "confirmed via a real-device crash one instruction past fix #80's clazz guard, while " +
                    "rendering loading-screen text) - it now safely falls through to the function's own " +
                    "existing 'nothing to free' return path instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
