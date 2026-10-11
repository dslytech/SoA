package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX for Mass Effect: Infiltrator's TWENTY-FIRST native crash - a NEW site in
 * `Hud::StateTransition(Hud::State)`, one call EARLIER than fix #45's own guard (that one
 * protects an individual vector ELEMENT; this one protects the vector's BEGIN pointer itself).
 *
 * ## How this was found
 *
 * After 0.47 shipped, the user proposed a controlled test to finally settle an old, never-quite
 * -resolved question from this project's early asset-loading investigation (Round 30/31): whether
 * the game's behavior differs with vs. without the ~900MB of downloaded level content present at
 * `Android/data/com.ea.games.meinfiltrator_gamepad/`. Two back-to-back real-device runs:
 * - **WITH** the data folder present: crashed at `Hud::StateTransition(Hud::State)+704`
 *   (`0x11461c`), fault addr exactly `0x0`.
 * - **WITHOUT** the data folder present: the FIRST run crashed at the exact same address
 *   (`0x11461c`, same fault addr `0x0`, even the same PID/timestamp - the two logs overlapped the
 *   identical crash event), and a SECOND run (relaunched under the same no-data condition) got
 *   further before crashing inside fix #45's own cave on a wild non-null pointer (`0xff100e00`,
 *   `SEGV_ACCERR`) - a materially different, harder problem tracked separately (see the README's
 *   "Round 46" note; not fixed by this patch).
 *
 * The controlled test's real finding: presence or absence of the downloaded level content makes
 * **no difference** to this crash. That settles the old Round 30/31 mystery in the negative - it
 * was never about missing external files reaching this code path.
 *
 * ## Root cause
 *
 * One call earlier than fix #45's own guarded read, `Hud::StateTransition()` computes its
 * listener/layer vector's bounds and only skips the loop if the byte-length looks too small:
 * ```
 * ldr  r3, [r4, #4]      ; r3 = this->[4]           (the vector's owning object)
 * ldr  r1, [r3, #328]    ; r1 = end
 * ldr  r2, [r3, #324]    ; r2 = begin
 * rsb  r3, r2, r1         ; r3 = end - begin (byte length)
 * cmp  r3, #11
 * ble  <skip loop>        ; only skips if byte length looks <= 11 (less than one element)
 * ...
 * ldr  r3, [r2, r6]      ; r3 = *(begin + byteOffset)     <-- CRASH: begin (r2) is NULL
 * ```
 * On this device, `begin` (`r2`) was exactly `NULL` while `end` (`r1`) held some other, non-null
 * (and, being a real address, numerically large) leftover value - so `end - begin` came out well
 * above the `11`-byte threshold and the size check passed, even though the container itself was
 * never actually constructed. This is the same overall "vector never populated" story as fixes
 * #42-#45 (this per-frame listener/layer container, one level up from the individual NULL/garbage
 * elements fix #45 already guards), just caught one step earlier: here the CONTAINER's own begin
 * pointer is invalid, not merely one element inside an otherwise-valid container.
 *
 * ## The fix: guard the container's begin pointer, reuse the existing "empty" exit
 *
 * The function already has an exit for "nothing to iterate" - the `ble` at the top of this same
 * block, landing at `0x114668`. If `begin` itself is implausibly small to be a real pointer (the
 * same `<= 0x1000` threshold fixes #42-#45 already use project-wide), the fix routes straight to
 * that same `0x114668` exit instead of entering the loop at all - exactly as if the size check
 * itself had already caught this case.
 *
 * - **Callsite** (`0x11461C`, 4 bytes): `ldr r3,[r2,r6]` becomes `bl 0x99599C`.
 * - **Cave** (`0x99599C`, 16 bytes, in previously-unused zero `.rodata` padding right after fix
 *   #45's own cave): `cmp r2,#0x1000` / `bls 0x114668` / `ldr r3,[r2,r6]` / `bx lr`.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` - explicitly checking the branch's *resolved target address* (`0x114668`, not a local
 * cave offset - the exact class of mistake that slipped into 0.46, see fix #45's own doc comment)
 * - plus a whole-file byte-diff and a combined re-verification alongside fixes #38-#45's own
 * adjacent trampolines confirming all nine coexist with no interference.
 *
 * ## Revision 2 (0.94-stateTransitionBeginGuardWidened): the `<= 0x1000` band wasn't enough either
 *
 * 0.93 (fixes #92/#93, GameWorld culling) shipped and real-device-tested clean past the entire
 * culling crash chain - but a NEW crash turned up on a background thread ("Thread-10"), this time
 * `SEGV_ACCERR` (not the usual `SEGV_MAPERR`) at fault addr `0x72757401`. The logcat excerpt alone
 * only gave 1 backtrace frame with no way to disassemble the actual fault; the user pulled a full
 * `adb bugreport`, whose embedded tombstone (`tombstone_01`, not just the logcat mirror) included
 * the "memory near pc"/"memory near lr" hex dumps logcat always drops. That was the missing piece:
 * ```
 * r2  72757401   r6  00000000   pc  7f2169a4 (static 0x9959a4, inside THIS cave, +8)
 * memory near pc: ... e7923006  ldr r3, [r2, r6]  <- the exact faulting instruction
 * ```
 * This is fix #46's own cave replaying its displaced `ldr r3,[r2,r6]` - i.e. `begin` (`r2`) was
 * `0x72757401` this time, comfortably above the `<= 0x1000` floor the original guard checks, so it
 * fell straight through to the real load. The memory map in the same tombstone confirmed
 * `0x72757401` falls inside `[anon:dalvik-non moving space]` mapped `---` (no access at all) - an
 * ART/Dalvik heap region reserved but currently unmapped, not a real `Hud`-owned allocation. Same
 * underlying story fix #46's own doc comment already named ("container never constructed, begin/
 * end hold whatever leftover bits were there") - just a different leftover bit pattern than the
 * `NULL` case this guard already caught.
 *
 * This project already solved the general version of "a magnitude-only blacklist band doesn't
 * reliably catch every flavor of garbage pointer" back in Round 49 (fix #48, `0x73616c62` - a
 * string literal's bytes handed to a call as if it were a `Playable*`, which cleared both of fix
 * #45's `<= 0x1000` / `>= 0xF0000000` bands too). The fix there flipped to a whitelist: every real
 * heap object pointer this whole project has ever seen in a crash dump lands in `0xd8000000`-
 * `0xdf000000`, so `>= 0xC0000000` (with headroom) accepts every genuine pointer and rejects NULL,
 * small sentinels, string literals, and this `0x72757401` ART-heap-gap value alike, all in one
 * check - no growing list of ad-hoc bands. This revision applies that same whitelist here: the
 * cave's `cmp r2,#0x1000` / `bls` becomes `cmp r2,#0xC0000000` / `blo` (branch on *unsigned less
 * than*, matching `bls`'s original "reject anything not looking like a real pointer" intent) -
 * same 16 bytes, same branch target (`0x114668`, re-confirmed by objdump), same trailing
 * `ldr r3,[r2,r6]` / `bx lr` untouched. For a real (`>= 0xC0000000`) begin pointer, behavior is
 * unchanged from Revision 1: identical load, identical return address.
 *
 * Verified with capstone (both old and new cave bytes decoded and diffed instruction-by-instruction
 * - only the `cmp` immediate and the branch condition code change, nothing else) and with the
 * qemu-arm harness: `tests/fix46rev2_invalid.cfg` (`r2=0x72757401` correctly lands on the
 * `0x114668` empty-exit PAD, never reaching the load) and `tests/fix46rev2_valid.cfg`
 * (`r2=0xd8000000` correctly falls through, executes the real `ldr` - `r3` comes back holding the
 * probe value written to that address - and returns via `bx lr`), both PASS.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original (pristine, never-patched)
 * bytes at both regions match byte-for-byte what this was built against - any mismatch leaves the
 * file completely untouched; both regions are applied together or not at all. For a real
 * (`>= 0xC0000000`) begin pointer, behavior is provably identical to the original - same load,
 * same return address.
 */
object ElfMassEffectHudStateTransitionBeginNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0011461CL
    private const val TARGET_VADDR_CAVE = 0x0099599CL

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x06, 0x30, 0x92.toByte(), 0xE7.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xDE.toByte(), 0x04, 0x22, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // Revision 2 (0.94): cmp r2,#0xC0000000 / blo 0x114668 / ldr r3,[r2,r6] / bx lr - same 16
    // bytes, same branch target, only the threshold (0x1000 -> 0xC0000000) and the branch
    // condition (bls -> blo, still "reject anything not looking like a real pointer") changed.
    // Capstone-confirmed byte-for-byte against the intended decode; qemu-arm harness-verified
    // (tests/fix46rev2_invalid.cfg, tests/fix46rev2_valid.cfg, both PASS).
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x52, 0xE3.toByte(),
        0x30, 0xFB.toByte(), 0xDD.toByte(), 0x3A,
        0x06, 0x30, 0x92.toByte(), 0xE7.toByte(),
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
                "Hud::StateTransition() no longer dereferences a never-constructed begin pointer " +
                    "(anything that doesn't look like a real heap pointer, < 0xC0000000 - covering " +
                    "NULL, small sentinels, and ART-heap-gap wild values like 0x72757401 alike) for " +
                    "its per-frame listener/layer vector - when the container itself looks absent, " +
                    "the whole loop is now skipped via the function's own existing 'nothing to " +
                    "iterate' exit, instead of crashing on the very first element read."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
