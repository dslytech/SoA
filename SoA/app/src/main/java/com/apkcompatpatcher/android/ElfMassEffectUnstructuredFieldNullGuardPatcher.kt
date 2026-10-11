package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Mass Effect: Infiltrator only) native patch: guards `im::serialization::
 * Database`'s three "unstructured object field" methods against a NULL `param_1`
 * (`ObjectHeaderDefinition*`) - a bug this project has NOT yet hit on a real device, found instead
 * by reading `v-atamanenko/masseffect-vita`'s (MIT-licensed,
 * https://github.com/v-atamanenko/masseffect-vita) source directly, after the user pointed at that
 * repository a second time. Its `loader/patch/deserialization_bug.c` hooks exactly these three
 * addresses with a `if (!param_1) { ...crash-avoidance... }` guard, and its own README documents
 * the user-visible symptom this prevents: "After you die or quit to menu and resume game, you may
 * see the long loading screen telling you that you should wait for about 40 seconds. This is a side
 * effect of fixing another, game-breaking bug" - i.e. without a guard here, resuming after death or
 * returning from the main menu crashes outright; their long-loading-screen workaround exists only
 * because they can't recover a native NULL-deref crash cleanly on Vita and have to fully relaunch
 * the game process instead. This project's own established in-process "just skip and return a safe
 * default" technique doesn't need that workaround at all.
 *
 * All three addresses were cross-checked against this project's own extracted 1.0.58
 * `libMassEffect.so` symbol table before writing this patch: every one of them lands exactly on the
 * demangled C++ symbol the Vita loader's comment says it does (see
 * [ElfMassEffectOnlineStubPatcher]'s doc comment for the original SHA1 cross-check that first
 * established this is byte-for-byte the same build), so there is no ambiguity about what's being
 * patched here.
 *
 * ## The three sites
 *
 * - `Database::GetUnstructuredObjectField(ObjectHeaderDefinition*, int, FieldDefinition**)` at
 *   `0x003B7C78` - `param_1` (`r1`) is read via `ldrb` at the object's very first field the moment
 *   the (8-register) prologue finishes; guarded to return `0` (the same "field not found" failure
 *   this function's own return type already uses elsewhere) instead of dereferencing NULL.
 * - `Database::AddUnstructuredObjectField(ObjectHeaderDefinition*, int, FieldType const&)` at
 *   `0x003BB158` - same `param_1` dereferenced the same way; guarded to return NULL (the same "field
 *   couldn't be added" pointer-failure result).
 * - `Database::RemoveUnstructuredObjectField(ObjectHeaderDefinition*, FieldDefinition*)` at
 *   `0x003B620C` - a `void` method; guarded to simply return immediately (removing a field that
 *   doesn't logically exist is correctly a no-op).
 *
 * ## The fix
 *
 * Each site's own first instruction is its (multi-register) `push` - relocated verbatim into the
 * cave exactly like every other guard in this project (a `push`/`pop` is a completely ordinary,
 * non-PC-relative instruction, safe to relocate byte-for-byte). The guard sits BEFORE that push, at
 * the function's true entry point, so the invalid path never has to unwind anything: it can `bx lr`
 * straight back to the real caller, using the exact `lr` that caller originally set up - no manual
 * `lr` arithmetic needed anywhere (same style of clean design as fix #76's vtable guard).
 *
 * Same standard 4-fragment "checked-pointer-null" cave shape used throughout this project (`cmp
 * r1,#0` / two-branch fragment / relocated-original-instruction-then-resume fragment / bail
 * fragment), just checking `r1` (this function family's actual vulnerable parameter) instead of the
 * usual `r0`/`this`. Every branch assembled and objdump-verified (not hand-encoded), and both the
 * invalid (`r1 = 0`) and valid (`r1` = a real pointer) paths for all three sites confirmed via the
 * qemu-arm harness: invalid always returns cleanly to the caller's own `lr` with the documented safe
 * default in `r0`; valid always falls through to the exact original, untouched instruction sequence.
 *
 * ## Why this is safe for every OTHER game
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libMassEffect.so`, and even then only if the exact original bytes at a given site's regions match
 * byte-for-byte what was recorded here from the real 1.0.58 build - any mismatch just skips that one
 * site, independent of the other two, without touching or even parsing anything for any other file.
 */
object ElfMassEffectUnstructuredFieldNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String, val siteNotes: List<String> = emptyList())

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private class Site(
        val name: String,
        val callsiteVaddr: Long,
        val expectedCallsite: ByteArray,
        val callsitePatch: ByteArray,
        val f1Vaddr: Long, val f1Patch: ByteArray,
        val f2Vaddr: Long, val f2Patch: ByteArray,
        val f3Vaddr: Long, val f3Patch: ByteArray,
        val bailVaddr: Long, val bailPatch: ByteArray,
        val description: String
    )

    private fun h(hex: String) = ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte() }

    // All bytes below were produced by assembling/linking the real trampoline source with
    // arm-linux-gnueabi-as/ld against the exact cave addresses used, then extracting the resulting
    // section bytes with objcopy - not hand-derived - and independently confirmed via
    // arm-linux-gnueabi-objdump (every branch resolves to exactly the intended target) and the
    // qemu-arm harness (both an invalid r1=0 and a valid r1=heap-pointer case per site).
    private val SITES = listOf(
        Site(
            name = "GetUnstructuredObjectField",
            callsiteVaddr = 0x003B7C78L,
            expectedCallsite = h("f047 2de9".replace(" ", "")), // push {r4,r5,r6,r7,r8,r9,sl,lr}
            callsitePatch = h("1e5f16ea"),
            f1Vaddr = 0x0094F8F8L, f1Patch = h("000051e3050800ea"),
            f2Vaddr = 0x00951918L, f2Patch = h("b00b000a990b00ea"),
            f3Vaddr = 0x00954788L, f3Patch = h("f0472de93a8de9ea"),
            bailVaddr = 0x009547E0L, bailPatch = h("0000a0e31eff2fe1"),
            description = "Database::GetUnstructuredObjectField(...) no longer dereferences a NULL " +
                "ObjectHeaderDefinition* param - returns 0 (field not found) instead of crashing."
        ),
        Site(
            name = "AddUnstructuredObjectField",
            callsiteVaddr = 0x003BB158L,
            expectedCallsite = h("f04f2de9"), // push {r4,r5,r6,r7,r8,r9,sl,fp,lr}
            callsitePatch = h("e46516ea"),
            f1Vaddr = 0x009548F0L, f1Patch = h("000051e3f30000ea"),
            f2Vaddr = 0x00954CC8L, f2Patch = h("c000000a0f0000ea"),
            f3Vaddr = 0x00954D10L, f3Patch = h("f04f2de91099e9ea"),
            bailVaddr = 0x00954FD0L, bailPatch = h("0000a0e31eff2fe1"),
            description = "Database::AddUnstructuredObjectField(...) no longer dereferences a NULL " +
                "ObjectHeaderDefinition* param - returns NULL (field couldn't be added) instead of crashing."
        ),
        Site(
            name = "RemoveUnstructuredObjectField",
            callsiteVaddr = 0x003B620CL,
            expectedCallsite = h("30002de9"), // push {r4,r5}
            callsitePatch = h("737b16ea"),
            f1Vaddr = 0x00954FE0L, f1Patch = h("000051e3250000ea"),
            f2Vaddr = 0x00955080L, f2Patch = h("2000000a0b0000ea"),
            f3Vaddr = 0x009550B8L, f3Patch = h("30002de95384e9ea"),
            bailVaddr = 0x00955108L, bailPatch = h("1eff2fe10000a0e1"),
            description = "Database::RemoveUnstructuredObjectField(...) no longer dereferences a NULL " +
                "ObjectHeaderDefinition* param - returns immediately (removing a field that doesn't " +
                "exist is already correctly a no-op) instead of crashing."
        )
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

            var patchedCount = 0
            val siteNotes = mutableListOf<String>()

            for (s in SITES) {
                val regions = listOf(
                    s.callsiteVaddr to s.expectedCallsite,
                    s.f1Vaddr to ByteArray(8), s.f2Vaddr to ByteArray(8),
                    s.f3Vaddr to ByteArray(8), s.bailVaddr to ByteArray(8)
                )
                // Only the callsite region has a real "expected original" to check (the actual
                // instruction being replaced) - the four cave regions must simply be all-zero
                // (confirmed-free .rodata padding), exactly like every other cave-based fix here.
                var ok = true
                var failNote = ""
                for ((vaddr, expected) in regions) {
                    val off = fileOffsetFor(vaddr)
                    if (off == null || off < 0 || off + 8 > bytes.size) {
                        ok = false; failNote = "0x${vaddr.toString(16)} doesn't map into this file"; break
                    }
                    if (vaddr == s.callsiteVaddr) {
                        for (i in expected.indices) {
                            if (bytes[off + i] != expected[i]) {
                                ok = false
                                failNote = "callsite 0x${vaddr.toString(16)} original bytes don't match expected (different build/version?)"
                                break
                            }
                        }
                    } else {
                        for (i in 0 until 8) {
                            if (bytes[off + i] != 0.toByte()) {
                                ok = false
                                failNote = "cave region 0x${vaddr.toString(16)} isn't free (different build/version, or already used?)"
                                break
                            }
                        }
                    }
                    if (!ok) break
                }
                if (!ok) {
                    siteNotes.add("${s.name}: $failNote - left completely untouched")
                    continue
                }
                fun write(vaddr: Long, patch: ByteArray) {
                    val off = fileOffsetFor(vaddr)!!
                    for (i in patch.indices) bytes[off + i] = patch[i]
                }
                write(s.callsiteVaddr, s.callsitePatch)
                write(s.f1Vaddr, s.f1Patch)
                write(s.f2Vaddr, s.f2Patch)
                write(s.f3Vaddr, s.f3Patch)
                write(s.bailVaddr, s.bailPatch)
                patchedCount++
                siteNotes.add("${s.name}: ${s.description}")
            }

            if (patchedCount == 0) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version?) - left completely untouched", siteNotes)
            }
            return Result(
                true,
                "guarded $patchedCount/${SITES.size} im::serialization::Database unstructured-object-field " +
                    "method(s) against a NULL ObjectHeaderDefinition* param (found via v-atamanenko/masseffect-vita's " +
                    "loader source, not yet seen on a real device here - see doc comment)",
                siteNotes
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
