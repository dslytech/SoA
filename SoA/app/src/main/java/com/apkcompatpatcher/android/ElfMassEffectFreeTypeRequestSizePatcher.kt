package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's SECOND native SIGSEGV - the one that
 * showed up only after [ElfMassEffectPrimaryStorageExitPatcher] (fix #26) fixed the first one
 * ("Round 24"). Real-device tested crash log ("Round 25"):
 * ```
 * signal 11 (SIGSEGV), fault addr 0x00000060
 * #00 FT_Request_Size+72
 * #01 FT_Set_Char_Size+104
 * #02 im::FreeTypeFont::FreeTypeFont(char const*, float)+260
 * #03 im::IFont::CreateDefaultFont(float)+228
 * #04 im::ui::plain::PlainSkin::PlainSkin()+240
 * #05 im::ui::UILayer::UILayer(char const*)+180
 * #06 im::debug::DebugMenuLayer::DebugMenuLayer()+20
 * #07 im::debug::DebugMenu::GetDebugMenuLayer()+100
 * #08 im::M3GApplication::OnCreate()+792
 * ```
 * (further up: `Application::OnCreate()` <- `im::M3GApplication::OnRunLoopTick()` <-
 * `im::BufferedPlatformDelegate::OnRunLoopTick()` <- `im::Platform::OnPlatformEvent(...)` <-
 * `EA::GameSkeleton::GameApplication::OnUpdate()` - same per-frame update loop as fix #26's crash,
 * just a different code path inside it, unconditionally run every time the game creates its
 * debug-menu UI overlay during startup.)
 *
 * ## Root cause: not a missing font file, and not Android-version-specific
 *
 * `im::IFont::CreateDefaultFont()` loads `/system/fonts/DroidSans.ttf` via FreeType's
 * `FT_New_Face()` - confirmed present on the test device (a Honor/Magic UI build keeps it for
 * legacy-app compatibility), and confirmed (via register dump: `r0`/`r4` hold a plausible,
 * non-null heap pointer) that `FT_New_Face()` really did succeed and returned a valid `FT_Face`.
 * The crash is one level deeper: `FT_Set_Char_Size()` -> `FT_Request_Size()` dereferences
 * `face->driver->clazz` (the loaded font driver's function table) at offset `0x60`, and that
 * pointer is NULL (`r3` in the register dump is exactly `00000000`, and the fault address `0x60`
 * matches `NULL + 0x60` exactly). `face->driver` itself is a valid, non-null pointer - only its
 * `clazz` field is unpopulated. This looks like the bundled FreeType build's module/driver
 * registration is incomplete for this driver - a pre-existing engine-side defect, not something
 * caused by modern Android. It's plausible this exact code path (building the "debug menu" UI
 * overlay, which `M3GApplication::OnCreate()` constructs unconditionally on every startup) was
 * never actually exercised successfully before - fix #26 is what let execution reach this far in
 * the first place.
 *
 * ## The fix: force FreeType's own generic fallback path
 *
 * `FT_Request_Size()`'s disassembly, immediately around the crash:
 * ```
 * ldr r3, [r4, #96]     ; r3 = face->driver
 * ldr r3, [r3, #20]     ; r3 = driver->clazz
 * ldr r5, [r3, #96]     ; r5 = clazz->request_size (a driver-specific hook)      <- CRASH (r3 NULL)
 * cmp r5, #0
 * beq <generic-fallback>   ; if the driver has no custom hook, use FreeType's own
 *                          ; standard FT_Request_Metrics()/FT_Match_Size()+FT_Select_Size()
 *                          ; fallback instead - already-existing, always-safe FreeType code
 * ldr r0, [r4, #88]
 * blx r5                   ; otherwise, call the driver's custom hook
 * ```
 * A driver with no custom hook (`clazz->request_size == 0`) is a completely normal, already-
 * handled case - FreeType's own generic fallback covers it correctly for any driver type. This
 * patch makes `FT_Request_Size()` always take that same, already-safe fallback path by replacing
 * just the crashing load with `mov r5, #0` - i.e. "pretend the driver never had a custom hook"
 * instead of "read whether it does from a broken pointer". `face->driver`/`driver->clazz`
 * themselves, and the driver-specific-hook path itself for callers where it isn't broken, are
 * otherwise untouched: this is a single, minimal, defensive change to how the FreeType library
 * bundled in this binary answers the question "should I use my fast path or my generic fallback",
 * biasing it towards the fallback it already fully implements and already uses for other drivers -
 * so it's expected to be safe for every font this binary loads, not just the one that crashed.
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
 * Real-device-confirmed root cause, but this specific patch has not yet been tested itself.
 */
object ElfMassEffectFreeTypeRequestSizePatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The `ldr r5, [r3, #96]` inside FreeType's FT_Request_Size() that reads a font driver's
    // optional custom size-request hook from its class/function table.
    private const val TARGET_VADDR = 0x005f81e8L

    // Real original 4 bytes, little-endian, confirmed via arm-linux-gnueabi-objdump against the
    // real extracted 1.0.58 libMassEffect.so: ldr r5, [r3, #96]
    private val EXPECTED_ORIGINAL = byteArrayOf(0x60, 0x50, 0x93.toByte(), 0xE5.toByte())

    // Assembled + disassembled via arm-linux-gnueabi-as/objdump to confirm byte-for-byte: mov r5, #0
    private val PATCHED_BYTES = byteArrayOf(0x00, 0x50, 0xA0.toByte(), 0xE3.toByte())

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
                "FreeType's FT_Request_Size() no longer reads a font driver's optional custom " +
                    "size-request hook through a NULL driver class pointer - it now always uses " +
                    "FreeType's own already-safe generic fallback sizing path instead, fixing the " +
                    "SIGSEGV in im::FreeTypeFont::FreeTypeFont() while creating the game's debug " +
                    "menu UI font."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
