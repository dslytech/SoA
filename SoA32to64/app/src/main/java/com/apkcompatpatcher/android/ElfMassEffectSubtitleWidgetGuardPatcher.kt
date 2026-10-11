package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's THIRTIETH native crash - a brand new call chain, not
 * part of the `Hud::StateTransition()`/`Playable` saga fixes #45-#52 cover. Found in the 0.56
 * test (fixes #51/#52 + every other fix present): both `EndTapToAim()` guards held (no
 * recurrence), and the game instead crashed earlier in the SAME level-load sequence, in
 * `LayerGameWorld::LoadLevel()`'s call to `GameWorld::GetSubtitleWidget()`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
 * Cause: null pointer dereference
 * r1  00000000
 * #00 pc 00302120  libMassEffect.so (UIWidget::GetWidget<Subtitles>(Symbol const&)+12)
 * #01 pc 00302284  libMassEffect.so (GameWorld::GetSubtitleWidget()+52)
 * #02 pc 00316d04  libMassEffect.so (LayerGameWorld::LoadLevel(LoadTransition*)+88)
 * ```
 * A plain NULL dereference (fault addr exactly `0x0`, not the usual small-sentinel garbage this
 * project keeps finding) - a genuinely different flavor of bug from the rest of this saga.
 *
 * ## Root cause
 *
 * `GameWorld::GetSubtitleWidget()`:
 * ```
 * bl   GameObject::GetHud()
 * ldr  r6, [r0, #568]                 ; r6 = hud->[568], the UI system's "root widget" field
 * ...
 * mov  r1, r6
 * mov  r2, r5                          ; &Symbol("Subtitles"-ish name)
 * bl   UIWidget::GetWidget<Subtitles>(Symbol const&)   ; <-- passes r6 through unchecked
 * ```
 * `UIWidget::GetWidget<Subtitles>()` immediately dereferences that pointer (`ldr r3,[r1]`, reading
 * its vtable to make a virtual call) with no null check. `hud->[568]` isn't always populated by
 * the time `GetSubtitleWidget()` runs during level load - the UI root widget hasn't been built
 * yet at this point in the sequence.
 *
 * ## The fix: skip the call when the root widget pointer is absent
 *
 * Same whitelist-floor technique as fixes #48/#49/#51/#52 (`>= 0xC0000000` - a strict superset of
 * a plain NULL check, and consistent with every other guard in this project), guarding this ONE
 * call site. The call's own return value isn't used afterward by the caller (`GetSubtitleWidget()`
 * returns its own `this`/cache slot unconditionally after the call either way), so skipping the
 * call entirely - leaving the subtitle widget uncached/unset for this one attempt - is safe.
 *
 * - **Callsite** (`0x302284`, 4 bytes): `bl 0x302114` (`GetWidget<Subtitles>()`) becomes a `bl`
 *   into a 2-part trampoline chain (no single free `.rodata` region left big enough for a
 *   contiguous cave, same situation fixes #51/#52 already worked around):
 *   - `0x951048` (8 bytes): `cmp r1,#0xC0000000` / `b 0x951060`
 *   - `0x951060` (8 bytes, terminal): `bxlo lr` (invalid: skip the call, return immediately - `lr`
 *     is already the correct return address) / `b 0x302114` (valid: tail-call the real
 *     `GetWidget<Subtitles>()`, same `lr`).
 *   Both fragments are verified-safe zero-byte gaps between adjacent string literals in
 *   `.rodata`, cross-checked against `readelf -r` for no relocations pointing in.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking every branch's resolved target address individually, plus a combined re-verification
 * alongside every other fix's trampoline.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL THREE regions
 * (callsite + 2 cave fragments) match byte-for-byte what this was built against - any single
 * mismatch leaves the file completely untouched; all three regions are applied together or not at
 * all. For a real root-widget pointer (which, on every device this project has seen data from, is
 * always `>= 0xC0000000` once populated), behavior is provably identical to the original: same
 * tail-call, same eventual return address.
 */
object ElfMassEffectSubtitleWidgetGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00302284L
    private const val TARGET_VADDR_CAVE_H1 = 0x00951048L
    private const val TARGET_VADDR_CAVE_H2 = 0x00951060L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xA2.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x6F, 0x3B, 0x19, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_H1 = byteArrayOf(
        0x03, 0x01, 0x51, 0xE3.toByte(),
        0x03, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_H2 = byteArrayOf(
        0x1E, 0xFF.toByte(), 0x2F, 0x31,
        0x2A, 0xC4.toByte(), 0xE6.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL THREE target regions match. Anything else (wrong file, any mismatch) is left
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

            val offCallsite = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val offH1 = fileOffsetFor(TARGET_VADDR_CAVE_H1)
            val offH2 = fileOffsetFor(TARGET_VADDR_CAVE_H2)
            if (offCallsite == null || offH1 == null || offH2 == null ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offH1 < 0 || offH1 + EXPECTED_ORIGINAL_CAVE.size > bytes.size ||
                offH2 < 0 || offH2 + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                !matches(offH1, EXPECTED_ORIGINAL_CAVE) ||
                !matches(offH2, EXPECTED_ORIGINAL_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE_H1.indices) bytes[offH1 + i] = PATCHED_BYTES_CAVE_H1[i]
            for (i in PATCHED_BYTES_CAVE_H2.indices) bytes[offH2 + i] = PATCHED_BYTES_CAVE_H2[i]
            return Result(
                true,
                "GameWorld::GetSubtitleWidget() no longer calls UIWidget::GetWidget<Subtitles>() " +
                    "with a UI root-widget pointer that doesn't look like a real heap object " +
                    "(< 0xC0000000, covering NULL - the UI root widget isn't always built yet at " +
                    "this point in level loading) - the call is now silently skipped instead of " +
                    "crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
