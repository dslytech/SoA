package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's FOURTH native SIGSEGV in the FreeType
 * font-driver chain - and the likely TRUE root cause behind fixes #27 and #29 as well. Real-device
 * tested after fix #29 (metrics-skip) and fix #30 (billing) both shipped together: for the first
 * time ever, NO crash occurred inside `FT_Request_Metrics()` - proving fix #29 fully works - but a
 * NEW crash appeared one call frame further UP the same chain:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xc0000014
 *     r0  e340aab5  r3  c0000000  r4  e340aab5  ...
 * backtrace:
 *     #00 pc 005f81e4  libMassEffect.so (FT_Request_Size+68)
 *     #01 pc 005f82b4  libMassEffect.so (FT_Set_Char_Size+104)
 *     #02 pc 00410d1c  libMassEffect.so (im::FreeTypeFont::FreeTypeFont(char const*, float)+260)
 *     ... (CreateDefaultFont <- PlainSkin <- UILayer <- DebugMenuLayer <- GetDebugMenuLayer <-
 *     M3GApplication::OnCreate, same chain as every round before)
 * ```
 *
 * ## What this crash reveals that the earlier three didn't
 *
 * `FT_Request_Size+68` is `ldr r3, [r3, #20]` - reading `driver->clazz`, ONE instruction *before*
 * fix #27's already-patched site (`+72`, `driver->clazz->request_size`). This time `r3` (`face->
 * driver`) itself is the garbage value (`0xc0000000`, a suspiciously round "sentinel-looking"
 * number), and - the real giveaway - the `face` pointer itself (`r0`/`r4` = `0xe340aab5`) is
 * **odd**: not 4-byte aligned, which no real heap-allocated C++ object pointer ever is. Every prior
 * round's garbage was consistent with a single explanation that was never actually confirmed until
 * now: this isn't `face->driver` or `driver->clazz` individually going bad - `face` itself is not a
 * valid pointer at all.
 *
 * Disassembling `im::FreeTypeFont::FreeTypeFont(char const*, float)` end to end
 * (`0x410c18`-`0x410d44`) shows exactly how that happens:
 * ```
 * add   r3, r4, #72        ; &this->face  (the FT_Face* output slot, aface)
 * bl    FT_New_Face        ; r0 = FT_Error (0 = success); writes *aface only via internal paths
 * vldr  s15, [pc, #52]     ; <-- r0 (FT_New_Face's return code) is NEVER inspected here
 * vmul.f32 s15, s16, s15
 * vcvt.s32.f32 s15, s15
 * mov   ip, #72
 * ldr   r0, [r4, #72]      ; r0 = this->face, reloaded and used completely unconditionally
 * ...
 * bl    FT_Set_Char_Size   ; called on whatever this->face is, success or not
 * ```
 * The game's own constructor never checks whether `FT_New_Face()` actually succeeded before using
 * the face it (may not have) produced. `this->face` lives inside a `FreeTypeFont` object that was
 * `operator new`-allocated by the caller just before this constructor runs (C++ `operator new`
 * does not zero-initialize), so if `FT_New_Face()` fails for this particular debug-menu font for
 * any reason, `this->face` is left holding uninitialized heap bytes - different leftover garbage on
 * every run, which is exactly why fixes #27/#29 each *appeared* to fully fix one specific-looking
 * symptom (a NULL `driver->clazz`, an unwritable `face->size`) only for a different-looking garbage
 * pattern to surface on the next test. This patch addresses the actual defect instead of another
 * downstream symptom of it.
 *
 * ## The fix: don't call FT_Set_Char_Size() when FT_New_Face() reported failure
 *
 * `FT_New_Face()`'s return value in `r0` is dead code in the original binary - computed, then
 * immediately discarded without being read. This patch makes the constructor check it: everything
 * from right after the `bl FT_New_Face` up to and including the `bl FT_Set_Char_Size` call is made
 * conditional on FreeType having reported success (`FT_Err_Ok` = 0), by turning a `cmp r0, #0` plus
 * ARM's per-instruction condition-code field into an inline "only if success" guard - no branch
 * needed, no code added or removed, same 11 instructions (44 bytes) as the original, byte-for-byte
 * position-for-position:
 * ```
 * cmp    r0, #0                    ; was: vldr  s15, [pc, #52]   (moved 1 slot later, now vldreq)
 * vldreq s15, [pc, #48]            ; was: vmul.f32 s15, s16, s15  (now vmuleq)
 * vmuleq.f32 s15, s16, s15         ; was: vcvt.s32.f32 s15, s15   (now vcvteq)
 * vcvteq.s32.f32 s15, s15          ; was: mov   ip, #72
 * ldreq  r0, [r4, #72]             ; was: ldr   r0, [r4, #72]     (now ldreq - only reload on success)
 * vstr   s16, [r4, #40]            ; unchanged - always records the requested point size on `this`
 * moveq  r1, r5                    ; was: mov   r1, r5            (now moveq; r5 = 0, char_width arg)
 * vmoveq r2, s15                   ; was: vmov  r2, s15           (now vmoveq)
 * moveq  r3, #72                   ; was: mov   r3, ip  (folded - ip's only use was holding #72 twice,
 *                                    so both the horizontal-resolution register and the
 *                                    vertical-resolution stack slot below now load #72 directly,
 *                                    freeing exactly the one instruction slot this fix needed)
 * streq  r3, [sp]                  ; was: str   ip, [sp]          (now streq, reuses r3 instead of ip)
 * bleq   FT_Set_Char_Size          ; was: bl    FT_Set_Char_Size  (now bleq - only call on success)
 * ```
 * On success (`r0 == 0`) every EQ-suffixed instruction executes exactly as before - identical
 * behavior, identical bytes computed, identical call made. On failure (`r0 != 0`) every one of them
 * is skipped: `this->face` is left as whatever `FT_New_Face` did or didn't write, `FT_Set_Char_Size`
 * is never called on it, and execution falls straight through to the constructor's own unmodified
 * epilogue (`mov r0, r4; ...; pop {r4,r5,r6,pc}`, still at the same address right after this block -
 * this patch doesn't move or resize anything after it). Assembled with `arm-linux-gnueabi-as`,
 * linked at this exact address with `arm-linux-gnueabi-ld -Ttext=0x410cf4` so every PC-relative
 * literal-pool and branch-target offset is computed for real by the toolchain (not hand-encoded),
 * then disassembled to confirm byte-for-byte before being embedded here - same discipline as every
 * other native patch in this project.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and other fonts in this game
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 44 bytes at this address
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched. The guarded call only ever runs differently when `FT_New_Face()` itself reports
 * failure - a font that loads successfully (the normal case for every other font this constructor
 * is used for) is completely unaffected; every EQ-conditioned instruction still runs, unchanged.
 */
object ElfMassEffectFreeTypeNewFaceGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // Start of the 11-instruction (44-byte) block right after `bl FT_New_Face` inside
    // im::FreeTypeFont::FreeTypeFont(char const*, float), up to and including `bl FT_Set_Char_Size`.
    private const val TARGET_VADDR = 0x00410cf4L

    // Real original 44 bytes, confirmed via arm-linux-gnueabi-objdump and a raw file-offset dump
    // against the real extracted 1.0.58 libMassEffect.so.
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x0D, 0x7A, 0xDF.toByte(), 0xED.toByte(), 0x27, 0x7A, 0x68, 0xEE.toByte(),
        0xE7.toByte(), 0x7A, 0xFD.toByte(), 0xEE.toByte(), 0x48, 0xC0.toByte(), 0xA0.toByte(), 0xE3.toByte(),
        0x48, 0x00, 0x94.toByte(), 0xE5.toByte(), 0x0A, 0x8A.toByte(), 0x84.toByte(), 0xED.toByte(),
        0x05, 0x10, 0xA0.toByte(), 0xE1.toByte(), 0x90.toByte(), 0x2A, 0x17, 0xEE.toByte(),
        0x0C, 0x30, 0xA0.toByte(), 0xE1.toByte(), 0x00, 0xC0.toByte(), 0x8D.toByte(), 0xE5.toByte(),
        0x4A, 0x9D.toByte(), 0x07, 0xEB.toByte()
    )

    // Assembled + linked at the real address + disassembled to confirm byte-for-byte (see doc
    // comment above for the annotated instruction-by-instruction meaning of each changed word).
    private val PATCHED_BYTES = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(), 0x0C, 0x7A, 0xDF.toByte(), 0x0D,
        0x27, 0x7A, 0x68, 0x0E, 0xE7.toByte(), 0x7A, 0xFD.toByte(), 0x0E,
        0x48, 0x00, 0x94.toByte(), 0x05, 0x0A, 0x8A.toByte(), 0x84.toByte(), 0xED.toByte(),
        0x05, 0x10, 0xA0.toByte(), 0x01, 0x90.toByte(), 0x2A, 0x17, 0x0E,
        0x48, 0x30, 0xA0.toByte(), 0x03, 0x00, 0x30, 0x8D.toByte(), 0x05,
        0x4A, 0x9D.toByte(), 0x07, 0x0B
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
                "im::FreeTypeFont's constructor no longer calls FT_Set_Char_Size() on this->face " +
                    "when FT_New_Face() itself already reported failure - it previously ignored that " +
                    "return value completely and always proceeded with whatever (possibly " +
                    "uninitialized) value was left in this->face, which this project's testing " +
                    "traces as the likely true root cause behind every font-related SIGSEGV variant " +
                    "seen while creating the game's debug menu UI font."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
