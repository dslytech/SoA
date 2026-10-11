package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DIAGNOSTIC-ONLY, TEMPORARY patch for Mass Effect: Infiltrator - NOT a real fix. Supersedes
 * [ElfMassEffectHandleMessageDiagnosticPatcher] (fix #23/#24, "Round 21"/"Round 22"): that probe
 * did its job and is no longer wired into [ApkPatcher] - see below for why, and for what it found.
 *
 * ## What the HandleMessage() probe (fix #24, 0.24-diag2) revealed
 *
 * Real-device tested ("Round 22"): the fixed probe's backtrace finally got past
 * `HandleMessage()` and showed the full caller chain:
 * ```
 * #00 EA::Blast::LifeCycle::HandleMessage(unsigned int, void*)+8      <- our probe's crash
 * #01 EA::Messaging::Server::MessageSend(unsigned int, void*, IHandler*)+508
 * #02 EA::Messaging::Server::ProcessQueue()+688
 * #03 EA::Blast::LoopLocked()+208
 * #04 Java_com_ea_blast_AndroidRenderer_NativeOnDrawFrame+32
 * ```
 * This says message `6` is *queued* (via `EA::Messaging::Server`) and only *dispatched* out of
 * that queue by `ProcessQueue()`, which `LoopLocked()` calls on every single rendered frame
 * regardless of what is or isn't in the queue. So `ProcessQueue()`/`LoopLocked()` are not the
 * source of message `6` at all - they are just the always-running pump that happens to deliver
 * whatever was queued earlier. The actual "poster" already ran and returned, frames (or at least
 * calls) before this backtrace even starts, which is exactly why it doesn't appear here - same
 * class of problem as before, just one level further back.
 *
 * ## Why the target moves to `EA::Blast::System::Exit(int)` (0x35c0b4)
 *
 * "Round 21"'s static analysis already found the one and only function in the whole 17MB binary
 * that hardcodes queuing message id `6` unconditionally, regardless of its own `int` argument:
 * `EA::Blast::System::Exit(int)`. Its body (real, unpatched disassembly):
 * ```
 * push  {r4, r5, lr}
 * ldr   ip, [r0, #8]     ; ip = this->someDispatcherPtr
 * mov   r3, #0
 * sub   sp, sp, #12
 * mov   r4, r0                                    <- this patch's probe replaces THIS point on
 * mov   r5, r1                                    <- ...and the crash happens right here
 * mov   r0, ip
 * mov   r2, r3
 * ldr   ip, [ip]
 * mov   r1, #6           ; <-- the hardcoded message id
 * str   r3, [sp]
 * ...                    ; virtual call that queues the message
 * str   r5, [r4, #96]    ; stores the real exit code for later NativeGetExitCode(), unrelated
 * ...
 * pop   {r4, r5, pc}
 * ```
 * Exactly like `System::Exit()` posting message 6, it has ZERO direct (`bl`) callers anywhere in
 * the binary - only reachable via C++ virtual dispatch, which is why "Round 21" couldn't
 * disambiguate its caller among ~350 same-vtable-slot candidates by static analysis alone. Since
 * calling `Exit()` at all unconditionally queues message `6` (the exit code only affects a field
 * stored for later, not whether/what gets queued), this probe crashes UNCONDITIONALLY the instant
 * `Exit()` is entered - no `cmp` needed this time, any call to this function at all is the thing
 * we're trying to catch.
 *
 * ## The stub, and the unwind-safety lesson from fix #23 -> #24
 *
 * `System::Exit(int)`'s own `.ARM.exidx` entry (confirmed via `arm-linux-gnueabi-readelf
 * --unwind`) is `vsp = vsp + 12; pop {r4, r5, r14}` - i.e. it assumes the FULL real prologue
 * (`push {r4,r5,lr}` *and* `sub sp, sp, #12`) has already run by the time of any crash inside this
 * function, not just the initial push (this function's steady-state frame is deeper than
 * `HandleMessage()`'s was). Learning from "Round 22"'s exact mistake, this patch therefore leaves
 * ALL FOUR of the real original first instructions completely untouched - `push {r4,r5,lr}`,
 * `ldr ip,[r0,#8]`, `mov r3,#0`, `sub sp,sp,#12` - and only replaces what comes next:
 * ```
 * push  {r4, r5, lr}     ; UNCHANGED (real original bytes)
 * ldr   ip, [r0, #8]     ; UNCHANGED (real original bytes)
 * mov   r3, #0           ; UNCHANGED (real original bytes)
 * sub   sp, sp, #12      ; UNCHANGED (real original bytes) - stack now matches what the exidx
 *                        ; entry ("vsp+12; pop r4,r5,r14") expects at any point from here on
 * mov   r0, #6           ; the probe: crash marker value, kept as 6 so it's instantly
 * str   r0, [r0]         ; recognizable in the next crash log's "fault addr" - guaranteed SIGSEGV
 * ```
 * At the crash point the stack is in exactly the state the real function's own unwind metadata
 * assumes, so `debuggerd` should now be able to walk straight through to `Exit()`'s real caller.
 * All 6 instructions (first 4 confirmed as real original bytes, last 2 assembled + disassembled
 * via `arm-linux-gnueabi-as`/`objdump`) verified byte-for-byte, same discipline as every other
 * native patch in this project.
 *
 * ## Trade-off and safety - same as every other diagnostic patch here
 *
 * Any code path that would normally call `Exit()` now crashes instead of exiting cleanly - this
 * build is diagnostic-only, not for real play, and gets removed/superseded once the real caller
 * is known. Same two-layer gate as always: only looks at `libMassEffect.so`, only patches if the
 * exact original 24 bytes at `Exit()`'s address match exactly - anything else (IG2, Haegemonia,
 * a different game/build) is left completely untouched.
 */
object ElfMassEffectSystemExitDiagnosticPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // EA::Blast::System::Exit(int)
    private const val TARGET_VADDR = 0x0035c0b4L

    // Real original first 24 bytes (6 instructions), little-endian, confirmed via
    // arm-linux-gnueabi-objdump against the real extracted 1.0.58 libMassEffect.so:
    //   push {r4,r5,lr} ; ldr ip,[r0,#8] ; mov r3,#0 ; sub sp,sp,#12 ; mov r4,r0 ; mov r5,r1
    private val EXPECTED_ORIGINAL = byteArrayOf(
        0x30, 0x40, 0x2D, 0xE9.toByte(),
        0x08, 0xC0.toByte(), 0x90.toByte(), 0xE5.toByte(),
        0x00, 0x30, 0xA0.toByte(), 0xE3.toByte(),
        0x0C, 0xD0.toByte(), 0x4D, 0xE2.toByte(),
        0x00, 0x40, 0xA0.toByte(), 0xE1.toByte(),
        0x01, 0x50, 0xA0.toByte(), 0xE1.toByte()
    )

    // First 16 bytes UNCHANGED (the real prologue, kept intact so the function's own .ARM.exidx
    // unwind entry - "vsp = vsp + 12; pop {r4, r5, r14}" - stays valid at the crash point); last 8
    // bytes assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte:
    //   mov r0, #6 ; str r0, [r0]
    private val DIAGNOSTIC_STUB = byteArrayOf(
        0x30, 0x40, 0x2D, 0xE9.toByte(),
        0x08, 0xC0.toByte(), 0x90.toByte(), 0xE5.toByte(),
        0x00, 0x30, 0xA0.toByte(), 0xE3.toByte(),
        0x0C, 0xD0.toByte(), 0x4D, 0xE2.toByte(),
        0x06, 0x00, 0xA0.toByte(), 0xE3.toByte(),
        0x00, 0x00, 0x80.toByte(), 0xE5.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at `System::Exit(int)`'s address match. Anything else (wrong file, mismatched bytes) is left
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
                return Result(false, "System::Exit() address doesn't map into this file - not touching")
            }
            for (i in EXPECTED_ORIGINAL.indices) {
                if (bytes[off + i] != EXPECTED_ORIGINAL[i]) {
                    return Result(false, "original bytes at System::Exit() don't match expected " +
                        "(different build/version?) - left completely untouched")
                }
            }
            for (i in DIAGNOSTIC_STUB.indices) bytes[off + i] = DIAGNOSTIC_STUB[i]
            return Result(
                true,
                "DIAGNOSTIC BUILD: EA::Blast::System::Exit(int) now crashes immediately, " +
                    "unconditionally, the instant it's called (real prologue kept intact so " +
                    "debuggerd's unwinder can show the real caller) - the resulting crash log's " +
                    "backtrace should show WHO calls Exit(). Any real exit path now crashes " +
                    "instead - NOT for normal play, one test run only."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
