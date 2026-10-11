package com.apkcompatpatcher.android

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * REAL FIX (not diagnostic) for Mass Effect: Infiltrator's EIGHTEENTH, NINETEENTH, TWENTIETH,
 * TWENTY-THIRD, TWENTY-FIFTH *and* TWENTY-SIXTH native crashes - all six at the exact same crash
 * site, fixed across six rounds: the first round's guard used too narrow a threshold, the second
 * round (meant to widen it) introduced a real regression, the third round fixed the regression,
 * the fourth round widened the threshold's *other* side, the fifth round added a completely new
 * check on the vtable pointer read *through* the element (rather than the element itself), and
 * this sixth round replaces that check's loose heuristic with the library's *actual* vtable
 * section bounds.
 *
 * ## Round 1 (0.45, shipped): exact-NULL guard
 *
 * Real-device tested after 0.44 (fix #43 + the proactive fix #44) shipped - a genuinely new
 * crash, one call further in than anything seen so far, landing right where fix #41's own guard
 * resumes execution:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
 * Cause: null pointer dereference
 * #00 Hud::StateTransition(Hud::State)+720
 * #01 Hud::InitWorld(GameWorld*)+712
 * #02 LayerGameWorld::LoadLevel(LoadTransition*)+76
 * ```
 * `Hud::StateTransition(Hud::State)` iterates a `this->[4]`-owned vector of raw pointers
 * (12-byte stride, begin/end at offsets 324/328 of that owned object) and calls a virtual method
 * (vtable slot `+448`) through EVERY element, with no null check on the element itself:
 * ```
 * ldr  r3, [r2, r6]      ; r3 = vector[index]           (r2 = begin, r6 = byte offset)
 * mov  r0, r3
 * ldr  r3, [r3]          ; r3 = element->vtable          <-- CRASH: element is invalid
 * mov  lr, pc
 * ldr  pc, [r3, #448]    ; call element->someVirtualMethod()
 * ```
 * `r0`, `r3` and the fault address were all exactly `0` in that first register dump, so 0.45
 * shipped a guard checking for exact NULL only: `cmp r0,#0` / `beq 0x114638` (skip straight to
 * the loop's post-call continuation) / `ldr r3,[r3]` / `bx lr`.
 *
 * ## Round 2 (0.46, shipped, WRONG): widened the threshold, broke the branch
 *
 * 0.45's own patch log confirmed this fix applied (`[fix] HudStateTransitionListenerNullGuard`),
 * but the very next real-device test crashed again, at a **new** address that is a dead giveaway
 * of exactly what went wrong:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000021
 * #00 pc 00995994  libMassEffect.so
 * ```
 * `0x995994` is `TARGET_VADDR_CAVE + 8` - the cave's own `ldr r3,[r3]` instruction, i.e. the
 * exact-NULL check (`cmp r0,#0`) correctly let this element through (`r0` was `0x21`, not `0`),
 * but `0x21` is exactly the same kind of small-integer "never constructed" sentinel value fixes
 * #42/#43/#44 already had to guard against elsewhere in this same input scheme (those saw `1` and
 * `0x1000`; this vector slot's sentinel happens to be `0x21`) - not a real heap pointer, but not
 * exactly zero either, so 0.45's narrower check missed it.
 *
 * 0.46 widened the check to `cmp r0,#0x1000` (correct), but its `bls` was assembled with a
 * *local* branch target (`0x995998`, the cave's own trailing `bx lr`, 12 bytes in) copied from a
 * different fix's cave shape instead of 0.45's own `beq 0x114638` (an *external* branch straight
 * to the loop's post-call continuation, past the entire inlined virtual call). Both the "skip"
 * and "fall-through" paths in 0.46's cave converge on the very same `bx lr`, which returns to
 * `0x114630` - the instruction right after the original callsite - either way. That address is
 * `mov lr,pc` immediately followed by `ldr pc,[r3,#448]`, the actual virtual dispatch - so 0.46's
 * guard branch, whichever way it went, always resumed the call. The `cmp`/`bls` executed, but had
 * no effect on control flow at all: 0.46 shipped a strictly wider check wired to a branch that
 * couldn't act on it.
 *
 * The next real-device test confirmed exactly this: a **new** crash type, one instruction further
 * in than either previous crash:
 * ```
 * Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x000001e1
 * #00 pc 00114634  libMassEffect.so  (Hud::StateTransition(Hud::State)+728)
 * ```
 * `0x114634` is `ldr pc,[r3,#448]` itself - the virtual dispatch 0.46's guard was supposed to
 * prevent for this element. `0x1e1 - 448 (0x1C0) = 0x21` - the exact same sentinel element as
 * before, reaching the exact same eventual dereference, because 0.46's branch never actually
 * diverted it. (A misaligned-load SIGBUS rather than SIGSEGV here only because `r3+448` happened
 * to land on a non-word-aligned address for this particular sentinel value - incidental, not a
 * different bug.)
 *
 * ## Round 3 (this fix): keep 0.45's branch target, only change its condition
 *
 * The actual minimal fix: 0.45's cave was structurally correct - only its condition was too
 * narrow. Restore the external `beq 0x114638` from 0.45 and change *only* its condition-code
 * nibble from EQ to LS, pairing it with the wider `cmp r0,#0x1000` (also from 0.46): `cmp
 * r0,#0x1000` / `bls 0x114638` / `ldr r3,[r3]` / `bx lr` - i.e. of the cave's 16 bytes, only ONE
 * byte differs from 0.45's original (the branch instruction's top nibble, `0x0A`->`0x9A`) besides
 * the `cmp` immediate's 2 bytes already changed in 0.46. The callsite (`bl 0x99598C`) and the
 * cave's address, its trailing `ldr r3,[r3]` / `bx lr`, and every other fix's cave are untouched.
 *
 * - **Callsite** (`0x11462C`, 4 bytes, unchanged since 0.45): `ldr r3,[r3]` becomes
 *   `bl 0x99598C`.
 * - **Cave** (`0x99598C`, 16 bytes, same address as 0.45/0.46's): `cmp r0,#0x1000` / `bls
 *   0x114638` (external - the loop's post-call continuation, exactly like 0.45's `beq`) / `ldr
 *   r3,[r3]` / `bx lr`.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` (confirms the branch resolves to `0x114638`, not a local cave offset) plus a
 * whole-file byte-diff (19 bytes differ from pristine: 4 at the call site, 15 of the cave's 16
 * bytes - two more than 0.45's 13, only because widening `cmp r0,#0` to `cmp r0,#0x1000` and
 * changing the branch's condition nibble both happen to turn a couple of bytes that were
 * pristine-zero in 0.45 into non-zero values; the byte *positions* touched are identical) - and
 * re-verified together with fixes #38-#44's own adjacent trampolines confirming all eight still
 * coexist with no interference (151 total bytes differ across all eight fixes combined - two more
 * than 0.45's 149, for the same reason, not a correctness concern).
 *
 * ## Round 4 (this fix, 0.50): the check only ever guarded against SMALL garbage
 *
 * 0.48 and 0.49 both made real progress on other crash sites in this same loop (fixes #46/#47) -
 * this exact site (fix #45's own cave) stayed quiet for two whole test rounds. Then it recurred,
 * with a register dump that rules out every theory used so far:
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xff0a0900
 * #00 pc 00995994  libMassEffect.so
 * r0  ff0a0900  r3  ff0a0900
 * ```
 * (An earlier, structurally identical crash during the Round 46 A/B test - `0xff100e00`, also
 * `SEGV_ACCERR`, also right here - was left open at the time specifically because a single
 * occurrence couldn't yet be told apart from a one-off fluke; this second, independent occurrence
 * confirms it's a real, recurring case.) `0xff0a0900` is neither `0` nor small - it sailed straight
 * through the `<= 0x1000` check unchanged since 0.47. Every real object pointer visible anywhere in
 * this same register dump (`r2`, `r4`, `r5`, `sp`, `ip`) sits in the `0xd8000000`-`0xdf000000`
 * range; both observed garbage values share a `0xff......` top byte, a strong, consistent
 * signature - almost certainly some fixed poison/fill pattern (an allocator's "freed" marker, or
 * simply how uninitialized memory happens to look on this particular device/build) rather than
 * arbitrary noise, but nothing this project can identify with certainty from a post-mortem log
 * alone. Whatever the exact origin, the fix doesn't need to know: a threshold that only ever
 * excluded values *near zero* was structurally incapable of catching this - it needed a second,
 * upper bound too.
 *
 * The fix widens the SAME check to a two-sided range: reject `r0` as before if `<= 0x1000`, and
 * now ALSO reject it if `>= 0xF0000000` - comfortably above every real pointer seen anywhere in
 * this project's crash logs, comfortably below both observed `0xff......` garbage values. Two
 * comparisons don't fit in the original 16-byte cave alongside the existing `ldr r3,[r3]` / `bx
 * lr`, so this cave MOVES to a fresh 24-byte slot in the same free `.rodata` block (the callsite's
 * `bl` target changes to match) - the old 16-byte slot is simply abandoned (left at its pristine
 * all-zero bytes, harmless, no patcher touches it anymore).
 *
 * - **Callsite** (`0x11462C`, 4 bytes): `ldr r3,[r3]` becomes `bl 0x9959AC` (was `bl 0x99598C`
 *   through 0.45-0.49).
 * - **Cave** (`0x9959AC`, 24 bytes, a fresh slot right after fix #46's cave): `cmp r0,#0x1000` /
 *   `bls 0x114638` / `cmp r0,#0xF0000000` / `bhs 0x114638` / `ldr r3,[r3]` / `bx lr` - both branches
 *   external, to the exact same loop-continuation target as every prior round.
 *
 * Assembled and linked at both real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` - explicitly checking BOTH branches' resolved target addresses (both `0x114638`) -
 * plus a whole-file byte-diff and a combined re-verification alongside fixes #38-#44's and #46's
 * own adjacent trampolines confirming everything still coexists with no interference (the old
 * 16-byte slot at `0x99598C` now contributes 0 bytes to the diff, since it's simply unused).
 *
 * ## Round 5 (this fix, 0.52): the ELEMENT can look valid while its VTABLE is garbage
 *
 * 0.51 (fix #48, a different call site) confirmed real progress, and this site (fix #45's own
 * cave) stayed quiet - until a NEW crash, at the same `Hud::StateTransition()+728` PC as every
 * prior round here, but with a register dump none of the four rounds so far can explain:
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xdd0aaa18
 * Cause: trying to execute non-executable memory.
 * r0  dce90160  r3  dcea8920
 * #00 pc 003aaa18  <anonymous:dcd00000>
 * #01 pc 00114634  libMassEffect.so (Hud::StateTransition(Hud::State)+728)
 * ```
 * `r0` (the element) is `0xdce90160` - comfortably inside the `0xC0000000`+ range this whole
 * project's evidence treats as "looks like a real heap pointer" (every genuine object pointer
 * ever seen in these crash logs sits in `0xd8000000`-`0xdf000000`), so it sails straight through
 * every check Round 4 has. The crash isn't in reading the element - it's one step later. `r3`
 * (`element->vtable`, read by the cave's own `ldr r3,[r3]`) is *also* `0xdc......` - but a real
 * vtable for this library lives in `.data.rel.ro`, a fixed, low, in-library address (this whole
 * project's evidence - every static address in every backtrace - shows this library loads with
 * zero bias on this device, so a genuine vtable pointer is well under 1/10th of `0xC0000000`).
 * `0xdcea8920` isn't a vtable at all - it's more of the exact same stale/uninitialized heap data
 * this whole project keeps finding, just now sitting where a vtable pointer should be. Dispatch
 * through it (`ldr pc,[r3,#448]`) then loads yet more heap garbage (`0xdd0aaa18`) and jumps to it
 * as code - landing in a heap region with no execute permission, hence `SEGV_ACCERR` /
 * "trying to execute non-executable memory" rather than a plain null-deref.
 *
 * The fix extends the SAME cave with one more check, this time on `r3` *after* the `ldr r3,[r3]`
 * that loads it: if it's `>= 0xC0000000` (looks heap-like rather than library-like), skip the
 * call exactly like every other rejected case here. The existing 24-byte cave has no room left
 * for an 8-byte addition, and the only contiguous free space left in the shared `.rodata` block is
 * a 29-byte tail after fix #48's cave - one short byte of the 32 needed for a single unbroken
 * block. Rather than hunt for a whole new free region elsewhere in the library, this fix chains
 * two small trampolines with a plain `b` (not `bl`, so `lr` - and thus the eventual return address
 * - is untouched throughout): the ORIGINAL `bl` target (`0x9959AC`, unchanged since Round 4, so
 * the callsite's own patched bytes don't even need to change) now holds only the two existing `r0`
 * checks (20 bytes) ending in an unconditional `b` into a second fragment at `0x9959D4` (in the
 * newly-free tail) holding `ldr r3,[r3]` / the new `r3` check / `bx lr` (16 bytes). Round 4's old
 * 24-byte cave region past the first 20 bytes, and the old Round 1-3 16-byte slot at `0x99598C`,
 * are both simply abandoned (pristine zero, unused, available for a future fix).
 *
 * - **Callsite** (`0x11462C`, 4 bytes, unchanged since Round 4): `ldr r3,[r3]` becomes
 *   `bl 0x9959AC`.
 * - **Cave part 1** (`0x9959AC`, 20 bytes): `cmp r0,#0x1000` / `bls 0x114638` /
 *   `cmp r0,#0xF0000000` / `bhs 0x114638` / `b 0x9959D4`.
 * - **Cave part 2** (`0x9959D4`, 16 bytes, a fresh slot right after fix #48's cave): `ldr r3,[r3]`
 *   / `cmp r3,#0xC0000000` / `bhs 0x114638` / `bx lr`.
 *
 * Assembled and linked at all three real addresses with `arm-linux-gnueabi-as`/`ld`, verified with
 * `objdump` - explicitly checking all three branches' resolved target addresses (two external to
 * `0x114638`, one internal to `0x9959D4`) - plus a combined re-verification alongside every other
 * fix's trampoline in the same shared `.rodata` block confirming everything still coexists with no
 * interference.
 *
 * ## Round 6 (this fix, 0.53): the heap-range heuristic wasn't tight enough either
 *
 * 0.52 shipped and was real-device tested - a NEW crash, same PC (`Hud::StateTransition()+728`,
 * `0x114634`, the vtable dispatch) as every round since Round 1, but a register dump Round 5's
 * `>= 0xC0000000` check doesn't catch:
 * ```
 * signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x00dfaab1
 * r0  dfc85701  r3  00dfa8f1
 * #00 pc 00114634  libMassEffect.so (Hud::StateTransition(Hud::State)+728)
 * ```
 * The element (`r0`) is `0xdfc85701` - again comfortably inside the real-pointer heap band, passes
 * cleanly. But this time the vtable pointer read through it (`r3`) is `0x00dfa8f1` - nowhere near
 * the heap band Round 5 was built to reject, but *also* not a real vtable: it's well past the end
 * of this library's own mapped image (`0xb3de58`, per its own program headers), and misaligned to
 * boot (`SIGBUS`/`BUS_ADRALN` rather than `SIGSEGV`, since `0xdfa8f1 & 3 != 0`). Round 5's
 * `>= 0xC0000000` check was really only ever a heuristic for "looks like the ONE bad pattern seen
 * so far" - there's no reason to expect every future garbage 32-bit value to share that pattern.
 *
 * Rather than add a SEVENTH one-off threshold for whatever the next garbage pattern turns out to
 * be, this round replaces the heuristic with the actual, structural answer: every real vtable in
 * this binary is emitted by the compiler into one specific section, `.data.rel.ro`
 * (`0x00A372A0`-`0x00A80D8C` per this library's own section headers) - nowhere else in the entire
 * 4GB address space is a legitimate vtable pointer ever going to point. The check becomes a
 * two-sided range test against that section (widened very slightly on each side, to the nearest
 * ARM-immediate-encodable round values - `0xA30000` low / `0xA90000` high - since the exact section
 * bounds aren't themselves encodable as a single ARM `cmp` immediate; the small margins stay well
 * clear of both `.text`/`.rodata` below and the heap band above, so nothing genuine is lost).
 *
 * `0x00dfa8f1` (~14.66M) is comfortably above even the widened `0xA90000` (~11.08M) bound, so this
 * check rejects it (as it would have rejected Round 5's `0xdcea8920` too - a strict superset).
 *
 * - **Callsite / cave part 1**: unchanged since Round 4/5 (`0x11462C` -> `bl 0x9959AC`, `0x9959AC`
 *   20 bytes: the two element-pointer checks, branching to part 2 on success).
 * - **Cave part 2** (`0x9959D4`, now 24 bytes, grown in place into 8 more bytes of the same free
 *   tail): `ldr r3,[r3]` / `cmp r3,#0xA30000` / `blo 0x114638` / `cmp r3,#0xA90000` /
 *   `bhs 0x114638` / `bx lr`.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking both new branches' resolved targets (`0x114638`, same as every other round) - plus a
 * combined re-verification alongside all eleven other fixes confirming no interference.
 *
 * ## Round 7 (this fix, 0.64): the VTABLE can be real while the SLOT inside it is garbage
 *
 * 0.63 (fix #58, a different call site) confirmed real progress, and this site stayed quiet -
 * until a NEW crash, found in the 0.62 test, with a register dump none of the six rounds so far
 * can explain, and critically a backtrace that couldn't even resolve a symbol:
 * ```
 * signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00065b3b
 * r0 00065b3b  r3 00065b3b
 * 1 total frames
 * #00 pc 009959d4  libMassEffect.so
 * ```
 * No frame #01, no function name - the unwinder couldn't even find a caller, because `pc` itself
 * had jumped into `.rodata` (`0x9959d4` sits inside this library's own `.rodata` section,
 * `0x94f690`-`0x9FAFF0` - coincidentally very close to this very patcher's own cave addresses, but
 * unrelated to them; every fragment this project has ever placed stays below `0x954000`). Working
 * backward from the raw device `pc`/`lr` pair and this library's (zero) load bias confirms the
 * actual caller: `lr` (translated) is `0x114630` - the instruction immediately after Round 6's
 * cave's own `bx lr`, i.e. this exact call site, having just passed every check Round 6 added.
 *
 * The register dump makes the gap obvious in hindsight: Round 6's check validates that `r3`
 * (`element->vtable`) itself falls inside `.data.rel.ro` - the section real vtables live in - but
 * says nothing about what's stored INSIDE that vtable. A real, correctly-located vtable can still
 * have a stale/never-initialized value sitting in one specific slot (offset `0x1c0`, the one this
 * loop calls through) - the original code loads that slot's value straight into `pc` with zero
 * validation (`ldr pc,[r3,#448]`), so a garbage function pointer is jumped to as code exactly like
 * a garbage data pointer is dereferenced everywhere else in this project.
 *
 * The fix adds a THIRD check, this time on the actual function pointer read from the vtable slot,
 * before ever jumping through it: it must fall inside this library's own executable region
 * (`.plt`+`.text`, `0x97f78`-`0x924684` per this library's own section headers - widened to
 * `0x90000`/`0x930000`, the nearest ARM-immediate-encodable round values with safe margin on both
 * sides). Round 6's cave part 2 has no room left for four more instructions, and the only free
 * space remaining in the shared `.rodata` block is scattered 8-byte gaps (no single contiguous run
 * big enough), so this round chains FIVE more small fragments off cave part 2's own end - the
 * exact same "1 real instruction + hop, 2 for the terminal" technique already used throughout this
 * project (fixes #54/#55/etc): cave part 2's own trailing `bx lr` becomes `b` into the new chain,
 * and the chain's own last fragment ends in the real `bx lr`.
 *
 * - **Callsite / cave part 1**: unchanged since Round 4/5/6 (`0x11462C` -> `bl 0x9959AC`,
 *   `0x9959AC` 20 bytes: the two element-pointer checks).
 * - **Cave part 2** (`0x9959D4`, 24 bytes, unchanged except its OWN last instruction): the vtable
 *   load and its two `.data.rel.ro`-bounds checks are untouched; only the trailing `bx lr` (4
 *   bytes) becomes `b 0x9537A8` (still 4 bytes - same size, so nothing else in this fragment
 *   moves).
 * - **Cave part 3** (five fresh 8-byte `.rodata` gaps, chained):
 *   - `0x9537A8` (K1): `ldr ip,[r3,#0x1c0]` (loads the vtable slot's actual function pointer) /
 *     `b 0x9539A0`
 *   - `0x9539A0` (K2): `cmp ip,#0x90000` / `b 0x953A30`
 *   - `0x953A30` (K3): `blo 0x114638` (invalid: below the executable region, jump to the loop's
 *     post-call continuation - same target as every prior round) / `b 0x953AA8`
 *   - `0x953AA8` (K4): `cmp ip,#0x930000` / `b 0x953AD8`
 *   - `0x953AD8` (K5, terminal): `bhs 0x114638` (invalid: above the executable region) /
 *     `bx lr` (valid: the real return, letting the untouched original `mov lr,pc`/`ldr
 *     pc,[r3,#448]` execute exactly as before - it re-reads the same slot, now already proven
 *     safe)
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump` - explicitly
 * checking all five new branch targets - and then verified again with this project's `qemu-arm`
 * micro-harness, entering at the real callsite with a fully synthetic element/vtable/slot chain in
 * memory: both a bogus slot value (`0x9959d4`, the exact real crash's jump target) and a valid
 * in-`.text` slot value (`0x114638`) resolve to the correct outcome - the loop's post-call
 * continuation for the invalid case (the real dispatch instruction at `0x114634` never reached),
 * and a confirmed real dispatch landing exactly on the injected valid address for the valid case.
 *
 * ## Round 8 (shipped as 0.69): cave part 1's OWN element-pointer check was too loose
 *
 * The 0.68 test's crash log showed this same call site faulting again - but this time landing
 * *inside this patcher's own cave part 2* (`#00 pc 009959d4`, a single frame, no caller resolved -
 * `0x9959d4` sits inside this library's `.rodata`, so the unwinder can't find a function there):
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x80000035
 *     r0  80000035  r1  aaaa7f0f  r2  e2ec8014  r3  80000035
 * #00 pc 009959d4  libMassEffect.so
 * ```
 * Translating the raw `pc`/`lr` through this library's (zero) load bias gives `lr = 0x114630` -
 * exactly the return address `bl 0x9959AC` (cave part 1's own callsite, since Round 4) pushes, i.e.
 * this is genuinely a call INTO this patcher's own cave, not some unrelated wild jump. `r0`/`r3`
 * (the element pointer) are both `0x80000035` - a mid-range garbage value. Cave part 1's Round
 * 4-era check only rejects `r0 <= 0x1000` (small sentinels) or `r0 >= 0xF0000000` (very-high wild
 * values) - `0x80000035` is neither, so it sailed straight through both checks and into cave part
 * 2's `ldr r3,[r3]`, which faults trying to dereference it. This exact "narrow sentinel/wild-range
 * check missed a mid-range garbage value" shape is the same one fix #60's own Round 2 hit and fixed
 * the same way: cave part 1 predates this project's later, tighter `< 0xC0000000` real-heap-floor
 * convention (used by every guard added from fix #48 onward), so it never got the memo.
 *
 * A second real-device occurrence (the 0.67 test, same fault addr `0x80000035`, same landing site,
 * same translated `lr = 0x114630`, only the heap-base-dependent high bits of the surrounding
 * registers differing) confirms this is a deterministic bug at this call site, not a one-off race -
 * the same stale/corrupt element value reliably recurs here.
 *
 * Round 8 simply widens cave part 1 to the same proven floor already used everywhere else: its two
 * checks (`cmp r0,#0x1000`/`bls` and `cmp r0,#0xF0000000`/`bcs`, 4 instructions) collapse into one
 * (`cmp r0,#0xC0000000`/`blo`), with the two freed instruction slots filled with `nop` so the
 * fragment's total size (20 bytes) - and therefore every later cave's address - stays unchanged.
 * Cave parts 2 and 3 are untouched.
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump`, and re-verified
 * with the `qemu-arm` micro-harness entering at the real callsite (`0x11462C`): the exact real crash
 * value (`r0 = r3 = 0x80000035`) now resolves to the loop's post-call continuation (`0x114638`)
 * without ever reaching cave part 2, and a plausible real element pointer (`r0 = r3 = 0xddf10cd4`,
 * comfortably above the new floor) resolves to landing exactly on cave part 2 (`0x9959d4`) exactly
 * as before this round.
 *
 * ## Round 9 (shipped as 0.70): Round 8 dropped the upper bound entirely
 *
 * The 0.69 test's crash log showed the exact same call site faulting a THIRD time, same landing
 * site, same translated `lr = 0x114630`, but a new element-pointer value:
 * ```
 * signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xff0b0a00
 *     r0  ff0b0a00  r1  aaaa7db9  r2  e3b48014  r3  ff0b0a00
 * #00 pc 009959d4  libMassEffect.so
 * ```
 * `0xff0b0a00` is comfortably above Round 8's `0xC0000000` floor, so it sailed straight through -
 * Round 8 collapsed the OLD two-sided check (`<= 0x1000` OR `>= 0xF0000000`) down to a ONE-sided
 * floor check, silently dropping the upper-bound protection that would have caught this value
 * (`0xff0b0a00 >= 0xF0000000`) along the way. (`r1`'s `0xaaaa`-prefixed value - a classic
 * uninitialized/poisoned-memory pattern - has now shown up in all three crash logs at this exact
 * site, reinforcing that this vector slot is reading genuinely uninitialized memory, not a
 * corrupted-but-otherwise-normal pointer; its value isn't itself read by this guard, but it's a
 * useful confirming signature.)
 *
 * Round 9 restores a real ceiling, but keeps Round 8's wider floor - a proper two-sided range check
 * (`0xC0000000 <= r0 < 0xF0000000`), matching the exact style already used by cave part 2 (vtable
 * bounds) and cave part 3 (function-pointer bounds) elsewhere in this same file: `cmp r0,#0xC0000000`
 * / `blo` bail / `cmp r0,#0xF0000000` / `bhs` bail / `b` continue - now using all five of cave part
 * 1's instruction slots productively (no `nop` padding needed this round).
 *
 * Assembled and linked with `arm-linux-gnueabi-as`/`ld`, verified with `objdump`, and re-verified
 * with the `qemu-arm` micro-harness entering at the real callsite: both real crash values (`0x80000035`
 * from Round 8's crash and `0xff0b0a00` from this round's) now resolve to the loop's post-call
 * continuation without ever reaching cave part 2, and a plausible real element pointer
 * (`r0 = r3 = 0xddf10cd4`) still resolves to landing exactly on cave part 2 as before.
 *
 * ## Safety for every other game (IG2, Haegemonia, anything else) and every other element
 *
 * Same two-layer gate as every other native patch here: only looks at a zip entry named
 * `libMassEffect.so`, and even then only patches if the exact original (pristine) bytes at both
 * regions match byte-for-byte what this was built against - any mismatch leaves the file
 * completely untouched; both regions are applied together or not at all. For a real (non-null,
 * non-sentinel, non-wild) element, behavior is provably identical to the original - same load,
 * same call, same return address.
 */
object ElfMassEffectHudStateTransitionListenerNullGuardPatcher {

    data class Result(val patched: Boolean, val note: String)

    private const val PT_LOAD = 1
    private class Phdr(val pType: Int, val pOffset: Long, val pVaddr: Long, val pFilesz: Long)

    private const val TARGET_VADDR_CALLSITE = 0x0011462CL
    private const val TARGET_VADDR_CAVE = 0x009959ACL
    private const val TARGET_VADDR_CAVE2 = 0x009959D4L
    private const val TARGET_VADDR_CAVE_K1 = 0x009537A8L
    private const val TARGET_VADDR_CAVE_K2 = 0x009539A0L
    private const val TARGET_VADDR_CAVE_K3 = 0x00953A30L
    private const val TARGET_VADDR_CAVE_K4 = 0x00953AA8L
    private const val TARGET_VADDR_CAVE_K5 = 0x00953AD8L

    private val EXPECTED_ORIGINAL_CALLSITE = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte()
    )
    private val PATCHED_BYTES_CALLSITE = byteArrayOf(
        0xDE.toByte(), 0x04, 0x22, 0xEB.toByte()
    )

    // Cave part 1 (0x9959AC, 20 bytes). Round 4-7: two separate range checks (r0 <= 0x1000 OR
    // r0 >= 0xF0000000). Round 8 (0.69) collapsed these into a single "< 0xC0000000" floor check,
    // which then let 0xff0b0a00 (>= 0xF0000000) through in the 0.69 test - see the class doc's
    // Round 9 section. Round 9 (0.70) restores a proper two-sided range check, keeping Round 8's
    // wider floor but adding back a real ceiling: valid iff 0xC0000000 <= r0 < 0xF0000000, using
    // all five instruction slots (no `nop` padding needed this round). Ends in an unconditional
    // branch into part 2 either way - lr is untouched by a plain "b", so part 2's eventual "bx lr"
    // still returns straight to the original callsite's return address.
    private val EXPECTED_ORIGINAL_CAVE = ByteArray(20)
    private val PATCHED_BYTES_CAVE = byteArrayOf(
        0x03, 0x01, 0x50, 0xE3.toByte(),
        0x20, 0xFB.toByte(), 0xDD.toByte(), 0x3A,
        0x0F, 0x02, 0x50, 0xE3.toByte(),
        0x1E, 0xFB.toByte(), 0xDD.toByte(), 0x2A,
        0x04, 0x00, 0x00, 0xEA.toByte()
    )

    // Cave part 2 (0x9959D4, 24 bytes as of Round 6, grown in place from Round 5's 16 bytes into
    // 8 more bytes of the same free tail): the vtable load plus a precise two-sided range check
    // against this library's actual .data.rel.ro (vtable) section bounds. As of Round 7 the
    // trailing instruction is no longer "bx lr" (the real return) but "b 0x9537A8" - the same 4
    // bytes' worth of space, now continuing into cave part 3's function-pointer check instead of
    // returning directly.
    private val EXPECTED_ORIGINAL_CAVE2 = ByteArray(24)
    private val PATCHED_BYTES_CAVE2 = byteArrayOf(
        0x00, 0x30, 0x93.toByte(), 0xE5.toByte(),
        0xA3.toByte(), 0x08, 0x53, 0xE3.toByte(),
        0x15, 0xFB.toByte(), 0xDD.toByte(), 0x3A,
        0xA9.toByte(), 0x08, 0x53, 0xE3.toByte(),
        0x13, 0xFB.toByte(), 0xDD.toByte(), 0x2A,
        0x6E, 0xF7.toByte(), 0xFE.toByte(), 0xEA.toByte()
    )

    // Cave part 3 (Round 7, NEW): five chained 8-byte fragments in fresh free `.rodata` gaps -
    // loads the vtable slot's actual function pointer and checks it falls inside this library's
    // own executable region (.plt+.text, ~0x97f78-0x924684, widened to 0x90000/0x930000) before
    // ever letting the original code jump through it.
    private val EXPECTED_ORIGINAL_CAVE_K = ByteArray(8)
    private val PATCHED_BYTES_CAVE_K1 = byteArrayOf(
        0xC0.toByte(), 0xC1.toByte(), 0x93.toByte(), 0xE5.toByte(),
        0x7B, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K2 = byteArrayOf(
        0x09, 0x08, 0x5C, 0xE3.toByte(),
        0x21, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K3 = byteArrayOf(
        0x00, 0x03, 0xDF.toByte(), 0x3A,
        0x1B, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K4 = byteArrayOf(
        0x93.toByte(), 0x08, 0x5C, 0xE3.toByte(),
        0x09, 0x00, 0x00, 0xEA.toByte()
    )
    private val PATCHED_BYTES_CAVE_K5 = byteArrayOf(
        0xD6.toByte(), 0x02, 0xDF.toByte(), 0x2A,
        0x1E, 0xFF.toByte(), 0x2F, 0xE1.toByte()
    )

    /**
     * Patches [bytes] IN PLACE if [entryName] is `libMassEffect.so` and the exact original bytes
     * at BOTH target regions match. Anything else (wrong file, either mismatch) is left completely
     * untouched - this is all-or-nothing, never a partial application.
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

            val offCallsite = fileOffsetFor(TARGET_VADDR_CALLSITE)
            val offCave = fileOffsetFor(TARGET_VADDR_CAVE)
            val offCave2 = fileOffsetFor(TARGET_VADDR_CAVE2)
            val offK1 = fileOffsetFor(TARGET_VADDR_CAVE_K1)
            val offK2 = fileOffsetFor(TARGET_VADDR_CAVE_K2)
            val offK3 = fileOffsetFor(TARGET_VADDR_CAVE_K3)
            val offK4 = fileOffsetFor(TARGET_VADDR_CAVE_K4)
            val offK5 = fileOffsetFor(TARGET_VADDR_CAVE_K5)
            val offsK = listOf(offK1, offK2, offK3, offK4, offK5)
            if (offCallsite == null || offCave == null || offCave2 == null || offsK.any { it == null } ||
                offCallsite < 0 || offCallsite + EXPECTED_ORIGINAL_CALLSITE.size > bytes.size ||
                offCave < 0 || offCave + EXPECTED_ORIGINAL_CAVE.size > bytes.size ||
                offCave2 < 0 || offCave2 + EXPECTED_ORIGINAL_CAVE2.size > bytes.size ||
                offsK.any { it!! < 0 || it + EXPECTED_ORIGINAL_CAVE_K.size > bytes.size }
            ) {
                return Result(false, "one or more target addresses don't map into this file - not touching")
            }
            fun matches(off: Int, expected: ByteArray): Boolean {
                for (i in expected.indices) if (bytes[off + i] != expected[i]) return false
                return true
            }
            if (!matches(offCallsite, EXPECTED_ORIGINAL_CALLSITE) || !matches(offCave, EXPECTED_ORIGINAL_CAVE) ||
                !matches(offCave2, EXPECTED_ORIGINAL_CAVE2) ||
                offsK.any { !matches(it!!, EXPECTED_ORIGINAL_CAVE_K) }
            ) {
                return Result(false, "original bytes at one or more target regions don't match expected " +
                    "(different build/version?) - left completely untouched")
            }
            for (i in PATCHED_BYTES_CALLSITE.indices) bytes[offCallsite + i] = PATCHED_BYTES_CALLSITE[i]
            for (i in PATCHED_BYTES_CAVE.indices) bytes[offCave + i] = PATCHED_BYTES_CAVE[i]
            for (i in PATCHED_BYTES_CAVE2.indices) bytes[offCave2 + i] = PATCHED_BYTES_CAVE2[i]
            for (i in PATCHED_BYTES_CAVE_K1.indices) bytes[offK1!! + i] = PATCHED_BYTES_CAVE_K1[i]
            for (i in PATCHED_BYTES_CAVE_K2.indices) bytes[offK2!! + i] = PATCHED_BYTES_CAVE_K2[i]
            for (i in PATCHED_BYTES_CAVE_K3.indices) bytes[offK3!! + i] = PATCHED_BYTES_CAVE_K3[i]
            for (i in PATCHED_BYTES_CAVE_K4.indices) bytes[offK4!! + i] = PATCHED_BYTES_CAVE_K4[i]
            for (i in PATCHED_BYTES_CAVE_K5.indices) bytes[offK5!! + i] = PATCHED_BYTES_CAVE_K5[i]
            return Result(
                true,
                "Hud::StateTransition() no longer dereferences a NULL/never-constructed small-sentinel " +
                    "entry (<= 0x1000), a wild/garbage entry (>= 0xF0000000), an entry whose vtable " +
                    "pointer itself falls outside this library's actual vtable section " +
                    "(.data.rel.ro, ~0xA30000-0xA90000), OR an entry whose vtable is real but whose " +
                    "own function-pointer slot (offset 0x1c0) doesn't fall inside this library's " +
                    "actual executable region (.plt+.text, ~0x90000-0x930000) in its per-frame " +
                    "listener/layer vector before calling a virtual method through it - that one " +
                    "element's call is now skipped entirely (the loop continues normally with the " +
                    "next element, exactly as if this element simply weren't in the vector) instead " +
                    "of crashing or jumping into non-executable/misaligned memory."
            )
        } catch (e: Exception) {
            return Result(false, "error while checking/patching: ${e.message}")
        }
    }
}
