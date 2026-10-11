package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's TWENTY-EIGHTH native crash - reached now that fix #49
 * (`Weapon::OnAbort()` call guard) let `Playable::OnBioticSelectBegin()` get past its third call
 * and into a type-check block this project hadn't reached before: two virtual dispatches through
 * `this`'s OWN vtable pointer, each guarded by a `strcmp()` against the returned type name.
 *
 * ## How this was found
 *
 * The 0.54 test (fix #49 + every other fix present) got past `Weapon::OnAbort()` cleanly and
 * crashed one instruction further into the same function:
 * ```
 * Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x0000014e
 * r0  dba88014  r1  00000001  r2  00004001  r3  00000096
 * r4  dba88014  r5  dd182e7c  r6  00022b3c  r7  00002e45
 * #00 pc 001fc7d0  libMassEffect.so (Playable::OnBioticSelectBegin()+44)
 * #01 pc 001147e8  libMassEffect.so (Hud::StateTransition(Hud::State)+1164)
 * #02 pc 00114cd0  libMassEffect.so (Hud::InitWorld(GameWorld*)+712)
 * ```
 * Unlike every prior crash in this saga, `r4` (`this`) is a perfectly plausible heap pointer
 * (`0xdba88014`, squarely in this device's observed `0xd8000000`-`0xdf000000` heap range) - it's
 * `this`'s OWN vtable pointer, loaded fresh into `r3` right before the dispatch, that's garbage
 * (`r3 = 0x96`). `0x96 + 0xB8 (184) = 0x14E`, matching the fault address exactly - so the object
 * is real, but whatever it's pointing at doesn't have a valid vtable at offset 0 (either a
 * partially-constructed object, or a raw block that was never a `Playable`-hierarchy instance at
 * all).
 *
 * ## Root cause and why fix #48/#49's technique doesn't apply here
 *
 * Disassembly of the surrounding block:
 * ```
 * ldr  r3, [r4]                       ; r3 = this->vtable  (GARBAGE: 0x96)
 * mov  r0, r4
 * mov  lr, pc
 * ldr  pc, [r3, #184]                 ; <-- CRASH: virtual call through garbage vtable
 * ldr  r1, [pc, #120]                 ; r1 = a type-name string literal
 * add  r1, pc, r1
 * bl   strcmp@plt                     ; strcmp(<call's return value>, r1)  <-- return value IS used
 * cmp  r0, #0
 * bne  0x1fc800                       ; mismatch -> skip to the block's own fallback continuation
 * ldr  r1, [pc, #104]                 ; (fallthrough: a SECOND type-name string)
 * ldr  r3, [r4]                       ; SECOND virtual dispatch, same (garbage) vtable pointer
 * mov  r0, r4
 * add  r1, pc, r1
 * mov  lr, pc
 * ldr  pc, [r3, #180]                 ; second virtual call, same crash risk
 * 0x1fc800: ldr r3, [r4, #1412]       ; <- natural continuation point both paths above merge into
 * ...
 * ```
 * This is a type-check idiom: call a virtual "get type name" method and `strcmp()` the result
 * against a literal to decide which special-case branch to take. Fix #48 and #49 worked by simply
 * *skipping* an unconditional call whose return value was discarded - that pattern doesn't apply
 * here, because the call's return value is immediately fed into `strcmp()`. Skipping only the
 * call (like fix #48/#49 do) would leave `r0` holding whatever garbage was last in it and pass
 * that straight to `strcmp()` as a "string" pointer - trading one native crash for another,
 * possibly harder to diagnose, native crash inside `strcmp()`.
 *
 * The second virtual dispatch reloads the exact same `this->vtable` (`ldr r3,[r4]`) that was just
 * found garbage, so if the first dispatch isn't safe, the second one won't be either - both must
 * be guarded together.
 *
 * ## The fix: validate the vtable pointer itself, skip the WHOLE type-check block on failure
 *
 * Reuses fix #45 Round 6's proven technique: a *structural* check against `libMassEffect.so`'s
 * actual `.data.rel.ro` section bounds (`0xA372A0`-`0xA80D8C`, widened to the nearest
 * ARM-immediate-encodable values `0xA30000`/`0xA90000`) is where the compiler places every real
 * vtable and nowhere else - this is what the original crash's `r3 = 0x96` fails, and what every
 * genuine vtable pointer in this library passes.
 *
 * - **Callsite** (`0x1FC7CC`, 4 bytes): the `mov lr, pc` immediately before the first vtable
 *   dispatch becomes `bl 0x99CFBC` (a newly-found free 28-byte `.rodata` gap - see below). The
 *   `ldr r3,[r4]` and `mov r0,r4` before it, and the `ldr pc,[r3,#184]` dispatch itself, are left
 *   completely untouched.
 * - **Cave** (`0x99CFBC`, 28 bytes):
 *   `cmp r3,#0xA30000` / `blo invalid` / `cmp r3,#0xA90000` / `bhs invalid` / `add lr,lr,#4` /
 *   `b 0x1fc7d0` (valid: re-executes the untouched `ldr pc,[r3,#184]` dispatch, with `lr` computed
 *   to equal exactly what the replaced `mov lr,pc` would have produced - `mov lr,pc` is
 *   PC-relative and can't be copied verbatim into a cave at a different address, so this computes
 *   the same value arithmetically instead: on `bl` entry, `lr` already equals the untouched
 *   dispatch instruction's own address (`0x1fc7d0`), so `+4` gives `0x1fc7d4`, identical to what
 *   `mov lr,pc` at `0x1fc7cc` would have set) / `invalid: b 0x1fc800` (skips BOTH virtual
 *   dispatches and the `strcmp()` between them entirely, landing directly on the block's own
 *   natural "neither special type" merge point - never calling `strcmp()` with a garbage/stale
 *   `r0`).
 *
 * ## Finding safe free space for the cave
 *
 * The shared `.rodata` trampoline block used by every earlier fix (`0x995914`-`0x9959F1`) is down
 * to 5 free bytes - not enough for this 28-byte cave. A systematic scan of the entire `.rodata`
 * section (`0x94F690`-`0x9FB000`) for zero-byte runs of at least 28 bytes, cross-referenced
 * against the library's full sized symbol table (`nm -C -S --defined-only`) to exclude every run
 * that falls inside a named symbol's declared range (real zero-valued data - crypto padding
 * buffers, lookup tables - not unused space), found exactly one candidate: a 29-byte gap at
 * `0x99CFBB`-`0x99CFD8`, sitting immediately after the 3-byte string literal `"ID3"` (symbol
 * `ID3V2_TAG_`, ends `0x99CFBA`) and immediately before an unrelated embedded debug path string
 * (`"D:\EAMT\masseffectinfiltrator_eamt\Packages\EAOS..."`, no owning symbol - an anonymous
 * string literal, same as many others throughout this section). Cross-checked against
 * `readelf -r` that no relocation's offset or target falls anywhere in this range. The cave itself
 * is placed at `0x99CFBC` (the first 4-byte-ALIGNED address within that gap - ARM instructions
 * require 4-byte alignment, and the gap's start address `0x99CFBB` is not itself aligned), using
 * 28 of the 29 free bytes.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking both branches' resolved target addresses (`b` -> `0x1fc7d0` and `b` -> `0x1fc800`,
 * both confirmed exactly) and the callsite `bl`'s resolved target (`0x99cfbc`, confirmed exactly)
 * - plus a combined re-verification alongside every other fix's trampoline, confirming no overlap
 * with any other fix's cave (this is a brand new address, not a reused slot).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original bytes at both regions
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched; both regions are applied together or not at all. For a real `Playable`-hierarchy
 * object (whose vtable pointer, on every device this project has seen data from, always falls in
 * `.data.rel.ro`'s actual bounds), behavior is provably identical to the original: same two
 * dispatches, same `strcmp()` checks, same eventual continuation.
 */
object ElfMassEffectBioticSelectVtableGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001FC7CCL
    private const val TARGET_VADDR_CAVE = 0x0099CFBCL

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x0F, 0xE0.toByte(), 0xA0.toByte(), 0xE1.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xFA.toByte(), 0x81.toByte(), 0x1E, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(28)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0xA3.toByte(), 0x08, 0x53, 0xE3.toByte(),
        0x03, 0x00, 0x00, 0x3A,
        0xA9.toByte(), 0x08, 0x53, 0xE3.toByte(),
        0x01, 0x00, 0x00, 0x2A,
        0x04, 0xE0.toByte(), 0x8E.toByte(), 0xE2.toByte(),
        0xFE.toByte(), 0x7D, 0xE1.toByte(), 0xEA.toByte(),
        0x09, 0x7E, 0xE1.toByte(), 0xEA.toByte()
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
                "Playable::OnBioticSelectBegin() no longer performs its two type-check virtual " +
                    "dispatches through a vtable pointer that doesn't fall inside libMassEffect.so's " +
                    "actual .data.rel.ro bounds (~0xA30000-0xA90000) - both dispatches and the strcmp() " +
                    "checks between them are skipped together, straight to the block's own fallback " +
                    "continuation, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
