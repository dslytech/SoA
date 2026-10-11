package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's TWENTY-FOURTH native crash - a brand new call site in
 * `Hud::StateTransition(Hud::State)`, distinct from fixes #45 and #46 (which guard a *different*
 * per-frame listener/layer vector earlier in the same function).
 *
 * ## How this was found
 *
 * The 0.50 test (with the wide-range revision of fix #45 plus fixes #46/#47 all present and all
 * doing their job - none of their own crashes recurred) got further into the same function and hit
 * a NEW crash: `SIGSEGV`/`SEGV_ACCERR`, fault addr `0x73617052`, inside `Playable::AbortSniper()+8`,
 * reached via `Playable::OnBioticSelectBegin()+12` <- `Hud::StateTransition(Hud::State)+1164`
 * (`0x1147e8`) <- `Hud::InitWorld(GameWorld*)+712`.
 *
 * The registers showed `r0 = r4 = 0x73616c62`. Decoded as bytes in memory order that spells
 * `"blas"` - i.e. this is not a wild pointer at all, it's the FRONT of a string literal (almost
 * certainly `"blast"` or similar UI/ability text) sitting in `.rodata`, being handed to
 * `OnBioticSelectBegin()`/`AbortSniper()` as if it were a `Playable*` "this" pointer. Disassembling
 * `OnBioticSelectBegin()` confirmed `AbortSniper()` receives `r0` completely unchanged from
 * `OnBioticSelectBegin()`'s own entry (only a spare copy is kept in `r4`), so the corrupted value
 * has to originate at the call into `OnBioticSelectBegin()` itself.
 *
 * ## Root cause
 *
 * This is a plain (non-virtual) function call, not a vtable dispatch - so it isn't the same call
 * site fixes #45/#46 already guard. The code just before it:
 * ```
 * ldr   r0, [r4, #4]      ; r0 = this->[4]                 (some owning object, may be NULL)
 * cmp   r0, #0
 * ldrne r0, [r0, #452]    ; if non-null: r0 = *(r0 + 452)  (a field that SHOULD hold a Playable*)
 * bl    Playable::OnBioticSelectBegin()   ; <-- called UNCONDITIONALLY, even when r0 stayed 0
 * ```
 * Two problems layered on top of each other: the call happens even when the owning object was
 * NULL (so `r0` is `0`), and - as this crash shows - even when the owning object *was* present,
 * the field at offset `452` isn't reliably holding a real `Playable*`; here it held leftover
 * `.rodata` string bytes instead. Whatever writes that field evidently doesn't always run before
 * this read does.
 *
 * This is a NEW flavor of "garbage" for this project's running taxonomy: not a small sentinel
 * (`0x21`, `0x1000`), not a huge wild `0xff......` pointer - a plausible-*looking* mid-range value
 * that happens to be ASCII text. Neither of fix #45's two existing thresholds (`<= 0x1000` /
 * `>= 0xF0000000`) would catch `0x73616c62`. Rather than bolt on a third arbitrary blacklist band,
 * this fix uses the project's own accumulated evidence the other way around: every genuine heap
 * object pointer observed in this whole project's crash register dumps, across every one of the
 * last twenty-plus crashes, has landed in the `0xd8000000`-`0xdf000000` band. A single lower-bound
 * whitelist check (`>= 0xC0000000`, with headroom below the observed band) accepts every real
 * pointer seen so far and rejects everything else in one shot - NULL, small sentinels, and this new
 * mid-range string-literal case alike.
 *
 * ## The fix: skip the call entirely when "this" doesn't look like a real heap pointer
 *
 * `OnBioticSelectBegin()`'s return value is discarded (the code after the call just resumes the
 * loop at `0x1144b4`), so skipping the call outright is safe - it is exactly as if this particular
 * loop iteration's "biotic select" notification never fired, instead of crashing.
 *
 * - **Callsite** (`0x1147E8`, 4 bytes): `bl 0x1fc7a4` (`Playable::OnBioticSelectBegin()`) becomes
 *   `bl 0x9959C4` (a new trampoline, right after fix #46's cave in the same shared `.rodata` block).
 * - **Cave** (`0x9959C4`, 16 bytes): `cmp r0,#0xC0000000` / `blo <skip>` / `b 0x1fc7a4` (tail-call -
 *   `lr` is untouched, so `OnBioticSelectBegin()`'s own return lands directly back at `0x1147ec`,
 *   exactly where the original `bl` would have returned to) / `skip: bx lr` (skip straight back to
 *   `0x1147ec` without ever calling the function).
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` - explicitly checking both branches' *resolved target addresses* (`blo` -> the `bx lr`
 * two instructions later, `b` -> `0x1fc7a4` itself, not some other local offset - the discipline
 * adopted project-wide after the 0.46 regression) - plus a whole-file byte-diff and a combined
 * re-verification alongside every other fix's trampoline in the same shared cave block, confirming
 * all coexist with no overlap.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions match
 * byte-for-byte what this was built against - any mismatch leaves the file completely untouched;
 * both regions are applied together or not at all. For a real heap `Playable*` (which, on every
 * device this project has seen data from, is always `>= 0xC0000000`), behavior is provably
 * identical to the original: same tail-call, same eventual return address.
 */
object ElfMassEffectHudBioticSelectCallGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001147E8L
    private const val TARGET_VADDR_CAVE = 0x009959C4L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xED.toByte(), 0x9F.toByte(), 0x03, 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x75, 0x04, 0x22, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x50, 0xE3.toByte(),
        0x00, 0x00, 0x00, 0x3A,
        0x74, 0x9B.toByte(), 0xE1.toByte(), 0xEA.toByte(),
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
                "Hud::StateTransition() no longer calls Playable::OnBioticSelectBegin() with a " +
                    "'this' pointer that doesn't look like a real heap object (< 0xC0000000, " +
                    "covering NULL, small sentinels, and stray .rodata string data alike) - that " +
                    "loop iteration's biotic-select notification is now silently skipped instead of " +
                    "crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
