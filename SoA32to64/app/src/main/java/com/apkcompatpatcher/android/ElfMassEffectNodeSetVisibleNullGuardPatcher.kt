package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `im::scene2d_new::
 * Node::SetVisible(bool)` against a NULL `this` pointer.
 *
 * ## Background
 *
 * Found by reading `v-atamanenko/masseffect-vita`'s (MIT-licensed,
 * https://github.com/v-atamanenko/masseffect-vita) loader source directly - not from a real-device
 * crash log here. Its `loader/patch/info_menu.c` hooks exactly this address with a one-line comment:
 * "Fix crash on INFO => ABOUT menu open" - i.e. tapping a specific main-menu screen this project's
 * own testing hasn't reached yet crashes on a NULL Node being made visible/invisible. Their fix is a
 * blanket `if (!this) return;` guard on the function itself, not on any one caller - deliberately
 * generic, since `SetVisible()` is called from a great many places throughout this engine's whole
 * scene-graph/widget system (this project's own earlier fixes #35-#37 already had to fight a
 * different, unrelated `SetVisible()`-adjacent crash inside `Hud::InitLayer()` - a phantom leftover
 * pointer being treated as a Node, not a genuine caller passing NULL). Guarding the callee itself
 * once, the same way, is far cheaper than waiting to discover and guard every individual caller one
 * crash log at a time - exactly the kind of preventable, already-known bug this project can adopt
 * outright rather than rediscover.
 *
 * The address was cross-checked against this project's own extracted 1.0.58 `libMassEffect.so`
 * symbol table before writing this patch (see [ElfMassEffectOnlineStubPatcher]'s doc comment for the
 * original SHA1 match that first confirmed this is byte-for-byte the same build): `0x00488FF8` lands
 * exactly on `im::scene2d_new::Node::SetVisible(bool)`, matching the Vita loader's own comment.
 *
 * ## The fix
 *
 * Same "leaf function, checked-pointer-null" cave shape used throughout this project (see fix #78's
 * `TeamGoals::OnUpdate` guard for the same style): the guard sits at the function's true entry point,
 * before its own (4-register) prologue runs, so the invalid path is a plain `bx lr` straight back to
 * whichever caller invoked it - no stack to unwind, no `lr` arithmetic. The function is `void`
 * (matches the Vita loader's own C signature, `void im__scene2d_new__Node__SetVisible(void*, bool)`),
 * so nothing needs computing on that path.
 *
 * Objdump-confirmed (every branch resolves to exactly the intended target, the relocated `push`
 * reassembles to the exact original 4 bytes) and harness-verified: the exact crash condition
 * (`r0 = 0`) returns immediately to a synthetic caller address untouched, and a valid `this` pointer
 * falls through to the exact original, untouched instruction sequence with every register preserved.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, anything else)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at every one of this fix's
 * regions match byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectNodeSetVisibleNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x00488FF8L
    private const val TARGET_VADDR_CAVE_F1 = 0x00955F18L
    private const val TARGET_VADDR_CAVE_F2 = 0x00955F30L
    private const val TARGET_VADDR_CAVE_F3 = 0x00956358L
    private const val TARGET_VADDR_CAVE_BAIL = 0x009564B8L

    // push {r4,r5,r6,lr}
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x70, 0x40, 0x2D, 0xE9.toByte()
    )
    // b 0x955f18
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xC6.toByte(), 0x33, 0x13, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0,#0 / b 0x955f30
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0x03, 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x9564b8 / b 0x956358
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x60, 0x01, 0x00, 0x0A,
        0x07, 0x01, 0x00, 0xEA.toByte()
    )
    // push {r4,r5,r6,lr} (relocated original) / b 0x488ffc
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0x70, 0x40, 0x2D, 0xE9.toByte(),
        0x26, 0xCB.toByte(), 0xEC.toByte(), 0xEA.toByte()
    )
    // bx lr / nop
    private val PATCHED_BYTES_CAVE_BAIL = byteArrayOf(
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte(),
        0x00, 0x00, 0xA0.toByte(), 0xE1.toByte()
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
            val f1Off = fileOffsetFor(TARGET_VADDR_CAVE_F1)
            val f2Off = fileOffsetFor(TARGET_VADDR_CAVE_F2)
            val f3Off = fileOffsetFor(TARGET_VADDR_CAVE_F3)
            val bailOff = fileOffsetFor(TARGET_VADDR_CAVE_BAIL)

            if (callsiteOff == null || f1Off == null || f2Off == null || f3Off == null || bailOff == null ||
                callsiteOff + 4 > bytes.size || f1Off + 8 > bytes.size || f2Off + 8 > bytes.size ||
                f3Off + 8 > bytes.size || bailOff + 8 > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(callsiteOff, EXPECTED_ORIGINAL_CALLSITE) ||
                !matches(f1Off, EXPECTED_ORIGINAL_CAVE) || !matches(f2Off, EXPECTED_ORIGINAL_CAVE) ||
                !matches(f3Off, EXPECTED_ORIGINAL_CAVE) || !matches(bailOff, EXPECTED_ORIGINAL_CAVE)
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version?) - left completely untouched")
            }

            fun write(off: Int, patch: ByteArray) { for (i in patch.indices) bytes[off + i] = patch[i] }
            write(callsiteOff, PATCHED_BYTES_CALLSITE)
            write(f1Off, PATCHED_BYTES_CAVE_F1)
            write(f2Off, PATCHED_BYTES_CAVE_F2)
            write(f3Off, PATCHED_BYTES_CAVE_F3)
            write(bailOff, PATCHED_BYTES_CAVE_BAIL)

            return Result(
                true,
                "im::scene2d_new::Node::SetVisible(bool) no longer dereferences a NULL 'this' pointer " +
                    "(found via v-atamanenko/masseffect-vita's loader source - their own comment: 'Fix crash " +
                    "on INFO => ABOUT menu open', not yet seen on a real device here) - it now returns " +
                    "immediately instead of crashing, covering every caller at once."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
