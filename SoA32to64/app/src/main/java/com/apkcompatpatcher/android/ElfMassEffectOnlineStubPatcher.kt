package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: forces `libMassEffect.so`'s own
 * EA Origin / DirtySDK online-service layer to always believe there is no network at all.
 *
 * ## Background
 *
 * This game shipped with EA's "Origin" account-linking layer and a tie-in to Mass Effect 3's
 * "Galaxy at War" online meta-game, both of which talk to EA servers that have been shut down for
 * many years. SoA's own real-device investigation ("Round 18" in this project's README/memory)
 * root-caused a native SIGSEGV that happens on the very first rendered game frame, right after
 * SoA's earlier dex-level fixes (#20/#21) got the game past the whole "download screen" saga:
 * `EA::Blast::LifeCycle` receives an internal "exit" message (id `6`) that it only ever handles
 * via `OnRawExit()`, which flips the engine's alive-state, which `EA::Blast::LoopLocked()` (called
 * from the very first `NativeOnDrawFrame`) checks and, finding the engine no longer "alive", tail-
 * calls `EA::Blast::UserExit()` - whose shutdown sequence crashes because it runs far too early
 * (`im::m3gext::ModelCache::Shutdown()` dereferences a near-null object that was never constructed
 * yet). WHAT posts that message `6` was never found by exhaustively ruling out every standard JNI/
 * Activity-lifecycle bridge (see README/memory "Round 18") - it has to be something purely inside
 * the native engine itself.
 *
 * The user separately found `v-atamanenko/masseffect-vita` (MIT-licensed,
 * https://github.com/v-atamanenko/masseffect-vita), a PS Vita native loader/port of this exact
 * game. Its own documented required SHA1 for `libMassEffect.so` - `ea58b733d3d267ab639431b50539542faa43f0d0` -
 * matches this project's own extracted copy byte for byte (confirmed independently), meaning every
 * address below is guaranteed to mean exactly what it says for that exact build. Their
 * `loader/patch/kill_network.c` hooks the addresses below to make the engine believe there is no
 * network and no EA Origin service available at all - necessary, per their own README/issues, to
 * get the game running since real EA Origin/"Galaxy at War" servers no longer exist. Two of the
 * hooked functions are especially suggestive for our own crash: `GalaxyAtWarWidget::OnDraw()` is
 * itself a per-frame draw call (plausibly part of that very first drawn frame), and
 * `ProtoSSLConnect`/`SocketCreate`/`SocketControl` are the actual low-level entry points any real
 * Origin network attempt would go through on real Android - unlike Vita, which has no networking
 * stack for this game at all. This is not yet confirmed on a real device; it is this project's
 * current best-evidenced theory, being tested via this patch.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, anything else)
 *
 * Every one of the 8 patch sites below is applied ONLY if:
 *   1. the zip entry's name ends in "libMassEffect.so", AND
 *   2. the exact 8 original bytes already sitting at that specific file offset match the exact
 *      original instructions this was built against (recorded below, verified via
 *      `arm-linux-gnueabi-objdump` against the real extracted library).
 *
 * A file that merely happens to be named `libMassEffect.so` but is some other build/version of
 * this game would almost certainly fail condition 2 at every single site (8 unrelated addresses
 * all lining up by chance is not realistic) and would be left completely untouched, site by site,
 * with each mismatch logged rather than silently ignored. No other game's APK contains a file
 * named `libMassEffect.so` at all, so this patcher is unconditionally a no-op for IG2, Haegemonia,
 * or anything else - it never even reaches the ELF-parsing code for any other file.
 *
 * ## The patch itself
 *
 * Every site gets its first 1-2 instructions overwritten with a minimal ARM stub that makes the
 * function return immediately with a fixed value, leaving whatever original bytes follow as
 * inert, unreachable code (same technique as [ElfTextRelPatcher]'s trampoline fix) - nothing is
 * ever shifted, resized, or moved, so no other offset in the file needs adjusting:
 *  - `RET0`      = `mov r0, #0` + `bx lr` (8 bytes) - reports failure/false/none.
 *  - `RET1`      = `mov r0, #1` + `bx lr` (8 bytes) - reports success/true.
 *  - `RETMINUS1` = `mvn r0, #0` + `bx lr` (8 bytes) - reports failure as `-1` (the `SocketCreate`/
 *    `SocketControl` convention, matching a fresh `retminus1` reused across many DirtySDK APIs).
 *  - `RETTHIS`   = `bx lr` alone (4 bytes) - returns immediately without touching `r0`, i.e. for a
 *    constructor, returns `this` unmodified.
 * All 4 encodings were assembled and verified with `arm-linux-gnueabi-as`/`objdump` (not typed in
 * from memory), and confirmed byte-identical to [ElfTextRelPatcher]'s own pre-existing `bx lr`
 * constant for the shared instruction.
 */
object ElfMassEffectOnlineStubPatcher {

    data class Result(val patched: Boolean, val note: String, val siteNotes: List<String> = emptyList())

    private const val PT_LOAD = 1

    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // Assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte, not
    // hand-derived: "mov r0,#0"/"bx lr" -> e3a00000 e12fff1e ; "mvn r0,#0" -> e3e00000 ;
    // "mov r0,#1" -> e3a00001. BX_LR matches ElfTextRelPatcher's own existing constant.
    private val BX_LR = byteArrayOf(0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte())
    private val RET0 = byteArrayOf(0x00, 0x00, 0xA0.toByte(), 0xE3.toByte()) + BX_LR
    private val RET1 = byteArrayOf(0x01, 0x00, 0xA0.toByte(), 0xE3.toByte()) + BX_LR
    private val RETMINUS1 = byteArrayOf(0x00, 0x00, 0xE0.toByte(), 0xE3.toByte()) + BX_LR
    private val RETTHIS = BX_LR

    private class Target(
        val vaddr: Long,
        // first 8 original bytes (2 instructions) at this address, little-endian, as confirmed
        // via arm-linux-gnueabi-objdump against the real extracted 1.0.58 libMassEffect.so.
        val expectedOriginal: ByteArray,
        val stub: ByteArray,
        val description: String
    )

    private fun b(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

    private val TARGETS = listOf(
        Target(
            0x007b6ee8L,
            b(0x94, 0x10, 0x9F, 0xE5, 0x10, 0x40, 0x2D, 0xE9),
            RETTHIS,
            "EA::SP::Origin::OriginMinimizedDialogState::OriginMinimizedDialogState() -> returns immediately"
        ),
        Target(
            0x006d021cL,
            b(0xF0, 0x4F, 0x2D, 0xE9, 0x74, 0xD0, 0x4D, 0xE2),
            RET0,
            "EA::SP::Origin::OriginImpl::ShowOriginUI(...) -> forced to return 0 (never shows the Origin dialog)"
        ),
        Target(
            0x00622ba8L,
            b(0xF0, 0x4F, 0x2D, 0xE9, 0x14, 0xD0, 0x4D, 0xE2),
            RET0,
            "ProtoSSLConnect(...) -> forced to return 0 (SSL connect always reports failure)"
        ),
        Target(
            0x00626c34L,
            b(0x70, 0x40, 0x2D, 0xE9, 0xE0, 0x30, 0x9F, 0xE5),
            RETMINUS1,
            "SocketCreate(...) -> forced to return -1 (no socket is ever created)"
        ),
        Target(
            0x00627c58L,
            b(0xF0, 0x41, 0x2D, 0xE9, 0x02, 0xDA, 0x4D, 0xE2),
            RETMINUS1,
            "SocketControl(...) -> forced to return -1"
        ),
        Target(
            0x0040e31cL,
            b(0x64, 0x00, 0x9F, 0xE5, 0x70, 0x40, 0x2D, 0xE9),
            RET1,
            "EA::Util::InstallWallpaper() -> forced to return 1 (reports success without doing anything)"
        ),
        Target(
            0x0027c244L,
            b(0x10, 0x40, 0x2D, 0xE9, 0x3C, 0x21, 0x90, 0xE5),
            RET0,
            "masseffect_widgets::GalaxyAtWarWidget::OnDraw(...) -> forced to return 0 (this online-tied widget never draws/updates)"
        ),
        Target(
            0x0027e96cL,
            b(0x70, 0x40, 0x2D, 0xE9, 0x00, 0x40, 0xA0, 0xE1),
            RET0,
            "widgets::UIWidget::CreateInstance<GalaxyAtWarWidget>(...) -> forced to return 0/null (the widget is never created)"
        )
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is a `libMassEffect.so` and at least one of the 8
     * known sites' original bytes match exactly. Every site is checked and applied independently;
     * a mismatch at any one site just skips that site (logged in [Result.siteNotes]) without
     * affecting the others or failing the whole patch. Anything that isn't `libMassEffect.so` at
     * all returns immediately without ever touching or parsing [bytes].
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

            // vaddr -> file offset, via whichever PT_LOAD segment's mapped range contains it -
            // true for every normal, non-prelinked .so (see sibling patchers for the same
            // assumption, already relied on elsewhere in this codebase).
            fun fileOffsetFor(vaddr: Long): Int? =
                phdrs.firstOrNull { it.pType == PT_LOAD && vaddr >= it.pVaddr && vaddr < it.pVaddr + it.pFilesz }
                    ?.let { (vaddr - it.pVaddr + it.pOffset).toInt() }

            var patchedCount = 0
            val siteNotes = mutableListOf<String>()

            for (t in TARGETS) {
                val off = fileOffsetFor(t.vaddr)
                if (off == null || off < 0 || off + t.expectedOriginal.size > bytes.size) {
                    siteNotes.add("0x${t.vaddr.toString(16)}: address doesn't map into this file - skipped")
                    continue
                }
                var matches = true
                for (i in t.expectedOriginal.indices) {
                    if (bytes[off + i] != t.expectedOriginal[i]) { matches = false; break }
                }
                if (!matches) {
                    siteNotes.add("0x${t.vaddr.toString(16)}: original bytes don't match expected " +
                        "(different build/version?) - left untouched: ${t.description}")
                    continue
                }
                for (i in t.stub.indices) bytes[off + i] = t.stub[i]
                patchedCount++
                siteNotes.add("0x${t.vaddr.toString(16)}: ${t.description}")
            }

            if (patchedCount == 0) {
                return Result(false, "named libMassEffect.so but no known site matched - left completely untouched", siteNotes)
            }
            return Result(
                true,
                "stubbed out $patchedCount/${TARGETS.size} EA Origin/DirtySDK online-service call site(s) " +
                    "(forces the engine to believe there is no network/online service available - see " +
                    "ElfMassEffectOnlineStubPatcher doc comment for the full rationale)",
                siteNotes
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
