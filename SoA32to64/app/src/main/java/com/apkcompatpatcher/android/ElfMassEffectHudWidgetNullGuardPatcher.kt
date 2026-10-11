package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's EIGHTH native SIGSEGV - and the first
 * one in a long while on the MAIN GL thread rather than a background asset-loading thread. Real-
 * device tested after fix #34 (the Object3D::Duplicate null-guard) shipped: the whole background
 * asset-loading crash chain is gone, and the game gets all the way to initializing its HUD during
 * a level transition before hitting this:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000138
 * Cause: null pointer dereference
 *     r0 00000000  r1 00000001  r2 00000000  r3 00000000
 *     r4 <valid Hud*>  ...
 * #00 Hud::InitLayer(eastl::intrusive_ptr<m3g::Renderer> const&, LayerGameWorld*)+500
 * #01 boost::...::invoke(...)+36
 * #02 im::M3GApplication::OnRunLoopTick()+724
 * #03 im::BufferedPlatformDelegate::OnRunLoopTick()+20
 * #04 EA::GameSkeleton::GameApplication::OnUpdate(void*)+356
 * ```
 *
 * ## Root cause
 *
 * `Hud::InitLayer()` creates a `"hud"`-named widget via `WidgetFactory::CreateWidget(name, w, h)`
 * and stores whatever it returns into `this->m_hudWidget` (offset `+568`/`0x238`), using a
 * self-assignment-guarded `eastl::shared_ptr` swap. It then spends roughly 50 instructions using
 * that stored widget completely unconditionally: writing this `Hud` back into the widget as an
 * owner pointer (`*(widget+312) = this` - the exact instruction that faults, `fault addr 0x138`
 * being offset 312 from a NULL widget), fetching its vtable to register an update callback via a
 * `boost::function`, and fetching its vtable again to register a second, named callback. If
 * `CreateWidget("hud", ...)` itself returns an empty/failed widget (this device's asset pipeline
 * failing to produce the `"hud"` widget resource - the same general "unchecked empty/failed
 * result" pattern behind fixes #32/#33/#34, now on the main thread and in the UI layer instead of
 * the background loader/model-cache subsystem), every one of these unconditional dereferences
 * would fault; the real-device crash happens to hit the very first one.
 *
 * ## The fix: make every widget-dependent instruction conditional on the widget being non-NULL
 *
 * Unlike fixes #32-#34, there's no nearby "empty/failed" fallback path to reroute into here - the
 * function just uses the widget unconditionally throughout, with no branch structure built around
 * that possibility at all. Skipping this with a single new branch was not possible in the fixed
 * instruction budget (several genuinely-needed setup instructions sit in between, and the block is
 * long enough, with its own internal conditional branches based on unrelated flags, that a single
 * flag-based guard could not survive intact all the way across it). Instead, every instruction that
 * actually dereferences the widget pointer (directly, or a value derived from dereferencing it) is
 * individually made NE-conditional, each paired with its own fresh, zero-cost null test - using the
 * `movs rX, rY`/`movs rX, rX` "copy (or no-op re-copy) plus set flags" idiom from fixes #31-#34 to
 * fold each test into an instruction that was needed anyway (a register copy this function already
 * had to make, or - in region C below - a memory read of the widget pointer this function already
 * had to make, that turns out to be redundant with a register the widget pointer already lives in
 * and can be replaced by a same-cost register copy that also sets flags). This keeps every touched
 * region exactly the same instruction count as the original, in three small, independently-sized,
 * independently-gated regions - all three must match and are applied together, atomically:
 *
 * - **Region A** (`0x114ef4`-`0x114f10`, 7 instructions/28 bytes): `movs r9, r2` (was `mov r3,#0`;
 *   also preserves the widget pointer in `r9` across the vtable read two instructions later, a copy
 *   the original code made anyway via a now-redundant reload) tests the widget; the owner-pointer
 *   write becomes `strne`; the vtable read and its offset-252 slot read become `ldrne`. On success
 *   this is instruction-for-instruction equivalent to the original (just reordered by one slot).
 * - **Region B** (`0x114f54`-`0x114f60`, 3 instructions/12 bytes): the first callback registration's
 *   `mov r0,r9`/`mov r1,r7`/`blx r8` become `movs r0,r9`/`movne r1,r7`/`blxne r8` - a fresh test
 *   right at the point of use, since the intervening `has_empty_target()` call and its own internal
 *   branches (unrelated to widget-null) clobber flags several times between region A and here.
 * - **Region C** (`0x114f9c`-`0x114fbc`, 8 instructions/32 bytes): the second callback registration
 *   re-reads the widget pointer from `this->m_hudWidget` a third time (redundant - `r9` still holds
 *   it, untouched since region A, being callee-saved across every intervening call); that reload
 *   becomes `movs r3, r9` (same cost, now also a fresh test), and the vtable read plus the final
 *   virtual call become `ldrne`/`movne`/`ldrne`.
 *
 * Assembled and linked at each real address with `arm-linux-gnueabi-as`/`ld` (full surrounding
 * instructions, unchanged ones included, reproduced as linker context to let the real toolchain
 * confirm every internal branch target in this function - several land inside these very regions -
 * is completely undisturbed), applied to a scratch copy of the real reference binary, and verified
 * with `objdump` plus a whole-file byte-diff confirming only these three regions changed.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other widget
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at all three regions
 * match byte-for-byte what this was built against - any mismatch (in any one of the three) leaves
 * the file completely untouched; all three are applied together or not at all, since a partial
 * application would leave some of the widget-dependent instructions unconditional and still
 * crash-prone. For a `"hud"` widget that creates successfully (the normal case), behavior is
 * provably identical to the original, instruction for instruction.
 */
object ElfMassEffectHudWidgetNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_A = 0x00114EF4L
    private const val TARGET_VADDR_B = 0x00114F54L
    private const val TARGET_VADDR_C = 0x00114F9CL

    private val EXPECTED_ORIGINAL_A = byteArrayOf(
        0x00, 0x30, 0xA0.toByte(), 0xE3.toByte(), 0x38, 0x41, 0x82.toByte(), 0xE5.toByte(),
        0x38, 0x92.toByte(), 0x94.toByte(), 0xE5.toByte(), 0xC8.toByte(), 0x60, 0x8D.toByte(), 0xE2.toByte(),
        0x0A, 0xA0.toByte(), 0x8F.toByte(), 0xE0.toByte(), 0x00, 0x20, 0x99.toByte(), 0xE5.toByte(),
        0xFC.toByte(), 0x80.toByte(), 0x92.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_A = byteArrayOf(
        0x02, 0x90.toByte(), 0xB0.toByte(), 0xE1.toByte(), 0x38, 0x41, 0x82.toByte(), 0x15,
        0x00, 0x30, 0xA0.toByte(), 0xE3.toByte(), 0xC8.toByte(), 0x60, 0x8D.toByte(), 0xE2.toByte(),
        0x0A, 0xA0.toByte(), 0x8F.toByte(), 0xE0.toByte(), 0x00, 0x20, 0x99.toByte(), 0x15,
        0xFC.toByte(), 0x80.toByte(), 0x92.toByte(), 0x15
    )

    private val EXPECTED_ORIGINAL_B = byteArrayOf(
        0x09, 0x00, 0xA0.toByte(), 0xE1.toByte(), 0x07, 0x10, 0xA0.toByte(), 0xE1.toByte(),
        0x38, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )
    private val PATCHED_BYTES_B = byteArrayOf(
        0x09, 0x00, 0xB0.toByte(), 0xE1.toByte(), 0x07, 0x10, 0xA0.toByte(), 0x11,
        0x38, 0xFF.toByte(), 0x2F, 0x11
    )

    private val EXPECTED_ORIGINAL_C = byteArrayOf(
        0x38, 0x32, 0x94.toByte(), 0xE5.toByte(), 0x60, 0x23, 0x9F.toByte(), 0xE5.toByte(),
        0x06, 0x00, 0xA0.toByte(), 0xE1.toByte(), 0x03, 0x10, 0xA0.toByte(), 0xE1.toByte(),
        0x02, 0x20, 0x8F.toByte(), 0xE0.toByte(), 0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0x0F, 0xE0.toByte(), 0xA0.toByte(), 0xE1.toByte(), 0x54, 0xF0.toByte(), 0x93.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_C = byteArrayOf(
        0x09, 0x30, 0xB0.toByte(), 0xE1.toByte(), 0x60, 0x23, 0x9F.toByte(), 0xE5.toByte(),
        0x06, 0x00, 0xA0.toByte(), 0xE1.toByte(), 0x03, 0x10, 0xA0.toByte(), 0xE1.toByte(),
        0x02, 0x20, 0x8F.toByte(), 0xE0.toByte(), 0x00, 0x30, 0x93.toByte(), 0x15,
        0x0F, 0xE0.toByte(), 0xA0.toByte(), 0x11, 0x54, 0xF0.toByte(), 0x93.toByte(), 0x15
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL THREE target regions match. Anything else (wrong file, any single mismatch) is left
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

            val offA = fileOffsetFor(TARGET_VADDR_A)
            val offB = fileOffsetFor(TARGET_VADDR_B)
            val offC = fileOffsetFor(TARGET_VADDR_C)
            if (offA == null || offB == null || offC == null ||
                offA < 0 || offA + EXPECTED_ORIGINAL_A.size > bytes.size ||
                offB < 0 || offB + EXPECTED_ORIGINAL_B.size > bytes.size ||
                offC < 0 || offC + EXPECTED_ORIGINAL_C.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offA, EXPECTED_ORIGINAL_A) || !matches(offB, EXPECTED_ORIGINAL_B) ||
                !matches(offC, EXPECTED_ORIGINAL_C)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_A.indices) bytes[offA + i] = PATCHED_BYTES_A[i]
            for (i in PATCHED_BYTES_B.indices) bytes[offB + i] = PATCHED_BYTES_B[i]
            for (i in PATCHED_BYTES_C.indices) bytes[offC + i] = PATCHED_BYTES_C[i]
            return Result(
                true,
                "Hud::InitLayer() no longer dereferences its 'hud' widget pointer unconditionally when " +
                    "WidgetFactory::CreateWidget() couldn't create it - every instruction that used to " +
                    "dereference it (the owner-pointer write and both callback-registration virtual " +
                    "calls) is now conditional on it actually being non-NULL, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
