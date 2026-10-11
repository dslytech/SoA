package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's FOURTEENTH native SIGSEGV. Real-device
 * tested after fix #40 (the `Hud::InitWorld()` widget null-guard) shipped: both `m_hudWidget` call
 * sites are gone, and the function runs all the way through HUD/input-scheme setup before hitting
 * this - still `Hud::InitWorld()`, still the background level-load thread, just much further in:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000c4
 * Cause: null pointer dereference
 *     r6 00000000
 * #00 Hud::InitWorld(GameWorld*)+672
 * #01 LayerGameWorld::LoadLevel(LoadTransition*)+76
 * #02 LoadTransitionTask::Run()+564
 * ```
 *
 * ## Root cause
 *
 * Early in `Hud::InitWorld()`, `r6` is set from `LevelIndexData::GetLevel(...)` - the exact same
 * "look up the level currently being loaded in the level index" call whose NULL result fixes #5
 * (`ElfMassEffectLevelIndexNullGuardPatcher`, a different caller) and #39
 * (`ElfMassEffectGameWorldLevelNullGuardPatcher`, `GameWorld::InitLevel()`'s own call) already had
 * to guard. `Hud::InitWorld()` uses that same result a third time, independently: 672 bytes in,
 * long after the widget setup fixes #40 patched, it reads `level->[196]` (fault addr `0xc4` is
 * exactly offset 196 read through a NULL `r6`) - a packed level-tied color value - reassembles it
 * (a byte swap: the top and bottom bytes of a 3-byte RGB value trade places) and stores the result
 * into `this->[580]`, a `Hud` field that (based on the surrounding `Hud::StateTransition()` call
 * right after) drives the level-title UI's color. No level found in the index -> no color to read
 * -> unconditional dereference of a NULL pointer.
 *
 * ## The fix: an out-of-line trampoline ("code cave") skipping the whole color-compute-and-store
 *
 * The read, the 6-instruction byte-swap, and the store are 8 instructions with no redundant one
 * among them and no nearby fallback branch structure to reroute into - the same shape as fixes
 * #38-#40, so this reuses their technique again, in the same free `.rodata` block (right after fix
 * #40's own trampoline). The block's first instruction (`ldr r3, [r6, #196]`) becomes a same-size
 * `bl` to a 4-instruction cave: on a non-NULL level it does the read and returns via `bx lr` right
 * back to the original next instruction, so the byte-swap and store proceed exactly as before; on
 * NULL it branches straight past all 8 original instructions to `Hud::StateTransition(Hud::State)`
 * (the call that already follows them, using neither the color nor `r6`) - leaving `this->[580]`
 * simply at whatever it already held (the same "just don't perform the update" fallback shape used
 * throughout this project whenever a lookup comes back empty) instead of crashing.
 *
 * - **Callsite** (`0x114ca8`, 4 bytes): `ldr r3,[r6,#196]` becomes `bl 0x995944`.
 * - **Cave** (`0x995944`, 16 bytes, in previously-unused zero `.rodata` padding): `cmp r6,#0` /
 *   `beq 0x114cd0` / `ldr r3,[r6,#196]` / `bx lr`.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld` (`--defsym` for the
 * cave's own branch target), verified with `objdump` against the real function's surrounding,
 * unmodified instructions (confirming the `beq` lands exactly on `Hud::InitWorld(GameWorld*)+0x2c8`,
 * the real `StateTransition()` call site) plus a whole-file byte-diff (18 bytes differ from the
 * pristine original: 4 at the call site, 14 of the cave's 16 bytes - the other 2 happened to already
 * be zero) - and re-verified together with fixes #38, #39, and #40's own adjacent trampolines in the
 * same free block, confirming all four coexist with no interference (72 total bytes differ across
 * all four fixes combined, exactly matching each fix's own independently-verified count).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other level
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions match
 * byte-for-byte what this was built against - any mismatch leaves the file completely untouched;
 * both regions are applied together or not at all. For a level that's found in the index (the
 * normal case), behavior is provably identical to the original, instruction for instruction.
 */
object ElfMassEffectHudLevelTitleColorNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00114CA8L
    private const val TARGET_VADDR_CAVE = 0x00995944L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xC4.toByte(), 0x30, 0x96.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x25, 0x03, 0x22, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x56, 0xE3.toByte(),
        0xE0.toByte(), 0xFC.toByte(), 0xDD.toByte(), 0x0A,
        0xC4.toByte(), 0x30, 0x96.toByte(), 0xE5.toByte(),
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
                "Hud::InitWorld() no longer reads a NULL level's title-color field - when " +
                    "LevelIndexData::GetLevel() didn't find the level currently loading (the same " +
                    "empty result fixes #5/#39 already handle at their own call sites), the whole " +
                    "color-compute-and-store sequence is now skipped entirely (via a small out-of-" +
                    "line trampoline) instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
