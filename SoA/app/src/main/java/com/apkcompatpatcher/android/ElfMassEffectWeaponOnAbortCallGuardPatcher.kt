package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's TWENTY-SEVENTH native crash - reached now that fix
 * #45's Round 6 (the `.data.rel.ro`-range vtable guard) let `Hud::StateTransition()`'s listener
 * loop's virtual dispatch through cleanly for the first time, letting `Playable::OnBioticSelectBegin()`
 * run further than ever before (past `AbortSniper()`, which fix #47 already guards) into a THIRD
 * call in the same function this project hadn't reached yet.
 *
 * ## How this was found
 *
 * The 0.53 test (fix #45 Round 6 + every other fix present) got past the vtable-dispatch site
 * entirely - no crash there at all this run - and instead crashed one function further down the
 * same call chain fix #48 already guards the entry to:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000259
 * Cause: null pointer dereference
 * r0  00000021
 * #00 pc 002f8814  libMassEffect.so (Weapon::OnAbort()+4)
 * #01 pc 001fc7c0  libMassEffect.so (Playable::OnBioticSelectBegin()+28)
 * #02 pc 001147e8  libMassEffect.so (Hud::StateTransition(Hud::State)+1164)
 * ```
 * `r0 = 0x21` - the exact same small-sentinel "never constructed" value this project has hit
 * repeatedly (fix #45's original guard, fix #47's original `AbortSniper()` guard). `0x21 + 0x238
 * (568) = 0x259`, matching the fault address exactly.
 *
 * ## Root cause
 *
 * `Playable::OnBioticSelectBegin()` (already disassembled while investigating fix #48):
 * ```
 * push {r4, lr}
 * sub  sp, sp, #8
 * mov  r4, r0
 * bl   Playable::AbortSniper()       ; fix #47 already guards this
 * mov  r3, #4
 * str  r3, [r4, #1288]
 * ldr  r0, [r4, #1192]               ; r0 = this->[1192], a "current weapon" field
 * bl   Weapon::OnAbort()             ; <-- CRASH: called unconditionally, even when r0 is garbage
 * ldr  r3, [r4]                      ; r4 (not r0) used next - OnAbort()'s return value is unused
 * ...
 * ```
 * `Weapon::OnAbort()` itself immediately dereferences its `this` (`ldr r3,[r0,#568]`) with no null
 * check at all. This is the same "field not always populated before it's read" pattern as fixes
 * #46-#48 - just a third field in the same general area of `Playable`/`Hud::StateTransition`'s
 * call chain.
 *
 * ## The fix: skip the call entirely when the weapon pointer doesn't look real
 *
 * `Weapon::OnAbort()`'s return value is discarded (the code right after the call uses `r4`, the
 * original `Playable*`, not anything from the call), so skipping the call outright is safe -
 * reuses the exact same technique and threshold as fix #48 (`ElfMassEffectHudBioticSelectCallGuardPatcher`):
 * every real heap object pointer seen anywhere in this project's crash logs sits in
 * `0xd8000000`-`0xdf000000`, so a single lower-bound whitelist (`>= 0xC0000000`) accepts every
 * genuine pointer and rejects `0x21` (and NULL, and anything else implausibly small) in one check.
 *
 * - **Callsite** (`0x1FC7C0`, 4 bytes): `bl 0x2f8810` (`Weapon::OnAbort()`) becomes
 *   `bl 0x99598C` (a trampoline reusing fix #45's now-abandoned Round 1-3 cave slot - unused since
 *   Round 4 moved that guard to a fresh address, confirmed still pristine zero).
 * - **Cave** (`0x99598C`, 16 bytes): `cmp r0,#0xC0000000` / `blo <skip>` / `b 0x2f8810` (tail-call -
 *   `lr` is untouched, so `Weapon::OnAbort()`'s own return lands directly back at `0x1fc7c4`,
 *   exactly where the original `bl` would have returned to) / `skip: bx lr` (skip straight back to
 *   `0x1fc7c4` without ever calling the function).
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking both branches' resolved target addresses (`blo` -> the `bx lr` two instructions later,
 * `b` -> `0x2f8810` itself) - plus a combined re-verification alongside every other fix's
 * trampoline in the same shared `.rodata` block, confirming no overlap with the currently-active
 * fix #45/#46/#48 trampolines that now surround this reclaimed slot.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched; both regions are applied together or not at all. For a real heap `Weapon*` (which,
 * on every device this project has seen data from, is always `>= 0xC0000000`), behavior is
 * provably identical to the original: same tail-call, same eventual return address.
 */
object ElfMassEffectWeaponOnAbortCallGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001FC7C0L
    private const val TARGET_VADDR_CAVE = 0x0099598CL

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x12, 0xF0.toByte(), 0x03, 0xEB.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x71, 0x64, 0x1E, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x50, 0xE3.toByte(),
        0x00, 0x00, 0x00, 0x3A,
        0x9D.toByte(), 0x8B.toByte(), 0xE5.toByte(), 0xEA.toByte(),
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
                "Playable::OnBioticSelectBegin() no longer calls Weapon::OnAbort() with a 'current " +
                    "weapon' pointer that doesn't look like a real heap object (< 0xC0000000, covering " +
                    "NULL and small never-constructed sentinels alike) - that call is now silently " +
                    "skipped instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
