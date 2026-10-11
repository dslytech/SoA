package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's THIRTEENTH native SIGSEGV. Real-device
 * tested after fix #39 (the `GameWorld::InitLevel` null-guard) shipped: that background-thread
 * crash is completely gone, and level loading now gets past `GameWorld::InitLevel()` into HUD
 * setup for the freshly-loaded world before hitting this - still on the same background level-
 * load thread:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 * Cause: null pointer dereference
 *     r0 <sret ptr>  r1 00000000  r2 <Symbol*>  r3 00000000
 * #00 widgets::UIWidget::GetWidget<HudDamageFeedback>(im::Symbol const&)+12
 * #01 masseffect_widgets::HudWidget::PostWorldSetup()+52
 * #02 Hud::InitWorld(GameWorld*)+36
 * #03 LayerGameWorld::LoadLevel(LoadTransition*)+76
 * #04 LoadTransitionTask::Run()+564
 * ```
 *
 * ## Root cause
 *
 * `Hud::InitWorld()` reads `this->m_hudWidget` (offset `+568`/`0x238` - the exact same field fix
 * #35 (`ElfMassEffectHudWidgetNullGuardPatcher`) already guards inside `Hud::InitLayer()` for the
 * case where `WidgetFactory::CreateWidget("hud", ...)` fails to produce it) and calls two of its
 * methods on it completely unconditionally: `HudWidget::PostWorldSetup()`, then
 * `HudWidget::TriggerLevelTitle(...)`. Fix #35 stops `InitLayer()` itself from crashing when that
 * widget creation fails, but it does not - and structurally cannot - make the widget un-fail; it
 * legitimately leaves `m_hudWidget` NULL in that case. `InitWorld()` runs later in the same level-
 * load sequence and re-reads that same, still-NULL field with no check at all. Per ARM's C++ ABI,
 * a member call with a non-trivial-by-value return type (here, `PostWorldSetup()`'s callee,
 * `UIWidget::GetWidget<HudDamageFeedback>()`, returns an `eastl::shared_ptr` by value) passes the
 * hidden return-storage pointer in `r0` and `this` in `r1` - so `r1 == 0` in the crash dump is
 * exactly "`this` is NULL", confirmed by tracing `PostWorldSetup()`'s own disassembly: it forwards
 * its own (NULL) `this` straight into `GetWidget<HudDamageFeedback>()`, whose very first
 * instruction dereferences `this` to fetch its vtable and crashes immediately - before
 * `PostWorldSetup()` itself ever touches the pointer. The exact same "unchecked empty/failed
 * result" pattern as fixes #32-#35/#38/#39, now hitting a second call site against the very field
 * fix #35 already had to reckon with once.
 *
 * ## The fix: an out-of-line trampoline ("code cave") skipping both calls when the widget is NULL
 *
 * `Hud::InitWorld()`'s two `m_hudWidget`-consuming calls sit back-to-back with only one unrelated
 * instruction between them (`add r1, r6, #112`, preparing an argument for the second call that
 * does not depend on the widget):
 * ```
 * ldr r0, [r4, #568]                  ; load m_hudWidget
 * bl  HudWidget::PostWorldSetup()      ; <- CRASHES if NULL
 * add r1, r6, #112
 * ldr r0, [r4, #568]                  ; reload m_hudWidget (r0 was clobbered by the call above)
 * bl  HudWidget::TriggerLevelTitle(...) ; <- would ALSO crash if NULL
 * ```
 * Making both calls conditional in place, the way fix #35 did for `InitLayer()`, needs a fresh
 * flag test at each call (flags do not survive an intervening `bl` to arbitrary code) - but unlike
 * `InitLayer()`, there is no spare instruction anywhere in this 5-instruction window to fold a test
 * into for free: the first call's `ldr` cannot itself set flags, and turning it into "load to a
 * scratch register, then flag-setting copy into `r0`" costs one instruction more than the window
 * has room for without growing the function (never allowed - see the project's byte-exact patching
 * discipline). So, as with fixes #38 and #39, this uses an out-of-line trampoline instead: the
 * first instruction (`ldr r0, [r4, #568]`) becomes a same-size `bl` to a 4-instruction cave reusing
 * the same 224-byte free `.rodata` block fixes #38/#39 already established (immediately after
 * fix #39's own 16-byte trampoline). The cave does the load and the test; on success it returns via
 * `bx lr` right back to the original next instruction (`bl PostWorldSetup()`, with `r0` correctly
 * holding the non-NULL widget, so everything downstream runs byte-for-byte as before); on failure
 * it branches directly past BOTH now-unreachable calls, straight to the instruction that already
 * follows them (`ldr r2, [r4, #2008]`, the start of unrelated bookkeeping neither call's absence
 * affects) - skipping the widget setup precisely the way it's already skipped in the `InitLayer()`
 * failure case fix #35 handles, instead of crashing.
 *
 * - **Callsite** (`0x114a28`, 4 bytes): `ldr r0,[r4,#568]` becomes `bl 0x995934`.
 * - **Cave** (`0x995934`, 16 bytes, in previously-unused zero `.rodata` padding): `ldr r0,[r4,#568]`
 *   / `cmp r0,#0` / `beq 0x114a3c` / `bx lr`.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld` (`--defsym` for the
 * cave's own branch target), verified with `objdump` against the real function's surrounding,
 * unmodified instructions (confirming the `beq` lands exactly on `Hud::InitWorld(GameWorld*)+0x34`,
 * the real post-both-calls continuation point) plus a whole-file byte-diff (18 bytes differ from
 * the pristine original: 4 at the call site, 14 of the cave's 16 bytes - the other 2 happened to
 * already be zero) - and re-verified together with fixes #38's and #39's own adjacent trampolines
 * in the same free block to confirm all three coexist with no interference (54 total bytes differ
 * across all three fixes combined, exactly matching each fix's own independently-verified count).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other widget
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions match
 * byte-for-byte what this was built against - any mismatch leaves the file completely untouched;
 * both regions are applied together or not at all. For a `"hud"` widget that creates successfully
 * (the normal case), behavior is provably identical to the original, instruction for instruction.
 */
object ElfMassEffectHudWorldSetupNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00114A28L
    private const val TARGET_VADDR_CAVE = 0x00995934L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x38, 0x02, 0x94.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xC1.toByte(), 0x03, 0x22, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x38, 0x02, 0x94.toByte(), 0xE5.toByte(),
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x3E, 0xFC.toByte(), 0xDD.toByte(), 0x0A,
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at BOTH target regions match. Anything else (wrong file, either mismatch) is left completely
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
            val offCave = fileOffsetFor(TARGET_VADDR_CAVE)
            if (offCallsite == null || offCave == null ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offCave < 0 || offCave + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) || !matches(offCave, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            return Result(
                true,
                "Hud::InitWorld() no longer calls HudWidget::PostWorldSetup()/TriggerLevelTitle() on " +
                    "a NULL m_hudWidget - when WidgetFactory::CreateWidget(\"hud\", ...) failed to produce " +
                    "it (the same failure fix #35 already handles inside Hud::InitLayer()), both calls " +
                    "are now skipped entirely (via a small out-of-line trampoline) instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
