package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's crash #63, found in the 0.67 test - a brand new crash
 * that happens automatically during level *loading*, before the player does anything at all
 * (the game's own initial-level music kickoff, on the render thread): a missing null check in
 * `MusicManager::StartLevel(eastl::basic_string<char,...> const&)`.
 *
 * ## How this was found
 *
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000b4
 * Cause: null pointer dereference
 *     r0  ddf10cd4  r1  ddf2a16c  r2  000000b0  r3  00000000
 * #00 pc 0026dfa0  libMassEffect.so (MusicManager::StartLevel(eastl::basic_string<char, eastl::allocator> const&)+116)
 * #01 pc 00315cb8  libMassEffect.so (LayerGameWorld::OnEvent(im::Event const&)+1220)
 * #02 pc 0012cff4  libMassEffect.so (LoadTransition::UpdateFromGameThread(int)+992)
 * ```
 * Unlike most of this project's earlier finds, `MusicManager::StartLevel` is a real, non-stripped
 * symbol in the binary (its mangled name resolves directly), so no manual reverse-engineering of
 * an anonymous function was needed - disassembling the function by name pinpointed the exact
 * instruction immediately.
 *
 * ## Root cause
 *
 * ```
 * bl   LevelIndexData::GetInstance()
 * mov  r1, r7                 ; r7 = the level-name string argument passed to StartLevel()
 * bl   LevelIndexData::GetLevel(eastl::basic_string<char,...> const&)
 * mov  r3, r0                 ; r3 = the returned level record pointer - NEVER NULL-CHECKED
 * add  r2, r3, #0xB0
 * add  r0, r4, #4
 * cmp  r0, r2                 ; self-assignment guard (unrelated to the bug)
 * beq  ...
 * ldr  r2, [r3, #0xB4]        ; <-- CRASH: r3 is NULL, fault addr = 0 + 0xB4
 * ldr  r1, [r3, #0xB0]
 * bl   eastl::basic_string::assign(char const*, char const*)
 * ```
 * `GetLevel()` looks up the level name in `LevelIndexData`'s table and returns null when the name
 * isn't found. `StartLevel()` then unconditionally treats that result as a pointer to the level
 * record, reads an embedded `eastl::basic_string`'s begin/end pointers at offsets `0xB0`/`0xB4` (the
 * level's associated music-track name), and hands them to `assign()` to update the currently
 * playing track. With `r3 == 0`, the first of those reads (`[r3, #0xB4]`) faults at address `0xB4` -
 * exactly matching the crash log's `fault addr 0x000000b4` and `r3 00000000`. Because this runs on
 * every level load as part of the normal music-kickoff sequence, it can fault before the player has
 * done anything interactive at all, which matches this being reported as happening "before I even
 * started playing."
 *
 * ## The fix: skip the track-name update when the level lookup fails
 *
 * If `GetLevel()` returns null, there is nothing valid to copy the track name from - the function's
 * own existing behavior when the *current* level's music happens to already match (the self-assign
 * guard a few instructions later) is to skip the `assign()` call entirely and fall through to
 * `MusicManager::ResumeLevelMusic()`. That is exactly the safe, already-present fallback this guard
 * reuses: on a failed lookup, keep whatever music was already playing rather than crash.
 *
 * - **Callsite** (`0x26DF90`, 4 bytes): the `add r2, r3, #0xB0` becomes a `b` into a 3-fragment
 *   trampoline chain (fresh free `.rodata` gaps, re-scanned to exclude every region every prior fix
 *   already claims):
 *   - `0x954080` (G1): `cmp r3, #0` / `b 0x9540D0`
 *   - `0x9540D0` (G2): `beq 0x26DFAC` (invalid - `GetLevel()` returned null: bail straight to the
 *     function's own epilogue, which just falls into `ResumeLevelMusic()` - the same path taken
 *     today when the self-assign guard trips) / `b 0x954168` (valid: fall through to G3)
 *   - `0x954168` (G3, terminal-valid): `add r2, r3, #0xB0` (the original instruction, relocated,
 *     restored byte-for-byte) / `b 0x26DF94` (resumes the untouched original code right after the
 *     callsite - the self-assign comparison)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all three branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness: the exact real crash's value (`r3 = 0`) resolves to landing on the bail target
 * without ever reaching the original crashing instruction, and a plausible real level-record
 * pointer (`r3 = 0xddf10cd4`, the actual non-null value seen in `r0`/`r4` in this same crash log)
 * resolves to resuming exactly at `0x26DF94` with `r2` correctly computed as `r3 + 0xB0`.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original (pristine, unpatched) bytes
 * at ALL FOUR target regions match byte-for-byte what this was built against - any single mismatch
 * leaves the file completely untouched; all four regions are applied together or not at all. For a
 * successful level lookup (the overwhelming common case), behavior is provably identical to the
 * original - only the null-lookup case, which previously crashed unconditionally, now takes the
 * function's own existing no-op path instead.
 */
object ElfMassEffectMusicStartLevelNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0026DF90L
    private const val TARGET_VADDR_CAVE_G1 = 0x00954080L
    private const val TARGET_VADDR_CAVE_G2 = 0x009540D0L
    private const val TARGET_VADDR_CAVE_G3 = 0x00954168L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xB0.toByte(), 0x20, 0x83.toByte(), 0xE2.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x3A, 0x98.toByte(), 0x1B, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_G1 = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0x11, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G2 = byteArrayOf(
        0xB5.toByte(), 0x67, 0xE4.toByte(), 0x0A,
        0x23, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_G3 = byteArrayOf(
        0xB0.toByte(), 0x20, 0x83.toByte(), 0xE2.toByte(),
        0x88.toByte(), 0x67, 0xE4.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL FOUR target regions match. Anything else (wrong file, any mismatch) is left completely
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
            val offG1 = fileOffsetFor(TARGET_VADDR_CAVE_G1)
            val offG2 = fileOffsetFor(TARGET_VADDR_CAVE_G2)
            val offG3 = fileOffsetFor(TARGET_VADDR_CAVE_G3)
            val caveOffs = listOf(offG1, offG2, offG3)
            if (offCallsite == null || caveOffs.any { it == null } ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                caveOffs.any { it!! < 0 || it + EXPECTED_ORIGINAL_CAVE.size > bytes.size }
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                caveOffs.any { !matches(it!!, EXPECTED_ORIGINAL_CAVE) }
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE_G1.indices) bytes[offG1!! + i] = PATCHED_BYTES_CAVE_G1[i]
            for (i in PATCHED_BYTES_CAVE_G2.indices) bytes[offG2!! + i] = PATCHED_BYTES_CAVE_G2[i]
            for (i in PATCHED_BYTES_CAVE_G3.indices) bytes[offG3!! + i] = PATCHED_BYTES_CAVE_G3[i]
            return Result(
                true,
                "MusicManager::StartLevel() no longer dereferences a null level-record pointer when " +
                    "the requested level name isn't found in LevelIndexData - it now falls straight " +
                    "into its own existing ResumeLevelMusic() path instead of crashing during level load."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
