package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards
 * `im::serialization_old::Deserializer`'s copy constructor against being copy-constructed
 * from a NULL/absent source reference.
 *
 * ## Background
 *
 * Found from a real-device crash log (Hihonor tablet, 2026-09-14) - a NEW crash class for this
 * project: it happens on a background asset-loading worker thread ("Thread-9"), not the render
 * thread (GLThread) every prior crash here has come from:
 *
 *   Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000, tid 26720 (Thread-9)
 *   Cause: null pointer dereference
 *   #00 im::serialization_old::Deserializer::Deserializer(im::serialization_old::Deserializer const&)+0
 *   #01 ParticleSystem::CacheSystem(eastl::basic_string<char,...> const&, float, unsigned int, im::m3gext::ModelCache*)+376
 *   #02 LoadTaskCache::Run()+8916
 *   #03 im::RunFunction(void*)+12
 *   #04 RunnableFunctionInternal(void*)+452
 *   #05 __pthread_start / #06 __start_thread
 *
 * Objdump-disassembling `ParticleSystem::CacheSystem(...)` shows the call chain leading here:
 * it calls `Deserializer::GetStructArray(name)` to look up a named struct-array entry inside the
 * currently-loading particle-effect definition file, and unconditionally copy-constructs a local
 * `Deserializer` FROM that result - `GetStructArray()`'s result slot is only populated with a
 * real `Deserializer*` if the named struct array actually exists in this particular particle
 * effect's data; when it doesn't (this effect simply doesn't define that block), the slot is left
 * NULL, and `CacheSystem()` copy-constructs from it anyway, passing that NULL straight through as
 * the copy constructor's `other` reference parameter (r1). The copy constructor's very first
 * instruction (`ldr r2,[r1]`, offset+0 in the backtrace, matching exactly) then dereferences it -
 * fault addr `0x0` confirms this precisely.
 *
 * `Deserializer::Deserializer(Deserializer const&)` is itself a small, shared leaf function
 * (no stack frame, never calls out) used everywhere a `Deserializer` needs copying - 16 separate
 * call sites throughout this library, not just this one. Rather than guard only the one observed
 * caller (`ParticleSystem::CacheSystem`), this fix guards the copy constructor's own entry point
 * once, covering every caller at once - the same "shared function, guard universally" choice
 * already used for fixes #77 (im::serialization::Database, a sibling but DIFFERENT namespace -
 * `serialization` vs `serialization_old` - unrelated bug, coincidentally similar shape), #78 and
 * #79.
 *
 * ## The fix
 *
 * On entry, checks `r1` (the `other` reference) for NULL. If it's non-NULL, the function runs
 * completely unchanged - the original first instruction executes exactly as before, then control
 * resumes at the function's own next instruction. If `r1` is NULL, the function does NOT
 * dereference it at all: instead it directly zeroes the three fields it would otherwise have
 * copied from `other` (`this->field0`, `this->field1`, `this->field3`, at offsets 0, 4 and 12) -
 * using `r1` itself as the zero source, since `r1` is already known to be exactly 0 on this path,
 * saving a `mov r2,#0` - and returns immediately via the function's own original `bx lr`,
 * skipping the refcount-increment block entirely (which would itself dereference the
 * now-known-absent `field1` pointer). This produces a validly-constructed but empty/inert
 * `Deserializer` (every pointer-shaped field zeroed) rather than either crashing or leaving the
 * new object's fields at whatever uninitialized stack garbage happened to be there before - a
 * strictly safer outcome for any downstream code that reads this object afterward, and a
 * behavior change only for this one previously-crashing case; every real copy is untouched.
 *
 * Objdump-confirmed (every branch resolves to exactly the intended target) and harness-verified:
 * `r1=0` on entry writes zero to all three fields and reaches the function's own original `bx lr`
 * with no fault, and `r1` pointing at real memory reaches the function's own original next
 * instruction with `r2` holding the exact value read from `[r1]` - the guarded read still
 * executes correctly on the valid path.
 *
 * ## Why this is safe for every OTHER game (IG2, Haegemonia, anything else)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally
 * named `libMassEffect.so`, and even then only if the exact original bytes at every one of this
 * fix's regions match byte-for-byte what was recorded here from the real 1.0.58 build.
 */
object ElfMassEffectDeserializerCopyCtorNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x003BE6ECL
    private const val TARGET_VADDR_CAVE = 0x009FA7F8L

    // ldr r2, [r1]  (Deserializer copy-ctor's own first instruction, unguarded)
    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x20, 0x91.toByte(), 0xE5.toByte()
    )
    // b 0x9fa7f8
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0x41, 0xF0.toByte(), 0x18, 0xEA.toByte()
    )

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(32)

    // cmp r1,#0 / bne valid / str r1,[r0] / str r1,[r0,#4] / str r1,[r0,#12] / b 0x3be720
    // valid: ldr r2,[r1] / b 0x3be6f0
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x00, 0x00, 0x51, 0xE3.toByte(),
        0x03, 0x00, 0x00, 0x1A,
        0x00, 0x10, 0x80.toByte(), 0xE5.toByte(),
        0x04, 0x10, 0x80.toByte(), 0xE5.toByte(),
        0x0C, 0x10, 0x80.toByte(), 0xE5.toByte(),
        0xC3.toByte(), 0x0F, 0xE7.toByte(), 0xEA.toByte(),
        0x00, 0x20, 0x91.toByte(), 0xE5.toByte(),
        0xB5.toByte(), 0x0F, 0xE7.toByte(), 0xEA.toByte()
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
                callsiteOff + 4 > bytes.size || caveOff + 32 > bytes.size
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
                "im::serialization_old::Deserializer's copy constructor no longer dereferences a NULL " +
                    "'other' reference (fault addr 0x0, confirmed via a real-device crash on a background " +
                    "asset-loading thread while caching a particle system) - it now safely constructs an " +
                    "empty/inert Deserializer instead of crashing, for every one of this function's 16 " +
                    "call sites throughout the library."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
