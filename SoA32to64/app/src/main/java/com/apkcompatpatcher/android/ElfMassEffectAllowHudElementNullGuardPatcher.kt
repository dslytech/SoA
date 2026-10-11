package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `GameWorld::AllowHudElement(im::Symbol const&)`
 * against a NULL sub-object pointer (a field at `this`+452/0x1c4 - the exact same field fixes #94/#95
 * already guard elsewhere) not having been constructed yet. This is fix #96, found via a systematic
 * static-disassembly scan of the whole `.text` section for the same confirmed bug shape, run after the
 * user asked for one proactive batch round (rather than continuing to chase crashes one real-device
 * report at a time) following fixes #94/#95 on 2026-09-16 - NOT yet observed as a real-device crash,
 * unlike every other fix in this family.
 *
 * ## Background
 *
 * Scanning `.text` for the pattern `ldr Ra,[Rb,#452]` followed by an unconditional dereference of `Ra`
 * (the exact shape fixes #94/#95 fixed) turned up a much larger cluster than either of those two single
 * call sites: `GameWorld::AllowHudElement(im::Symbol const&)` (`0x002FF4EC`) reads `this`+452 into `r6`'s
 * sub-object pointer FIVE separate times across one contiguous section, none of them guarded:
 *
 *   2ff624: ldr  r3, [r6, #452]   ; r3 = this->[452]           (the same GameObject* fixes #94/#95 guard)
 *   2ff628: mov  r0, r3
 *   2ff62c: ldr  r3, [r3]         ; vtable pointer - UNGUARDED, THIS instruction is the fix target
 *   2ff630: mov  lr, pc
 *   2ff634: ldr  pc, [r3, #184]   ; virtual dispatch (vtable+0xb8) -> result strcmp'd against a symbol name
 *   2ff638-2ff648: strcmp against symbol #1, beq 0x2ff5e0 (disallow) on match
 *   2ff64c: ldr  r3, [r6, #452]   ; SAME field, re-read (2nd use)
 *   2ff650-2ff670: identical vtable dispatch + strcmp against symbol #2
 *   2ff674: ldr  r3, [r6, #452]   ; SAME field, re-read (3rd use)
 *   2ff678-2ff698: identical vtable dispatch + strcmp against symbol #3
 *   2ff69c: ldr  r0, [r6, #452]   ; SAME field, re-read (4th use) - passed directly as `this`
 *   2ff6a0: bl   Playable::IsRunning() const   ; non-virtual call, no NULL check on `this` either
 *   2ff6ac: ldr  r0, [r6, #452]   ; SAME field, re-read (5th use)
 *   2ff6b0: bl   Playable::IsRolling() const   ; same
 *   2ff6bc: ldr  r4, [r5]         ; next section - unrelated (reloads the symbol-name argument),
 *                                 ; doesn't touch r6->[452] or r3 at all
 *
 * Confirmed (by reading back through the function from its start, `0x2FF4EC`) that nothing anywhere
 * before `0x2FF624` ever checks `this`+452 for NULL - unlike the false-positive candidate this same
 * scan also turned up in `Hud::OnUpdate(int)` (`0x00117BE8`), where an equivalent field-452 read is
 * reloaded into `r3` after an *earlier* instruction in the same block already confirmed the identical
 * value non-NULL via a different register (`r2`) - that site needed no fix and was left untouched.
 * Here, by contrast, every one of the five reads is genuinely unguarded on every path into this block.
 *
 * ## The fix
 *
 * Patching all five sites individually would be five call sites but is unnecessary: nothing in this
 * whole span writes to `this`+452 or otherwise invalidates the pointer once read, so a SINGLE guard at
 * the first use (`0x2FF62C`) that skips the ENTIRE five-use block on a bad pointer - landing at
 * `0x2FF6BC`, the function's own next, unrelated section - covers all five. This is a slightly larger
 * version of the same "skip the whole consumer block" strategy fix #95 used for `VFX::Update(int)`'s
 * four uses of its own NULL-checked pointer, extended here to a block that also contains two ordinary
 * `bl` calls (not just adjacent instructions), which is fine since nothing between the reads writes to
 * `this`+452 or to `r6` itself.
 *
 * On a NULL/implausible `this`+452 pointer, skipping the whole block is the conservative choice: this
 * section only ever *disallows* the hud element (`mov r0,#0` at `0x2FF5E0`) when it detects specific
 * conditions on the sub-object (name matches one of three symbols, or it's running/rolling) - if the
 * sub-object doesn't exist yet, none of those conditions can be true, so skipping straight to the
 * function's own next section (which does unrelated symbol-name checks against different literals) is
 * exactly what the original code would do if this GameObject simply weren't relevant right now.
 *
 *   cmp  r3, #0xC0000000
 *   blo  0x2FF6BC        ; doesn't look like a real heap pointer (covers NULL): skip the whole block
 *   ldr  r3, [r3]        ; real pointer: the exact original, untouched instruction
 *   b    0x2FF630        ; branch back to the function's OWN untouched 'mov lr,pc'
 *
 * `>= 0xC0000000` is this project's established whitelist convention (see fix #46 Revision 2's doc
 * comment).
 *
 * Cave lives at `0x0098DC48`, the next 16 bytes in the same `.rodata` gap fix #90 vetted
 * (`0x0098DBF8`, 296 bytes; fixes #90/#92/#93/#94/#95 have claimed the first 80) - re-confirmed here
 * specifically byte-identical/zero and free of any ELF relocation before being claimed.
 * Objdump/capstone-confirmed (every branch resolves to exactly the intended target) and `qemu-arm`
 * harness-verified with two vectors (`tests/fix96_invalid.cfg`, `tests/fix96_valid.cfg`): `r3=0` lands
 * directly on `0x2ff6bc` (skipping the whole five-use block) with no fault; `r3` pointing at real memory
 * replays the guarded read and branches back into the function's own next instruction (`0x2ff630`) with
 * `r3` holding the exact value read from `[r3]`, confirming the valid path is untouched.
 *
 * ## Why this one is different from fixes #92-#95
 *
 * Unlike every other fix in this family, this was found via a proactive static scan, not a real-device
 * crash report - the user asked for one batch round covering everything this pattern can find, rather
 * than continuing to chase these one real-device crash at a time. It's built and verified with exactly
 * the same rigor (capstone + qemu-arm harness, byte-exact original verification) as every crash-driven
 * fix in this project, but should be understood as preventative rather than confirmed-necessary.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at both target regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectAllowHudElementNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x002FF62CL
    private const val TARGET_VADDR_CAVE = 0x0098DC48L

    // ldr r3, [r3]  (this->[452]'s vtable pointer, unguarded - 1st of 5 uses in this block)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    // bl 0x98dc48
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x85.toByte(), 0x39, 0x1A, 0xEB.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16)

    // cmp r3,#0xC0000000 / blo 0x2ff6bc (skip the whole 5-use block) / ldr r3,[r3] (replay) /
    // b 0x2ff630 (branch back into the function's own untouched 'mov lr,pc')
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x53, 0xE3.toByte(),
        0x9A.toByte(), 0xC6.toByte(), 0xE5.toByte(), 0x3A,
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0x75, 0xC6.toByte(), 0xE5.toByte(), 0xEA.toByte()
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
                "GameWorld::AllowHudElement(im::Symbol const&) no longer dereferences a never-constructed " +
                    "(NULL, or anything that doesn't look like a real heap pointer, < 0xC0000000) " +
                    "sub-object pointer at this+452 across its whole 5-use symbol/state-check block " +
                    "(same field fixes #94/#95 already guard elsewhere) - found via a proactive static " +
                    "scan rather than a real-device crash, it now safely skips the entire block and " +
                    "resumes with the function's own next section, exactly as if this GameObject simply " +
                    "weren't relevant to the check yet, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
