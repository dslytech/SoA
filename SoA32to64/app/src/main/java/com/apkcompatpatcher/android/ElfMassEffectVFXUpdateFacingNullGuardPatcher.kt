package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `VFX::Update(int)` against a
 * NULL `GameObject*` (a field at `this->[4]->[452]` - the exact same 0x1c4 offset fix #94 already
 * guards in `GameWorld::UpdateObjectsPostCamera(int)`, just reached through a different owning
 * object here) not having been constructed yet. This is fix #95, found on a real-device 0.95 test.
 *
 * ## Background
 *
 * Found from a real-device crash log (Hihonor tablet, Android 16, 2026-09-16), on the 0.95 build
 * carrying fix #94 - that fix worked (`GameWorld::UpdateObjectsPostCamera` didn't recur, the
 * backtrace shows `GameWorld::OnUpdate(int)+472` this time vs. `+448` before, i.e. genuinely further
 * into the same per-frame update), and the game hit a new, equally ordinary `SEGV_MAPERR`:
 *
 *   signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 *   Cause: null pointer dereference
 *   #00 VFX::Update(int)+260
 *   #01 GameWorld::OnUpdate(int)+472
 *   #02 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
 *   ... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
 *
 * Objdump-disassembling `VFX::Update(int)` (`0x002F0938`) around the fault (`0x002F0A3C`):
 *
 *   2f0a34: ldr  r3, [r4, #4]     ; r3 = this->[4]            (some owning object)
 *   2f0a38: ldr  r7, [r3, #452]   ; r7 = r3->[452]             (a GameObject* - same field fix #94 guards)
 *   2f0a3c: ldr  r3, [r7]         ; r3 = r7's vtable pointer - THIS instruction, unguarded
 *   2f0a40: mov  r0, r7
 *   2f0a44: mov  lr, pc
 *   2f0a48: ldr  pc, [r3, #244]   ; virtual dispatch (vtable+0xf4) - result copied to this+80
 *   2f0a4c-2f0a5c: (copies the dispatch's result into this->[80..92])
 *   2f0a5c: mov  r1, r7
 *   2f0a60: bl   GameObject::GetFacing() const   ; r7 used AGAIN, result copied to this->[96..108]
 *   2f0a74: mov  r1, r7
 *   2f0a78: bl   GameObject::GetUp() const        ; r7 used a THIRD time, result to this->[112..124]
 *   2f0a88: mov  r1, r7
 *   2f0a90: bl   GameObject::GetSide() const       ; r7 used a FOURTH time, result to this->[128..140]
 *   2f0aa0: ldr  r3, [r4, #152]   ; next section - an unrelated per-element list loop, doesn't touch
 *                                 ; r7 or this->[80..140] at all
 *
 * The crash register dump confirms `r0`/`r2`/`r7` are all `0x0` (`Cause: null pointer dereference`)
 * - the GameObject at `this->[4]->[452]` is NULL, same "sub-system pointer not always constructed
 * when a per-frame caller assumes it is" shape as fix #94 and the whole GameObject-accessor family.
 * This whole block computes VFX's local orientation basis (facing/up/side vectors, stored at
 * `this->[80..140]`) from that GameObject - and unlike fix #94's single dispatch, `r7` is used FOUR
 * separate times here (the vtable dispatch, then three direct calls into `GameObject::GetFacing()`/
 * `GetUp()`/`GetSide()`, none of which are known to already guard a NULL `this` themselves) before
 * the function moves on to an unrelated section at `0x2F0AA0`.
 *
 * ## The fix
 *
 * Guarding only the first read wouldn't be enough - `GetFacing()`/`GetUp()`/`GetSide()` would still
 * be called with a NULL `this` right afterward. Since none of `this->[80..140]` are touched again
 * before the function's own unrelated next section (`0x2F0AA0`, a per-element list loop that doesn't
 * reference the orientation-basis fields or `r7` at all), the safest guard is to skip the ENTIRE
 * orientation-basis block on a bad pointer - exactly as if this GameObject had nothing to
 * orient this VFX off of this frame (the fields simply keep whatever they already held, same
 * trade-off this project has already made for `GameWorld::UpdateViewCulling()` etc.):
 *
 *   cmp  r7, #0xC0000000
 *   blo  0x2F0AA0        ; doesn't look like a real heap pointer (covers NULL): skip the whole block
 *   ldr  r3, [r7]        ; real pointer: the exact original, untouched instruction
 *   b    0x2F0A40        ; branch back to the function's OWN untouched 'mov r0,r7' - no lr-relocation
 *                         ; arithmetic needed anywhere downstream
 *
 * `>= 0xC0000000` (not just `== 0`) is this project's established whitelist convention (see fix #46
 * Revision 2's doc comment for why a narrower band isn't safe to assume up front).
 *
 * Cave lives at `0x0098DC38`, the next 16 bytes in the same `.rodata` gap fix #90 vetted
 * (`0x0098DBF8`, 296 bytes; fixes #90/#92/#93/#94 have claimed the first 64) - re-confirmed here
 * specifically byte-identical/zero and free of any ELF relocation before being claimed.
 * Objdump/capstone-confirmed (every branch resolves to exactly the intended target) and `qemu-arm`
 * harness-verified with two vectors (`tests/fix95_invalid.cfg`, `tests/fix95_valid.cfg`): `r7=0`
 * lands directly on `0x2f0aa0` (skipping the whole orientation-fill block) with no fault; `r7`
 * pointing at real memory replays the guarded read and branches back into the function's own next
 * instruction (`0x2f0a40`) with `r3` holding the exact value read from `[r7]`, confirming the valid
 * path is untouched.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at both target regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectVFXUpdateFacingNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x002F0A3CL
    private const val TARGET_VADDR_CAVE = 0x0098DC38L

    // ldr r3, [r7]  (the GameObject's vtable pointer, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x97.toByte(), 0xE5.toByte()
    )
    // bl 0x98dc38
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x7D, 0x74, 0x1A, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r7,#0xC0000000 / blo 0x2f0aa0 (skip the whole orientation-fill block) / ldr r3,[r7]
    // (replay) / b 0x2f0a40 (branch back into the function's own untouched 'mov r0,r7')
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x57, 0xE3.toByte(),
        0x97.toByte(), 0x8B.toByte(), 0xE5.toByte(), 0x3A,
        0x00, 0x30, 0x97.toByte(), 0xE5.toByte(),
        0x7D, 0x8B.toByte(), 0xE5.toByte(), 0xEA.toByte()
    )

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

            val callsiteOff = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val caveOff = fileOffsetFor(TARGET_VADDR_CAVE)

            if (callsiteOff == null || caveOff == null ||
                callsiteOff + 4 > bytes.size || caveOff + 16 > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(callsiteOff, EXPECTED_ORIGINAL_CALLSITE) || !matches(caveOff, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version?) - left completely untouched")
            }

            fun write(off: Int, patch: ByteArray) { for (i in patch.indices) bytes[off + i] = patch[i] }
            write(callsiteOff, PATCHED_BYTES_CALLSITE)
            write(caveOff, PATCHED_BYTES_CAVE)

            return Result(
                true,
                "VFX::Update(int) no longer builds its per-frame orientation basis (facing/up/side " +
                    "vectors) from a never-constructed (NULL, or anything that doesn't look like a " +
                    "real heap pointer, < 0xC0000000) GameObject pointer (fault addr 0x0, confirmed " +
                    "via a real-device crash in the normal per-frame update tick) - the whole vtable " +
                    "dispatch plus GetFacing()/GetUp()/GetSide() block is now skipped together, " +
                    "resuming with the function's own next, unrelated section, instead of crashing " +
                    "on the very first read."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
