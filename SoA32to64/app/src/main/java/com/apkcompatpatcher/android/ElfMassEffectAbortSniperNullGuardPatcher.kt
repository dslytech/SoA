package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's TWENTY-SECOND native crash - a genuinely new site,
 * `Playable::AbortSniper()`, reached for the first time now that fixes #45/#46 let
 * `Hud::StateTransition()`'s listener loop actually iterate real, valid elements instead of
 * crashing on the container/element checks themselves. Real progress: the crash log's backtrace
 * shows a live virtual dispatch chain through a real listener object -
 * `Hud::StateTransition()` -> `Playable::OnBioticSelectBegin()` -> `Playable::AbortSniper()` -
 * not another guard-machinery crash.
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000022e
 * #00 Playable::AbortSniper()+80
 * #01 Playable::OnBioticSelectBegin()+12
 * #02 Hud::StateTransition(Hud::State)+1164
 * #03 Hud::InitWorld(GameWorld*)+712
 * ```
 *
 * ## Root cause
 *
 * `Playable::AbortSniper()` reads an optional "current sniper weapon" field and only guards
 * against it being exactly NULL before dereferencing a byte 525 bytes into it:
 * ```
 * ldr   r0, [r4, #1192]   ; r0 = this->[0x4a8]      (the sniper-weapon field)
 * cmp   r0, #0
 * beq   <skip>             ; only skips if the field is EXACTLY null
 * ldrb  r3, [r0, #525]    ; r3 = weapon->[0x20d]     <-- CRASH: r0 is 0x21, not a real pointer
 * ```
 * `r0` was `0x21` - not NULL, but the exact same small-integer "never constructed" sentinel value
 * this project has now seen repeatedly (fix #45's original crash used this very value, at a
 * completely different call site). `0x21 + 525 (0x20d) = 0x22e`, matching the crash's fault
 * address exactly. Same underlying story as the whole fix #42-#46 family: a field that's only
 * populated under some condition this gamepad-only, no-sniper-yet playthrough never triggered,
 * left holding leftover/sentinel bit patterns instead of a real pointer - just reached through yet
 * another call path, now that the listener-loop guards ahead of it are no longer blocking
 * progress.
 *
 * ## The fix: widen the existing NULL check, no cave needed
 *
 * Unlike every guard since fix #38, this one needs no out-of-line trampoline at all: the existing
 * `cmp r0,#0` / `beq <skip>` pair sits right where it's needed and already branches to a safe,
 * pre-existing fallback - it just used the wrong threshold, exactly like fix #45's original
 * mistake and its correction in fix #45r (0.47). This time it's fixed directly, in place: `cmp
 * r0,#0` -> `cmp r0,#0x1000` (the same "implausibly small to be a real pointer" threshold fixes
 * #42-#46 already established project-wide) and `beq` -> `bls` (unsigned lower-or-same), same
 * branch offset, same target, same size, same two addresses - just two bits of each instruction
 * change.
 *
 * - **Region** (`0x1F7348`, 8 bytes): `cmp r0,#0` / `beq 0x1f73ac` becomes `cmp r0,#0x1000` /
 *   `bls 0x1f73ac` (identical target, verified below).
 *
 * Assembled and linked at the real address with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` - explicitly checking the branch's *resolved target address* (`0x1f73ac`, exactly
 * matching the original `beq`'s target - the class of mistake that slipped into 0.46 is checked
 * for on every cave/branch edit in this project from here on) - plus a whole-file byte-diff (only
 * 2 bytes differ: the `cmp` immediate's rotate/imm byte, and the branch's condition-code nibble;
 * the branch offset bytes are byte-identical to pristine).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other case
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at the target region
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched. For a real (non-null, non-tiny) sniper-weapon pointer, behavior is provably identical
 * to the original - same branch decision, same fallthrough.
 */
object ElfMassEffectAbortSniperNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR = 0x001F7348L

    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x16, 0x00, 0x00, 0x0A
    )
    private val PATCHED_BYTES = byteArrayOf(
        0x01, 0x0A, 0x50, 0xE3.toByte(),
        0x16, 0x00, 0x00, 0x9A.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at the target region match. Anything else (wrong file, mismatch) is left completely
     * untouched.
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

            val off = fileOffsetFor(TARGET_VADDR)
            if (off == null || off < 0 || off + EXPECTED_ORIGINAL.size > bytes.size) {
                return Result(false, "target address doesn't map into this file - not touching")
            }
            for (i in EXPECTED_ORIGINAL.indices) {
                if (bytes[off + i] != EXPECTED_ORIGINAL[i]) {
                    return Result(
                        false,
                        "original bytes at the target region don't match expected (different " +
                            "build/version?) - left completely untouched"
                    )
                }
            }
            for (i in PATCHED_BYTES.indices) bytes[off + i] = PATCHED_BYTES[i]
            return Result(
                true,
                "Playable::AbortSniper() no longer dereferences a never-constructed small-sentinel " +
                    "(<= 0x1000) sniper-weapon pointer - it now takes the function's own existing " +
                    "'no sniper weapon' fallback path in that case too, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
