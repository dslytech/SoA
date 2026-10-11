package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `GameWorld::UpdateObjectsPostCamera(int)`
 * against a NULL sub-object pointer (a field at `this`+452/0x1c4) not having been constructed yet. This
 * is fix #94, the FIRST crash found past the entire `Hud::StateTransition()` wild-pointer saga (fixes
 * #45-#51), on a real-device 0.94 test.
 *
 * ## Background
 *
 * Found from a real-device crash log (Hihonor tablet, Android 16, 2026-09-16), on the 0.94 build
 * carrying the widened `Hud::StateTransition()` begin-pointer guard (fix #46, revision 2) - that fix
 * worked, the `0x72757401`/`SEGV_ACCERR`/Thread-10 crash didn't recur, and the game got further into
 * its normal per-frame update tick before hitting a completely new, ordinary `SEGV_MAPERR`:
 *
 *   signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 *   Cause: null pointer dereference
 *   #00 GameWorld::UpdateObjectsPostCamera(int)+132
 *   #01 GameWorld::OnUpdate(int)+448
 *   #02 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
 *   #03 LayerGameWorld::OnEvent(im::Event const&)+1564
 *   #04 im::LayerStack::OnEvent(im::Event const&)+124
 *   #05 Application::OnUpdate()+972
 *   ... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
 *
 * Objdump-disassembling `GameWorld::UpdateObjectsPostCamera(int)` (`0x0030350C`) around the fault:
 *
 *   303580: mov  r0, r7
 *   303584: bl   GameWorld::FlushObjects()
 *   303588: ldr  r3, [r7, #452]   ; r3 = this->[452]  (some GameWorld sub-object, not yet named)
 *   30358c: mov  r0, r3
 *   303590: ldr  r3, [r3]         ; r3 = sub-object's vtable pointer - THIS instruction, unguarded
 *   303594: mov  lr, pc
 *   303598: ldr  pc, [r3, #244]   ; virtual dispatch (vtable+0xf4)
 *   30359c: vldr s14, [r7, #968]  ; unrelated floating-point work, doesn't touch r0/r3 - return value unused
 *
 * The crash register dump confirms `r0`/`r2`/`r3`/`ip` are all `0x0` at the fault (`Cause: null pointer
 * dereference`, explicit this time) - the sub-object at `this`+452 is NULL, not a wild pointer. Same
 * "some GameWorld/GameObject sub-system pointer isn't always constructed by the time a per-frame caller
 * assumes it is" shape as fixes #92/#93 (`m_culling`) and the whole GameObject-accessor family, just on
 * a different, not-yet-named member.
 *
 * ## The fix
 *
 * The instruction right after the virtual dispatch (`vldr s14, [r7, #968]`) doesn't touch `r0` or `r3`,
 * confirming the call's return value is discarded - so on a NULL sub-object, the cleanest and safest
 * guard is to skip the dispatch entirely and resume at that next instruction (`0x30359C`), exactly as
 * if this update step had nothing to do this frame. The one unguarded read (`ldr r3,[r3]` at `0x303590`)
 * is replaced with a branch to a small cave:
 *
 *   cmp  r3, #0xC0000000
 *   blo  0x30359C        ; doesn't look like a real heap pointer (covers NULL): skip the dispatch
 *   ldr  r3, [r3]        ; real pointer: the exact original, untouched instruction
 *   b    0x303594        ; branch back to the function's OWN untouched 'mov lr,pc' - no lr-relocation
 *                         ; arithmetic needed, the real call's return address comes out correct for free
 *
 * The `>= 0xC0000000` whitelist (not just `== 0`) is deliberate: fix #46's `Hud::StateTransition()`
 * begin-pointer guard was JUST widened from a `<= 0x1000`-only check after a real-device 0.93 test
 * found a "looks plausible but isn't" wild pointer (`0x72757401`) slipping through a narrower band (see
 * that patcher's Revision 2 doc comment) - adopting the project's established whitelist convention here
 * from the start, rather than waiting for the same lesson to repeat on this call site too.
 *
 * Cave lives at `0x0098DC28`, the next 16 bytes in the same genuinely spacious `.rodata` gap fix #90
 * vetted (`0x0098DBF8`, 296 bytes; fix #90/#92/#93 have claimed the first 48) - re-confirmed here
 * specifically byte-identical/zero and free of any ELF relocation before being claimed. Objdump/capstone
 * -confirmed (every branch resolves to exactly the intended target) and `qemu-arm` harness-verified with
 * two vectors (`tests/fix94b_invalid.cfg`, `tests/fix94b_valid.cfg`): `r3=0` lands directly on `0x30359c`
 * with no fault; `r3` pointing at a real vtable (with a real function pointer at `+244`) replays the
 * guarded read, branches back into the function's own `mov lr,pc`/virtual dispatch unmodified, and the
 * callee's own return lands back on `0x30359c` too - `r3` ends up holding the exact vtable pointer read
 * from `[r3]`, and `lr` is the function's own correct return address throughout, confirming the whole
 * round trip (not just the guard) behaves identically to the original on the valid path.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at both target regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectUpdateObjectsPostCameraNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00303590L
    private const val TARGET_VADDR_CAVE = 0x0098DC28L

    // ldr r3, [r3]  (sub-object's vtable pointer, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // bl 0x98dc28
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xA4.toByte(), 0x29, 0x1A, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3,#0xC0000000 / blo 0x30359c (skip the dispatch) / ldr r3,[r3] (replay) /
    // b 0x303594 (branch back into the function's own untouched 'mov lr,pc')
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x53, 0xE3.toByte(),
        0x5A, 0xD6.toByte(), 0xE5.toByte(), 0x3A,
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0x56, 0xD6.toByte(), 0xE5.toByte(), 0xEA.toByte()
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
                "GameWorld::UpdateObjectsPostCamera(int) no longer dereferences a never-constructed " +
                    "(NULL, or anything that doesn't look like a real heap pointer, < 0xC0000000) " +
                    "sub-object pointer at this+452 (fault addr 0x0, confirmed via a real-device crash " +
                    "in the normal per-frame update tick) - it now safely skips the virtual dispatch " +
                    "through it and resumes with the rest of the function, exactly as if this update " +
                    "step had nothing to do this frame, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
