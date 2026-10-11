package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards a direct virtual dispatch inside
 * `GameWorld::OnUpdate(int)` itself against a NULL/never-constructed sub-object pointer at `this`+76
 * (`0x4c` - a DIFFERENT field from the `this`+452 one fixes #94/#95/#96 guard). This is fix #97, found
 * on a real-device 0.97 test.
 *
 * ## Background
 *
 * 0.97 (fix #96) real-device tested clean - the `AllowHudElement` block didn't crash (consistent with
 * it being a preventative guard, not a confirmed-necessary one) - and the game got further along again
 * before hitting a new, ordinary `SEGV_MAPERR`:
 *
 *   signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 *   Cause: null pointer dereference
 *   #00 GameWorld::OnUpdate(int)+504
 *   #01 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
 *   ... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
 *
 * Unlike fixes #92-#96 (each inside a CALLEE reached from `GameWorld::OnUpdate`), this crash is inline
 * in `OnUpdate(int)` itself (`0x0030DA38`), between the `VFX::Update`/`GameWorld::UpdateTriggers` calls
 * and `GameWorld::UpdateEnvironments`:
 *
 *   30dc1c: bl   GameWorld::UpdateTriggers(int)
 *   30dc20: ldr  r3, [r4, #76]    ; r3 = this->[76]           (a GameWorld sub-object, not yet named -
 *                                 ;                             confirmed DIFFERENT from the this->[452]
 *                                 ;                             field fixes #94/#95/#96 already guard)
 *   30dc24: mov  r1, r5
 *   30dc28: add  r8, r4, #1328
 *   30dc2c: mov  r0, r3
 *   30dc30: ldr  r3, [r3]         ; r3 = sub-object's vtable pointer - THIS instruction, unguarded
 *   30dc34: mov  lr, pc
 *   30dc38: ldr  pc, [r3, #20]    ; virtual dispatch (vtable+0x14)
 *   30dc3c: mov  r0, r4           ; next call's setup - r0/r1 overwritten immediately, so the dispatch's
 *   30dc40: mov  r1, r5           ; return value is discarded
 *   30dc44: bl   GameWorld::UpdateEnvironments(int)
 *
 * The crash register dump confirms `r0`/`r2`/`r3` are all `0x0` at the fault (`Cause: null pointer
 * dereference`) - `this`+76 is NULL, the same "per-frame update-tick subsystem pointer not always
 * constructed" shape as every fix from #92 onward, just a different field and, for the first time,
 * inline in `OnUpdate` itself rather than inside a callee.
 *
 * ## The fix
 *
 * The instruction right after the dispatch (`mov r0,r4`) overwrites `r0` unconditionally before the
 * next call, confirming the dispatch's return value is discarded - so exactly like fix #94, the
 * cleanest guard skips just the dispatch on a bad pointer and resumes at that next instruction
 * (`0x30DC3C`), exactly as if this per-frame subsystem update had nothing to do this frame:
 *
 *   cmp  r3, #0xC0000000
 *   blo  0x30DC3C        ; doesn't look like a real heap pointer (covers NULL): skip the dispatch
 *   ldr  r3, [r3]        ; real pointer: the exact original, untouched instruction
 *   b    0x30DC34        ; branch back to the function's OWN untouched 'mov lr,pc' - no lr-relocation
 *                         ; arithmetic needed, the real call's return address comes out correct for free
 *
 * `>= 0xC0000000` is this project's established whitelist convention (see fix #46 Revision 2's doc
 * comment).
 *
 * Cave lives at `0x0098DC58`, the next 16 bytes in the same `.rodata` gap fix #90 vetted
 * (`0x0098DBF8`, 296 bytes; fixes #90/#92/#93/#94/#95/#96 have claimed the first 96) - re-confirmed
 * here specifically byte-identical/zero and free of any ELF relocation before being claimed.
 * Objdump/capstone-confirmed (every branch resolves to exactly the intended target) and `qemu-arm`
 * harness-verified with two vectors (`tests/fix97_invalid.cfg`, `tests/fix97_valid.cfg`): `r3=0` lands
 * directly on `0x30dc3c` (skipping the dispatch) with no fault; `r3` pointing at real memory replays
 * the guarded read and branches back into the function's own next instruction (`0x30dc34`) with `r3`
 * holding the exact value read from `[r3]`, confirming the valid path is untouched.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at both target regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectOnUpdateSubsystemDispatchNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0030DC30L
    private const val TARGET_VADDR_CAVE = 0x0098DC58L

    // ldr r3, [r3]  (this->[76]'s vtable pointer, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // bl 0x98dc58
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x08, 0x00, 0x1A, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3,#0xC0000000 / blo 0x30dc3c (skip the dispatch) / ldr r3,[r3] (replay) /
    // b 0x30dc34 (branch back into the function's own untouched 'mov lr,pc')
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x53, 0xE3.toByte(),
        0xF6.toByte(), 0xFF.toByte(), 0xE5.toByte(), 0x3A,
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0xF2.toByte(), 0xFF.toByte(), 0xE5.toByte(), 0xEA.toByte()
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
                "GameWorld::OnUpdate(int) no longer dereferences a never-constructed (NULL, or anything " +
                    "that doesn't look like a real heap pointer, < 0xC0000000) sub-object pointer at " +
                    "this+76 (fault addr 0x0, confirmed via a real-device crash in the normal per-frame " +
                    "update tick) - it now safely skips the virtual dispatch through it and resumes with " +
                    "the rest of the function, exactly as if this update step had nothing to do this " +
                    "frame, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
