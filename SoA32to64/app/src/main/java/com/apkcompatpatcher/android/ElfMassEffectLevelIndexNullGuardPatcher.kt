package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's FIFTH native SIGSEGV - and the first one
 * in this whole project that is NOT part of the FreeType font-driver chain. Real-device tested
 * after fix #31 (the FreeType root-cause fix) shipped: the font-driver saga is fully closed (no
 * more font-related crash of any kind), and the game now proceeds all the way to actually starting
 * a new game - `Application::StartNewGame()` -> `Application::LoadLevelWithCinematics()` - before
 * hitting a brand new, different-in-kind SIGSEGV:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000030
 * Cause: null pointer dereference
 *     r0 00000000  r3 00000000  r4 e7a4bf30 (== r1, the eastl::basic_string<char>& level name arg)
 * backtrace:
 *     #00 pc 003298a0  libMassEffect.so (Application::LoadLevelWithCinematics(...)+32)
 *     #01 pc 00329b2c  libMassEffect.so (Application::StartNewGame()+148)
 *     #02 pc 00329c00  libMassEffect.so (Application::Go()+160)
 *     #03 pc 0032a704  libMassEffect.so (Application::OnCreate()+2568)
 *     ... (OnRunLoopTick <- BufferedPlatformDelegate <- Platform::OnPlatformEvent <-
 *     GameApplication::OnUpdate <- UpdateHandler::HandleMessage <- MessageSend <- ProcessQueue <-
 *     LoopLocked <- NativeOnDrawFrame, same tail as every earlier native crash in this project)
 * ```
 *
 * ## Root cause
 *
 * `LoadLevelWithCinematics()` calls `LevelIndexData::GetLevel(levelName)` and immediately
 * dereferences the returned pointer (`ldr r2, [r0, #48]`) with no NULL check - `GetLevel()` returns
 * NULL whenever the requested level name isn't present in the in-memory level index.
 * `StartNewGame()` looks up its level name via `LevelIndexData::GetStartLevel("randall")` (a
 * hardcoded literal baked into the binary, unrelated to any user data) - so this crashes on the
 * very first "new game" attempt, every time, if the level index doesn't contain that entry.
 *
 * Traced why the index doesn't contain it: `LevelIndexData::Load("/published/data/level_index.
 * prefabs.sb")` is called once from `Application::OnCreate()`, reading through a VFS mounted at
 * `"/"` onto a generic `im::Platform::GetFileSystem()` filesystem object. This project confirmed
 * with the user, via `adb shell find`, that `/sdcard/Android/data/com.ea.games.meinfiltrator_
 * gamepad/published/data/level_index.prefabs.sb` genuinely exists on their device (this game
 * downloads ~900MB of level/asset content post-install the first time it's played, from an EA
 * server that's been dead for years - the user already has it from having played this game before)
 * - so the data is present at the expected location, but something in the native VFS/file-open path
 * between `Load()` and the physical file still isn't finding it at runtime, for a reason this
 * project's static-analysis-plus-post-mortem-crash-log tooling has not yet pinned down (no live
 * debugger available). Root-causing exactly why the read comes up empty is a separate, ongoing
 * investigation; this patch addresses the immediate, unconditional NULL-pointer crash that a
 * missing level entry causes today, regardless of the eventual answer to "why is the index empty".
 *
 * ## The fix: fail gracefully into the level's own existing empty-cinematics fallback path
 *
 * The very same function already has a completely different, already-correct fallback for a level
 * that DOES exist but simply has no intro/outro cinematics: it skips straight to building an empty
 * cinematic name and calling `Application::LoadLevel(name, "")` directly. That fallback path uses
 * only `r4` (level name) and `r5` (`this`) - both set up before this function even reaches the
 * crash site, and neither touched by the code being patched - so it's already safe to jump to
 * unconditionally, regardless of whether `GetLevel()` found a real entry or not.
 * ```
 * movs  r1, r0            ; was: ldr r2, [r0, #48]   - r1 := GetLevel()'s result; Z=1 iff NULL
 * ldrne r2, [r0, #48]      ; was: ldr r3, [r0, #52]   - only dereference if non-NULL
 * ldrne r3, [r0, #52]      ; was: mov r1, r0          - (r1 already holds this from the movs above)
 * cmpne r2, r3             ; was: cmp r2, r3          - only compare if non-NULL (else flags are
 *                            left exactly as the leading `movs` set them - an un-executed
 *                            conditional ARM instruction never touches the flags)
 * beq   <no-cinematics fallback>   ; UNCHANGED instruction, same target - now taken whenever
 *                            GetLevel() returned NULL, in addition to its original "found, but no
 *                            cinematics" case
 * ```
 * On a non-NULL result this is instruction-for-instruction equivalent to the original code (same
 * final register/flag state, just computed in a different order) - completely unaffected for every
 * level that IS present in the index. On a NULL result, the two `ldrne`/`cmpne` dereferences are
 * skipped (ARM condition codes - no branch needed), the leading `movs r1, r0` already set the zero
 * flag, so the existing `beq` fires and execution falls into the level's own no-cinematics-fallback
 * path, calling `Application::LoadLevel(name, "")` with the plain requested name instead of
 * dereferencing a NULL `LevelStruct*`. Exactly 5 instructions replaced by 5 - the function's size
 * and every address after it, including the following unmodified `mov r0, r5` / `add r1, r1, #48`
 * (which now reads the level-index pointer back out of `r1`, populated by this patch's `movs`
 * instead of the original's `mov r1, r0`) and the whole call chain after it, is untouched. Assembled
 * and linked at the real address with `arm-linux-gnueabi-as`/`ld -Ttext=0x3298a0` so the `beq`
 * target and the (unmodified, reproduced-for-context-only, not part of the patched bytes) `bl
 * StartCinematic` right after it were both resolved by the real toolchain against the real
 * addresses, then verified via the usual kotlinc harness + `objdump` + whole-file byte-diff.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other level
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 20 bytes at this address
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched. For a level that IS found (the normal case for every level whose data is actually
 * present), behavior is provably identical to the original, instruction for instruction.
 */
object ElfMassEffectLevelIndexNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The 5-instruction (20-byte) block inside Application::LoadLevelWithCinematics(...) right
    // after `bl LevelIndexData::GetLevel(...)`, up to (and including, unchanged) the existing
    // `beq <no-cinematics fallback>`.
    private const val TARGET_VADDR = 0x003298a0L

    // Real original 20 bytes, confirmed via arm-linux-gnueabi-objdump and a raw file-offset dump
    // against the real extracted 1.0.58 libMassEffect.so.
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x30, 0x20, 0x90.toByte(), 0xE5.toByte(), 0x34, 0x30, 0x90.toByte(), 0xE5.toByte(),
        0x00, 0x10, 0xA0.toByte(), 0xE1.toByte(), 0x03, 0x00, 0x52, 0xE1.toByte(),
        0x06, 0x00, 0x00, 0x0A
    )

    // Assembled + linked at the real address + disassembled to confirm byte-for-byte (see doc
    // comment above for the annotated instruction-by-instruction meaning of each changed word).
    private val PATCHED_BYTES = byteArrayOf(
        0x00, 0x10, 0xB0.toByte(), 0xE1.toByte(), 0x30, 0x20, 0x90.toByte(), 0x15,
        0x34, 0x30, 0x90.toByte(), 0x15, 0x03, 0x00, 0x52, 0x11,
        0x06, 0x00, 0x00, 0x0A
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
                "Application::LoadLevelWithCinematics() no longer dereferences a NULL pointer when " +
                    "LevelIndexData::GetLevel() can't find the requested level in the level index - " +
                    "it now falls into the same level's own existing 'no cinematics' fallback path " +
                    "(Application::LoadLevel() with no cinematic name) instead of crashing, exactly " +
                    "as it already does for a level that's found but simply has none."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
