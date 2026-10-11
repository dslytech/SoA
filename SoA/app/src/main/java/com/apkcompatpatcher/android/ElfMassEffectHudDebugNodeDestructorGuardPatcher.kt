package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's TENTH native SIGSEGV. Real-device tested
 * after fix #36 (the HUD debug-node skip) shipped: the `SetVisible()` crash it fixed is gone, and
 * `Hud::InitLayer()` gets 8 bytes further - to the very next instruction - before hitting this:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000004
 *     r0 e72e9154  r3 00000000  r4 e72e9154  r6 e72e9154  ...
 * #00 eastl::shared_ptr<im::scene2d_new::Node,...>::~shared_ptr()+12
 * #01 Hud::InitLayer(eastl::intrusive_ptr<m3g::Renderer> const&, LayerGameWorld*)+736
 * ```
 * Unlike fix #36's crash (`SetVisible()`, `SEGV_ACCERR`, writing into read-only library memory),
 * this one is back to the far more common `SEGV_MAPERR` shape - but at a highly distinctive fault
 * address of exactly `0x00000004`, i.e. a NULL pointer dereferenced with a `+4` offset.
 *
 * ## Root cause: the same reused stack slot fix #36 diagnosed, now caught by its OTHER reader
 *
 * Fix #36 documented that `Hud::InitLayer()` reuses the 12 stack bytes at `sp+100..111` for two
 * unrelated things: earlier, as scratch space for a `boost::function` used to register an event
 * callback (`sp+100` gets a fixed "manager" function pointer, `sp+104` gets explicitly zeroed,
 * `sp+108` gets `this`); later, misread as an `eastl::shared_ptr<im::scene2d_new::Node>` local that
 * is never actually constructed. Fix #36 correctly identified that the `SetVisible()` call reading
 * this slot always "passes" its (bogus) validity check and always faults - and made that call
 * unconditionally skipped. But fix #36's doc comment also claimed the *destructor* call for that
 * same phantom `Node` (`0x114fe0`/`0x114fe4`, right after) was "provably harmless", reasoning that
 * `eastl::shared_ptr`'s destructor would read the always-zeroed second word (`sp+104`, this
 * pseudo-shared_ptr's "control block" field, at `this+4`) and treat "no control block" as "nothing
 * to release, return immediately". Real-device testing disproves this: disassembling the actual
 * compiled destructor (`0xb2608`) shows it does NOT check its control-block pointer for NULL before
 * using it - it unconditionally does `ldr r3,[r0,#4]` (read the control-block pointer) followed
 * immediately by `ldr r2,[r3,#4]` (read the ref-count *through* it). When `sp+104` is `0` (always
 * true here, since nothing between the `boost::function` setup and this call ever gives it a real
 * value - the widget-dependent call that *would* populate it, guarded by fix #35's Region C, only
 * runs when the widget is non-NULL, and even then writes into `r6`-relative memory this destructor's
 * own reasoning doesn't independently re-verify), that second dereference reads `*(0 + 4)` - fault
 * address exactly `0x00000004`, matching the real crash precisely. So this specific call was always
 * going to crash once actually reached (which it only started being reached once fix #35 got
 * `Hud::InitLayer()` this far in the first place) - fix #36's "harmless" claim was wrong, and this
 * patch corrects it.
 *
 * ## The fix: check the real control-block field before calling the destructor
 *
 * Unlike fix #36 (where the buggy check tested the *wrong* stack word and could simply be made to
 * always take its already-safe branch), here the destructor's own logic never checks the right thing
 * at all - so this patch adds that check at the call site instead, without growing it. Two small,
 * non-contiguous regions, applied together:
 *
 * - At `0x114fbc`/`0x114fc0`, fix #36 already made the 3-instruction `ldr r0,[sp,#100]` /
 *   `cmp r0,#0` / `b 0x114fe0` sequence unconditional - which means the `ldr`/`cmp` pair's *result*
 *   is now completely unused (dead code) and its *flags* survive untouched all the way to the
 *   destructor call 24 bytes later (neither the unconditional `b` nor the intervening `mov r0,r6`
 *   touch CPSR). This patch repurposes that already-dead load-and-compare for a *different*, live
 *   purpose: `ldr r1,[sp,#104]` (was `ldr r0,[sp,#100]`) loads the shared_ptr's real control-block
 *   field instead, and `cmp r1,#0` (was `cmp r0,#0`) tests it - same instruction shape, same cost,
 *   now testing the field that actually matters. The `b 0x114fe0` third instruction is left exactly
 *   as fix #36 shipped it (this patch's precondition requires fix #36 already applied here, since
 *   the two are designed to run back-to-back in the same patch pass on the same file).
 * - At `0x114fe4`, the unconditional `bl 0xb2608` (call the destructor) becomes `blne 0xb2608` -
 *   same target, same link-register-setting call semantics, only made conditional on the flags set
 *   16 bytes earlier still reading "not equal" (control-block field non-zero). A 1-nibble condition
 *   change, same as fix #36's own single-instruction technique, just on a `bl` instead of a `b`.
 *
 * When the control-block field is zero (always true on this bogus phantom-Node path), the
 * destructor call is skipped entirely - correct, since there is nothing to release. When it's
 * genuinely non-zero (a real, validly-constructed `Node` shared_ptr - this same call site would also
 * execute on a code path this project hasn't seen crash, if one exists), the call still happens,
 * unchanged. Verified via `arm-linux-gnueabi-as`/`ld` at the real addresses with full surrounding
 * instruction context (including fix #36's own patched byte, and the still-present-but-dead code
 * fix #36 branches around) reproduced and confirmed byte-identical, plus the usual kotlinc harness +
 * `objdump` + whole-file byte-diff.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call site
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact bytes at both target regions match
 * byte-for-byte what this was built against - which, for the first region, includes requiring fix
 * #36's own patched byte to already be present at `0x114fc4`, since this fix's logic depends on that
 * branch already being unconditional. Any mismatch (either region, including fix #36 not having run)
 * leaves the file completely untouched. The shared `~shared_ptr()` destructor function itself
 * (called from other, legitimately-valid sites both within `Hud::InitLayer()` and potentially
 * elsewhere in the binary) is never modified - only this one call site.
 */
object ElfMassEffectHudDebugNodeDestructorGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // 3 instructions: ldr r1,[sp,#104] / cmp r1,#0 / b 0x114fe0 (the last already shipped by
    // ElfMassEffectHudDebugNodeSkipPatcher - reproduced here read-only as a precondition, since
    // this fix's correctness depends on that branch already being unconditional).
    private const val TARGET_VADDR_A = 0x00114FBCL

    // The bl -> blne conditional-call change.
    private const val TARGET_VADDR_B = 0x00114FE4L

    // ldr r0,[sp,#100] / cmp r0,#0 / b 0x114fe0 (the third instruction is fix #36's PATCHED byte,
    // not the pristine original "beq" - see doc comment above for why).
    private val EXPECTED_ORIGINAL_A = byteArrayOf(
        0x64, 0x00, 0x9D.toByte(), 0xE5.toByte(),
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x05, 0x00, 0x00, 0xEA.toByte()
    )
    // ldr r1,[sp,#104] / cmp r1,#0 / b 0x114fe0 (unchanged, same as fix #36 left it).
    private val PATCHED_BYTES_A = byteArrayOf(
        0x68, 0x10, 0x9D.toByte(), 0xE5.toByte(),
        0x00, 0x00, 0x51, 0xE3.toByte(),
        0x05, 0x00, 0x00, 0xEA.toByte()
    )

    // "bl 0xb2608"
    private val EXPECTED_ORIGINAL_B = byteArrayOf(0x87.toByte(), 0x75, 0xFE.toByte(), 0xEB.toByte())
    // "blne 0xb2608" - only the top condition nibble changed from AL (0xE) to NE (0x1).
    private val PATCHED_BYTES_B = byteArrayOf(0x87.toByte(), 0x75, 0xFE.toByte(), 0x1B)

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact bytes at both
     * target regions match (including fix #36's own patched byte at the tail of region A). Anything
     * else (wrong file, mismatched bytes, fix #36 not applied) is left completely untouched.
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
            if (offA == null || offB == null ||
                offA < 0 || offA + EXPECTED_ORIGINAL_A.size > bytes.size ||
                offB < 0 || offB + EXPECTED_ORIGINAL_B.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offA, EXPECTED_ORIGINAL_A) || !matches(offB, EXPECTED_ORIGINAL_B)) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version, or fix #36 not applied?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_A.indices) bytes[offA + i] = PATCHED_BYTES_A[i]
            for (i in PATCHED_BYTES_B.indices) bytes[offB + i] = PATCHED_BYTES_B[i]
            return Result(
                true,
                "Hud::InitLayer() no longer unconditionally destructs a phantom scene2d Node built from " +
                    "leftover boost::function stack scratch data - the destructor call is now skipped " +
                    "unless the shared_ptr's control-block field is actually non-zero, instead of always " +
                    "dereferencing NULL+4 and crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
