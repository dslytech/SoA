package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's native startup SIGSEGV - the crash this
 * whole investigation ("Round 17" through "Round 23", see [ElfMassEffectSystemExitDiagnosticPatcher]
 * and [ElfMassEffectHandleMessageDiagnosticPatcher]'s doc comments for the full chase) has been
 * chasing since it was first found.
 *
 * ## The root cause, finally: a legacy "is primary external storage writable?" watchdog
 *
 * The diagnostic probe chain (fix #23 through #25) traced the crash to a real, symbolized,
 * complete backtrace on real-device test ("Round 23"):
 * ```
 * EA::Blast::System::Exit(int)
 * <- EA::Blast::Application::Exit(int)
 * <- EA::GameSkeleton::GameApplication::OnUpdate(void*)+488     (0x40bb30, the actual call site)
 * <- EA::Blast::UpdateHandler::HandleMessage(unsigned int, void*)
 * <- EA::Messaging::Server::MessageSend(...)/ProcessQueue()
 * <- EA::Blast::LoopLocked()  <- Java_com_ea_blast_AndroidRenderer_NativeOnDrawFrame
 * ```
 * Disassembling `GameApplication::OnUpdate()` around the call site (0x40bb1c-0x40bb30) shows
 * exactly what decides to quit:
 * ```
 * bl    EA::IO::StorageDirectory::GetWritableStorageDirectory(unsigned int, PathString8&)
 *       ; called with storageType=2, output = a purely local, throwaway stack buffer (its result
 *       ; is never stored anywhere else - even on success this function's only further action is
 *       ; a generic temporary-vector destructor loop, confirmed by reading the code on both sides
 *       ; of the branch below) - this is a pure yes/no AVAILABILITY CHECK, not something anything
 *       ; else depends on.
 * cmp   r0, #1          ; <-- THIS instruction (0x40bb20) is what this patch changes
 * beq   <skip-exit>
 * mov   r1, #0          ; exit code 0
 * mov   r0, r4
 * bl    EA::Blast::Application::Exit(int)   ; <-- the crash, several frames later
 * ```
 * `GetWritableStorageDirectory`'s second argument, `2`, is not a storage "type enum" as first
 * guessed - disassembling `GetAllStorageList(vector&, unsigned flags)` (its callee) shows `flags`
 * is a BITMASK: bit 0 (1) = internal, bit 1 (2) = **primary external** storage, bit 2 (4) =
 * secondary external, bit 3 (8) = custom mounted paths, bit 4 (16) = shared storage. `2` requests
 * PRIMARY external storage specifically - i.e. the classic `/storage/emulated/0/`-style shared
 * storage every Android device has. On a 2011-era engine, checking this used to be a trivial,
 * always-true availability check. On modern Android (API 30+, scoped storage, enforced regardless
 * of any manifest flag once targetSdkVersion reaches 30 - exactly what this project's own
 * `targetSdkVersion` patch raises it to, to let the APK install at all), raw legacy filesystem
 * access of the kind this old code performs to that path is blocked outright, so
 * `Directory::EnsureExists()` on it fails every time, `GetWritableStorageDirectory()` returns
 * "not found" (0, not `1`), and `OnUpdate()` decides the game can't continue - and crashes trying
 * to say so cleanly.
 *
 * ## Why this is safe to just skip
 *
 * The game's actual save data and downloaded content already go through the proper, scoped-storage
 * -compliant Android APIs handled entirely separately by this project's dex-level fixes (#9-#21,
 * `eamobile`/`AssetManager` - already proven working: the game reaches its real 3D view and renders
 * frames before this crash). This particular check is a narrow, vestigial "legacy external storage"
 * watchdog with no other observable side effect (see above - its result is never stored beyond this
 * one `cmp`). Making `OnUpdate()` always take the "storage is fine" branch, regardless of what
 * `GetWritableStorageDirectory()` actually returns, does not skip any initialization anything else
 * relies on - it just stops this one obsolete check from killing an otherwise-working game.
 *
 * ## The patch
 *
 * Single instruction, single byte-group changed, at `OnUpdate()+0x40bb20`:
 * ```
 * cmp r0, #1   ->   cmp r0, r0
 * ```
 * `cmp r0, r0` always sets the zero flag (a register trivially equals itself), so the following
 * `beq <skip-exit>` is now unconditionally taken - `GetWritableStorageDirectory()` still runs
 * normally (nothing about it is touched), only its result is no longer allowed to trigger `Exit()`.
 * `EA::Blast::System::Exit(int)`/`EA::Blast::Application::Exit(int)` themselves are untouched, so
 * any OTHER, legitimate reason the game might have to exit (finishing, a real fatal error
 * elsewhere) still works normally - this patch is narrowly scoped to this one watchdog only.
 * Assembled + disassembled via `arm-linux-gnueabi-as`/`objdump` to confirm the replacement
 * instruction byte-for-byte (not hand-derived), same discipline as every other native patch here.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else)
 *
 * Same two-layer gate as every other native patch in this project: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 4 bytes at this address
 * match exactly what this was built against - any mismatch (different build/version, different
 * game, anything) leaves the file completely untouched.
 *
 * ## Status
 *
 * This is this project's best-supported real-fix candidate for the crash, built from a complete,
 * real-device-confirmed backtrace and a concrete, narrowly-scoped root cause - but it has not yet
 * been real-device tested itself. If it's wrong, or the game hits a different problem past this
 * point, the diagnostic probes ([ElfMassEffectSystemExitDiagnosticPatcher],
 * [ElfMassEffectHandleMessageDiagnosticPatcher]) remain in the repo (unwired) as a starting point.
 */
object ElfMassEffectPrimaryStorageExitPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The `cmp r0, #1` inside EA::GameSkeleton::GameApplication::OnUpdate(void*) that gates
    // whether a failed GetWritableStorageDirectory(2, ...) call triggers Application::Exit(0).
    private const val TARGET_VADDR = 0x0040bb20L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: cmp r0, #1
    private val EXPECTED_ORIGINAL = byteArrayOf(0x01, 0x00, 0x50, 0xE3.toByte())

    // Assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte: cmp r0, r0
    private val PATCHED_BYTES = byteArrayOf(0x00, 0x00, 0x50, 0xE1.toByte())

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at the target address match. Anything else (wrong file, mismatched bytes) is left completely
     * untouched.
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
                return Result(false, "target address doesn't map into this file - not touching")
            }
            for (i in EXPECTED_ORIGINAL.indices) {
                if (bytes[off + i] != EXPECTED_ORIGINAL[i]) {
                    return Result(false, "original bytes at the target address don't match expected " +
                        "(different build/version?) - left completely untouched")
                }
            }
            for (i in PATCHED_BYTES.indices) bytes[off + i] = PATCHED_BYTES[i]
            return Result(
                true,
                "GameApplication::OnUpdate()'s legacy 'is primary external storage writable' " +
                    "watchdog can no longer force-quit the game when that check fails (as it always " +
                    "does under modern Android's scoped storage) - the check itself still runs, only " +
                    "its result can no longer trigger Application::Exit()."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
