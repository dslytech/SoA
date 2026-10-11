package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's NINTH native SIGSEGV. Real-device
 * tested after fix #35 (the HUD widget null-guard) shipped: the crash it fixed is gone, and
 * `Hud::InitLayer()` gets 228 bytes further before a new one, still on the main GL thread:
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xe08956dc
 *     r0 e0895670  r4 e0895670  r10 e0895670  ...
 * #00 im::scene2d_new::Node::SetVisible(bool)+28
 * #01 Hud::InitLayer(eastl::intrusive_ptr<m3g::Renderer> const&, LayerGameWorld*)+728
 * ```
 * Unlike every previous native fix in this project, this one is NOT a null-pointer dereference
 * (`code 2 SEGV_ACCERR` is a permission fault on *mapped* memory, not `code 1 SEGV_MAPERR` on
 * unmapped memory) - `r0` here is a real, non-null address, just not one this code is allowed to
 * write to.
 *
 * ## Root cause: a stack slot shared between two unrelated locals, read before it's ever written
 *
 * A few instructions earlier (`0x114f14`, unrelated to and unchanged by fix #35's own patched
 * regions), `Hud::InitLayer()` writes a `boost::function`'s internal "manager" pointer - a fixed
 * address of a template-instantiated function *inside this very library's own `.text`/`.rodata`* -
 * into the 12 stack bytes at `sp+100..111`, as scratch space for registering an event callback.
 * Later, at `0x114fbc`, the function reads those *exact same* 12 bytes back out, this time
 * interpreting them as an `eastl::shared_ptr<im::scene2d_new::Node>` local: it checks the first
 * word for non-NULL and, if a second, unrelated flag is also set, calls `SetVisible(false)` on it
 * (`0x114fd8`-`0x114fdc`). Nothing in this function ever actually constructs a real `Node` there
 * between the two uses - the check at `0x114fbc` is testing 100%-unrelated leftover data from the
 * `boost::function` setup. Since that leftover value is always the same fixed, non-NULL library
 * address, the null-check always "passes". Calling `SetVisible()` on it dereferences that address
 * as if it were a live object and writes a byte into it (`this+108`) - which faults, because the
 * address in question is inside the *library's own code/rodata*, mapped read-only (or otherwise not
 * writable), not a legitimate heap object. (The one companion instruction that also reads this slot,
 * the `shared_ptr<Node>::~shared_ptr()` call right after at `0x114fe0`, is provably harmless: the
 * *second* word of this same 12 bytes is always explicitly zeroed at `0x114f1c`, which every
 * `shared_ptr` destructor reads as "no control block, nothing to release" and returns immediately
 * without touching the first word at all - so it never needed guarding.) This is presumably a real,
 * dormant bug in the original 2012 binary - stack-slot reuse across two variables whose lifetimes
 * the compiler believed didn't overlap - that likely never fired on original hardware/OS (perhaps
 * because the second flag this depends on was reliably false there, or memory protections were
 * looser), now newly reachable because fix #35 is what let `Hud::InitLayer()` progress this far for
 * the first time.
 *
 * ## The fix: this specific dead/buggy check can only ever be unsafe - always skip it
 *
 * Unlike every other native fix in this project, there is no "make the risky part conditional on a
 * *real* validity check" available here, because there is no real Node to check for - the value
 * being tested was never anything but leftover scratch data. The safe behavior is exactly what
 * already happens whenever the (accidentally-always-true) check fails: skip straight to the
 * destructor call and continue. This patch makes that the *only* outcome, by turning the existing
 * `beq 0x114fe0` (`0x114fc4`, taken only when the leftover value happens to read as zero - which it
 * never does) into an unconditional `b 0x114fe0` - a 1-instruction, condition-field-only change
 * (`0x0A` -> `0xEA`), same size, same branch target, verified via `arm-linux-gnueabi-as`/`ld` at the
 * real address plus the usual kotlinc harness + `objdump` + whole-file byte-diff.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call site
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original 4 bytes at this address
 * match byte-for-byte what this was built against - any mismatch leaves the file completely
 * untouched.
 */
object ElfMassEffectHudDebugNodeSkipPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // The single `beq 0x114fe0` instruction inside Hud::InitLayer(...) that (accidentally-always-
    // true) gates the buggy SetVisible() call described above.
    private const val TARGET_VADDR = 0x00114FC4L

    // Real original 4 bytes ("beq 0x114fe0"), confirmed via arm-linux-gnueabi-objdump and a raw
    // file-offset dump against the real extracted 1.0.58 libMassEffect.so.
    private val EXPECTED_ORIGINAL = byteArrayOf(0x05, 0x00, 0x00, 0x0A)

    // "b 0x114fe0" - same condition-branch encoding, only the top condition nibble changed from
    // EQ (0x0A) to AL (0xEA); assembled + linked at the real address + disassembled to confirm.
    private val PATCHED_BYTES = byteArrayOf(0x05, 0x00, 0x00, 0xEA.toByte())

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
            if (bytes[0] != 0x7F.toByte() || bytes[1] != 'E'.toByte() ||
                bytes[2] != 'L'.toByte() || bytes[3] != 'F'.toByte()
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
                "Hud::InitLayer() no longer treats a leftover boost::function manager pointer on its " +
                    "stack as if it were a real scene2d Node and calling SetVisible() on it - that " +
                    "check-and-call was always operating on unrelated scratch data (never a real Node), " +
                    "and always faulted when actually reached; it's now unconditionally skipped, exactly " +
                    "like the already-safe case where the leftover value happened to read as zero."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
