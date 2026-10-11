package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DIAGNOSTIC-ONLY, TEMPORARY patch for Mass Effect: Infiltrator - NOT a real fix, and not meant
 * to be kept once it's done its job. Ships as its own separate "probe" build so the resulting
 * native crash log reveals WHO calls `EA::Blast::LifeCycle::HandleMessage(unsigned int, void*)`
 * with message id `6` - the still-unidentified trigger for the native SIGSEGV this project has
 * been chasing since "Round 17"/"Round 18" (see [ElfMassEffectOnlineStubPatcher]'s doc comment
 * for the full background - that patch (fix #22) conclusively ruled out the EA Origin/DirtySDK/
 * Galaxy-at-War network layer as the cause in "Round 20": identical crash, byte for byte, even
 * with all 8 of those call sites hard-disabled).
 *
 * ## v1 ("Round 21", fix #23, `0.23-diag`) actually worked - partially
 *
 * The v1 probe (bare `cmp r1,#6 / streq r1,[r1] / mov r0,#0 / bx lr`, no stack frame at all) DID
 * prove the core theory: the real-device crash log's `fault addr` was exactly `0x00000006` at
 * `HandleMessage()+4` (the `streq`), confirming `HandleMessage()` really is called with message id
 * `6` exactly as hypothesized. But the backtrace stopped dead one frame later at an unsymbolized
 * `[anon:stack_and_tls:...]` address instead of showing the caller - v1's stub never executed a
 * `push`, so the stack didn't contain what `debuggerd`'s ARM EHABI unwinder expected to find there.
 *
 * Root cause, confirmed against the real binary's own unwind tables
 * (`arm-linux-gnueabi-readelf --unwind`): the `.ARM.exidx` entry for `HandleMessage()` is a single
 * per-function "compact model" rule - `pop {r4, r14}` - that debuggerd applies at ANY crash PC
 * inside the function, on the assumption that the real prologue's `push {r4, lr}` already ran and
 * nothing since has moved the stack pointer. v1's stub replaced that `push` with `cmp`, so at the
 * crash point the stack pointer was still wherever the CALLER left it - the unwinder popped two
 * unrelated words off the caller's own stack area and got garbage, not a real return address.
 *
 * ## v2 (this patch, fix #24, `0.24-diag2`) - keeps the real prologue/epilogue shape
 *
 * Same idea, but now the stub's shape matches what the unwind table already assumes, by actually
 * keeping a real `push {r4, lr}` / `pop {r4, pc}` around the probe - i.e. this now patches 20 bytes
 * (5 instructions) instead of v1's 16 (4), replacing `HandleMessage()`'s entire body with:
 * ```
 * push  {r4, lr}        ; UNCHANGED from the real original instruction - this is exactly what the
 *                       ; function's .ARM.exidx entry ("pop {r4, r14}") assumes has just happened,
 *                       ; so the unwinder's assumption is true again at the crash point below.
 * cmp   r1, #6          ; r1 is the real ARM EABI register for this method's first real argument
 *                       ; (`unsigned int messageId`) - confirmed against the real function's own
 *                       ; dispatch logic (it compares r1 against 0xa0006 a few instructions later
 *                       ; in the untouched original code, for a different, unrelated branch).
 * streq r1, [r1]        ; if id == 6: store 6 to address 6 - guaranteed SIGSEGV. At this exact
 *                       ; point the stack is in precisely the state the exidx entry expects (r4
 *                       ; and the caller's return address freshly pushed, nothing else touched),
 *                       ; so debuggerd's unwinder can now correctly pop them and show the true
 *                       ; caller chain.
 * mov   r0, #0          ; for any OTHER message id: harmless "handled" return value...
 * pop   {r4, pc}        ; ...and a proper matching epilogue (not a bare `bx lr`) - this is the
 *                       ; exact same instruction (byte-for-byte) the real original function itself
 *                       ; already uses to return in its untouched fast path a few instructions
 *                       ; later, so it is definitely correct for undoing this stub's own `push`.
 * ```
 * All 5 instructions assembled + disassembled via `arm-linux-gnueabi-as`/`objdump` to confirm
 * byte-for-byte (not hand-derived); the `push`/`pop` pair is additionally cross-checked against
 * the real original bytes already present at/around this same address (verified with
 * `arm-linux-gnueabi-readelf --unwind` and `objdump -d` directly against the extracted 1.0.58
 * `libMassEffect.so`), same verification discipline as [ElfMassEffectOnlineStubPatcher].
 *
 * ## Trade-off - why this is diagnostic-only and gets reverted
 *
 * Replacing the WHOLE method body means every other lifecycle message (focus gained/lost, pause,
 * resume, start) becomes a no-op for as long as this patch is applied - i.e. this build is
 * expected to otherwise misbehave/not really be playable. That is fine and expected for a single
 * one-shot test whose only purpose is capturing one crash log; this patch must never ship as part
 * of a real release build, and should be removed again (or superseded by a real, root-caused fix
 * once the caller is known) right after its job is done.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else)
 *
 * Same two-layer gate as [ElfMassEffectOnlineStubPatcher]: only even looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 20 bytes already at
 * `HandleMessage()`'s address match exactly what this was built against - any mismatch (different
 * build/version, different game, anything) leaves the file completely untouched.
 */
object ElfMassEffectHandleMessageDiagnosticPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // EA::Blast::LifeCycle::HandleMessage(unsigned int, void*)
    private const val TARGET_VADDR = 0x0035b44cL

    // Real original first 20 bytes (5 instructions), little-endian, confirmed via
    // arm-linux-gnueabi-objdump against the real extracted 1.0.58 libMassEffect.so:
    //   push {r4, lr} ; ldr r2, [r0, #12] ; mov r3, r0 ; cmp r2, #0 ; bne 0x35b478
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x10, 0x40, 0x2D, 0xE9.toByte(),
        0x0C, 0x20, 0x90.toByte(), 0xE5.toByte(),
        0x00, 0x30, 0xA0.toByte(), 0xE1.toByte(),
        0x00, 0x00, 0x52, 0xE3.toByte(),
        0x05, 0x00, 0x00, 0x1A
    )

    // Assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte:
    //   push {r4, lr} ; cmp r1, #6 ; streq r1, [r1] ; mov r0, #0 ; pop {r4, pc}
    // The push/pop pair keeps HandleMessage()'s stack-frame shape matching what its .ARM.exidx
    // unwind entry ("pop {r4, r14}") assumes, so debuggerd can unwind past the crash to the real
    // caller this time (v1/fix #23 skipped the push and broke that assumption - see doc comment).
    private val DIAGNOSTIC_STUB = byteArrayOf(
        0x10, 0x40, 0x2D, 0xE9.toByte(),
        0x06, 0x00, 0x51, 0xE3.toByte(),
        0x00, 0x10, 0x81.toByte(), 0x05,
        0x00, 0x00, 0xA0.toByte(), 0xE3.toByte(),
        0x10, 0x80.toByte(), 0xBD.toByte(), 0xE8.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at `HandleMessage()`'s address match. Anything else (wrong file, mismatched bytes) is left
     * completely untouched.
     */
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

            val off = fileOffsetFor(TARGET_VADDR)
            if (off == null || off < 0 || off + EXPECTED_ORIGINAL.size > bytes.size) {
                return Result(false, "HandleMessage() address doesn't map into this file - not touching")
            }
            for (i in EXPECTED_ORIGINAL.indices) {
                if (bytes[off + i] != EXPECTED_ORIGINAL[i]) {
                    return Result(false, "original bytes at HandleMessage() don't match expected " +
                        "(different build/version?) - left completely untouched")
                }
            }
            for (i in DIAGNOSTIC_STUB.indices) bytes[off + i] = DIAGNOSTIC_STUB[i]
            return Result(
                true,
                "DIAGNOSTIC BUILD v2: LifeCycle::HandleMessage() replaced with a probe that crashes " +
                    "immediately if it's ever called with message id 6, now with a real push/pop " +
                    "stack frame so debuggerd's unwinder can show the actual caller (v1/fix #23 " +
                    "proved the message-id-6 theory right but broke the unwind - see doc comment). " +
                    "Every OTHER lifecycle message becomes a no-op in this build - NOT for normal " +
                    "play, one test run only."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
