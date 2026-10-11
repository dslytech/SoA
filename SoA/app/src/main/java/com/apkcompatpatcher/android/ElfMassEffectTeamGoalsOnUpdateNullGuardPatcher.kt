package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `TeamGoals::OnUpdate(int)`
 * against a NULL `this` pointer.
 *
 * ## Background
 *
 * Found in the 0.79 real-device test (fixes #75/#76 both confirmed working - `GameWorld::OnUpdate()`
 * no longer crashes on either of its own two previously-guarded sites) - the game progressed further
 * into the SAME function, hitting a new crash 316 bytes later (`+368` vs. fix #75's `+52`):
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000004
 * Cause: null pointer dereference
 *     r0  daf314a8  r1  00000006  r4  daf314a8  r7  00000000
 * #00 pc 00165418  (TeamGoals::OnUpdate(int)+56)
 * #01 pc 0030dba8  (GameWorld::OnUpdate(int)+368)
 * ```
 * Disassembly of the caller confirms the root cause immediately: `GameWorld::OnUpdate(int)` reads
 * its own `this->[1180]` field (a `TeamGoals*` member) straight into `r0` and unconditionally calls
 * `TeamGoals::OnUpdate(int)` through it - no NULL check at all:
 * ```
 * 30dba0: ldr r0, [r4, #1180]  ; this->teamGoals
 * 30dba4: mov r1, r5
 * 30dba8: bl TeamGoals::OnUpdate(int)  ; <-- crashes if teamGoals is NULL
 * ```
 * `TeamGoals::OnUpdate(int)` itself saves its `this` into `r7` at entry, does two calls that don't
 * touch it (`IApplication::GetApplication()`, `Application::GetWorld() const`), then at `+56`
 * dereferences it for the first time (`ldr r1, [r7, #4]`) - `r7` is `0` in the crash log, meaning the
 * `TeamGoals` sub-object was simply never constructed for this game session (a team-based objectives
 * tracker plausibly not present at all outside a specific mode), matching this whole project's
 * recurring "engine subsystem not built for this player/session, but called into unconditionally
 * anyway" bug family (fixes #66-#77).
 *
 * ## The fix
 *
 * Rather than guard the one caller found so far (`GameWorld::OnUpdate()`'s call site), this guards
 * `TeamGoals::OnUpdate(int)`'s own entry point directly - the same "leaf function, checked-pointer-
 * null" cave shape used throughout this project, covering every caller at once. Since the guard sits
 * before the function's own (9-register) prologue even runs, the invalid path is a plain `bx lr`
 * straight back to whichever caller invoked it, using that caller's own already-correct `lr` - no
 * stack to unwind, no manual `lr` arithmetic (same clean-entry-point style as fix #77's
 * `RemoveUnstructuredObjectField` guard). `TeamGoals::OnUpdate(int)` is a void method (matches this
 * engine's `*::OnUpdate(int)` naming convention throughout, e.g. `GameWorld::OnUpdate(int)` itself),
 * so nothing needs to be returned on the invalid path.
 *
 * Objdump-confirmed (every branch resolves to exactly the intended target, the relocated `push`
 * reassembles to the exact original 4 bytes) and harness-verified: the exact real crash condition
 * (`r0 = 0`) returns immediately to a synthetic caller address untouched, and a valid `this` pointer
 * falls through to the exact original, untouched instruction sequence with every register preserved.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, anything else)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at every one of this fix's
 * regions match byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectTeamGoalsOnUpdateNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x001653E0L
    private const val TARGET_VADDR_CAVE_F1 = 0x009552B8L
    private const val TARGET_VADDR_CAVE_F2 = 0x00955638L
    private const val TARGET_VADDR_CAVE_F3 = 0x00955E48L
    private const val TARGET_VADDR_CAVE_BAIL = 0x00955EF8L

    // push {r4,r5,r6,r7,r8,r9,sl,fp,lr}
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0xF0.toByte(), 0x4F, 0x2D, 0xE9.toByte()
    )
    // b 0x9552b8
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xB4.toByte(), 0xBF.toByte(), 0x1F, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(8)

    // cmp r0,#0 / b 0x955638
    private val PATCHED_BYTES_CAVE_F1 = byteArrayOf(
        0x00, 0x00, 0x50, 0xE3.toByte(),
        0xDD.toByte(), 0x00, 0x00, 0xEA.toByte()
    )
    // beq 0x955ef8 / b 0x955e48
    private val PATCHED_BYTES_CAVE_F2 = byteArrayOf(
        0x2E, 0x02, 0x00, 0x0A,
        0x01, 0x02, 0x00, 0xEA.toByte()
    )
    // push {r4,r5,r6,r7,r8,r9,sl,fp,lr} (relocated original) / b 0x1653e4
    private val PATCHED_BYTES_CAVE_F3 = byteArrayOf(
        0xF0.toByte(), 0x4F, 0x2D, 0xE9.toByte(),
        0x64, 0x3D, 0xE0.toByte(), 0xEA.toByte()
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
                "TeamGoals::OnUpdate(int) no longer dereferences a NULL 'this' pointer when GameWorld::OnUpdate() " +
                    "calls it on a TeamGoals sub-object that was never constructed for this session - it now " +
                    "returns immediately instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
