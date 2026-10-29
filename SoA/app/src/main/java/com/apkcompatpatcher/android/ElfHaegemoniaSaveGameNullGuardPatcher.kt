package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Game-specific (Haegemonia: Legions of Iron only) native patch: guards `cSaveGame::SetGameState
 * (cGameInfo*, cWorld*)` against a NULL sub-record pointer when loading a save that doesn't carry a
 * "type == 1" block in its game-state data. This is the save/load crash found on a real-device 1.04
 * test: saving the game and then loading that very save - even without fully exiting the app first -
 * crashed it every time (confirmed on at least one save slot; other slots were suspected too, and
 * since the root cause below is structural rather than slot-specific, the fix applies uniformly to
 * any save that hits this code path, not just the one that was reproduced).
 *
 * ## Background
 *
 * Found from a real-device crash log (tablet, Logcat native tombstone, 2026-09/10):
 *
 *   Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
 *   Cause: null pointer dereference
 *   #00 cSaveGame::SetGameState(cGameInfo*, cWorld*)+492
 *   #01 cGame::ScenarioLoad(...)
 *   #02 cOptionsScreen::Load()
 *   ... (the game's own "Load" menu action -> scenario load -> save-state restore chain)
 *
 * Objdump-disassembling `cSaveGame::SetGameState` around the faulting offset (+492, `0x0029DAB0`):
 *
 *   29daa6: 2801        cmp   r0, #1          ; record-type tag read earlier in the loop
 *   29daa8: f040 837a   bne.w 0x29e1a0        ; type != 1: skip this record (the function's own
 *                                             ;   pre-existing "not applicable" exit)
 *   29daac: 6820        ldr   r0, [r4, #0]    ; r0 = some sub-record pointer off cGameInfo (r4)
 *   29daae: 9509        str   r5, [sp, #0x24] ; (unrelated local spill, kept as-is)
 *   29dab0: 6805        ldr   r5, [r0, #0]    ; <-- FAULTS HERE: r0 is NULL for this save
 *   29dab2: 2000        movs  r0, #0
 *   ...
 *
 * The crash register dump confirms `r0 == 0x0` at the fault - `[r4, #0]` (the "type==1" sub-record
 * inside the save's `cGameInfo`) is legitimately absent/NULL for whatever save format or game state
 * produced the crashing saves, and the loader assumes it's always populated once the type tag matches.
 * Same family as this whole project's long-running "a sub-object pointer isn't always populated when
 * a caller assumes it always is" pattern (the Mass Effect GameObject-accessor and culling-null-guard
 * fixes, etc.), just in Haegemonia's own save/load code this time.
 *
 * ## The fix
 *
 * `SetGameState`'s own very next conditional branch (`bne.w 0x29e1a0`, two instructions earlier) is
 * already exactly the "this record doesn't apply, skip it" exit the function uses for a non-matching
 * record type - reused here unchanged as the safe bail-out when the type DOES match (`r0`/type==1) but
 * the pointer it hands back is NULL. The redirect only touches the two instructions at `0x0029DAAE`
 * (`str r5,[sp,#0x24]` / `ldr r5,[r0,#0]`, 4 bytes) - replaced with a branch into a small cave that:
 *
 *   str  r5, [sp, #0x24]   ; replicate the original, unrelated spill - untouched behavior
 *   cmp  r0, #0
 *   bne.w cont             ; r0 != 0 (non-NULL): skip the far branch below
 *   b.w  0x0029E1A0        ; r0 == 0 (NULL): bail to the function's own pre-existing "skip" exit
 *   cont:
 *   ldr  r5, [r0, #0]      ; replicate the original, now-guarded read (only reached when safe)
 *   b.w  0x0029DAB2        ; resume the function's own very next instruction
 *
 * The two-instruction `bne.w`/`b.w` pair (rather than a single conditional branch straight to
 * `0x29E1A0`) is needed because the safe-exit target is a little over 4 MB away from the cave, outside
 * Thumb-2's +-1 MB conditional-branch range; the unconditional `b.w` used for the actual jump has the
 * full +-16 MB range and covers it comfortably. Both branches in the cave are plain PC-relative Thumb-2
 * branches (no `ldr rX,=SYMBOL` absolute-address literal anywhere), so the fix is inherently ASLR-safe
 * by construction - the same discipline this project adopted after the earlier Haegemonia video-folder
 * patch's own ASLR bug (see [ElfHaegemoniaDirectVideoFolderPatcher]'s doc comment).
 *
 * Cave lives at `0x006856C4`, 18 bytes into the same 2048-byte all-zero, non-relocated `.rodata` gap
 * [ElfHaegemoniaDirectVideoFolderPatcher] already vetted at `0x0068560C` (184 bytes claimed there as of
 * its own round 2) - this fix claims the next 18 still-unused bytes of that same gap, re-confirmed zero
 * and relocation-free at this specific offset independently of that earlier patch.
 *
 * Verified two ways against the real, extracted `libhaegemonia_base.so`: objdump-disassembled both the
 * entry-point redirect and the full cave after patching (every branch resolves to exactly the intended
 * target - `0x6856C4`, `0x6856D0`, `0x29E1A0`, `0x29DAB2`); and run under the `unicorn` CPU emulator
 * directly against the patched binary, mapped at a deliberately nonzero simulated load bias, entering
 * at the real `ldr r0,[r4,#0]` instruction for two scenarios - `r0` resolving to NULL (reaches the
 * function's own safe exit at `0x29E1A0`, no fault) and `r0` resolving to valid memory (reaches the
 * resume point at `0x29DAB2` with `r5` correctly holding the dereferenced value, confirming the
 * guarded read still executes correctly on the valid path).
 *
 * ## Why this is safe for every OTHER game (IG2, Mass Effect: Infiltrator, Colin McRae Rally)
 *
 * Same discipline as every other native patcher here: applied only to a zip entry literally named
 * `libhaegemonia_base.so`, and even then only if the exact original bytes at both target regions (the
 * two-instruction redirect site, and the cave) match byte-for-byte what was recorded here from the real
 * Haegemonia 1.00 build. Every save that does NOT hit this exact NULL case is completely unaffected -
 * the guarded read executes with the identical original semantics either way.
 */
object ElfHaegemoniaSaveGameNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_ENTRY = 0x0029DAAEL
    private const val TARGET_VADDR_CAVE = 0x006856C4L

    // str r5,[sp,#0x24] / ldr r5,[r0,#0] - the two original instructions right after the
    // type==1 check in cSaveGame::SetGameState, the second of which dereferences a NULL pointer.
    private val EXPECTED_ORIGINAL_ENTRY = byteArrayOf(0x09, 0x95.toByte(), 0x05, 0x68)

    // b.w 0x6856c4
    private val PATCHED_BYTES_ENTRY = byteArrayOf(0xE7.toByte(), 0xF3.toByte(), 0x09, 0xBE.toByte())

    private val EXPECTED_ORIGINAL_CAVE = ByteArray(18)

    // str r5,[sp,#0x24] / cmp r0,#0 / bne.w cont / b.w 0x29e1a0 / cont: ldr r5,[r0,#0] / b.w 0x29dab2
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x09, 0x95.toByte(), 0x00, 0x28,
        0x40, 0xF0.toByte(), 0x02, 0x80.toByte(),
        0x18, 0xF4.toByte(), 0x68, 0xBD.toByte(),
        0x05, 0x68, 0x18, 0xF4.toByte(),
        0xEE.toByte(), 0xB9.toByte()
    )

    fun fixIfNeeded(entryName: String, bytes: ByteArray): Result {
        if (!entryName.endsWith("libhaegemonia_base.so")) {
            return Result(false, "not libhaegemonia_base.so - not applicable")
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

            val entryOff = fileOffsetFor(TARGET_VADDR_ENTRY)
            val caveOff = fileOffsetFor(TARGET_VADDR_CAVE)

            if (entryOff == null || caveOff == null ||
                entryOff + 4 > bytes.size || caveOff + PATCHED_BYTES_CAVE.size > bytes.size
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }

            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }

            if (!matches(entryOff, EXPECTED_ORIGINAL_ENTRY) || !matches(caveOff, EXPECTED_ORIGINAL_CAVE)) {
                return Result(false, "original bytes at one or more target regions don't match expected (different build/version, or the Haegemonia video-folder fix already claimed this gap differently) - left completely untouched")
            }

            fun write(off: Int, patch: ByteArray) { for (i in patch.indices) bytes[off + i] = patch[i] }
            write(entryOff, PATCHED_BYTES_ENTRY)
            write(caveOff, PATCHED_BYTES_CAVE)

            return Result(
                true,
                "cSaveGame::SetGameState no longer dereferences a NULL sub-record pointer when loading a " +
                    "save whose \"type==1\" game-state block is absent (fault addr 0x0, confirmed via a " +
                    "real-device crash reproduced by saving and then loading that very save) - it now " +
                    "skips that record via the function's own existing \"not applicable\" exit instead of " +
                    "crashing, exactly as it already does for a non-matching record type."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
