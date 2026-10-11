package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's ELEVENTH native SIGSEGV. Real-device
 * tested after fix #37 (the HUD debug-node destructor guard) shipped: the whole
 * `Hud::InitLayer()` HUD-initialization crash chain is gone (fixes #35/#36/#37 all confirmed
 * working together), and the game reaches an entirely different function before hitting this:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000028
 *     r0 <widget>  r1 <node ref>  r3 00000000
 * #00 im::scene2d_new::layouts::Widget::AppendChild(eastl::shared_ptr<im::scene2d_new::Node,...> const&)+16
 * #01 pc 00a78e78 (unresolved - lands in .data.rel.ro, not .text; an unreliable second frame from
 *     a frame-pointer-less unwind, not a genuine caller)
 * ```
 *
 * ## Root cause
 *
 * `Widget::AppendChild(node)` reads the child `Node*` out of the passed-in `shared_ptr<Node>`
 * reference (`ldr r3,[r1]`) and, without checking it for NULL, immediately reads a "name" string
 * pointer from offset 40 of it (`ldr r6,[r3,#40]`) to compare against two special child names
 * (`"_clip"`, `"_mask"`) before falling through to the generic `Group::AppendChild()` add. A NULL/
 * empty child `shared_ptr` (the caller passed a `Node` that was never actually created/assigned -
 * the same general "unchecked empty/failed result" pattern as fixes #32-#34) crashes on that very
 * first dereference, fault address exactly 40 (`0x28`), matching `r3 == 0` precisely.
 *
 * Unlike those earlier fixes, there is no cheap way to keep going here: `Group::AppendChild()`
 * itself (the function every path in `AppendChild()` eventually calls) is *also* not NULL-safe for
 * its `node` argument - it unconditionally dereferences the child's ref-count control block a few
 * instructions in whenever its internal child vector isn't currently empty, which would just
 * relocate this crash one level deeper depending on how many children the widget already has. So
 * simply making the two name-comparison strcmp() calls null-safe (e.g. by feeding them a pointer to
 * a harmless empty string) is not sufficient by itself - the real fix has to stop a NULL child from
 * ever reaching `Group::AppendChild()` at all: skip the append entirely and return, exactly as if
 * this call had never been made.
 *
 * ## The fix: an out-of-line trampoline ("code cave"), since there is no in-place room
 *
 * `AppendChild()`'s own instruction budget has no slack anywhere in it (every register copy and
 * every stack-pointer adjustment is genuinely needed on every path, verified instruction by
 * instruction - unlike fix #35, there was no redundant reload to repurpose here). Making the crash
 * site itself branch elsewhere and testing/returning from there needs 4 instructions (a compare, an
 * early conditional return that restores this function's own callee-saved registers and returns to
 * *its* caller, the original (now safe, since NULL was already excluded) name-pointer load, and a
 * normal return back into `AppendChild()`) - more than the single instruction being replaced, and
 * this function cannot be grown in place without breaking every subsequent address in the 17MB
 * binary. Instead, the single crashing instruction (`ldr r6,[r3,#40]`, at `0x483da8`) is replaced
 * with a same-size `bl` to a small 4-instruction trampoline written into 16 bytes of confirmed-
 * unused, never-referenced zero-padding in `.rodata` (`0x995914`-`0x995923`, part of the same
 * read+execute `LOAD` segment as `.text` on this binary, sitting just before a real rapidxml lookup
 * table with no relocation or symbol pointing into the 16 bytes used) - a classic code-cave
 * trampoline, not a change to `AppendChild()`'s own instruction count at all:
 * ```
 * cmp   r3, #0
 * popeq {r4, r5, r6, r7, pc}   ; r3==0: restore AppendChild's OWN saved registers (pushed at its
 *                              ; entry, untouched since - "sub sp,#20" hasn't run yet at this point)
 *                              ; and return straight to AppendChild's caller, skipping every
 *                              ; remaining instruction in the function, including the
 *                              ; Group::AppendChild() call.
 * ldr   r6, [r3, #40]          ; r3!=0 (normal case): do exactly what the replaced instruction did -
 *                              ; safe now, this is only reached when r3 is confirmed non-NULL.
 * bx    lr                     ; return to AppendChild(), which continues completely unchanged.
 * ```
 * Verified via `arm-linux-gnueabi-as`/`ld` at both real addresses (the call site and the cave, with
 * full surrounding instruction context reproduced and confirmed byte-identical to the pristine
 * binary aside from the one replaced instruction) plus the usual kotlinc harness + `objdump` +
 * whole-file byte-diff (20 bytes differ from the pristine original in the whole 17MB file: 4 at the
 * call site, 16 at the cave).
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other call site
 *
 * Same two-layer gate as every other native patch here, now across two regions: only looks at a
 * zip entry named `libMassEffect.so`, and even then only patches if the exact bytes at BOTH the
 * call site (the real original instruction) AND the cave (16 zero bytes, confirmed still unused)
 * match byte-for-byte what this was built against - any mismatch in either region leaves the file
 * completely untouched, applied atomically (both or neither), exactly like fix #35's multi-region
 * pattern. `Group::AppendChild()` and every other call site of `AppendChild()` itself are untouched.
 */
object ElfMassEffectAppendChildNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    // "ldr r6,[r3,#40]" inside Widget::AppendChild(...) - the crashing instruction.
    private const val TARGET_VADDR_CALLSITE = 0x00483DA8L

    // 16 bytes of confirmed-unused, unreferenced zero padding in .rodata, part of the same
    // read+execute LOAD segment as .text, immediately before a real rapidxml lookup table symbol
    // (0x9959f0) with nothing between here and there referencing this range.
    private const val TARGET_VADDR_CAVE = 0x00995914L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(0x28, 0x60, 0x93.toByte(), 0xE5.toByte())
    // "bl 0x995914"
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(0xD9.toByte(), 0x46, 0x14, 0xEB.toByte())

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(16) // all zero
    // cmp r3,#0 / popeq {r4,r5,r6,r7,pc} / ldr r6,[r3,#40] / bx lr
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x53, 0xE3.toByte(),
        0xF0.toByte(), 0x80.toByte(), 0xBD.toByte(), 0x08,
        0x28, 0x60, 0x93.toByte(), 0xE5.toByte(),
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact bytes at BOTH
     * the call site and the cave region match. Anything else (wrong file, either region mismatched)
     * is left completely untouched - this is all-or-nothing, never a partial application.
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

            val offCall = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val offCave = fileOffsetFor(TARGET_VADDR_CAVE)
            if (offCall == null || offCave == null ||
                offCall < 0 || offCall + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offCave < 0 || offCave + EXPECTED_ORIGINAL_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCall, EXPECTED_ORIGINAL_CALLSITE) || !matches(offCave, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at the call site or the cave region don't match " +
                    "expected (different build/version, or the cave space isn't actually free?) - " +
                    "left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCall + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            return Result(
                true,
                "im::scene2d_new::layouts::Widget::AppendChild() no longer dereferences a NULL/empty " +
                    "child Node pointer - it now safely skips the append entirely (returning immediately, " +
                    "via a small out-of-line trampoline) when the child shared_ptr passed in has no " +
                    "backing Node, instead of crashing."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
