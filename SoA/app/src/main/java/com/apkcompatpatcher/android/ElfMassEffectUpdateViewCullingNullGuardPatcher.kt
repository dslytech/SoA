package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `GameWorld::UpdateViewCulling()`
 * against a NULL `Culling*` object (the game's culling subsystem - `PortalCulling`/`QuadtreeCulling`,
 * both subclasses of `Culling`, stored as a raw pointer at offset 0x41c/1052 inside `GameWorld`) not
 * having been constructed yet. This is fix #92 - the FIRST crash found past the entire FreeType saga
 * (fixes #27-31, #80, #91, the fallback-font-path fix), on a real-device 0.92 test.
 *
 * ## Background
 *
 * Found from a real-device crash log (Hihonor tablet, Android 16, 2026-09-15), on the first 0.92
 * build carrying both the `glyph->library` guard and the fallback-font-path fix - both worked, the
 * game got all the way past loading-screen text rendering into the actual gameplay update loop:
 *
 *   Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0, tid (GLThread)
 *   #00 GameWorld::UpdateViewCulling()+20
 *   #01 GameWorld::OnUpdate(int)+436
 *   #02 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
 *   #03 LayerGameWorld::OnEvent(im::Event const&)+1564
 *   #04 im::LayerStack::OnEvent(im::Event const&)+124
 *   #05 Application::OnUpdate()+972
 *   ... (im::M3GApplication::OnRunLoopTick() -> the game's normal per-frame update tick)
 *
 * Objdump-disassembling `GameWorld::UpdateViewCulling()` (0x002FD334):
 *
 *   2fd334: push {r4, lr}
 *   2fd338: ldr  r3, [r0, #1052]  ; r3 = this->m_culling (a Culling* - PortalCulling/QuadtreeCulling)
 *   2fd33c: mov  r4, r0
 *   2fd340: add  r1, r0, #52     ; r1 = &this->someFrustumOrViewParam (arg for the first virtual call)
 *   2fd344: mov  r0, r3          ; r0 = m_culling
 *   2fd348: ldr  r3, [r3]        ; r3 = m_culling's vtable pointer - THIS instruction, unguarded
 *   2fd34c: mov  lr, pc
 *   2fd350: ldr  pc, [r3, #40]   ; virtual dispatch #1 (vtable+0x28) - e.g. Culling::Cull(...)
 *   2fd354: ldr  r3, [r4, #1052] ; reload the same field
 *   2fd358: mov  r0, r3
 *   2fd35c: ldr  r3, [r3]        ; vtable pointer again - same object, can't differ from the first read
 *   2fd360: mov  lr, pc
 *   2fd364: ldr  pc, [r3, #60]   ; virtual dispatch #2 (vtable+0x3c)
 *   2fd368: pop  {r4, pc}
 *
 * The crash register dump confirms r0/r3/ip are all `0x0` at the fault (`ldr r3,[r3]` reading offset
 * 0 from address 0) - `this->m_culling` itself is NULL, not a wild/garbage pointer. `GameWorld`'s
 * culling subsystem plainly isn't guaranteed to be constructed by the time `UpdateViewCulling()` is
 * called every frame - consistent with this whole project's long-running "some GameWorld/GameObject
 * sub-system pointer isn't always populated when a caller assumes it always is" pattern (the fixes
 * #67-#74/#82-#88 GameObject-accessor family, `Hud::InitWorld()`'s widget guards, etc.), just on a
 * different member this time.
 *
 * ## The fix
 *
 * `UpdateViewCulling()`'s return value is never used by any of its own callers (`GameWorld::OnUpdate`
 * calls it and moves straight on) and it performs two independent virtual dispatches through the same
 * object with no other side effects in between - so on a NULL `m_culling`, the cleanest and safest
 * guard is to skip BOTH dispatches entirely and go straight to the function's own existing epilogue
 * (`pop {r4,pc}`, 0x2FD368), exactly as if there were nothing to cull this frame. The one unguarded
 * read (`ldr r3,[r3]` at 0x002FD348) is replaced with a branch to a small cave:
 *
 *   cmp  r3, #0
 *   beq  0x002FD368      ; NULL: skip straight to the function's own epilogue
 *   ldr  r3, [r3]        ; non-NULL: the exact original, untouched instruction
 *   b    0x002FD34C      ; resume the function's own next instruction (mov lr,pc)
 *
 * Cave lives at `0x0098DC08`, the first 16 (of a still-unused ~280) bytes remaining in the same
 * genuinely spacious `.rodata` gap fix #90 already vetted (`0x0098DBF8`, 296 bytes, only fix #90's
 * own 16 were previously claimed) - re-confirmed here specifically byte-identical/zero and free of
 * any ELF relocation before being claimed. Objdump/capstone-confirmed (every branch resolves to
 * exactly the intended target) and `qemu-arm` harness-verified with two vectors against the real,
 * patched `libMassEffect.so` (`tests/fix92_invalid.cfg`, `tests/fix92_valid.cfg`): `r3=0` (NULL
 * culling object) reaches the function's own epilogue (0x2FD368) with no fault; `r3` pointing at real
 * memory reaches the function's own next instruction (0x2FD34C) with `r3` now holding the exact value
 * read from `[r3]`, confirming the guarded read still executes correctly on the valid path.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at both target regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectUpdateViewCullingNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x002FD348L
    private const val TARGET_VADDR_CAVE = 0x0098DC08L

    // ldr r3, [r3]  (m_culling's vtable pointer, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // b 0x98dc08
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x2E, 0x42, 0x1A, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3,#0 / beq 0x2fd368 (skip both dispatches) / ldrne-equiv ldr r3,[r3] / b 0x2fd34c (resume)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0xD5.toByte(), 0xBD.toByte(), 0xE5.toByte(), 0x0A,
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0xCC.toByte(), 0xBD.toByte(), 0xE5.toByte(), 0xEA.toByte()
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
                "GameWorld::UpdateViewCulling() no longer dereferences a NULL m_culling pointer (fault " +
                    "addr 0x0, confirmed via a real-device crash the first frame after loading a level) - " +
                    "it now safely skips both virtual dispatches through it and returns, exactly as if " +
                    "there were nothing to cull this frame, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
