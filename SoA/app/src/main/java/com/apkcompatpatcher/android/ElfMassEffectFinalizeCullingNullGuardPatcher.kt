package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `GameWorld::FinalizeCulling()`
 * against the exact same NULL `Culling*` (`this->m_culling`, offset 0x41c/1052) that fix #92
 * (`ElfMassEffectUpdateViewCullingNullGuardPatcher`) guards in its immediate sibling function -
 * this is fix #93, a PROACTIVE companion, not yet itself seen on a real-device crash.
 *
 * ## Background
 *
 * `GameWorld::FinalizeCulling()` sits immediately before `UpdateViewCulling()` in the library
 * (0x002FD318 vs. 0x002FD334) and reads the exact same `this->m_culling` field the exact same
 * unguarded way, just with only one virtual dispatch instead of two:
 *
 *   2fd318: push {r4, lr}
 *   2fd31c: ldr  r3, [r0, #1052]  ; r3 = this->m_culling (same field fix #92 guards)
 *   2fd320: mov  r0, r3
 *   2fd324: ldr  r3, [r3]         ; r3 = m_culling's vtable pointer - THIS instruction, unguarded
 *   2fd328: mov  lr, pc
 *   2fd32c: ldr  pc, [r3, #56]    ; virtual dispatch (vtable+0x38)
 *   2fd330: pop  {r4, pc}
 *
 * Same class (`Culling`, base of `PortalCulling`/`QuadtreeCulling`), same member, same "not always
 * constructed by the time a per-frame caller assumes it is" shape fix #92 already confirmed via a
 * real crash log one function over - this project's established practice (the fixes #70-74/#82-88
 * GameObject-accessor batches) is to guard every sibling instance of an already-confirmed bug shape
 * together rather than wait for each one to crash on a real device independently, since both
 * functions are reached from the same per-frame update path and either could plausibly be hit first
 * depending on frame timing/level state.
 *
 * ## The fix
 *
 * Identical shape to fix #92, scaled down to this function's single dispatch: the one unguarded read
 * (`ldr r3,[r3]` at 0x002FD324) is replaced with a branch to a small cave:
 *
 *   cmp  r3, #0
 *   beq  0x002FD330      ; NULL: skip straight to the function's own epilogue
 *   ldr  r3, [r3]        ; non-NULL: the exact original, untouched instruction
 *   b    0x002FD328      ; resume the function's own next instruction (mov lr,pc)
 *
 * Cave lives at `0x0098DC18`, the NEXT 16 bytes in the same fix #90/#92 `.rodata` gap
 * (`0x0098DBF8`, 296 bytes total - fix #90 claimed the first 16, fix #92 the next 16, this fix the
 * 16 after that; still ~264 bytes free afterward) - re-confirmed zero/relocation-free before being
 * claimed, same as fix #92. Objdump/capstone-confirmed and `qemu-arm` harness-verified with two
 * vectors against the real, patched `libMassEffect.so` (`tests/fix93_invalid.cfg`,
 * `tests/fix93_valid.cfg`): `r3=0` reaches the function's own epilogue (0x2FD330) with no fault;
 * `r3` pointing at real memory reaches the function's own next instruction (0x2FD328) with `r3`
 * holding the exact value read from `[r3]`, confirming the guarded read still executes correctly on
 * the valid path.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at both target regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectFinalizeCullingNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x002FD324L
    private const val TARGET_VADDR_CAVE = 0x0098DC18L

    // ldr r3, [r3]  (m_culling's vtable pointer, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // b 0x98dc18
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x3B, 0x42, 0x1A, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3,#0 / beq 0x2fd330 (skip the dispatch) / ldr r3,[r3] (original) / b 0x2fd328 (resume)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0xC3.toByte(), 0xBD.toByte(), 0xE5.toByte(), 0x0A,
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0xBF.toByte(), 0xBD.toByte(), 0xE5.toByte(), 0xEA.toByte()
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
                "GameWorld::FinalizeCulling() no longer dereferences a NULL m_culling pointer (same field " +
                    "fix #92 guards in its sibling UpdateViewCulling(), same failure shape) - it now safely " +
                    "skips the virtual dispatch through it and returns instead of crashing. Proactive - not " +
                    "yet itself seen on a real device."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
