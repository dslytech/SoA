package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's TWENTY-NINTH native crash - reached now that fix #50
 * let `Playable::OnBioticSelectBegin()`'s type-check block through cleanly, letting
 * `Hud::StateTransition()` progress into a SIBLING function it hadn't reached before:
 * `Playable::EndTapToAim(bool)`.
 *
 * ## How this was found
 *
 * The 0.55 test (fix #50 + every other fix present) got past `OnBioticSelectBegin()`'s
 * type-check block entirely - no crash there at all - and instead crashed in a different function
 * one call further down the same `Hud::StateTransition()` chain:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000025
 * Cause: null pointer dereference
 * r0  ddfc4884  r3  00000021
 * #00 pc 000a1de4  libMassEffect.so (eastl::shared_ptr<GameObject,...>::~shared_ptr()+12)
 * #01 pc 000a6660  libMassEffect.so (eastl::shared_ptr<GameObject,...>::reset()+196)
 * #02 pc 001fc630  libMassEffect.so (Playable::EndTapToAim(bool)+116)
 * #03 pc 001147e8  libMassEffect.so (Hud::StateTransition(Hud::State)+1164)
 * ```
 * `r3 = 0x21` - the exact same small "never constructed" sentinel this project has hit
 * repeatedly (fixes #45/#47/#48/#49's original bugs), this time inside a `shared_ptr<GameObject>`
 * member instead of a raw pointer field.
 *
 * ## Root cause
 *
 * `Playable::EndTapToAim(bool)` (disassembled around the crash):
 * ```
 * cmp  r6, #0
 * addne r6, r4, #1408            ; r6 = &this->m_weaponShared (a shared_ptr<GameObject> MEMBER)
 * addne r6, r6, #4
 * beq  <skip>
 * mov  r0, r6
 * bl   eastl::shared_ptr<GameObject,...>::reset()   ; <-- CRASH: resets the member unconditionally
 * ```
 * `reset()` allocates a fresh control block for the new (empty) state, then unconditionally calls
 * `~shared_ptr()` on the member's OLD value to release it - and `~shared_ptr()` itself
 * unconditionally dereferences the old control-block pointer (`this->mRefCount`) with no null
 * check at all (confirmed by disassembling it: this EA/eastl build always keeps a real, non-null
 * control-block object even for an "empty" shared_ptr - by design there's no null case to guard
 * against in normal operation). The member here (`this->m_weaponShared` at offset 1412) was never
 * properly constructed - its control-block field holds raw uninitialized heap memory (`0x21`)
 * instead of a real allocated control block, so the very first dereference inside `~shared_ptr()`
 * faults (`0x21 + 4 = 0x25`, matching the fault address exactly).
 *
 * ## The fix: validate the member's OWN control-block pointer before calling reset() on it
 *
 * Same whitelist-floor technique as fixes #48/#49 (every real heap allocation in this project's
 * crash data sits `>= 0xC0000000`), applied to this ONE call site only - not to the shared
 * `reset()`/`~shared_ptr()` functions themselves, which are used from many other places in the
 * engine this project hasn't audited, so guarding only where a crash was actually observed keeps
 * the change's blast radius as narrow as everywhere else in this project.
 *
 * - **Callsite** (`0x1FC630`, 4 bytes): `bl 0xa659c` (`reset()`) becomes a `bl` into a 3-part
 *   trampoline chain (see below). The `mov r0, r6` right before it is untouched - `r0` is already
 *   the member's address when the cave runs.
 * - **Cave, 3 chained fragments** (`.rodata` is essentially out of contiguous free space after
 *   fix #50 - the only run big enough for a single 12+ byte cave turned out to be a genuine binary
 *   lookup table, not unused padding, so this reuses the project's established "chained
 *   trampolines" technique across three separate small gaps instead):
 *   - `0x94F988` (8 bytes): `ldr r3,[r0,#4]` (loads the member's control-block pointer) /
 *     `b 0x94FB60`
 *   - `0x94FB60` (8 bytes): `cmp r3,#0xC0000000` / `b 0x9501A8`
 *   - `0x9501A8` (8 bytes, terminal): `bxlo lr` (invalid: return immediately, skipping `reset()`
 *     entirely - `lr` is already the correct return address since the callsite's `bl` set it) /
 *     `b 0xa659c` (valid: tail-call the real `reset()`, same `lr`).
 *   All three fragments sit in verified-safe gaps: each is a run of zero bytes between two
 *   adjacent string literals in `.rodata` (confirmed via byte-context inspection, not just
 *   "outside any symbol's declared size" - the same alignment-padding pattern as every other cave
 *   in this project), cross-checked against `readelf -r` for no relocations pointing in.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking every branch's resolved target address (the callsite `bl`, both inter-fragment `b`s,
 * and the final fragment's `bxlo`/`b` pair) individually, plus a combined re-verification
 * alongside every other fix's trampoline.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at ALL FOUR regions
 * (callsite + 3 cave fragments) match byte-for-byte what this was built against - any single
 * mismatch leaves the file completely untouched; all four regions are applied together or not at
 * all. For a real `shared_ptr<GameObject>` member (whose control-block pointer, on every device
 * this project has seen data from, always falls in `0xd8000000`-`0xdf000000`), behavior is
 * provably identical to the original: same `reset()` call, same eventual continuation.
 */
object ElfMassEffectEndTapToAimResetGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001FC630L
    private const val TARGET_VADDR_CAVE_F1 = 0x0094F988L
    private const val TARGET_VADDR_CAVE_F2 = 0x0094FB60L
    private const val TARGET_VADDR_CAVE_F3 = 0x009501A8L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xD9.toByte(), 0xA7.toByte(), 0xFA.toByte(), 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xD4.toByte(), 0x4C, 0x1D, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x04, 0x30, 0x90.toByte(), 0xE5.toByte(),
        0x73, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x03, 0x01, 0x53, 0xE3.toByte(),
        0x8F.toByte(), 0x01, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x1E, 0xFF.toByte(), 0x2F, 0x31,
        0xFA.toByte(), 0x58, 0xDD.toByte(), 0xEA.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at ALL FOUR target regions match. Anything else (wrong file, any mismatch) is left
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
            val offF1 = fileOffsetFor(TARGET_VADDR_CAVE_F1)
            val offF2 = fileOffsetFor(TARGET_VADDR_CAVE_F2)
            val offF3 = fileOffsetFor(TARGET_VADDR_CAVE_F3)
            if (offCallsite == null || offF1 == null || offF2 == null || offF3 == null ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offF1 < 0 || offF1 + EXPECTED_ORIGINAL_CAVE.size > bytes.size ||
                offF2 < 0 || offF2 + EXPECTED_ORIGINAL_CAVE.size > bytes.size ||
                offF3 < 0 || offF3 + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) ||
                !matches(offF1, EXPECTED_ORIGINAL_CAVE) ||
                !matches(offF2, EXPECTED_ORIGINAL_CAVE) ||
                !matches(offF3, EXPECTED_ORIGINAL_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE_F1.indices) bytes[offF1 + i] = PATCHED_BYTES_CAVE_F1[i]
            for (i in PATCHED_BYTES_CAVE_F2.indices) bytes[offF2 + i] = PATCHED_BYTES_CAVE_F2[i]
            for (i in PATCHED_BYTES_CAVE_F3.indices) bytes[offF3 + i] = PATCHED_BYTES_CAVE_F3[i]
            return Result(
                true,
                "Playable::EndTapToAim() no longer calls reset() on its weapon shared_ptr member " +
                    "when that member's own control-block pointer doesn't look like a real heap " +
                    "object (< 0xC0000000) - the reset is now silently skipped instead of crashing " +
                    "while releasing the old (corrupted) value."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
