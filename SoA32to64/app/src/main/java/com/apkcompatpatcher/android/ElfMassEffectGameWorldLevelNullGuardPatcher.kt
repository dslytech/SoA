package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's TWELFTH native SIGSEGV. Real-device
 * tested after fix #38 (the AppendChild null-guard) shipped: every crash seen so far in this
 * project's HUD/widget-init and main-thread-startup path is gone, and this is the first crash on
 * the background LEVEL-LOADING thread since fix #33 - real forward progress into actually loading
 * level content, not just initializing UI:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000107
 *     r0 00000000  ...
 * #00 GameWorld::InitLevel(eastl::basic_string<char,...> const&, eastl::basic_string<char,...> const&)+300
 * #01 LayerGameWorld::LoadLevel(LoadTransition*)+60
 * #02 LoadTransitionTask::Run()+564
 * ```
 *
 * ## Root cause
 *
 * The exact same "unchecked empty/failed result" pattern as fix #33 (`LevelIndexData::GetLevel()`
 * returning NULL when the requested level isn't in the level index) - but at a *different* call
 * site. `GameWorld::InitLevel()` calls `LevelIndexData::GetInstance()->GetLevel(levelName)` and,
 * without checking the result, immediately reads a flag byte at offset 263 of it (`ldrb
 * r6,[r0,#263]`) to decide which of two ways to continue setting up the level. Fault address
 * exactly `0x107` (263 decimal) confirms `r0 == 0`.
 *
 * Unlike fix #33's call site, there's no small nearby "not found" branch to reuse here - the
 * `beq`/fallthrough right after this read both lead deeper into normal, successful level-loading
 * work (allocating `PortalCulling`, an object array, etc.), not an error path. `GameWorld::InitLevel()`
 * is a single very large function (~3000 bytes) with exactly ONE return point, at its very end -
 * everything else funnels through there, including destructing a `PerfTimer` RAII local
 * constructed near the top of the function. So "level not found" has to mean "clean up what's
 * already been constructed and return", which means reaching that one shared epilogue - too far
 * away, and past too much intervening logic, for the fixed-size, in-place guard techniques used
 * everywhere else in this project so far.
 *
 * ## The fix: an out-of-line trampoline ("code cave"), same technique as fix #38
 *
 * The single crashing instruction (`ldrb r6,[r0,#263]`, at `0x30f71c`) becomes a same-size `bl` to
 * a small 4-instruction routine written into 16 bytes of confirmed-unused, unreferenced zero
 * padding in `.rodata` (`0x995924`-`0x995933`, the same free block fix #38's own trampoline lives
 * in, used at a different offset within it - part of the same read+execute `LOAD` segment as
 * `.text`, still with nothing between it and the next real symbol referencing these bytes):
 * ```
 * cmp  r0, #0
 * beq  0x3101c0     ; not found: jump straight into InitLevel()'s own shared epilogue (destructs
 *                   ; the already-constructed PerfTimer, restores the stack, returns) - the exact
 *                   ; same exit every other path through this function already uses, just reached
 *                   ; early instead of after ~2900 more bytes of level-loading work.
 * ldrb r6, [r0, #263]  ; found (r0 != 0): do exactly what the replaced instruction did - safe now,
 *                       ; only reached when r0 is confirmed non-NULL.
 * bx   lr              ; return to GameWorld::InitLevel(), which continues completely unchanged.
 * ```
 * Verified via `arm-linux-gnueabi-as`/`ld` at both real addresses (full surrounding instruction
 * context reproduced and confirmed byte-identical aside from the one replaced instruction) plus the
 * usual kotlinc harness + `objdump` + whole-file byte-diff (20 bytes differ from the pristine
 * original in the whole 17MB file: 4 at the call site, 16 at the cave).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call site
 *
 * Same two-layer gate as every other native patch here, across two regions: only looks at a zip
 * entry named `libMassEffect.so`, and even then only patches if the exact bytes at BOTH the call
 * site (the real original instruction) AND the cave (16 zero bytes, confirmed still unused after
 * fix #38's own trampoline occupies the 16 bytes immediately before this one) match byte-for-byte
 * what this was built against - any mismatch in either region leaves the file completely untouched,
 * applied atomically (both or neither). Every other call site that reads the same level-index result
 * safely, and `GameWorld::InitLevel()`'s own huge body otherwise, are untouched.
 */
object ElfMassEffectGameWorldLevelNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // "ldrb r6,[r0,#263]" inside GameWorld::InitLevel(...) - the crashing instruction.
    private const val TARGET_VADDR_CALLSITE = 0x0030F71CL

    // 16 bytes of confirmed-unused, unreferenced zero padding in .rodata, immediately after the
    // 16 bytes fix #38's own trampoline (ElfMassEffectAppendChildNullGuardPatcher) occupies in the
    // same free block - part of the same read+execute LOAD segment as .text.
    private const val TARGET_VADDR_CAVE = 0x00995924L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(0x07, 0x61, 0xD0.toByte(), 0xE5.toByte())
    // "bl 0x995924"
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(0x80.toByte(), 0x18, 0x1A, 0xEB.toByte())

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16) // all zero
    // cmp r0,#0 / beq 0x3101c0 / ldrb r6,[r0,#263] / bx lr
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x24, 0xEA.toByte(), 0xE5.toByte(), 0x0A,
        0x07, 0x61, 0xD0.toByte(), 0xE5.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact bytes at BOTH
     * the call site and the cave region match. Anything else (wrong file, either region mismatched)
     * is left completely untouched - this is all-or-nothing, never a partial application.
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

            val offCall = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val offCave = fileOffsetFor(TARGET_VADDR_CAVE)
            if (offCall == null || offCave == null ||
                offCall < 0 || offCall + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offCave < 0 || offCave + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCall, EXPECTED_ORIGINAL_CALLSITE) || !matches(offCave, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at the call site or the cave region don't match " +
                    "expected (different build/version, or the cave space isn't actually free?) - " +
                    "left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCall + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            return Result(
                true,
                "GameWorld::InitLevel() no longer dereferences a NULL LevelIndexData::GetLevel() " +
                    "result - when the requested level isn't found in the level index, it now cleanly " +
                    "unwinds through its own existing shared exit path (destructing what it already " +
                    "constructed) instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
