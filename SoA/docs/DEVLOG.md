> **Development log (historical).** This is the long internal engineering log of the project, kept for reference.
> The "1.xx" numbers in it are internal build numbers; the first public release is **0.1**.
> Read the main `README.md` first.

# SoA (Save old APK)

An on-device Android app version of [apk-compat-patcher](../apk-compat-patcher):
pick your own, legally-owned old (~2011-2015) SDL2-based game APK right on
your tablet/phone, tap Patch, tap Install - no PC, no Python, no Java
install required on the device itself.

**This app does not contain, embed, or download any game files.** You point
it at your own APK via Android's normal file picker, and it writes a
patched copy to its own private storage, which you can then tap to install
through the normal system installer. Nothing here is a substitute for
owning the game.

## Read this before building it

This project was originally written without the ability to compile or run
it against a real device - it was built in a sandboxed environment whose
network policy blocks Google's and Maven Central's servers, which is
exactly where Android's own SDK and this app's own dependencies (`smali`,
`baksmali`, `apksig`) live. **That's no longer the current state**: the app
has since been built successfully in Android Studio and run on real
hardware, including a full patch pipeline against a real game APK, and (the
same way the PC tool was originally debugged) a real crash was found via
`adb logcat`, root-caused, and fixed - see "Fixed via real-device testing"
below. The section right after this one is kept as history, for context on
how the patch logic was originally validated before any of that was
possible.

That doesn't mean the original approach was a guess, though - two
different things were deliberately kept separate:

- **The actual patch logic** (find `SDL_ffmpeg` in a manifest / find the
  Moga controller crash / find the AdManager crash, and produce the fixed
  version) **was validated**: it's a straight, careful port of the exact
  same string/byte logic already proven on your two real games in the PC
  tool, and it was re-run in this sandbox against the real, decoded smali
  and manifest data from both of your actual games, then diffed
  byte-for-byte against the PC tool's own (already-working) output. All of
  it matched exactly. So *if* this app builds and the on-device dex
  decode/re-encode step works, what it decides to change and how should be
  correct.
- **The `smali`/`baksmali`/`apksig` library calls that wire that logic up**
  (`DexPatcher.kt`, `ApkSigningUtil.kt`) were written from memory of those
  libraries' APIs, since the actual library code couldn't be downloaded
  here to check field/method names against. This is the part most likely
  to need a fix.

**If you hit a red squiggly line or a build error in Android Studio**,
that's expected on a first attempt - copy the *exact* error text (Android
Studio will name one specific class/method/field it couldn't find) and
send it back. That's normally a one-line fix, not a redesign.

**If it builds but crashes on your device**, use the in-app "Napló
letöltése" (download log) button - it writes every step to a log file
(everything the console `[patch] ...` lines showed in the PC tool, plus
the full stack trace of whatever went wrong) and saves it straight to the
device's Downloads folder, no share sheet involved. Send that file back.
If the patcher app itself ran fine but the *patched game* then crashes or
shows a black screen, a real `adb logcat` capture while reproducing that
(see "Fixed via real-device testing" below for the exact commands and a
worked example) is far more useful than the in-app log, since the crash
is happening in the *game's* process, not this app's.

## Fixed via real-device testing

One real crash has been found and fixed this way so far: the bundled Moga
Bluetooth-controller SDK's `Controller.init()` throws
`IllegalArgumentException: Service Intent must be explicit` on modern
Android (see `docs/case-study.md` in the PC tool for the general
background on this bug). The original on-device patch for this used an
exact literal-text match lifted from one specific decompile, the same way
the PC tool's `patch_moga_controller` does - too brittle in practice, since
the precise smali bytecode for the same source line (register numbers,
whether debug `.line`/`.local` pragmas are even present) can differ
between builds and between baksmali configurations, so the match silently
failed to apply. It's now a whole-method try/catch wrap
(`DexPatcher.kt`'s `wrapWholeMethod`, the same mechanism already used for
the AdManager fix), which works regardless of the exact bytecode shape.

That whole-method wrap was *also* verified (via a hand-run simulation
against real decompiled smali) before ever touching a device, and it
*still* failed on the tablet, twice - "found `Controller.smali` /
`AdManager.smali`, but the wrap didn't match anything inside it" per the
in-app log. The actual cause turned up only after directly diffing that
assumption against the real on-device library: `BaksmaliOptions()`'s own
default is `localsDirective = false`, which makes baksmali emit
`.registers N` (every register, including parameters, numbered `vN`)
instead of `.locals N` (only the non-parameter locals, with parameters
named `p0`/`p1`/...). `wrapWholeMethod` specifically looks for a `.locals`
line to know where to insert the try-block and how to allocate a spare
register - with the library's own default, that line never existed in the
real output, so the wrap silently no-opped on every method it touched. The
verification step that "confirmed" the logic beforehand had used
apktool-decoded smali as ground truth, and apktool's baksmali *command-line
tool* defaults `--use-locals` to on - a different default than the
`BaksmaliOptions` *library* class this app calls directly - so that gap
never showed up until a real device was in the loop. The fix is one line:
`DexPatcher.kt` now sets `localsDirective = true` explicitly on the
`BaksmaliOptions` it builds, so on-device output always matches what was
actually verified. (`DexPatcher.kt` also now logs the first ~14 lines of
raw smali for either method if a wrap ever fails to match again, so any
*next* mismatch shows the real text directly instead of needing another
round of guessing.)

**Round 5 - Haegemonia: dead ad SDK, background thread this time.** Same
day, next game: Haegemonia patched cleanly (all three dex fixes applied),
installed, launched - and still crashed, with a *different* stack trace
this time:

```
E AndroidRuntime: FATAL EXCEPTION: Chartboost Thread #2
Process: com.microids.google.HaegemoniaFull
java.lang.NoClassDefFoundError: Failed resolution of: Lorg/apache/http/HttpEntity;
    at com.chartboost.sdk.impl.az$b$a.<init>(SourceFile:322)
    at com.chartboost.sdk.impl.az$b.run(SourceFile:259)
```

This is `docs/case-study.md`'s Round 4 crash exactly (`org.apache.http.*`
was removed from Android's boot classpath years ago; old ad SDKs like
Chartboost still reference it) - except on a *background* thread the
`AdManager.getMessage()` wrap doesn't cover, because this particular call
never goes through `AdManager` at all; it's the Chartboost SDK's own
thread calling straight into the missing classes. This is exactly the gap
the PC tool's fix #5 (a global background-thread uncaught-exception
handler) exists to close - and it turned out to be *much* easier to port
than first thought (see "What it fixes" below): while checking, the PC
tool itself turned out to have the same fix defined but never actually
wired up (`patch_apk.py`'s `SILENT_HANDLER_CLASS` was built as a string
but never written to a file in the decoded smali tree, so the handler it
installs referenced a class that didn't exist - fixed there too, in the
same commit as this one).

**Round 6 - Colin McRae Rally: a totally different engine, same install-vs-load fight.**
Third game, and this time the patch log showed *nothing* matched (no
`libSDL_ffmpeg.so`, no Moga SDK, no `AdManager`) - because this game isn't
SDL2 at all, it's Unity3D (`UnityPlayerNativeActivity`). It still crashed:

```
E AndroidRuntime: FATAL EXCEPTION: main
java.lang.UnsatisfiedLinkError: Unable to load native library ".../lib/arm/libmain.so":
dlopen failed: "...libmain.so" has text relocations
    at android.app.NativeActivity.onCreate(NativeActivity.java:181)
    at com.unity3d.player.UnityPlayerNativeActivity.onCreate(...)
```

Same underlying cause as the `libSDL_ffmpeg.so` fix (fix #2 below) - a
native library built the old way (pre-2014 NDK, no `-fPIC`), which modern
Android's linker refuses to `dlopen()` once `targetSdkVersion` is 23+ - but
this time it's not a skippable video-helper library, it's the game's *own*
engine (`libmain.so`, and - checked while investigating - `libunity.so` and
`libmono.so` too, all three). No stub is possible for the actual game
engine, so this needed a real fix in
[`ElfTextRelPatcher.kt`](app/src/main/java/com/apkcompatpatcher/android/ElfTextRelPatcher.kt).

First attempt, and why it wasn't enough: mark the code segment holding the
relocation targets writable, clear the flag that makes the linker refuse
to load the file. This was shipped and tested for real - it DID clear the
"has text relocations" error, but a fresh `adb logcat` capture showed a
*different* refusal right after it:

```
dlopen failed: "...libmain.so" has load segments that are both writable
and executable
```

Turns out Android has a *second*, independent linker hardening check,
unconditional (not gated by `targetSdkVersion` at all): it refuses to load
any ELF with a segment that's simultaneously writable and executable
("W^X"). Permanently marking the code segment writable is exactly what
that check exists to catch - a real dead end, not a tuning problem.

The actual fix disassembles the bytes right before every problem
relocation and, in all three affected libraries, finds the exact same
thing: a tiny, universal, pre-2014-toolchain C++ cleanup trampoline (loads
a DSO handle, tail-calls `__cxa_finalize()` - runs static destructors when
a library is `dlclose()`'d, which an Android game process essentially
never does). Since it never actually runs, it's disabled outright: its
first instruction is overwritten with `bx lr` (immediate return), and the
relocation entries that used to target its now-dead literal-pool bytes are
voided (`r_info` zeroed to the standard "do nothing" `R_ARM_NONE` type)
instead of satisfied. No code segment ever needs to become writable, so
neither linker check has anything left to object to. Full reasoning,
exact byte patterns, and a fallback path for any *unrecognized* case are
in that file's doc comment. Applies automatically to every `.so` in the
APK that needs it, not just these three specific files - fix #6 below.

Checked offline against the real extracted `libmain.so`/`libmono.so`/
`libunity.so` before shipping: all three problem relocations matched the
recognized trampoline pattern exactly (no fallback case needed for any of
them), `readelf` confirms `TEXTREL` is gone and no `PT_LOAD` segment ever
gets both `W` and `E`, file sizes are unchanged, and a disassembly of each
patched trampoline site shows the `bx lr` landing correctly with the real
function right after it completely untouched.

**Confirmed on real hardware**: Colin McRae Rally now launches and plays
after this fix, same real device as the IG2/Haegemonia confirmations
above. (Android shows its own unrelated stock compatibility banner on
first launch - "This app is not optimized for your device, you may
experience performance issues during gameplay" - that's Android's normal
generic warning for any app whose declared device/screen profile doesn't
exactly match the hardware, unrelated to anything this tool patches; it's
not an error and doesn't affect whether the game runs.)

**Round 7 - Mass Effect: Infiltrator and Dead Space, two EA "Blast"-engine
games, two different problems.**

Mass Effect crashed with a brand new error shape - not a text relocation:

```
java.lang.UnsatisfiedLinkError: dlopen failed: library
"D:/EAMT/masseffectinfiltrator_eamt/Packages/EAOS/Framework2/RL/AndroidSDK/
14-ndk-r7-1/installed/android-ndk-r7/platforms/android-9/arch-arm/usr/lib/
libc.so" not found: needed by .../libMassEffect.so in namespace clns-9
```

`libMassEffect.so` has a `DT_NEEDED` entry - "I need this other library
loaded first" - whose value isn't a plain library name at all, it's a dead
absolute path from whatever Windows machine built/linked this file back in
~2012. Modern bionic takes that string completely literally as a filename
to search for, and obviously no such path exists on an Android device, so
it refuses to load the library at all.
[`ElfNeededPathPatcher.kt`](app/src/main/java/com/apkcompatpatcher/android/ElfNeededPathPatcher.kt)
fixes this the same low-risk, in-place way as the other ELF fixes: finds
the broken string in the library's string table and rewrites it down to
just its basename (`libc.so`), zero-filling the leftover bytes - nothing
else in the file moves or changes size. Confirmed on the real device: a
fresh patch log shows all three broken paths in the real
`libMassEffect.so` fixed (`libc.so`, `libstdc++.so`, `libm.so`), and the
original `dlopen failed: ... not found` crash is gone from the next
`adb logcat` - the app now gets past that point and into `onCreate()`.

It then hit a second, later-stage crash - a completely different problem,
not a native-library one at all:

```
java.lang.IllegalArgumentException: Service Intent must be explicit:
Intent { act=com.sec.android.iap.service.iapService }
  at ...ContextImpl.bindServiceCommon
  at ...MassEffectActivity.initializeIAP(MassEffectActivity.java:47)
  at ...PlasmaWrapper.<init>(PlasmaWrapper.java:39)
  at ...MassEffectActivity.loadPlasma(MassEffectActivity.java:9)
  at ...MassEffectActivity.onCreate(MassEffectActivity.java:94)
```

This is EA's old "Plasma" SDK (leaderboards/IAP glue used across several
EA mobile ports of this era) unconditionally trying to bind to the
**Samsung** Galaxy Apps in-app-purchase service by its bare intent action
string, with no package set - an implicit service `Intent`, forbidden
since Android 5.0, and doubly certain to fail on a non-Samsung device
(a Hihonor tablet has no such service to resolve to at all).
[`DexPatcher.kt`](app/src/main/java/com/apkcompatpatcher/android/DexPatcher.kt)
now wraps any `initializeIAP()` method it finds in a try/catch, the exact
same whole-method-wrap technique already used for the Moga controller and
`AdManager` fixes - matched by method name rather than by a fixed file
path, since the containing class/package is different in every game
(here `com.ea.games.meinfiltrator_gamepad.MassEffectActivity`), so this
should also catch other old EA "Plasma"-SDK ports that hit the same
problem. Confirmed on the real device: the `IllegalArgumentException`
above is gone from the next `adb logcat` - the app now gets past
`onCreate()` entirely and starts rendering frames.

It then hit a *third* crash, again a completely different subsystem -
this time on a background thread (`GLThread`, not `main`):

```
java.lang.SecurityException: : The uid 10252 does not meet the
requirements to access device identifiers.
  at ...TelephonyManager.getDeviceId
  at com.eamobile.download.DownloadActivityInternal.getAndroidUniqueId(...)
  at com.eamobile.download.DownloadActivityInternal.printDeviceAndAppInfo(...)
  at com.eamobile.download.DownloadActivityInternal.init(...)
  at com.eamobile.download.DownloadActivity.init(DownloadActivity.java:2)
  at com.ea.blast.AndroidRenderer.onDrawFrame(AndroidRenderer.java:9)
  at android.opengl.GLSurfaceView$GLThread.guardedRun
```

Yet another dead-EA-SDK problem: `com.eamobile.download` is EA's shared
"phone home and check for game updates/DLC" component from this same
era, called once per frame from the game's own render loop. It tries to
read the device's IMEI via the old `TelephonyManager.getDeviceId()` API
to identify the device to EA's (decade-dead) download servers - and
since Android 10, that call is flatly refused with a `SecurityException`
to any app that isn't a system/carrier component, permanently, on any
device. Not a fluke, not device-specific - that exact call can never
succeed again on a modern OS for an app like this.
[`DexPatcher.kt`](app/src/main/java/com/apkcompatpatcher/android/DexPatcher.kt)
now wraps the outer `com/eamobile/download/DownloadActivity.smali`'s
`init()` in a try/catch (guarding the whole dead download-check
subsystem in one place, not just the one call that happened to be the
first to throw, in case a retest turns up another failure further down
the same chain) - scoped to that fixed file path, the same way the Moga
and AdManager fixes are, since `com/eamobile/download` is EA's own
shared SDK package, not this specific game's. Verified offline with a
synthetic smali method shaped like the real one; not yet retested on the
real device.

One more thing this log incidentally confirmed, relevant to Dead Space
below: right before this third crash, the exact same window-boxing
symptom shows up for *Mass Effect* too -
`mBounds=Rect(605, 0 - 1316, 1200)`, the same
`"The window frame calculated by WMS is inconsistent..."` warning, and
critically, log lines naming `com.android.server.wm.Letterbox` and
surfaces called `"Letterbox - left"`/`"Letterbox - right"` being created
around the game's window. That's stock Android's own (Android 12+)
fixed-orientation/aspect-ratio compatibility letterboxing system - a
real, documented AOSP feature, not something specific to this Hihonor
tablet's own shell. Seeing it independently on a second, unrelated old
game is strong evidence this is a systemic "old fixed-orientation game
vs. modern Android's own letterbox compat system" issue, not a Dead
Space-only or Hihonor-only quirk - which also means `android.max_aspect`
(below) is aimed at the actual right mechanism, not just a plausible
guess.

Dead Space is a different, harder problem: it doesn't crash at all, it
launches and runs, but renders small and centered instead of filling the
screen - and once came up correctly full-screen, then didn't again after
a reinstall, so whatever's wrong isn't 100% deterministic. The one solid
lead from the log: very shortly after `DeadSpaceActivity` starts, Android
relaunches (destroys and recreates) it -
`ActivityThread.handleRelaunchActivityInner` fires while the GL surface is
still being set up - and the game's internal renderer, which sizes its
surface once at startup, doesn't know to resize what it draws to match
afterwards.

Getting the actual (unpatched) Dead Space APK to inspect confirmed why:
`DeadSpaceActivity` already declares
`android:configChanges="0x000004A0"` - `orientation | keyboardHidden |
screenSize`, i.e. the handful of config changes that mattered on a
2011-era phone. It's just missing `screenLayout`, `uiMode`,
`smallestScreenSize`, `density`, and `layoutDirection` - config aspects
this Hihonor tablet's multi-window-aware shell ("Magic Win") actively
juggles right at launch, in a way a 2011 phone never did, and every one of
which still triggers the same destroy-and-recreate behavior if it isn't
explicitly declared as self-handled.
[`ManifestPatcher.widenActivityConfigChanges()`](app/src/main/java/com/apkcompatpatcher/android/ManifestPatcher.kt)
ORs those five extra bits into whatever `configChanges` value an activity
already declares (existing bits are only ever added to, never cleared) -
the same low-risk, overwrite-an-existing-4-byte-value technique as the
`targetSdkVersion` patch, since Dead Space happening to already declare a
partial `configChanges` is what makes this a safe in-place fix rather
than a much riskier AXML insertion (adding the attribute to a game that
doesn't declare it *at all* would mean growing the string pool and
attribute table - not attempted here; such a game is simply left alone by
this specific fix). Verified offline against the real extracted
`AndroidManifest.xml`: the patched manifest still parses cleanly, every
byte is unchanged except the one that actually holds this flag
(`0x04A0` -> `0x3FA0`), and only `.DeadSpaceActivity`'s declaration is
touched.

That fix alone didn't visibly change anything on a real re-test (two
tablet photos, before/after, looked the same). Two follow-up `adb logcat`
captures - one of a broken cold start, one spanning a Home-button-then-
Recents-back cycle that the user found reliably fixes it to full-screen
for the rest of that run - gave much stronger evidence: the *same*
`DeadSpaceActivity` window's `WindowManager`-reported `mBounds` flips
between `Rect(0,0-1920,1200)` tagged `"lrg land"` (correct, full screen)
and `Rect(605,0-1316,1200)` tagged `"nrml port"` (711px wide, centered -
the broken state) within a single session, right after a log line reading
`"The window frame calculated by WMS is inconsistent with the size
requested by the app."` That's the platform actively boxing/unboxing the
same window, not a one-time layout bug in the game.

The manifest also already declares
`<meta-data android:name="android.max_aspect" android:value="2.400000"/>`
on `DeadSpaceActivity` - Android's own pre-8.0, officially documented
mechanism for "letterbox/pillarbox me past this aspect ratio" (the
modern replacement is the `android:maxAspectRatio` activity attribute).
2.4:1 covered the tallest phones anyone shipped around 2013; it's far
narrower than this tablet's own screen ratio, so it's a real, live,
currently-in-effect limit.
[`ManifestPatcher.widenMaxAspect()`](app/src/main/java/com/apkcompatpatcher/android/ManifestPatcher.kt)
raises it to effectively "no limit" (99.0), the same in-place,
overwrite-a-4-byte-value technique as the `configChanges` fix. One real
wrinkle worth recording: `android:value="2.400000"` *looks* like a string
in decoded XML, but byte-level inspection of the real manifest showed
it's actually stored as a raw `TYPE_FLOAT` attribute - the IEEE-754 bits
for `2.4f` sitting directly in the attribute's 4-byte data slot, not a
string-pool reference at all. An initial version of this fix wrongly
assumed it was a string and tried to patch the string pool instead;
running it against the real captured manifest immediately showed nothing
matched, which is what caught the mistake before it shipped. The
corrected version checks the raw type byte first and only touches the
slot when it's actually a float. Verified against the real extracted
`AndroidManifest.xml`: still parses cleanly (`androguard` re-decodes it
to `android:value="99.000000"`), and combined with the `configChanges`
fix only 5 bytes total differ from the original.

This is chosen deliberately as a *ratio*, not a fixed resolution - the
person testing this pointed out that hard-coding anything to one specific
tablet's screen size wouldn't help anyone with a different device, and
`android.max_aspect` widens a ratio ceiling rather than assuming any
particular width/height, so it should generalize.

**Real-device retest: still not fixed.** Both manifest levers shipped and
were retested; the small/centered rendering is still there. Combined
with seeing the identical `Letterbox`/`mBounds` boxing independently on
Mass Effect (above, a completely different game, never touched by the
`android.max_aspect` fix since it has no such meta-data at all), this
now looks less like "this one app's manifest is missing a specific
opt-out" and more like modern Android's own fixed-orientation letterbox
compatibility system applying regardless of what the APK declares - the
`configChanges`/`max_aspect` widening were real, correct, evidence-based
levers for the mechanisms they target, they just aren't the whole
mechanism actually in control here. Every config dump in both logs also
shows `landScapeFreeformFlag:1`, which still suggests this Hihonor
tablet's own "Magic Win" layer is involved on top of stock Android's own
letterboxing. No device-level "force full screen" setting was found for
this app yet, though stock Android 12+ does expose a per-app "aspect
ratio" override in Settings > Apps > (app info) for exactly
non-resizable, orientation-locked apps like this one - worth a specific
look on this tablet under that exact name, since OEM skins sometimes
rename or relocate it, before concluding there's truly no user-facing
switch. Needs fresh logcat evidence from the actual retest to know what
to try next; guessing further without it isn't the way this project has
gotten anywhere so far.

**Round 7 continued - the actual retest, and a concrete device-side lever.**
Fresh logs on both games, same pattern each time: the game launches, gets
boxed within about a second (well before either manifest fix could matter
- this is a relaunch decision Android makes independent of what the APK
declares), and self-corrects back to full screen a few seconds later, but
*only* after the user manually backgrounds it (Home) and brings it back
(Recents) - exactly the same behavior on Mass Effect as on Dead Space, so
this is confirmed as one shared platform-level issue, not two unrelated
per-game bugs. This round's logs also caught the real decision-making
struct directly, `AppCompatTaskInfo`, in the window-transition log lines
(logged by the system itself around Home/Recents-triggered transitions,
Android 12+'s actual size-compat/letterbox controller):

```
topActivityInSizeCompat=false eligibleForLetterboxEducation=false
eligibleForUserAspectRatioButton=false topActivityBoundsLetterboxed=true
isUserFullscreenOverrideEnabled=false isSystemFullscreenOverrideEnabled=false
hasMinAspectRatioOverride=false
```

Every override field reads `false` on both games, every time - so nothing
is currently telling the platform "let this app fill the screen." This
also settles something the earlier write-up above was still guessing at:
`eligibleForUserAspectRatioButton=false` means these games aren't even
eligible for Android's own per-app "aspect ratio" Settings entry (a real,
documented Android 12+/14+ feature - see the "Device compatibility mode"
and Android 14 QPR1 aspect-ratio-override docs) - which is exactly why
the user couldn't find a matching switch on the tablet earlier: there
isn't one to find for these two specific games, not a case of it being
hidden or renamed by the OEM skin.

There IS a lower-level lever that isn't gated by that eligibility check,
though: Android's `platform_compat` framework, settable per-package over
adb (no root needed) with the connection already used for every install
in this project:

```
adb shell am compat enable OVERRIDE_ANY_ORIENTATION <package>
adb shell am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER <package>
adb shell am compat enable FORCE_RESIZE_APP <package>
adb shell am force-stop <package>
```

(`<package>` = `com.eamobile.deadspace_full_azn` for Dead Space,
`com.ea.games.meinfiltrator_gamepad` for Mass Effect.) Reversible any
time with `am compat reset <package>`. Important honest caveat: this
lives in Android's per-device app-compat settings, not in the APK at
all, so even though it works (see below), it isn't something this
patcher can bake into the redistributed APK itself the way the
manifest/dex/ELF fixes are - it has to be a one-time adb command run
after installing each patched game, not something `patch()` can do on
its own.

**Confirmed on the real device: this works.** With the three overrides
enabled, Mass Effect now launches directly at full screen, cold start,
no Home/Recents trick needed at all. This is the first fully-confirmed
fix for the window-boxing problem in this project (the manifest-side
`configChanges`/`android.max_aspect` levers were real but insufficient
on their own, as recorded above) - it just lives outside the APK rather
than inside it.

Separately: Mass Effect's second and third crash fixes both held (no
`IllegalArgumentException`, no `SecurityException` in the fresh logs)
across every retest. But a longer capture (over a minute of idle
logcat) showed its `GLThread` (the rendering thread, not `main`)
permanently stops producing frames right after enabling the orientation
sensor - reproduced twice, same exact spot both times, so it's
deterministic, not a fluke. No crash, no exception, no ANR (Android's
ANR watchdog only watches the main thread and broadcast/service
timeouts - a background-thread hang like this is invisible to it
entirely, confirmed by touch-gesture-handler logs still running fine on
the main thread the whole time it's frozen). The original theory here -
that this was caused by the same window-resize churn behind the
box/unbox transition - turned out to be wrong: with the `am compat`
override above, the game never boxes at all, and it *still* hangs in
the exact same place. So whatever this is, it's independent of the
windowing issue, and the resize-churn hypothesis is retired.

`adb shell kill -3 <pid>` (a zero-cost way to force a full thread dump
without a real ANR) was tried as a next step and confirmed blocked -
`Operation not permitted`, because the patched APK isn't a debuggable
build, which is the actual gate on that technique despite it not
needing root. Getting further would need attaching a real debugger
(Android Studio + NDK, or `debuggerd`) to a debuggable build - well
outside the adb-only, logcat-driven workflow this whole project has
used - so this is where the Mass Effect hang investigation stood, until
one more piece of evidence actually cracked it.

**The real cause: this game isn't fully inside the APK at all.**
Running the original, unpatched APK in a PC emulator (BlueStacks, not
this project's target, but useful precisely because it exposes UI the
tablet install path hides) surfaced a "Download and Play" screen saying
outright: *"ME Infiltrator requires approximately 440-900 MB additional
content to run. Select 'Download' to begin."* - and pressing Download
does nothing; no download starts. This is a second EA on-demand asset
delivery system - install-time DLC, thematically the same idea as (and
implemented by the same `com.eamobile.download` package already
patched above for the device-ID crash) - fetching the bulk of the
actual game data (models, levels, audio) from EA's own servers on
first run, rather than shipping it in the APK. Those servers are,
unsurprisingly, gone.

**Confirmed by decompiling the real APK (2026-09-04), not just
inferred from `strings`.** The user supplied `classes.dex`,
`config.properties`, and finally the full original
`masseffectinfiltratorv1058.apk`, which was decoded with `apktool` to
read the actual smali of the whole `com.eamobile.download` package
(`DownloadActivityInternal`, `AssetManager`, `CheckUpdatesView`,
`ContactingServerView`, `CheckingHostIpView`, ~100 classes total).
That answered the user's own question - *"couldn't it be that it
wants to download but can't, and that's what's hanging?"* - precisely:

- `AssetManager.getAssetPath()` resolves to
  `Environment.getExternalStorageDirectory()` + the `DATA_FOLDER`
  value from `config.properties`, which for this game is
  `/Android/data/com.ea.games.meinfiltrator_gamepad` - i.e. the exact
  folder the user manually copied the "obb" data into
  (`.../Belső tárhely/Android/data/com.ea.games.meinfiltrator_gamepad`).
  The folder guess was right.
- But `AssetManager.assetsFoundLocally()` doesn't just look for the
  game's data files - it first requires a small text marker file
  named **`Downloaded.indicate`** to exist directly inside that
  folder and *contain the literal word `COMPLETE`*. Without that
  marker (which only the original ADC downloader itself ever wrote,
  on a successful download from the now-dead server), the app treats
  local data as **not installed**, no matter what's actually sitting
  in the folder - so even a perfect copy of the missing content would
  still be ignored without also creating that one small text file.
  (A second, optional `Downloaded2.indicate` file - a list of
  filenames it double-checks all exist - is only consulted if
  present; its absence is *not* itself a failure.)
- When local data isn't found, the flow moves to
  `DownloadActivityInternal.checkServerContent()`, which calls
  `updateDownloadFilesData()`, which calls a private `sendHttpPost()`
  helper - an **HTTP POST to `DOWNLOAD_URL`** (`http://gam.eamobile.com/`
  for this game) built on the old Apache `DefaultHttpClient`. That
  method sets `HttpConnectionParams.setConnectionTimeout(params,
  10000)` (10s) but **never calls the matching
  `HttpConnectionParams.setSoTimeout(...)`** - the *socket read*
  timeout. So: if the TCP connect succeeds at all (a dead domain
  bought by a parking service, an ad network, or anything else that
  accepts the handshake but never finishes a proper HTTP response),
  the 10s connect timeout never fires, and the subsequent read has no
  timeout at all - it can hang forever. This runs on a plain
  background `Thread` (not the main thread, not the game's GLThread),
  which is exactly why Android's ANR watchdog never sees it either.
  Separately, `canFindHostIP()` (used by the "checking host IP" step
  on the way to `sendHttpPost()`) calls
  `InetAddress.getByName(host)` directly, which has no timeout
  parameter in Java at all - a second, independent way this same
  general area of code can hang indefinitely on a real network,
  regardless of the HttpClient gap above.

This turned an EA-SDK-wide, real bug (not a game-specific edge case)
into a genuinely patchable gap, unlike the missing content itself:
**fix #12** (`DexPatcher.kt`'s `patchEaHttpPostReadTimeout()`) inserts
the missing `setSoTimeout(params, 10000)` call right next to the
existing `setConnectionTimeout(...)` call, wherever that exact Apache
HttpClient API pair appears in the smali tree (unscoped, matched by
the fully-qualified API call itself, the same approach as the ffmpeg
and `initializeIAP()` fixes above - this is shared EA SDK code, not
tied to one file path per game). Verified via a standalone
kotlinc-compiled harness against the real decompiled method text, then
a full `apktool b` reassembly of the whole patched smali tree back
into a working `classes.dex` (the resulting dex's string pool contains
exactly one `setConnectionTimeout` and one `setSoTimeout` reference,
confirming the insertion landed correctly and the tree still
assembles).

**What this fix does and does not do.** It does **not** make the
missing ~440-900 MB of game content download successfully - the
server is still dead, and no APK patch can conjure data that no
longer exists anywhere reachable. What it does: turns an
unrecoverable, silent, infinite hang into a normal failure. Once
`sendHttpPost()` can no longer block forever, `checkServerContent()`
reaches its own error-handling paths (`STATE_SERVER_ERROR` /
`ServerErrorView`, or similar) within roughly the same ~10 seconds
instead of hanging - so the user should see an actual error screen
(or the app returning control to the OS) rather than a tablet that
looks frozen for good. Whether the game can be played at all after
that still depends entirely on whether the missing content can be
sourced and correctly placed (both the raw files under
`Android/data/com.ea.games.meinfiltrator_gamepad/` *and* a
`Downloaded.indicate` file containing `COMPLETE` in that same folder -
and pushed via `adb push`, not Windows Explorer/MTP, since Android
11+'s Scoped Storage silently blocks PC-side MTP writes into other
apps' `Android/data/<package>/` folders even when Explorer's UI shows
the copy as having "succeeded"). This is still, fundamentally, a
missing-content problem for this specific game - fix #12 just makes
the failure honest instead of making the tablet look broken.

**Round 8: fix #11 was wrapping the wrong thread, and this project's
own earlier fix was hiding that.** The user placed real game content
in the right folder (the exact path `AssetManager.getAssetPath()`
resolves to, confirmed by decompiling), including a genuine
`Downloaded.indicate` (containing `COMPLETE`, plus a real
`adc_v1.0.10_chunk01-15.zip` file list) and a matching
`Downloaded2.indicate` - and the game still hung in the same place.
The breakthrough came from `com.eamobile.download`'s *own* debug
logger (`Logging.DEBUG_OUT()`): it turns out this SDK has always had
detailed logging for exactly this state machine, but it's a silent
no-op unless a file called `debug.enable` exists in the SD card root
(confirmed by decompiling `Logging.smali`) - which is why *no* prior
log, from any earlier round of this whole investigation, ever showed
a single `com.eamobile.download` message. With `adb shell touch
/storage/emulated/0/debug.enable` in place and a fresh `adb logcat`
capture, the real trace (tag `DownloadActivity`) finally showed up,
and it stops in the same place every time: `ADC lib info` / `Device:
HONOR-NDL-W09` / `Brand: HONOR` - then silence. Decompiling
`DownloadActivityInternal.smali` shows the very next call after
printing `Brand:` is `getAndroidUniqueId()`, which calls
`TelephonyManager.getDeviceId()` **with no try/catch of its own** -
guaranteed to throw `SecurityException` on Android 10+ (the same
permanent OS restriction fix #11 already documented). A real crash
log confirmed it happening at exactly that point:
`Logging.DEBUG_INIT(Logging.java:85) <- DownloadActivityInternal.<init>
<- DownloadActivity.<init> <- MassEffectActivity.loadContent` -
this whole chain runs on a **background thread**
`DownloadActivityInternal.init()` spawns, not the thread fix #11's
wrap runs on. A try/catch around `DownloadActivity.init()` (fix #11)
only catches an exception thrown synchronously on the thread that
*calls* `init()` - it cannot catch one thrown later on a thread
`init()` merely started running. So fix #11 never actually guarded
this crash. What silently ate it instead was this project's *own*
fix #5 (the global `SilentUncaughtHandler` for background-thread
crashes) - it caught the `SecurityException`, logged nothing
user-visible, and let that thread die where it stood. No crash
report, no ANR (a dead background thread is invisible to the ANR
watchdog), just a permanently stuck "please wait" state that looks
exactly like a hang - which is exactly what every log in this whole
investigation showed, over and over, because that is what it was.
**Fix #13** (`patchWholeMethod`-wrapping `getAndroidUniqueId()`
itself, unscoped/method-name-matched like `initializeIAP()`) closes
the actual gap: the exception is now caught right where it's thrown,
regardless of which thread runs it. Verified the same way as every
dex patch here - a standalone kotlinc harness against the real
decompiled method, then a full `apktool b` reassembly of the whole
patched tree back into a working `classes.dex`. Fix #11 stays (it's
harmless, and a real guard for whatever else might call
`DownloadActivity` directly on its own thread), but fix #13 is what
actually stops this specific hang - and note this whole chain of
evidence started from the user pushing back on "just missing local
data" and asking why it looked like it wanted to download but
couldn't; that pushback is what led here.

**Round 9: fix #13 genuinely worked - and immediately exposed this
project's own next regression.** Retested on the real device with the
fix #13 build: the game no longer hung at the EA screen, confirming
the whole Round 8 diagnosis. But it now got kicked straight back to
the home screen instead. A fresh `adb logcat` capture (same
`debug.enable` setup as Round 8) showed a real, uncaught
`FATAL EXCEPTION` on a background thread this time, not a silent
hang:

```
java.lang.NoClassDefFoundError: Failed resolution of: Lorg/apache/http/impl/client/DefaultHttpClient;
    at com.eamobile.download.DownloadActivityInternal.sendHttpPost(DownloadActivityInternal.java:1)
    at com.eamobile.download.DownloadActivityInternal.updateDownloadFilesData(DownloadActivityInternal.java:843)
    at com.eamobile.download.DownloadActivityInternal.checkServerContent(DownloadActivityInternal.java:5)
    at com.eamobile.download.ContactingServerView$2.run(ContactingServerView.java:9)
Caused by: java.lang.ClassNotFoundException: org.apache.http.impl.client.DefaultHttpClient
```

This 2012-era EA SDK still uses the old Apache HTTP client
(`org.apache.http.*`) for its "contact the server, check for updates"
step - which the same log shows this game running on *every* launch
regardless of local data (`NUMBER_OF_HOURS_TO_UPDATE_CHECKING: 0
hours`, from its own `config.properties`), so fix #12's HTTP
read-timeout was real but moot: the class can't even be loaded to
reach that timeout code. The actual cause is a self-inflicted
regression from this project's *own* [`patchTargetSdk`](#) fix:
Google removed the Apache HTTP client classes from Android's default
bootclasspath starting at API 28, and raising an old game's
`targetSdkVersion` to 30 (needed for other reasons, and unrelated to
this SDK) is exactly what makes them disappear for an app that was
never rebuilt against a modern SDK and so never declared the
documented opt-back-in itself: `<uses-library
android:name="org.apache.http.legacy" android:required="false"/>`
inside `<application>`.

Unlike every other manifest patch in this app, this one needs a real
AXML *insertion* (a brand-new element with new attributes and new
string-pool entries), not just an in-place attribute-value overwrite
- exactly the kind of edit `ManifestPatcher`'s own hand-rolled byte
reader was deliberately never extended to do (see its top-of-file
doc comment). Rather than risk hand-rolling that, fix #14 pulls in
[ARSCLib](https://github.com/REAndroid/ARSCLib) (`io.github.reandroid:ARSCLib`),
a real, independent AXML-writing library - the same "use the real
tool, don't hand-roll it" choice already made for dex editing
(dexlib2/smali) and signing (apksig). Verified offline against two
different real, complete manifests pulled from this project's own
earlier work (Imperium Galactica 2's and Haegemonia: Legions of
Iron's actual `AndroidManifest.xml`), inserted with a standalone JVM
harness calling the exact same `ManifestPatcher.addUsesLibrary()`
code, then independently re-decoded with `apktool` (a wholly separate
codebase from ARSCLib) to confirm no parse errors, the new element
lands correctly inside `<application>` with both attributes intact,
and every other element in both manifests survives untouched. One
real trap surfaced during that verification and is now documented in
`addUsesLibrary()`'s own doc comment: ARSCLib needs an explicit
`refreshFull()` call before serializing, or it silently produces a
manifest that *looks* fine through its own API but is actually
malformed at the byte level - `apktool` failed to even parse it until
that call was added. Added unconditionally rather than gated on
detecting `org.apache.http` references first, since a `required=
"false"` declaration is a no-op for any APK that doesn't use these
classes, and this SDK is far from the only old (pre-2014) Android SDK
that still does.

**Round 10: the actual, final, external cause - and a genuine
behavior change rather than another safety net.** With fix #14
shipped, `sendHttpPost()` finally ran for real on the retest - and
immediately failed with `UnknownHostException: Unable to resolve host
"gam.eamobile.com"`. EA's own domain no longer exists in DNS at all,
not merely "down" - permanently unfixable by anything in this app.
Screenshots from the same retest showed a "WI-FI CONNECTION NOT
FOUND" dialog, which turned out to be a red herring: the tablet's
WiFi was genuinely connected (confirmed both by screenshot and by the
same log's own `OneTouchWifiManager: onReceive wifi connected`), but
`DownloadActivityInternal.canFindHostIP()`'s own network check
conflates "can't resolve my one hardcoded server" with "no WiFi" and
shows the wrong dialog either way.

The real, remaining problem: this game's own local content was
already complete and correctly placed (`Downloaded.indicate` =
`COMPLETE`, confirmed by `AssetManager.assetsFoundLocally()`
returning true), but `DownloadActivityInternal.init()` still tried
the (now permanently dead) server on every single launch and never
fell back to what was already on disk. Decompiling `init()` traced
this to one specific decision: it only skips straight to
`STATE_SUCCESS` (load the game from local assets immediately) when
`shouldSkipUpdateCheck()` returns `true` - and that method's own
logic unconditionally returns `false` whenever
`NUMBER_OF_HOURS_TO_UPDATE_CHECKING <= 0`, which is exactly what this
game's `config.properties` sets it to. That was a reasonable default
in 2012, with EA's server alive; in 2026, with the domain deleted,
it's a permanent dead end for a game whose local content is already
correct.

Fix #15 forces `shouldSkipUpdateCheck()` to always return `true`
(`DexPatcher.kt`'s new `forceReturnTrue()`, a much blunter tool than
every earlier `wrapWholeMethod()` fix - see its own doc comment for
why this one is a genuine, intentional behavior change rather than a
safety net, and why it's still narrowly scoped: per `init()`'s own
logic this method's return value is only ever consulted *after*
`assetsFoundLocally()` already returned `true`, so this has zero
effect on anyone who hasn't already placed complete local content -
the normal "please download" flow for a game with no local data at
all is untouched). Verified with this project's usual two-step
pipeline: a standalone kotlinc harness running the exact
`forceReturnTrue()` code against the real, decompiled
`shouldSkipUpdateCheck()` method from the user's own uploaded APK,
then a full `apktool b` reassembly of the entire patched smali tree
back into a working APK, then re-decoding that output to confirm the
patched method round-trips byte-for-byte as expected. Not yet
confirmed on the real device as of last write - genuinely can't be,
since this is the first fix in this whole project that changes what
the app *does* rather than only guarding an existing crash, so a
retest is the only way to know whether `checkLocalAssetVersion()`
(the one remaining check between "local assets found" and actually
loading the game - see fix #15's own doc comment) also passes against
whatever the user's local content reports as its version.

**Round 11: fix #15 retested on the real device - still stuck, but not
for the reason it looked like at first, and a correction to Round
10's own claim above.** Round 10's text above says local content was
"confirmed" complete via `assetsFoundLocally()` returning `true` -
that was actually an inference from the earlier `checkLocalAssetVersion()`
doc-comment reasoning, not a log-confirmed fact, and the real retest
showed it was wrong. The retest log (`d5b16c88`) went straight to
`STATE_CONTACTING_SERVER` again, exactly as if fix #15 had never been
applied. First surprise: `adb shell ls -la` on the game's data folder
came back completely empty - no `Downloaded.indicate`, no game
content, nothing but `.`/`..`. Best working theory (not fully
confirmed): each freshly-repatched test APK needed a full uninstall
before reinstall (a signature mismatch, since re-patching regenerates
the game APK's signature), and Android 11+'s scoped storage deletes
an app's entire `Android/data/<package>` folder on uninstall - wiping
out everything the user had manually `adb push`-ed there, independent
of anything this project's code does.

The user re-pushed the full content and reran the test - the folder
was confirmed populated again (`Downloaded.indicate` = `COMPLETE`,
`Downloaded2.indicate` listing 1180 files), and a direct `adb shell`
loop checking every single one of those 1180 paths for existence
found zero missing. And yet the very next retest log showed the exact
same `STATE_CONTACTING_SERVER` path, with **zero**
`"Checking file: ... (OK/NOT FOUND)"` lines anywhere in it - meaning
`AssetManager.checkFiles()` (which logs one such line per entry) was
never even reached. Decompiling `assetsFoundLocally()` shows only two
ways to return `false` without ever calling `checkFiles()`:
`Downloaded.indicate` missing, or its content not containing
"COMPLETE" - both independently ruled out by the user's own
`adb shell cat` of that exact file right after the failing run. The
one thing left standing: this method wraps its entire body in a
`try/catch` that silently swallows **any** exception and just returns
`false` - unlike every other method in this same class
(`getLocalAssetVersion()`, etc.), which all log `"[ERROR] ..."` plus a
stack trace on failure, this one logs nothing at all. That silence is
exactly why nothing in any of these logs explains the `false` result.

Fix #16 (`DexPatcher.kt`'s new `addAssetsFoundLocallyDiagnostics()`)
is diagnostic-only - it changes no behavior whatsoever, it only adds
two bits of logging: the swallowed exception (message + full stack
trace, if there is one) right in its own `catch` block, and the
method's actual final `true`/`false` return value right before its
single physical `return` instruction. Both insertions land immediately
before that one shared exit point that every code path in the method
funnels through via `goto`, which is what makes this safe to splice in
by exact text match rather than real bytecode surgery: nothing
downstream of either insertion depends on any register except the
return value itself (never touched), so any of the method's other
already-declared locals can be reused for the log strings without
disturbing anything else. Unlike every other dex fix in this project,
this one is scoped to one exact method body in one exact class via
literal text match (not a method-name-matched primitive like
`forceReturnTrue()`/`wrapWholeMethod()`), and aborts with no change at
all if the exact snippets it looks for aren't found - appropriate for
something this narrow and interior, rather than guessing. Verified
with the same two-step pipeline as every other dex fix here: a
standalone kotlinc harness running the exact function against the
real decompiled `AssetManager.smali` from the user's own uploaded
APK, then a full `apktool b` reassembly of the entire patched smali
tree into a working APK, then re-decoding that output and confirming
it matches the harness's own output byte-for-byte. Not yet run on the
real device as of last write - the whole point of this fix is that
the next real log capture will finally say definitively why
`assetsFoundLocally()` is failing, instead of leaving it a guess.

**Round 12: fix #16 shipped, retested, and (per the user's own patch
log) silently never applied at all.** The retest log again showed the
exact same `STATE_CONTACTING_SERVER` path with zero `[SoA-diag]`
lines - and this time, rather than guessing why, the user's own SoA
app patch log (`apkcompatpatcherpatchlog...txt`, saved via **Napló
letöltése**) settled it directly: every other fix through #15 shows
its `[fix] ...` line exactly as expected, but there's no
`assetsFoundLocally() instrumented with diagnostic logging` line
anywhere in it. `addAssetsFoundLocallyDiagnostics()` (fix #16) never
even ran - it's an exact-text match, and it silently no-ops (by
design, per its own doc comment) when the text it's looking for isn't
found, with no fallback warning of its own. The likely reason: this
sandbox's apktool-based decompile of `AssetManager.smali` (which fix
#16 was written and verified against) isn't necessarily
byte-identical to what the *real* on-device `dexlib2`/`baksmali`
library - the exact one Android Studio actually links - produces for
this same method. This is the exact risk this project's own
top-of-file doc comment has flagged from the start: this sandbox
cannot download that library to verify against, so anything this
precise (an exact interior-text match, not a whole-method replace or
a name-matched wrap) can only be confirmed for real on the actual
device.

Fix #17 doesn't change fix #16's logic at all - it adds the fallback
this should have shipped with the first time: if `assetsFoundLocally()`
is found in a file but the exact snippets fix #16 looks for aren't
there, it now logs the method's **entire real body** (up to 170
lines, not just the usual ~14-line snippet other fallbacks here use,
since this method is long and the parts that matter - the `catch`
block and the final `return` - are near the end) straight into the
patch log. The next patch log will either show fix #16 finally
applying, or hand over the literal on-device text needed to fix the
match precisely, on the first try, instead of another guess.

Also bumped `versionCode`/`versionName` again (now 17/"0.17") -
see "Checking which build is actually installed" above.

**Round 13: fix #17's fallback worked exactly as designed, and handed
over the real fix.** Retested with 0.17: the game crash log again
showed zero `[SoA-diag]` output (same `STATE_CONTACTING_SERVER` path
as ever), but this time the SoA app's own patch log
(`apkcompatpatcherpatchlog...txt`) contained fix #17's new fallback
warning - `[!] ... found assetsFoundLocally() but the diagnostic
patch's exact-text match did NOT hit`, followed by the method's full
real on-device smali text. Comparing it line-by-line against this
sandbox's apktool-decoded copy of the same method showed the
instructions, strings and **register numbers** (`v12` as the boolean
accumulator, `v2` as the caught exception, etc.) are identical - only
the **label names** differ: apktool gives sequential decimal labels
(`:catch_0`, `:cond_2`, `:goto_1`), while the real on-device
`dexlib2`/`baksmali` library gives raw bytecode-offset hex labels
(`:catch_8d`, `:cond_6f`, `:goto_6f`). That makes sense in hindsight -
label names are a decompiler's own invention for naming branch
targets, not something stored in the dex bytecode itself, so two
different `baksmali` configurations were always free to name them
differently even while producing byte-identical instructions.

Fix #16's original match was hardcoded against apktool's specific
label names, which is exactly why it silently missed on the real
device. **Fix #18** rewrites `addAssetsFoundLocallyDiagnostics()` to
match on registers/instructions/strings only and treat every label
name as a wildcard captured verbatim from whatever the file's text
actually says, instead of assuming either tool's naming scheme.
Verified with the project's standard kotlinc-harness pipeline against
*both* texts - this sandbox's apktool copy and the literal real
on-device text captured in Round 12/13's patch logs - each independently
round-tripped through a full `apktool b` reassembly of the whole
patched smali tree and re-decoded to confirm a clean match (only a
single, purely cosmetic blank-line normalization either way, no
instruction-level difference).

Also bumped `versionCode`/`versionName` again (now 18/"0.18") - see
"Checking which build is actually installed" above.

**Round 14: fix #18 confirmed working, and it revealed a genuinely
new mystery.** Retested with 0.18: the SoA patch log now correctly
shows `[fix] ... added diagnostic-only logging to
assetsFoundLocally()`, and the game crash log finally showed real
`[SoA-diag]` output - `assetsFoundLocally() returning: false`, with
**no** "caught an exception" line and **zero** `Checking file:` lines,
meaning `checkFiles()` was never reached - so the false comes from one
of the two early-exit checks (`Downloaded.indicate` missing, or
present but not containing "COMPLETE"). At first this looked like
another folder-wipe (see Round 11) - but `adb shell ls -la` on the
data folder, taken right after the failing run, showed
`Downloaded.indicate`/`Downloaded2.indicate` present with exactly the
expected byte sizes (475 / 69034 bytes), and `adb shell cat
Downloaded.indicate` confirmed byte-correct content: "COMPLETE"
followed by all 15 chunk lines at version 1.0.10. A full device
**reboot** (to rule out a stale scoped-storage/FUSE view of files
`adb push`-ed in by a different UID) followed by relaunching the
already-installed game, with no rebuild/re-patch/reinstall at all,
still produced the exact same `returning: false` with zero
diagnostic-relevant lines - ruling out both the folder-wipe theory and
the stale-cache theory for this specific failure.

That leaves a real, previously-unconsidered possibility: the game
process's own `getAssetPath()` might resolve to a **different
absolute path** than the one being verified via `adb shell` - the
decompiled path-construction logic
(`DownloadActivityInternal.setAssetPathAux()`) turned out to be more
involved than the original "confirmed via config.properties'
DATA_FOLDER" check accounted for. Rather than reverse-engineer that
logic fully, fix #19 makes the actual path directly observable: logs
the literal resolved absolute path being checked for
`Downloaded.indicate`, right at the point `assetsFoundLocally()`
builds it - so the next log capture will show, in plain text, exactly
which folder the game itself thinks it should be reading from. Pure
addition (registers/instructions only, no labels involved here since
this snippet has no branches, and confirmed identical between apktool
and the real on-device dump), verified via the same kotlinc-harness +
full apktool-b-reassembly-and-redecode pipeline as every other dex fix
here, against both texts.

Bumped `versionCode`/`versionName` again (now 19/"0.19") - see
"Checking which build is actually installed" above.

**Round 15: stopped chasing the exact cause and forced the outcome
directly.** Before fix #19's path-logging even got its next real-device
test, the user raised the practical point directly: at some point the
only thing that actually matters is telling the SDK "yes, everything is
downloaded, keep going" - which is exactly what `assetsFoundLocally()`
being forced to `true` does. Given everything already independently
confirmed by this point - all 1180 files in `Downloaded2.indicate`
present on disk, `Downloaded.indicate` byte-correct with "COMPLETE",
no exception ever logged, the false surviving a full device reboot -
continuing to chase the exact remaining cause (a path mismatch? some
other silent condition?) stopped being worth it compared to just
forcing the same "trust local content" outcome fix #15 already forces
one step later. Fix #20 does exactly that: `forceReturnTrue()` (the
same primitive fix #15 already uses, and already real-device confirmed
working) applied directly to `assetsFoundLocally()`, superseding fixes
#16/#18/#19's diagnostic-only approach for this method entirely. Since
`forceReturnTrue()` only needs the method's declaration line and
`.end method` - not any of its interior text - it doesn't care about
the apktool-vs-real-device label-naming difference that fixes #16/#18
had to work around at all, and needed no new verification beyond
confirming it still matches this method's signature (it does, trivially,
against both texts) plus the usual full `apktool b` round-trip.

Bumped `versionCode`/`versionName` again (now 20/"0.20") - see
"Checking which build is actually installed" above.

**Round 16: fix #20 unblocked the download screen, and the very next
real-device log showed the same silent problem one step later.** After
fix #20 shipped, the user confirmed the download screen no longer
appears - but the game now freezes at the EA splash screen instead. The
new `mei_crash.txt` showed exactly why, every single rendered frame
(this whole check runs from `DownloadActivityInternal.init()`, which the
game calls from its own render loop -
`MassEffectActivity.onDrawFrame()` -> `AndroidRenderer.onDrawFrame()` ->
`GLSurfaceView$GLThread.guardedRun()` - not just once at startup):
`setState(STATE_SUCCESS)` calls `AssetManager.getLocalAssetVersion()`,
which returned `null` instead of "1.0.10" ("Local asset version: null"
in the log), which then flows into
`isAssetVersionCompatible()` -> `isVersionLower()` -> `normalisedVersion()`,
crashing with a `NullPointerException` (`Pattern.split()` on a null
`CharSequence`) that gets caught inside `setState()` but never resolved
- so `init()` re-throws-and-catches the same exception forever, once per
frame, which is what a permanent freeze with no forward progress looks
like from the outside.

Decompiling `getLocalAssetVersion()` showed it does the exact same
`getFilePath("Downloaded.indicate")` + `File.exists()` check
`assetsFoundLocally()` does, and returns `null` outright (no exception,
no log line) the moment that check is false - so this is almost
certainly the same unresolved path-resolution issue as fix #20's,
just hit from a second call site with a different symptom. Rather than
chase it a second time, or hardcode the expected version string (which
would silently mismatch for any game/build whose real minimum differs),
fix #21 applies the same "trust local content, skip the check that
can't resolve it" philosophy one level higher: `forceReturnTrue()` on
`AssetManager.isAssetVersionCompatible(String)` instead of on
`getLocalAssetVersion()` itself. Reading `checkLocalAssetVersion()`
(its only caller) and its own caller in `DownloadActivityInternal`
confirmed `true` here is exactly the "OK, use the local asset path"
branch success already takes - not a guess. It also fully sidesteps the
`NullPointerException`, since `isVersionLower()`/`normalisedVersion()`
are never called once this method's body is replaced outright.
Verified via the same kotlinc-harness + full apktool round-trip as
every other fix here.

Bumped `versionCode`/`versionName` again (now 21/"0.21") - see
"Checking which build is actually installed" above.

**Round 17: fix #21 confirmed working - the EA download SDK problem is
fully solved - and a completely different, new problem appeared right
behind it.** The real-device log for 0.21 shows, for the first time in
this whole thread:

```
DownloadActivityInternal setState: STATE_SUCCESS
onResult assetPath = /storage/emulated/0/Android/data/com.ea.games.meinfiltrator_gamepad result = -1
MassEffect: onResult(/storage/emulated/0/Android/data/com.ea.games.meinfiltrator_gamepad, -1, ...
```

That's the game's own callback firing with the correct local asset path
- the entire EA "eamobile" download-SDK chain this project has been
debugging since fix #11 (device-ID crash, dead server, `shouldSkipUpdateCheck()`,
`assetsFoundLocally()`, `isAssetVersionCompatible()`) is now conclusively
done. (One remaining harmless line right after it - `[ERROR] An exception
occurred in setState: NullPointerException ... at Logging.DEBUG_CLOSE`
- is just the SDK's own debug-log-closing routine hitting a null
`OutputStream` because debug logging isn't enabled; it's caught, logged,
and doesn't stop anything - the game moves straight on to
`setContentView(MainView)`, i.e. switches from the download UI to its
actual 3D game view.)

Immediately after that, on what looks like the very first rendered
frame of the real game view, a **new and completely different kind of
crash** appears - not Java, not the EA download SDK, not anything this
project's dex/manifest patches touch:

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x38 in tid 9732 (GLThread 38)
  #00 libMassEffect.so (im::m3gext::ModelCache::Shutdown()+280)
  #01 libMassEffect.so (Application::OnDestroy()+384)
  ...
  #08 libMassEffect.so (EA::Blast::UserExit()+212)
  #09 libMassEffect.so (Java_com_ea_blast_AndroidRenderer_NativeOnDrawFrame+32)
```

This is a native (compiled ARM machine code) crash inside the game
engine itself (`libMassEffect.so`), not the Java EA SDK - the engine's
own native code calls into its own shutdown/exit path (`UserExit()` ->
`System::Shutdown()` -> ... -> `ModelCache::Shutdown()`) on its very
first draw call and crashes while doing so (dereferencing something at
a near-null offset, consistent with an object that was never properly
initialized). Nothing in the log explains *why* the native engine
decides to call `UserExit()` there in the first place - that decision
happens entirely inside the compiled binary, with no Java-level
equivalent this project's text-based smali/manifest patching approach
can reach the same way it reached everything so far.

This is a fundamentally different, much harder category of problem than
anything fixed in this whole project to date - every prior Mass Effect
fix was a text-level edit to decompiled Java bytecode (smali) or a
binary manifest field; this would require disassembling and patching
actual compiled ARM machine code inside a stripped third-party engine
binary, with no source and no debug symbols beyond exported names.
Deliberately left unfixed for now rather than attempted blind, given
Mass Effect's explicitly secondary/bonus status in this project and
that IG2/Haegemonia (unaffected either way - neither uses any of this
EA SDK or this native engine) remain the actual priority. No new fix
shipped this round (still 0.21) - this is a status update, not a patch.

**Round 18: disassembled `libMassEffect.so` directly and traced the crash
to the exact assembly instruction that decides to exit.** The library
ships unstripped (full C++ symbols), so its logic is readable with a
plain ARM-capable `objdump` (the box's default `objdump` has no ARM
support; `arm-linux-gnueabi-objdump` does). Traced the chain precisely:
`EA::Blast::LoopLocked()` (called from `NativeOnDrawFrame` on the very
first pass, right after `SystemAndroid::Init()`) calls
`EA::Blast::System::IsAlive() const`, a one-line wrapper around
`LifeCycle::GetState()` (vtable slot 0) that returns false exactly when
that state equals `1`. Reading every `LifeCycle::OnRaw*` handler's state
writes shows only `OnRawExit()` ever sets state to `1` - every other
transition (`OnRawStart`/`OnRawResume`/`OnRawPause`/`OnRawFocusLost`/
`OnRawFocusGained`) sets a different value. `OnRawExit()` is reached via
`LifeCycle::HandleMessage()` on lifecycle message id `6` specifically
(the other lifecycle sub-messages are composite ids: `0x20006`=focus
gained, `0x40006`=focus lost, `0x60006`=pause, `0x80006`=resume,
`0xa0006`=start). So the proven chain is: *something* posts message `6`
-> `OnRawExit()` -> state=1 -> `IsAlive()` false on the very first frame
-> `LoopLocked()` calls `UserExit()` -> crashes inside
`ModelCache::Shutdown()` mid-improvised-shutdown.

Ruled out the obvious suspects: `Java_com_ea_blast_MainActivity_NativeOsExit`
(called from `MainActivity.onDestroy()`, which `MassEffectActivity`
inherits) doesn't touch `LifeCycle`/`HandleMessage()` at all - it drives
a wholly separate global shutdown flag straight to
`EA::Blast::Shutdown()`. `MassEffectActivity.onResult()` only calls
`finish()`+`killProcess()` on its failure branch (`result != -1`); our
log's `result == -1` takes the success branch, which just marks
`.nomedia`, calls `DownloadActivityInternal.destroyDownloadActvity()`
(frees a bitmap, resets static fields - no native calls), and posts a
20ms-delayed `Runnable` - no `finish()`/`onDestroy()` anywhere on that
path. The crash log itself has zero `onDestroy`/`onPause`/`onStop` for
this game's pid either - Android's real Activity lifecycle never fired
it. So the actual call site that posts message `6` is still
unidentified - it's very likely reached through a registered
message-dispatcher callback rather than a plain direct call, which
isn't a quick search across a 17MB stripped-of-context binary, and
there's no guarantee of a safely patchable spot even once found (unlike
every fix so far, which edited decompiled Java bytecode text - directly
patching compiled ARM machine code would be a new, unproven, materially
riskier technique for this project).

Given the diminishing returns past this point, the next step tried
first is the free one: a full device reboot before the next retest -
already this project's known-good way to clear any leftover corrupted
native/OS-level state from the many repeated force-stop-mid-native-init
cycles this one game has been through over roughly 20 test rounds (the
same move already worked once before, in "Round 14", to rule out a
stale FUSE view). No new APK build needed for that test, and no fix
shipped this round (still 0.21) - pure investigation.

**Round 19: a full reboot did NOT fix the native crash, ruling out
leftover corrupted process/OS state as the cause - then the user found
the actual reference this needed.** Web search (XDA threads, all
blocked to automated fetching; a PS Vita native port/loader project)
turned up nothing conclusive on its own, but the user then personally
browsed `v-atamanenko/masseffect-vita`
(https://github.com/v-atamanenko/masseffect-vita, MIT-licensed) - a
from-scratch PS Vita loader for this exact game - and found
`loader/patch/kill_network.c`. Its documented required SHA1 for
`libMassEffect.so` for game version 1.58 - `ea58b733d3d267ab639431b50539542faa43f0d0` -
matches this project's own extracted copy byte for byte (confirmed via
`sha1sum`), meaning it's the identical binary and every address in that
file means exactly what it says for our copy too. That file hooks 8
functions to make the engine believe there is no network and no EA
Origin online service available at all: `ProtoSSLConnect`,
`SocketCreate`, `SocketControl` (the low-level DirtySDK network
primitives), `EA::SP::Origin::OriginImpl::ShowOriginUI` and
`OriginMinimizedDialogState`'s constructor (the EA Origin account-link
UI), `EA::Util::InstallWallpaper`, and -  most suggestively -
`masseffect_widgets::GalaxyAtWarWidget::OnDraw()` and its
`CreateInstance` (a per-frame-drawn HUD widget tied to Mass Effect 3's
"Galaxy at War" online meta-game). Verified independently against our
own `libMassEffect.so` via `nm -C`: all 8 addresses resolve to exactly
these same mangled symbol names, byte for byte.

This lines up with "Round 18"'s finding that the crash happens on the
very first rendered frame: `GalaxyAtWarWidget::OnDraw()` is itself a
per-frame call that could plausibly run as part of that very frame, and
real Android (unlike Vita, which never had a networking stack for this
game at all) would actually attempt these dead-server network calls for
real. Working theory (not yet confirmed on-device): a failed/mishandled
EA Origin or Galaxy-at-War network attempt is what posts the lifecycle
message `6` traced in Round 18.

**Fix #22, `ElfMassEffectOnlineStubPatcher.kt` (new native-binary patch
- the first one in this project that edits compiled ARM machine code
rather than decompiled Java bytecode text):** stubs out all 8 of those
same call sites directly in `libMassEffect.so`, using the same
overwrite-the-prologue-with-a-tiny-return-stub technique
`ElfTextRelPatcher` already uses for its dead trampoline (`mov r0,#0`/
`mvn r0,#0`/`mov r0,#1` + `bx lr`, or a bare `bx lr` for the
constructor) - nothing is ever moved/resized, so no other offset in the
16.9 MB file needs adjusting. Verified two ways before shipping: (1) a
standalone kotlinc harness ran the exact patcher code against the real
extracted `libMassEffect.so` and confirmed all 8 sites matched and were
patched; (2) `arm-linux-gnueabi-objdump` disassembled the patched output
at all 8 addresses and confirmed correct instructions AND correct
symbol names (`ProtoSSLConnect`, `SocketCreate`, etc. - the
disassembler's own independent symbol resolution agrees these are the
intended functions), plus a full byte-level diff against the original
confirming the only 58 changed bytes in the entire file are exactly the
8 intended sites - nothing else moved.

Safety for IG2/Haegemonia/every other game: this patcher only even
looks at a zip entry named `libMassEffect.so` (no other game's APK
contains a file with this name at all), and even then only touches a
given site if the exact original 8 bytes already there match the exact
instructions this was built against - any mismatch (different build,
different game, anything) just skips that one site, logged, leaving it
untouched. Not yet confirmed on a real device - that's the next step.

**Round 20: fix #22 (0.22) REAL-DEVICE TESTED - the patch log confirms
all 8/8 sites applied, but the crash is IDENTICAL.** Fresh crash log:
same exact sequence (`onResult` succeeds, `setContentView(MainView)`,
one `NativeOnDrawFrame` call that gets as far as disabling the
orientation listener), then the identical `Fatal signal 11 (SIGSEGV)`,
fault addr `0x38`, at the exact same native addresses as "Round 17"/
"Round 18" - `ModelCache::Shutdown()+280` (`0x3aadd0`, still literally
`ldr r3, [sl, #56]` with `sl`/r10 null-or-near-null) <-
`EA::Blast::UserExit()+212` (`0x358218`) <-
`Java_com_ea_blast_AndroidRenderer_NativeOnDrawFrame`. Byte-for-byte the
same crash, down to the instruction.

This is a real, useful negative result, not a wasted round: it
conclusively RULES OUT the EA Origin/DirtySDK/Galaxy-at-War network
layer as the trigger for lifecycle message `6` - with `ProtoSSLConnect`/
`SocketCreate`/`SocketControl`/`ShowOriginUI`/`GalaxyAtWarWidget`
(OnDraw *and* its own construction) ALL hard-forced to fail/no-op
instantly, there is now zero possibility any of those 8 sites attempted
a real network call, blocked, or threw - and the engine still decides
to exit on the very first frame regardless. So whatever posts message
`6` is unrelated to networking/online services entirely - something
else, still unidentified, purely internal to the engine's own startup
sequence. Fix #22 is kept (still a real, correctly-verified, harmless
patch - matches a working reference implementation 1:1, and does no
damage even though it didn't fix this particular bug), but the "Round
19" theory is now disproven rather than just unconfirmed.

No new leads at this point - the next step, if pursued, is back to pure
native disassembly to find the literal call site that posts message
`6` (flagged as a new, materially riskier technique in "Round 18" - fix
#22 was the first attempt at exactly that, and it correctly patched
what it targeted, it just targeted the wrong thing). Given Mass
Effect's secondary/bonus status in this project and the diminishing
returns so far, this is a natural point to check with the user on
whether to keep going.

**Round 21: user asked to keep going, so - rather than more pure static
guesswork - traced the exact mechanics of message `6` far enough to
build a targeted diagnostic probe.** Found `EA::Blast::System::Exit(int)`
(`0x35c0b4`): a small function that hardcodes posting message `6` to the
message dispatcher REGARDLESS of the `int` exit-code argument passed in
(the argument is only stored at `this+96`, presumably for
`NativeGetExitCode()` later) - the single place in the whole 17MB binary
that does this exact "load id=6, dispatch via vtable slot 8" sequence.
It has zero direct (`bl`) callers anywhere, meaning every call to it
goes through C++ virtual dispatch on an `ISystem`/`System` pointer -
and that same vtable slot number is reused by ~350 unrelated call sites
across the whole engine (mostly UI widget classes with their own,
unrelated vtables at the same slot index), which is not realistically
disambiguable by hand from static disassembly alone. Traced
`EA::Blast::LoopLocked()` in full detail as the most promising
candidate (it already runs a `System::Exit`-shaped virtual call every
single frame) but ruled it out too: that call runs unconditionally on
literally every frame once "alive", which every other working install
of this exact game (5T/S20 per "Round 19", or historically) disproves
as the trigger - so that particular vtable slot on that particular
object must be something else entirely, not `Exit`.

Rather than keep guessing which of ~350 candidates is the real one,
shipped a deliberate diagnostic probe instead: **fix #23 (0.23-diag,
`ElfMassEffectHandleMessageDiagnosticPatcher.kt`) is NOT a real fix and
is not meant to be kept.** It replaces
`EA::Blast::LifeCycle::HandleMessage(unsigned int, void*)`'s entire body
with `cmp r1,#6 / streq r1,[r1] / mov r0,#0 / bx lr` - if the incoming
message id is `6`, this stores to address `6` and crashes immediately,
*before* touching the stack, so the caller's (and grandcaller's, etc.)
stack frames are still intact for Android's own `debuggerd` unwinder -
the exact same tool that already correctly identified `UserExit()` and
`ModelCache::Shutdown()` in "Round 17"/"18". The real problem with the
CURRENT crash is only that it happens two calls too late (inside
`LoopLocked()`, well after `HandleMessage()` already returned) to show
who posted the message - this probe crashes at the right moment instead.
Trade-off (why this build is diagnostic-only): every OTHER lifecycle
message (focus/pause/resume/start) becomes a no-op while this patch is
in effect, so this build is not expected to be playable beyond the
first native crash - that's fine, since it only needs to run once.
Verified via kotlinc harness (confirmed match + patch against the real
binary) and `arm-linux-gnueabi-objdump` (correct 4 instructions,
byte-level diff confirming only the intended 14 bytes changed). Gated
the same two-layer way as fix #22 (filename + exact-original-bytes
match), so it's inert for IG2/Haegemonia/anything else. Next step: user
installs this 0.23-diag build, launches Mass Effect once, and sends
back the resulting crash log - its backtrace should finally show the
real caller of message `6`. This patch gets removed/superseded by a
real fix once that's known.

**Round 22: 0.23-diag REAL-DEVICE TESTED - proved the theory right,
but the unwinder still couldn't show the caller, for a fixable
reason.** The real crash log's native backtrace:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000006
#00 pc 0035b450 libMassEffect.so (EA::Blast::LifeCycle::HandleMessage(unsigned int, void*)+4)
#01 pc 00103316 [anon:stack_and_tls:23189]
```
`fault addr 0x00000006` at `HandleMessage()+4` (the `streq`) is a direct
hit: `HandleMessage()` really is called with message id `6` exactly as
"Round 21" hypothesized. But frame `#01` is unsymbolized garbage instead
of the real caller - the backtrace still didn't answer the question.
Root-caused with `arm-linux-gnueabi-readelf --unwind` against the real
binary: `HandleMessage()`'s `.ARM.exidx` entry is one per-function
"compact model" rule, `pop {r4, r14}`, that `debuggerd`'s unwinder
applies at ANY crash address inside the function, on the assumption
that the real prologue's `push {r4, lr}` already ran and the stack
pointer hasn't moved since. 0.23-diag's stub replaced that `push` with
`cmp`, so at the crash point the stack pointer was still wherever the
*caller* had left it - the unwinder popped two unrelated words off the
caller's own stack and got garbage, not a real return address.

**Fix #24 (0.24-diag2, same file) keeps the real `push {r4, lr}` /
`pop {r4, pc}` around the probe**, so the stack shape matches what the
unwind table already assumes:
```
push  {r4, lr}     ; UNCHANGED - now the exidx entry's assumption is true again
cmp   r1, #6
streq r1, [r1]     ; crash here - stack is in exactly the state exidx expects
mov   r0, #0
pop   {r4, pc}     ; proper matching epilogue - byte-for-byte the same instruction
                    ; the real original function already uses for its own fast-path return
```
Now patches 20 bytes (5 instructions) instead of 16 - the original
5th instruction (`bne 0x35b478`, part of the real dispatch logic) is
sacrificed too, which is fine since this build's `HandleMessage()` is
diagnostic-only anyway. The `push`/`pop` pair was cross-checked two
ways: assembled+disassembled standalone via `arm-linux-gnueabi-as` (byte
for byte match), and confirmed against the *actual* original bytes the
real function itself already uses at its own fast-path epilogue a few
instructions later. Verified via kotlinc harness (match + patch against
the real binary), `objdump` (exact resulting instructions:
`push {r4,lr} / cmp r1,#6 / streq r1,[r1] / mov r0,#0 / pop {r4,pc}`),
and a byte-diff (only the intended `0x35b44c`-`0x35b460` window
changed). Same two-layer gate as before (filename + exact-original-bytes
match, now 20 bytes), so IG2/Haegemonia/anything else stays untouched.
Next step: user installs 0.24-diag2, launches Mass Effect once, sends
back the resulting crash log - its backtrace should now go past
`HandleMessage()` and finally show the real caller of message `6`.

**Round 23: 0.24-diag2 REAL-DEVICE TESTED - the unwind fix worked, and
the full backtrace finally came through.** Real crash log:
```
signal 11 (SIGSEGV), fault addr 0x00000006
#00 EA::Blast::LifeCycle::HandleMessage(unsigned int, void*)+8
#01 EA::Messaging::Server::MessageSend(unsigned int, void*, EA::Messaging::IHandler*)+508
#02 EA::Messaging::Server::ProcessQueue()+688
#03 EA::Blast::LoopLocked()+208
#04 Java_com_ea_blast_AndroidRenderer_NativeOnDrawFrame+32
```
This answers the immediate question but reframes the real one: message
`6` is *queued* through `EA::Messaging::Server`, and only *dispatched*
out of that queue by `ProcessQueue()`, which `LoopLocked()` calls on
literally every rendered frame regardless of what's in the queue.
`ProcessQueue()`/`LoopLocked()` are just the always-running pump, not
the source - whoever actually queued message `6` already ran and
returned before this backtrace even starts, one level further back
than `HandleMessage()` was. Which is exactly `EA::Blast::System::Exit(int)`
(`0x35c0b4`) again - "Round 21" already found it's the one function in
the whole binary that hardcodes queuing message `6` unconditionally,
just never resolved *its* caller among ~350 same-vtable-slot
candidates.

So the probe moves one level further upstream: **fix #25 (0.25-diag3,
`ElfMassEffectSystemExitDiagnosticPatcher.kt`) replaces
`System::Exit(int)` itself with an unconditional crash the instant it's
entered** (no `cmp` needed this time - any call to `Exit()` at all
queues message `6`, so any call to `Exit()` at all is the thing being
hunted). Learned from "Round 22"'s exact mistake: `Exit()`'s own
`.ARM.exidx` entry (`vsp = vsp + 12; pop {r4, r5, r14}`) assumes the
FULL real prologue (`push {r4,r5,lr}` **and** `sub sp,sp,#12`) has
already run, not just the initial push - so this patch leaves all four
of those real original instructions completely untouched, and only
replaces what comes after with `mov r0,#6 / str r0,[r0]` (kept as `6`
so it's instantly recognizable in the next log's `fault addr`).
Verified via kotlinc harness (match + patch against the real binary),
`objdump` (exact resulting instructions, real prologue intact), and a
byte-diff (only the intended `0x35c0c4`-`0x35c0cc` window changed).
Fix #23/#24 (`ElfMassEffectHandleMessageDiagnosticPatcher.kt`) is now
superseded and unwired from `ApkPatcher` (kept in the repo for
history/reference only) - it did its job. Same two-layer gate as
always (filename + exact-original-bytes match, 24 bytes this time).
Next step: user installs 0.25-diag3, launches Mass Effect once, sends
back the resulting crash log - its backtrace should now show who calls
`System::Exit()`.

**Round 24: 0.25-diag3 REAL-DEVICE TESTED - full backtrace, real root
cause found. First REAL FIX attempt shipped (fix #26, 0.26-storagefix).**
The complete, symbolized crash log:
```
signal 11 (SIGSEGV), fault addr 0x00000006
#00 EA::Blast::System::Exit(int)+20
#01 EA::Blast::Application::Exit(int)+20
#02 EA::GameSkeleton::GameApplication::OnUpdate(void*)+488
#03 EA::Blast::UpdateHandler::HandleMessage(unsigned int, void*)+148
#04 EA::Messaging::Server::MessageSend(...)+508
#05 EA::Messaging::Server::ProcessQueue()+688
#06 EA::Blast::LoopLocked()+208
#07 Java_com_ea_blast_AndroidRenderer_NativeOnDrawFrame+32
```
Disassembling `GameApplication::OnUpdate()` around the `Application::Exit`
call site (`0x40bb1c`-`0x40bb30`) shows exactly why: it calls
`EA::IO::StorageDirectory::GetWritableStorageDirectory(2, &tempPath)`
just before, and quits if that returns anything other than `1`.
Disassembling that function's callee, `GetAllStorageList(vector&, unsigned
flags)`, shows `flags` is a bitmask (bit0=internal, bit1=primary external,
bit2=secondary external, bit3=custom mounted, bit4=shared) - `2` requests
PRIMARY external storage specifically, i.e. the completely ordinary
`/storage/emulated/0/`-style shared storage every Android device has.
On this 2011-era engine that used to be a trivial always-true check; on
modern Android (API 30+ - exactly what this project's own
`targetSdkVersion` patch raises the app to, to let it install at all),
scoped storage blocks this kind of raw legacy filesystem access outright,
so the check fails every time, and `OnUpdate()` decides the game can't
continue - and crashes trying to say so.

Confirmed safe to just skip: the output path this check produces is a
purely local, throwaway stack buffer - on the "success" branch, the code
does nothing with it beyond a generic temporary-object cleanup loop (read
directly, on both sides of the branch). The game's actual save/content
data already goes through separate, working, scoped-storage-compliant
Android APIs (the dex-level `eamobile`/`AssetManager` fixes, #9-#21,
already proven working since the game reaches its real 3D view and
renders frames before this crash). So fix #26
(`ElfMassEffectPrimaryStorageExitPatcher.kt`) changes exactly one
instruction at `0x40bb20`: `cmp r0, #1` -> `cmp r0, r0` (always true, so
the following `beq` always takes the "storage is fine" branch,
regardless of what the check actually returned). `GetWritableStorageDirectory()`
itself, and `Application::Exit()`/`System::Exit()` themselves, are
completely untouched - any other, legitimate reason the game might exit
still works. Verified via kotlinc harness (match + patch against the real
binary), `objdump` (confirms the resulting `cmp r0, r0`), and a byte-diff
(only the intended `0x40bb20`-`0x40bb24` window changed, and only 2 of
those 4 bytes actually differ). Both diagnostic probes (fix #23/#24/#25)
are now unwired from `ApkPatcher` (kept in the repo for history/reference)
- their job (finding this) is done; this is a real-fix build, not a
diagnostic one, so it should otherwise behave like a normal build.
Same two-layer gate as always (filename + exact-original-bytes match).

**Round 25: 0.26-storagefix REAL-DEVICE TESTED - fix #26 completely
eliminated the storage-exit crash. HUGE win: this is the first native
SIGSEGV that had blocked Mass Effect: Infiltrator since "Round 17".** The
patch log confirms 0.26-storagefix applied correctly, and the crash log
that used to show `System::Exit`/`Application::Exit` is simply gone -
startup proceeds past that point entirely. But it doesn't reach gameplay
yet: a NEW, different native SIGSEGV shows up further along, in a
completely different code path than the storage watchdog:
```
signal 11 (SIGSEGV), fault addr 0x00000060
#00 FT_Request_Size+72
#01 FT_Set_Char_Size+104
#02 im::FreeTypeFont::FreeTypeFont(char const*, float)+260
#03 im::IFont::CreateDefaultFont(float)+228
#04 im::ui::plain::PlainSkin::PlainSkin()+240
#05 im::ui::UILayer::UILayer(char const*)+180
#06 im::debug::DebugMenuLayer::DebugMenuLayer()+20
#07 im::debug::DebugMenu::GetDebugMenuLayer()+100
#08 im::M3GApplication::OnCreate()+792
```
(further up: `Application::OnCreate()` <- `im::M3GApplication::OnRunLoopTick()`
<- `im::BufferedPlatformDelegate::OnRunLoopTick()` <-
`im::Platform::OnPlatformEvent(...)` <-
`EA::GameSkeleton::GameApplication::OnUpdate()` - the same per-frame
update loop as "Round 24"'s crash, just a different code path inside it,
run unconditionally every time the game builds its debug-menu UI overlay
during startup.)

First hypothesis: the game hardcodes `/system/fonts/DroidSans.ttf` (via
`im::IFont::CreateDefaultFont()` -> `FT_New_Face()`), and that font was
deprecated/removed from stock Android around 2012 - looked like a classic
"missing legacy file" crash. **Asked the user to run
`adb shell ls /system/fonts/` on their actual device to check. This
theory is DISPROVEN**: `DroidSans.ttf` and `DroidSans-Bold.ttf` are both
present (this device's Honor/Magic UI build keeps them for legacy-app
compatibility), and the register dump (`r0`/`r4` hold a plausible non-null
heap pointer) confirms `FT_New_Face()` really did succeed and returned a
valid `FT_Face`. The crash is one level deeper, inside FreeType itself:
`FT_Set_Char_Size()` -> `FT_Request_Size()` dereferences
`face->driver->clazz` (the loaded font driver's function table) at offset
`0x60` - and that pointer is NULL (`r3` in the register dump is exactly
`00000000`, matching the fault address `0x60` = `NULL + 0x60` exactly).
`face->driver` itself is a valid, non-null pointer; only its `clazz`
field is unpopulated - this looks like the bundled FreeType build's
module/driver registration is incomplete, a pre-existing engine-side
defect unrelated to Android version. It's plausible this exact code path
(building the "debug menu" UI overlay, which `M3GApplication::OnCreate()`
constructs unconditionally on every startup) was never actually exercised
successfully before - fix #26 is what first let execution reach this far.

Disassembling `FT_Request_Size()` right around the crash:
```
ldr r3, [r4, #96]     ; r3 = face->driver
ldr r3, [r3, #20]     ; r3 = driver->clazz
ldr r5, [r3, #96]     ; r5 = clazz->request_size (a driver-specific hook)   <- CRASH (r3 NULL)
cmp r5, #0
beq <generic-fallback>  ; if the driver has no custom hook, use FreeType's own
                         ; standard, already-safe generic fallback sizing path instead
ldr r0, [r4, #88]
blx r5                   ; otherwise, call the driver's custom hook
```
A driver with no custom hook (`clazz->request_size == 0`) is a completely
normal, already-handled case - FreeType's own generic fallback covers it
correctly for any driver type. A proper `driver->clazz == NULL` check
would need more instruction slots than the gap between the crash site and
the next fixed branch target actually has room for (8 slots available,
9 needed for a fully general fix), so fix #27
(`ElfMassEffectFreeTypeRequestSizePatcher.kt`, 0.27-fontfix) takes the
simpler, universally-safe route instead: replace just the crashing load
with `mov r5, #0` - i.e. "pretend the driver never had a custom hook",
which always routes into FreeType's own already-safe fallback path
regardless of *why* the driver-specific path is unusable. `face->driver`/
`driver->clazz` themselves, and the driver-specific-hook path itself for
any other driver where it isn't broken, are completely untouched.
Verified via kotlinc harness (match + patch against the real binary),
`objdump` (confirms the resulting instruction is exactly `mov r5, #0`),
and a byte-diff (only the intended `0x5f81e8`-`0x5f81ec` window changed,
2 of those 4 bytes actually differ). Same two-layer gate as always
(filename + exact-original-bytes match). **Not yet real-device tested.**
Next step: user installs 0.27-fontfix and actually tries to play - does
this second crash disappear too, and if so, does the game finally reach
real, playable gameplay?

**Round 26: 0.27-fontfix REAL-DEVICE TESTED - fix #27 confirmed effective
(the exact crash it targeted is gone), but a THIRD, deeper native SIGSEGV
shows up immediately after, in the very code fix #27 routes execution
into.** New backtrace:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00dd8814
#00 FT_Request_Metrics+68
#01 FT_Request_Size+124
#02 FT_Set_Char_Size+104
#03 im::FreeTypeFont::FreeTypeFont(char const*, float)+260
... (same #04-#08 chain as "Round 25": CreateDefaultFont -> PlainSkin ->
UILayer -> DebugMenuLayer -> DebugMenu::GetDebugMenuLayer -> M3GApplication::OnCreate)
```
`FT_Request_Size+124` is the `bl FT_Request_Metrics` call fix #27's
`mov r5, #0` routes into (confirmed via objdump: this is exactly
FreeType's own generic-fallback branch, proving fix #27 works as
designed - the crash truly did move to a new, deeper location, not just
shift by coincidence). Inside `FT_Request_Metrics`, the crash is
`str r2, [r4, #20]` where `r4 = face->size` (the same `face+88` field
`FT_Request_Size` reads) - i.e. `face`'s `FT_Size` object itself, not
just the driver's optional hook, turns out to be bad: register dump shows
`r4 = 0x00dd8800` (a plausible-looking, non-null address) but writing 20
bytes past it (`0x00dd8814`) faults with `SEGV_MAPERR` (no mapping at
all, not a permission/read-only issue) - so `face->size` doesn't point at
a real, fully-usable allocation.

Traced this back through `FT_New_Size()` and `FT_New_GlyphSlot()`
(FreeType's own functions that allocate and populate `face->size` when a
face is first opened, both disassembled from the pristine reference
binary): both read `driver->clazz` at the exact same offset as the
already-known-broken read in `FT_Request_Size`, and BOTH dereference it
**without any null check** (`ldr r8, [r2, #44]` / `ldr sl, [r7, #40]`,
straight off `clazz`, no `cmp`/`beq` guard beforehand). If `driver->clazz`
were already NULL when these ran, they'd crash immediately, deep inside
`FT_New_Face()` itself - but the crash logs (both this one and "Round
25"'s) clearly show `FT_New_Face()` already returned successfully before
`FT_Set_Char_Size()` is ever called (same constructor, `FT_New_Face` at
offset+216, `FT_Set_Char_Size` at offset+260 - only about 8 pure
floating-point instructions apart, no function calls, no syscalls, no
possible thread-yield point in between). So `driver->clazz` demonstrably
**was valid** moments earlier, for this exact same `face->driver`, and is
demonstrably **NULL** by the time `FT_Set_Char_Size` reads it - with no
plausible legitimate code path in between that could explain the change.
That points away from "an incompletely-registered driver" (fix #27's
working theory, which is still correct for what it fixed) and toward
something writing over `driver->clazz` from outside - most likely genuine
heap corruption (a stray write, a use-after-free, or a buffer overflow
elsewhere in this heavily cross-compiled 2011 engine landing on this
exact structure) rather than a single well-understood defect.

Also confirmed `im::IFont::CreateDefaultFont(float)` is called from (at
least) four places in this binary, not just the debug menu - so this
isn't isolated to debug-only tooling; anything else in the game that asks
for the "default font" would hit the identical crash once reached.
Skipping just the debug-menu construction (the obvious lower-risk
alternative) would therefore not be a real fix, only a delay.

Given `FT_New_Size`/`FT_New_GlyphSlot` are shared by every font this
binary loads (not just the broken one), and the evidence now points at
memory corruption rather than a single missing null-check, a further
patch here carries real risk of "fixing" this one symptom while masking
or interacting unpredictably with whatever is actually smashing this
structure - a materially different risk profile than fixes #26/#27's
single, narrowly-gated, provably-safe instruction swaps. Findings
reported to the user, who asked to keep going ("we've come this far,
it has to work now") - so fix #28 (0.28-fontsizefix) was shipped as the
next, most-actionable experiment rather than stopping here.

**Fix #28 (`ElfMassEffectFreeTypeSizeAllocPatcher.kt`, 0.28-fontsizefix):**
disassembled FreeType's own `FT_New_Size()` (the real function that
allocates `face->size`) from the pristine reference binary. It reads
`clazz->size_object_size` (how many bytes to allocate for the face's
`FT_Size` object) straight off `driver->clazz`, with no null check, and
passes that value straight through to the allocator - so a wrong/too-small
value from this driver's (evidently at least partially broken)
registration would produce exactly the observed symptom: a plausible,
non-null `face->size` pointer whose buffer isn't big enough for
`FT_Request_Metrics()` to write its metrics fields into. Also confirmed
`FT_New_Size()` and its sibling `FT_New_GlyphSlot()` both dereference
`driver->clazz` unguarded elsewhere in the same function - if `clazz`
were NULL at that point, the crash would happen immediately inside
`FT_New_Face()`, which the crash logs prove doesn't happen (it returns
successfully; `FT_Set_Char_Size()` runs only ~8 pure-FPU instructions
later, no calls/syscalls in between) - so `clazz` was genuinely valid
when this ran, making "wrong reported size" more likely than "clazz was
already missing" for this specific spot. The fix replaces just the
`ldr sl, [r7, #40]` read with `mov sl, #64` - always allocate a generous
64-byte buffer (comfortably more than the ~40 bytes actually needed)
regardless of what the driver reports, exactly the same "stop trusting a
possibly-broken driver value, use a safe generic default instead"
philosophy as fix #27, just applied to an allocation size instead of a
hook pointer. Verified via kotlinc harness, `objdump` (confirms the
resulting `mov sl, #64`), and byte-diff (only `0x5f844c`-`0x5f8450`
changed, 2 of 4 bytes differ). Same two-layer gate as always. Honestly
documented in the patcher's own doc comment as the best-supported
hypothesis so far, not a certainty - if `face->size` turns out to be
getting corrupted/overwritten *after* a correctly-sized allocation rather
than allocated too small in the first place, this alone won't fully fix
"Round 26"'s crash, but it's a safe, low-risk, easily-verified next
experiment either way. **Not yet real-device tested.**

**Round 27: 0.28-fontsizefix REAL-DEVICE TESTED - the exact same crash,
byte-for-byte, with the forced 64-byte allocation in place. Fix #28's
"undersized allocation" hypothesis is disproven.** User's response to
"Round 26"'s findings: keep going, we've come too far to turn back - so
rather than stopping here, the investigation continued.

With the size-allocation theory ruled out empirically, dumped the real,
static `tt_driver_class` structure straight out of the binary's
`.data.rel.ro` section (found via its symbol name - this binary keeps
FreeType's internal symbols, not just the public API ones) - the actual,
compile-time-constant TrueType driver table, impossible to "corrupt" at
runtime since nothing ever writes to it after the linker's relocations
run. Every field in it is sane: `size_object_size` is genuinely 304 bytes
(not some tiny/garbage value - fix #28's hardcoded 64 was already smaller
than the real correct value, for whatever that turned out to be worth),
`slot_object_size` is 160, and `request_size` is a real, valid,
non-NULL function pointer (`tt_size_request`) - fix #27 forces this
specific driver instance's copy of that field to look like "no custom
hook" (0), which works around the crash but isn't what a correctly
working driver actually has. This confirms the driver's own static data
was never the problem - the defect is purely in some runtime piece of
state (most likely the live, heap-allocated `driver` object's `clazz`
field) not reliably holding the value it should, for reasons this
project's tooling (static disassembly plus post-mortem crash dumps, no
live debugger) can't fully pin down.

Given two independent real-device tests now agree that writing into
`face->size` inside `FT_Request_Metrics()`'s "not scalable" fallback
branch reliably faults regardless of how that buffer was allocated, fix
#28 (`ElfMassEffectFreeTypeSizeAllocPatcher`) is unwired from the build -
its hypothesis didn't pan out, and forcing an arbitrary, wrong allocation
size for every font in the binary is pure unnecessary risk with no
upside. In its place: fix #29
(`ElfMassEffectFreeTypeMetricsSkipPatcher`, 0.29-metricsskip) skips the
entire 7-field metrics-zeroing write sequence in that branch, jumping
straight to the function's own `pop {...,pc}` epilogue with a single
branch instruction - provably safe since that return value is discarded
by the only caller, and this removes the only way this exact code path
can fault without needing to fully explain why `face->size` is
unreliable here. Verified via kotlinc harness, `objdump` (confirms the
branch correctly lands on the existing epilogue, skipping all 6 other
writes), and byte-diff (only `0x5f7cd0`-`0x5f7cd4` changed, all 4 bytes
differ - a full instruction swap, not a partial one, as expected for a
`str` -> `b` replacement). Same two-layer gate as always. **Not yet
real-device tested.**

**Round 28: 0.29-metricsskip REAL-DEVICE TESTED - NO NATIVE CRASH AT
ALL.** For the first time in this entire chase (since "Round 17"), the
crash log has no `Fatal signal`, no `SIGSEGV`, no `backtrace:` anywhere
in it. Fix #29 works. The native-crash chase that ran from fixes #23
through #29 - the storage-exit watchdog, then three separate layers of a
broken FreeType font driver - is done: the game now runs for several
real seconds (past `OnCreate`, past the debug menu, into whatever
initialization follows) before hitting a completely different, ordinary,
well-understood **Java-level** crash:
```
java.lang.IllegalArgumentException: Service Intent must be explicit:
Intent { act=com.android.vending.billing.MarketBillingService.BIND }
    at ... BillingService.bindToMarketBillingService(BillingService.java:16)
    at ... BillingService$BillingRequest.runRequest(BillingService.java:16)
    at ... BillingService.checkBillingSupported(BillingService.java:5)
    at ... MarketJNI$1.run(MarketJNI.java:10)
```
Exactly the same class of bug as the Moga controller (fix #3) and the EA
Plasma/Samsung IAP initializer (fix #10) - an implicit service `Intent`,
forbidden since Android 5.0 - just for Google Play Billing's own
`bindService()` call this time, runs on the main thread via a Handler
callback so it's a genuine app-killing crash, not a silently-swallowed
background failure. Fixed the same well-established way as those two:
fix #30 (`DexPatcher.kt`, `com/ea/easp/mtx/market/BillingService.smali`,
0.30-marketbilling) wraps `bindToMarketBillingService()` in a try/catch
using the same `wrapWholeMethod()` helper already proven correct across
three earlier fixes (`initializeIAP`, `DownloadActivity.init`,
`getAndroidUniqueId`) - only the target method name and containing file
path are new. In-app purchases going unavailable is an acceptable, minor
trade-off for a 2012-era Play Billing integration; actual gameplay
doesn't depend on it. This dex-level fix follows this project's
established pattern of shipping smali patches without a local compile
check (the `smali`/`dexlib2`/`baksmali` libraries aren't fetchable in
this project's own sandbox, only inside the Android Studio build the
user runs) - verified by careful manual review against the already-
proven helper instead, same as every other dex fix here; final
confirmation comes from the next real-device test as usual. **Not yet
real-device tested.**

**Round 29: 0.30-marketbilling REAL-DEVICE TESTED - fixes #29 and #30 BOTH
confirmed working, plus a fourth native crash one frame further up the
same FreeType chain.** The `FT_Request_Metrics()` crash fix #29 targeted
is gone for good this time too (still no `Fatal signal` there) - and the
billing crash fix #30 targeted didn't recur either. But a new native
`SIGSEGV` now appears:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xc0000014
    r0  e340aab5  r3  c0000000  r4  e340aab5
backtrace:
    #00 pc 005f81e4  libMassEffect.so (FT_Request_Size+68)
    #01 pc 005f82b4  libMassEffect.so (FT_Set_Char_Size+104)
    #02 pc 00410d1c  libMassEffect.so (im::FreeTypeFont::FreeTypeFont(char const*, float)+260)
    ... (same CreateDefaultFont <- ... <- M3GApplication::OnCreate chain as every round before)
```
`FT_Request_Size+68` is one instruction *before* fix #27's already-patched
site - this time `face->driver` itself reads as a bogus, round-looking
`0xc0000000`, and - the real tell - the `face` pointer argument
(`r0`/`r4` = `0xe340aab5`) is **odd**, not 4-byte aligned, which no real
C++ object pointer ever is. Disassembling
`im::FreeTypeFont::FreeTypeFont(char const*, float)` end to end
(`0x410c18`-`0x410d44`) found the actual root cause tying together every
font crash seen since "Round 25": the constructor calls `FT_New_Face()`
and then **never checks its return value** before reloading `this->face`
and calling `FT_Set_Char_Size()` on it. Since the enclosing object was
`operator new`-allocated (never zero-initialized) just before this
constructor runs, any time `FT_New_Face()` fails to load this specific
debug-menu font, `this->face` is left holding whatever uninitialized
heap bytes happened to already be there - different garbage on every
run, which is exactly why fixes #27 and #29 each looked like they fixed
"the" crash, only for a different-looking symptom of the very same
underlying bug to surface next time.

Fix #31 (`ElfMassEffectFreeTypeNewFaceGuardPatcher`, 0.31-newfaceguard)
addresses this directly instead of patching yet another downstream
symptom: `FT_New_Face()`'s return code in `r0` was dead code in the
original binary (computed, never read), so the patch turns the 11
instructions between the `bl FT_New_Face` and `bl FT_Set_Char_Size` calls
into an inline "only run this on success" guard, using ARM's per-
instruction condition-code field (a `cmp r0, #0` up front, then every
following instruction in that span changed from unconditional to
EQ-conditional) rather than inserting a branch - so the patch is the same
44 bytes, same instruction count, same position, as the original. One
redundant instruction (the original loaded the constant 72 into a
temp register twice, once for each of the call's last two arguments) was
folded into a direct load, freeing exactly the one slot the `cmp` needed.
On success every guarded instruction still runs, byte-for-byte identical
behavior to before; on failure they're all skipped and execution falls
straight into the constructor's own unmodified epilogue, never touching
`FT_Set_Char_Size` with a face that failed to load. Assembled and linked
at the real address with `arm-linux-gnueabi-as`/`ld -Ttext=0x410cf4` so
every PC-relative offset (the literal-pool load, the `bl` target) was
computed by the real toolchain rather than hand-encoded, then verified
via the usual kotlinc harness (`patched=true`), `objdump` (confirms the
exact intended instruction sequence, correctly resolving to the real
`FT_Set_Char_Size` address and the real literal pool), and a byte-diff
across the whole ~17MB file (only `0x410cf4`-`0x410d1f`, 22 of 44 bytes,
changed - nothing else in the file touched). Same two-layer gate as
always. **Not yet real-device tested.**

**Round 30: 0.31-newfaceguard REAL-DEVICE TESTED - fix #31 fully works.
No FreeType/font crash of any kind anymore.** This closes the entire
font-driver saga that ran from "Round 25" through fix #31 (5 fixes: #27,
the disproven-and-unwired #28, #29, and #31 - the real root cause). The
game now proceeds all the way to actually starting a new game
(`Application::StartNewGame()` -> `LoadLevelWithCinematics()`) before
hitting a brand new crash - a NULL pointer dereference (fault addr
`0x30`) because `LevelIndexData::GetLevel("randall")` (a hardcoded
start-level lookup key baked into the binary itself) returns NULL: the
level index has nothing in it.

This one is a different kind of problem from every crash fixed so far.
Digging into *why* the level index is empty: the binary references
`/published/data/level_index.prefabs.sb` and several sibling
`/published/data/*.prefabs.sb` files, none of which are bundled inside
the APK's own `assets/`. The APK's own
`assets/downloadcontent/config.properties` explains why - this is an EA
"ADC"-style game: the installed APK is a small wrapper, and **~900MB**
of the actual game content (`TOTAL_SPACE_MB=900`) is meant to be
downloaded *after* install from `http://gam.eamobile.com/` (an EA
server that has been dead for years) into external storage at
`DATA_FOLDER=/Android/data/com.ea.games.meinfiltrator_gamepad`. Fixes
#20/#21 earlier in this project (`assetsFoundLocally()` /
`isAssetVersionCompatible()` forced to always return true) were designed
to bypass a broken, always-failing verification against that same dead
server, on the assumption the real content was *already* sitting on the
device from some earlier download. If that ~900MB was in fact never
downloaded onto this particular device, those two fixes just mean the
game now silently trusts content that was never fetched, instead of
showing a (more honest, if unhelpful today) "content missing" message.
The native code itself resolves external storage through a real JNI
call (`GetPrimaryExternalStorageDirectory`), not a hardcoded legacy
path, so this doesn't look like a patchable scoped-storage bug - it
looks like the ~900MB of level/asset content genuinely isn't present on
this device.

This is not something a code patch can fix, and per this project's core
principle it's also not something this project should try to source or
work around - only real, user-supplied game content can fill an empty
level index. **Asked the user directly whether they have (or can get) a
copy of that downloadable content**; if not, this is likely the natural
stopping point for this particular bonus/secondary game, with the font
saga and every earlier native/dex crash all fixed along the way.

**Round 31: the ~900MB content is confirmed present on the device.** The
user ran `adb shell ls -la .../com.ea.games.meinfiltrator_gamepad/` and
`adb shell find ... -iname "*level_index*"` and confirmed the exact file
the engine looks for really is there:
`/sdcard/Android/data/com.ea.games.meinfiltrator_gamepad/published/data/
level_index.prefabs.sb`, alongside the download SDK's own completion
markers (`Downloaded.indicate`, `AssetInfo.indicate`, etc., all owned by
the app's own UID) - this really is a device that once fully downloaded
this game's content, not a fresh install missing it. So the "missing
content" theory from earlier in this Round is ruled out; something in
the native code's own file-open path between `LevelIndexData::Load()`
and that (real, present) file still isn't finding it, for a reason not
yet root-caused (would need a live debugger to fully pin down - traced
the VFS mount, `im::Platform::GetFileSystem()`, and `EA::IO::
StorageDirectory`'s primary/secondary-external-storage enumeration order
as far as static disassembly reasonably allows without one).

Rather than leave the game hard-crashing on this while that deeper
question stays open, shipped fix #32
(`ElfMassEffectLevelIndexNullGuardPatcher`, 0.32-levelindexguard): the
exact same function already has a completely different, already-correct
fallback for a level that's found but simply has no cinematics - it
skips straight to calling `Application::LoadLevel(name, "")` directly,
using only two registers (the level name and `this`) that are set up
before the crash site and never touched by it. The patch turns the 5
instructions right after `bl LevelIndexData::GetLevel(...)` into an
ARM-condition-code guard (`movs r1, r0` to capture the result and set
the zero flag in one instruction, then the two dereferences and the
comparison all made NE-conditional, leaving the existing `beq` -
completely unchanged, same target - to fire whenever `GetLevel()`
returned NULL, in addition to its original "found, no cinematics" case).
For any level that IS found, this is instruction-for-instruction
identical behavior to the original, just reordered; only a missing
level's crash changes, into a graceful fallback instead. Same 5-for-5
instruction swap discipline as fix #31, assembled and linked at the real
address, verified via kotlinc harness + `objdump` + whole-file byte-diff
(only `0x3298a0`-`0x3298af`, 12 of 20 bytes in the patched window,
changed - the `beq` itself is byte-identical, as expected since neither
its condition nor its target changed). **Not yet real-device tested.**
Whether the level actually loads and plays once this crash is gone, or
whether the deeper "why is the index empty" question surfaces something
else further down, is the next thing to find out.

**Round 32: 0.32-levelindexguard REAL-DEVICE TESTED - fix #32 works, the
level-index crash is gone, and the game gets much further into actually
loading a level** (the crash log more than doubled in length) before
hitting a SIXTH native SIGSEGV, this time on a background asset-loading
thread rather than the main GL thread:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000, r3 00000000
#00 im::m3gext::ModelCache::LoadNodeUncached(...)+212
#01 im::m3gext::ModelCache::CachePreloadNode(...)+324
#02 LoadTaskCache::Run()+324
#03 im::RunFunction(void*)+12
#04 RunnableFunctionInternal(void*)+452
```
Same shape of bug as fix #32, one level deeper: `LoadNodeUncached()`
calls `m3g::Loader::Load(name, flag)` to load a model/node file into a
local vector, then unconditionally dereferences that vector's first
element with no check for whether anything actually got loaded - an
empty result (the file couldn't be loaded) leaves the vector's `begin`
pointer NULL, and dereferencing it crashes exactly as observed.

Fixed as fix #33 (`ElfMassEffectModelCacheNullGuardPatcher`,
0.33-modelcacheguard): the same function already has a clean "no node
produced" fallback a few instructions later (originally reached only
when the *first loaded element itself* is a null pointer); the patch
reroutes "the vector is empty to begin with" into that same fallback too,
using the same ARM condition-code approach as fixes #31/#32 (`movs r7,
r3` to capture the vector's begin pointer and set the zero flag in one
instruction, the dereference made NE-conditional, and the existing,
byte-identical `beq` now firing on either condition). Documented
trade-off: fitting this into the original, non-growable instruction
budget meant giving up the original code's *separate* check for "a
non-empty vector whose first entry is itself a null pointer" - a
narrower, more hypothetical case that no real-device test has ever
actually hit (every test hit the empty-vector case this patches).
Verified via kotlinc harness + `objdump` + whole-file byte-diff (only
`0x3aa8b8`-`0x3aa8bf`, 6 bytes, changed).

**Round 33: 0.33-modelcacheguard REAL-DEVICE TESTED - fix #33 works,
`LoadNodeUncached()` no longer crashes there** - the same background
asset-loading thread now gets one call frame deeper before hitting a
SEVENTH native SIGSEGV:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
r0 00000000  r1 00000000  r4 00000000  r5 00000000
#00 m3g::Object3D::Duplicate(m3g::Object3D::DuplicateContext*)+356
#01 im::m3gext::ModelCache::CacheCreateNode(...)+64
#02 im::m3gext::ModelCache::CachePreloadNode(...)+964
#03 LoadTaskCache::Run()+324
```
A third recurrence of the same "not found/empty result used unchecked"
pattern as fixes #32/#33, one level deeper still: `CacheCreateNode()`
reads a "source node to clone" pointer and, without checking it for
NULL, unconditionally calls `m3g::Object3D::Duplicate()` on it, which
immediately dereferences that pointer (`this`) as its very first step
when called with no external context (always the case at this call
site) - a NULL source crashes there exactly as observed.

Fixed as fix #34 (`ElfMassEffectObject3DDuplicateNullGuardPatcher`,
0.34-duplicateguard): `CacheCreateNode()` already has a clean
"duplicate came back NULL" fallback a few instructions later
(originally reached only by checking `Duplicate()`'s return value);
the patch reroutes "the source pointer was NULL to begin with" into
that same fallback, by never calling `Duplicate()` in that case. Since
`bl`/`blne` doesn't preserve CPU flags across a call, this needed a
variant of the usual technique: `cmp r0,#0` before a conditional
`blne Duplicate`, then a fresh `movs r4,r0` right after the (possibly
skipped) call to both capture the result and re-test it, followed by
three NE-conditional instructions (`ldrne`/`movne`/`ldrne`, replacing
what were previously unconditional) that fall straight through to the
existing continuation whenever `r4 == 0` - reached now on either "no
source to duplicate" or "duplicate failed", with zero net instruction
growth (6 instructions in, 6 out). Verified via `arm-linux-gnueabi-as`/
`ld` toolchain assembly at the real address + kotlinc harness +
`objdump` + whole-file byte-diff (only `0x3aa278`-`0x3aa28f`, 24 bytes,
changed).

**Round 33: 0.34-duplicateguard REAL-DEVICE TESTED - fix #34 works,
the entire background asset-loading crash chain is gone**, and the
game reaches actual HUD initialization during a level transition -
the first crash in a long while on the main GL thread instead of a
background thread - a SIGSEGV, the EIGHTH native crash:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000138
r0 00000000  r1 00000001  r2 00000000  r3 00000000  r4 <valid Hud*>
#00 Hud::InitLayer(eastl::intrusive_ptr<m3g::Renderer> const&, LayerGameWorld*)+500
#01 boost::...invoke(...)+36
#02 im::M3GApplication::OnRunLoopTick()+724
```
Same "unchecked empty/failed result" pattern as fixes #32-#34, but in
the UI layer this time: `Hud::InitLayer()` creates a `"hud"`-named
widget via `WidgetFactory::CreateWidget()` and, if that fails,
unconditionally dereferences the resulting NULL pointer across roughly
50 instructions spread through the function (writing an owner pointer
into it, then twice fetching its vtable to register callbacks) - the
crash hits the very first of these.

Fixed as fix #35 (`ElfMassEffectHudWidgetNullGuardPatcher`,
0.35-hudwidgetguard): unlike fixes #32-#34, there was no nearby
existing "empty" fallback to reroute into, and the widget-dependent
code was too long and too full of its own unrelated internal branches
(each clobbering flags for its own purposes) to guard with a single
upfront branch in the available instruction budget. Instead, every
individual instruction that actually dereferences the widget is made
NE-conditional, each paired with its own fresh, zero-cost null test
via the `movs`-combine trick (each test folded into a register copy -
or, in one spot, a now-redundant reload of the widget pointer that
becomes a same-cost register copy instead - that the function already
had to do), across three small, independently-verified,
independently-gated regions applied atomically (all three or none):
region A (`0x114ef4`-`0x114f10`, 28 bytes: the owner-pointer write and
the first vtable fetch), region B (`0x114f54`-`0x114f60`, 12 bytes:
the first callback-registration call), and region C
(`0x114f9c`-`0x114fbc`, 32 bytes: the second callback-registration
call). Verified via `arm-linux-gnueabi-as`/`ld` toolchain assembly at
each real address + kotlinc harness + `objdump` + whole-file byte-diff
(only those three regions, 21 bytes total, changed).

**Round 34: 0.35-hudwidgetguard REAL-DEVICE TESTED - fix #35 works**,
and `Hud::InitLayer()` gets 228 bytes further before hitting a NINTH
native crash, still on the main GL thread but of a different kind:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xe08956dc
r0 e0895670  r4 e0895670  r10 e0895670  ...
#00 im::scene2d_new::Node::SetVisible(bool)+28
#01 Hud::InitLayer(...)+728
```
`code 2` (a permission fault on *mapped* memory) instead of the usual
`code 1` (unmapped/NULL) - `r0` is a real, non-NULL address, just not
one this code may write to. Root cause: a 12-byte stack slot gets
written once (`0x114f14`) as scratch space for a `boost::function`
callback registration (a "manager" pointer - a fixed address *inside
this library's own `.text`/`.rodata`*), then later read back
(`0x114fbc`) as if it were an entirely different local, a never-
actually-populated `eastl::shared_ptr<Node>` - the leftover manager
pointer always reads as "non-NULL", so the accidentally-always-true
check calls `SetVisible()` on it, writing a byte into the library's
own read-only segment. Presumably a dormant bug in the original 2012
binary, newly reachable only because fix #35 is what let this
function progress this far for the first time.

Fixed as fix #36 (`ElfMassEffectHudDebugNodeSkipPatcher`,
0.36-huddebugnodeskip): since the checked value was never a real Node
on any reachable path, there's no "make it conditional on validity" -
the always-safe behavior is exactly what already happens when the
(never-actually-true) check reads zero: skip straight to the
destructor call. A single-byte fix: `beq 0x114fe0` becomes an
unconditional `b 0x114fe0` (condition nibble `0x0A` -> `0xEA`, same
size, same target). Verified via `arm-linux-gnueabi-as`/`ld` +
kotlinc harness + `objdump` + whole-file byte-diff (exactly 1 byte
changed in the whole 17MB file).

**Round 35: 0.36-huddebugnodeskip REAL-DEVICE TESTED - fix #36 works,
the `SetVisible()` crash is gone**, and `Hud::InitLayer()` gets exactly
8 bytes further (the very next instruction) before hitting a TENTH
native crash:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000004
r0 e72e9154  r3 00000000  r4 e72e9154  r6 e72e9154
#00 eastl::shared_ptr<im::scene2d_new::Node,...>::~shared_ptr()+12
#01 Hud::InitLayer(...)+736
```
**Correction to Round 34/fix #36's writeup above:** it claimed the
companion `~shared_ptr()` destructor call right after the skipped
`SetVisible()` (same phantom stack-slot "Node") was "provably harmless"
because the shared_ptr's control-block field is always zeroed and a
destructor would treat that as "nothing to release". Real-device
testing disproved this - the compiled destructor never actually checks
its control-block field for NULL before dereferencing through it
(`ldr r3,[r0,#4]` then immediately `ldr r2,[r3,#4]`), so a zeroed
control-block field makes it dereference `NULL+4`, faulting at exactly
`0x00000004` - which matches this crash precisely. The claim was wrong;
this round fixes what it wrongly waved off.

Fixed as fix #37 (`ElfMassEffectHudDebugNodeDestructorGuardPatcher`,
0.37-huddestructorguard): fix #36 already made the 3-instruction
`ldr r0,[sp,#100]` / `cmp r0,#0` / `b 0x114fe0` sequence unconditional,
leaving the `ldr`/`cmp` pair's *result* dead but its *flags* still
alive all the way to the destructor call 16 bytes later (nothing in
between touches CPSR). This patch repurposes that already-dead pair to
test the field that actually matters instead: `ldr r1,[sp,#104]` (was
`ldr r0,[sp,#100]`) loads the real control-block field, `cmp r1,#0`
(was `cmp r0,#0`) tests it, and the destructor call itself becomes
`blne 0xb2608` (was unconditional `bl`) - same target, same
link-register-setting call semantics, just conditional now. Same
single-nibble condition-flip technique as fix #36, applied to a `bl`
instead of a `b`. The shared destructor function's own body (called
from other, legitimately-valid sites) is never touched - only this one
call site. Verified via `arm-linux-gnueabi-as`/`ld` at the real
addresses with full surrounding context (fix #36's own patched byte
included) + kotlinc harness + `objdump` + whole-file byte-diff (5 bytes
differ from the pristine original in the whole 17MB file: the 1 byte
fix #36 already changed, plus 4 new bytes from this fix).

**Round 36: 0.37-huddestructorguard REAL-DEVICE TESTED - fix #37 works,
the entire `Hud::InitLayer()` crash chain (fixes #35/#36/#37 together)
is gone**, and the game reaches a completely different function - an
ELEVENTH native crash:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000028
r3 00000000
#00 im::scene2d_new::layouts::Widget::AppendChild(eastl::shared_ptr<im::scene2d_new::Node,...> const&)+16
```
The same "unchecked empty/failed result" pattern as fixes #32-#34:
`AppendChild()` reads a child `Node*` out of the `shared_ptr` it's
given and, without a NULL check, immediately reads a "name" pointer at
offset 40 of it to compare against two special names (`"_clip"`,
`"_mask"`). Unlike those earlier fixes, there's no cheap guard here -
`Group::AppendChild()`, which every path in this function eventually
calls, is *also* not NULL-safe for its argument, so simply making the
name comparisons safe would just relocate the crash one level deeper.
The real fix has to skip the append entirely for a NULL child. But
`AppendChild()`'s own instruction budget has zero slack anywhere in it
(verified instruction-by-instruction - no redundant reload to
repurpose this time), and the needed guard logic (compare, conditional
early return, the now-safe original load, normal return) needs 4
instructions where only 1 is being replaced.

Fixed as fix #38 (`ElfMassEffectAppendChildNullGuardPatcher`,
0.38-appendchildguard) using a technique new to this project: an
out-of-line trampoline ("code cave"). The single crashing instruction
becomes a same-size `bl` to a small 4-instruction routine written into
16 bytes of confirmed-unused, unreferenced zero padding in `.rodata`
(part of the same read+execute `LOAD` segment as `.text` on this
binary, sitting right before a real rapidxml lookup table, with no
relocation or symbol anywhere in those 16 bytes): test the child
pointer, and either restore this function's own saved registers and
return straight to *its* caller (NULL - skips the append and the
`Group::AppendChild()` call entirely), or do the original safe-now
load and return normally (non-NULL - completely unchanged behavior).
Verified via `arm-linux-gnueabi-as`/`ld` at both real addresses (full
surrounding context reproduced and confirmed byte-identical aside from
the one replaced instruction) + kotlinc harness + `objdump` +
whole-file byte-diff (18 bytes differ from the pristine original in
the whole 17MB file: 4 at the call site, 14 of the cave's 16 bytes -
the other 2 happened to already be zero).

**Round 37: 0.38-appendchildguard REAL-DEVICE TESTED - fix #38 works**,
and the game hits its first crash on the BACKGROUND level-loading
thread since fix #33 - real forward progress into loading actual level
content, not just UI/HUD init - a TWELFTH native crash:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000107
r0 00000000
#00 GameWorld::InitLevel(...)+300
#01 LayerGameWorld::LoadLevel(LoadTransition*)+60
#02 LoadTransitionTask::Run()+564
```
The exact same "unchecked empty result" pattern as fix #33
(`LevelIndexData::GetLevel()` returning NULL for a level not found in
the index) but at a different call site: `GameWorld::InitLevel()`
reads a flag byte at offset 263 of the result without checking it
first. Unlike fix #33's call site, there's no small nearby "not found"
branch here - both ways this flag check continues lead deeper into
normal successful level setup. `GameWorld::InitLevel()` is a single
~3000-byte function with exactly ONE return point, all the way at the
end (destructing a `PerfTimer` RAII local and unwinding a large stack
frame) - too far away for an in-place guard.

Fixed as fix #39 (`ElfMassEffectGameWorldLevelNullGuardPatcher`,
0.39-gameworldlevelguard), reusing fix #38's out-of-line-trampoline
("code cave") technique in the same 224-byte free block of unused
`.rodata` padding (right after fix #38's own 16-byte trampoline): the
crashing instruction becomes a same-size `bl` to a 4-instruction cave
that tests the result, and either jumps straight into
`GameWorld::InitLevel()`'s own existing shared epilogue (not found -
cleanly destructs what's already been constructed and returns, exactly
like every other exit from this function) or does the original,
now-safe read and returns normally (found). Verified via
`arm-linux-gnueabi-as`/`ld` at both real addresses + kotlinc harness +
`objdump` + whole-file byte-diff (18 bytes differ: 4 at the call site,
14 of the cave's 16 bytes - the other 2 happened to already be zero;
also re-verified together with fix #38's own trampoline in the
adjacent 16 bytes to confirm no interference). **Not yet real-device
tested.**

**Side investigation (no code change): the "masseffect-portmaster"**
**PortMaster package.** The user found and shared a PortMaster port
(`EapRules/masseffect-portmaster` on GitHub) that runs the *exact same*
original `libMassEffect.so` (matching SHA1) on Linux ARM handhelds
through its own loader, rather than recompiling the game - raising the
question of whether that project had already solved any of the same
native crashes this project has been chasing. Checked: the bundled
`eapx.py` is purely an install-time asset extractor/validator (recipe
loading, staged extraction, hook running) with nothing crash- or
game-logic-related - grepped for crash/signal/JNI/null-guard keywords
and found only generic installer-hook machinery. The port's own README
documents real, substantial runtime work of its own (PVRTC texture
fallback, SDL audio-device init fix, 40 obsolete VFP short-vector mixer
instructions expanded into scalar trampolines for missing NEON, a
softfp/hardfp ABI bridge for the input path) but explicitly nothing
about null-pointer dereferences or memory-corruption patches, and a
code search of the repo for the specific symbols this project has been
patching (`InitLevel`, `AppendChild`, `InitLayer`, `shared_ptr`) found
no matches. Conclusion: genuinely a different, interesting project
solving different compatibility problems (missing NEON, texture
formats, glibc vs. bionic ABI) - but it doesn't appear to have hit or
worked around the same null-check bugs in the game's own code, so
there's nothing there to import into this project's fixes. The
crash-fixing work here continues from its own evidence (the crash logs
+ disassembly), as before.

**Round 38: 0.39-gameworldlevelguard REAL-DEVICE TESTED - fix #39**
**works**, and level loading gets past `GameWorld::InitLevel()` into HUD
setup for the freshly-loaded world before hitting a THIRTEENTH native
crash, still on the same background level-load thread:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
Cause: null pointer dereference
    r1 00000000
#00 widgets::UIWidget::GetWidget<HudDamageFeedback>(im::Symbol const&)+12
#01 masseffect_widgets::HudWidget::PostWorldSetup()+52
#02 Hud::InitWorld(GameWorld*)+36
```
`Hud::InitWorld()` calls `HudWidget::PostWorldSetup()` and
`TriggerLevelTitle()` on `this->m_hudWidget` (offset `+568`/`0x238`)
completely unconditionally - the *exact same field* fix #35
(`ElfMassEffectHudWidgetNullGuardPatcher`) already guards inside
`Hud::InitLayer()` for the case where `WidgetFactory::CreateWidget("hud",
...)` fails to produce it. Fix #35 stops `InitLayer()` itself from
crashing on that failure, but the widget legitimately stays NULL - and
`InitWorld()`, a different function later in the same level-load
sequence, never checks it at all.

Fixed as fix #40 (`ElfMassEffectHudWorldSetupNullGuardPatcher`,
0.40-hudworldsetupguard): same "no spare in-place instruction budget"
situation as fixes #38/#39 (the two widget-dependent calls sit back-to-
back with no room to fold in a fresh flag test), so this reuses their
out-of-line-trampoline ("code cave") technique, in the same free
`.rodata` block right after fix #39's own trampoline: the first
instruction of the pair becomes a same-size `bl` to a 4-instruction cave
that tests the widget and either returns normally (widget present - both
calls proceed exactly as before) or branches straight past both calls to
the unrelated code that already follows them. Verified via
`arm-linux-gnueabi-as`/`ld` at both real addresses + kotlinc harness +
`objdump` (confirming the branch lands exactly on
`Hud::InitWorld(GameWorld*)+0x34`) + whole-file byte-diff (18 bytes
differ: 4 at the call site, 14 of the cave's 16 bytes) + a combined re-
verification with fixes #38's and #39's own adjacent trampolines
confirming all three coexist with no interference (54 bytes differ in
total, matching each fix's own independently-verified count exactly).
**Not yet real-device tested.**

**Round 39: 0.40-hudworldsetupguard REAL-DEVICE TESTED - fix #40**
**works**, and `Hud::InitWorld()` runs all the way through HUD/input-
scheme setup before hitting a FOURTEENTH native crash, still the same
function, still the same background level-load thread:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000c4
Cause: null pointer dereference
    r6 00000000
#00 Hud::InitWorld(GameWorld*)+672
```
`r6` holds the result of `LevelIndexData::GetLevel(...)` - the same call
whose NULL result fixes #5 and #39 already guard at their own, different
call sites. `Hud::InitWorld()` uses it a third time, independently: it
reads `level->[196]` (fault addr `0xc4` = offset 196 through a NULL
`r6`), a level-tied title color, byte-swaps it, and stores it into a
`Hud` field that feeds `Hud::StateTransition()` - completely
unconditionally.

Fixed as fix #41 (`ElfMassEffectHudLevelTitleColorNullGuardPatcher`,
0.41-hudtitlecolorguard): same shape as fixes #38-#40 (an 8-instruction
read-transform-store block with nothing redundant in it and no nearby
fallback to reroute into), so it reuses their code-cave technique again,
in the same free `.rodata` block right after fix #40's own trampoline:
the block's first instruction becomes a same-size `bl` to a 4-instruction
cave that either does the read and returns normally (level found), or
branches straight past the whole block to the `Hud::StateTransition()`
call that already follows it (level not found - the title color is
simply left at whatever it already held, the same "skip the update"
fallback shape used everywhere else in this project). Verified via
`arm-linux-gnueabi-as`/`ld` at both real addresses + kotlinc harness +
`objdump` (confirming the branch lands exactly on
`Hud::InitWorld(GameWorld*)+0x2c8`) + whole-file byte-diff (18 bytes
differ: 4 at the call site, 14 of the cave's 16 bytes) + a combined re-
verification with fixes #38, #39, and #40's own adjacent trampolines
confirming all four coexist with no interference (72 bytes differ in
total, matching each fix's own independently-verified count exactly).
**Not yet real-device tested.**

**Round 40: 0.41-hudtitlecolorguard REAL-DEVICE TESTED - fix #41**
**works**, and `Hud::InitWorld()` finishes and calls
`Hud::StateTransition()`, which runs for hundreds of bytes before hitting
a FIFTEENTH native crash in a new function entirely:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000008d
Cause: null pointer dereference
    r0 00000001  r1 00000000
#00 InputForwarderTouchDPad::SetAutoFireEnabled(bool)+4
#01 InputSchemeBasic::SetFireEnabled(bool)+48
#02 Hud::StateTransition(Hud::State)+672
```
`StateTransition()` calls `InputSchemeBasic::SetFireEnabled(false)` on
the `Hud`'s embedded input scheme. That function already null-checks ONE
of its two dependent fields (an `InputRegionBoolean*`) but reads a
*sibling* field - meant to be an `InputForwarderTouchDPad*` - completely
unconditionally and passes it straight in as `this` for another call. On
this device that field holds the literal value `1`, not `0`: a distinct
"not assigned" sentinel, most likely because this build runs with a
physical gamepad and the touch D-pad forwarder that field would normally
point to is simply never constructed. Same "one dependency checked, its
sibling isn't" gap seen throughout this project, just with a sentinel of
`1` instead of the usual `0`.

Fixed as fix #42 (`ElfMassEffectTouchDPadForwarderNullGuardPatcher`,
0.42-touchdpadforwarderguard): the call site has no spare instruction to
fold a test into, so this reuses fixes #38-#41's code-cave technique
again, in the same free `.rodata` block: the `bl` to
`SetAutoFireEnabled()` becomes a `bl` to a 4-instruction cave that treats
any value `<= 1` (unsigned) as "not present" and returns immediately, or
otherwise tail-branches into the real function so it returns straight to
the original caller exactly as before. Verified via
`arm-linux-gnueabi-as`/`ld` at both real addresses + kotlinc harness +
`objdump` (confirming the tail-branch resolves to the real
`SetAutoFireEnabled` and the guard's own `bx lr` path is correct) +
whole-file byte-diff (15 bytes differ - fewer than the usual 18, since
this cave's bytes happen to overlap more existing zero padding) + a
combined re-verification with fixes #38-#41's own adjacent trampolines
confirming all five coexist with no interference (87 bytes differ in
total, matching each fix's own independently-verified count exactly).
**Not yet real-device tested.**

**Round 41: 0.42-touchdpadforwarderguard REAL-DEVICE TESTED - fix #42**
**works** (its own crash frame, `InputSchemeBasic::SetFireEnabled(bool)`
`+48`, no longer appears in the backtrace at all, confirming that call
site is fixed), and the SAME crash site is hit one call later, called
straight from `Hud::StateTransition()` this time - a SIXTEENTH native
crash:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000108c
Cause: null pointer dereference
#00 InputForwarderTouchDPad::SetAutoFireEnabled(bool)+4
#01 Hud::StateTransition(Hud::State)+672
```
`SetFireEnabled()` doesn't call `SetAutoFireEnabled()` once - it calls it
on two sibling forwarder fields back to back: `this->[80]` (fixed by
fix #42) and, via a tail-call, `this->[92]`. On this device `[92]` holds
`0x1000` - a *different* small garbage value than `[80]`'s `1`. Two
different small values at neighboring fields of the same object, in the
same run, point to genuinely uninitialized memory (both touch-D-pad
forwarders never get built on this gamepad-only setup) rather than one
fixed sentinel - so this fix guards on "the loaded value is at or below
one page (`0x1000`)", comfortably below any real heap pointer seen
anywhere in this entire crash log while covering both garbage values
actually observed.

Fixed as fix #43 (`ElfMassEffectTouchDPadForwarderSecondNullGuardPatcher`,
0.43-touchdpadforwarder2guard): this call site is a genuine tail-call
(`pop {r4,r5,r6,lr}; b SetAutoFireEnabled`, not `bl`), so the cave can't
just test-and-skip - the guard path also has to perform that same
pop/return itself to keep the stack balanced, which it does. Reuses
fixes #38-#42's code-cave technique in the same free `.rodata` block.
Verified via `arm-linux-gnueabi-as`/`ld` + kotlinc harness + `objdump`
(confirming the guard path's own pop/return is byte-correct) + whole-
file byte-diff (24 bytes differ: 4 at the call site, 20 of the cave's 24
bytes) + a combined re-verification with fixes #38-#42's own adjacent
trampolines confirming all six coexist with no interference (111 bytes
differ in total, matching each fix's own independently-verified count
exactly). **Not yet real-device tested.**

**Round 42: 0.44-onEventTouchToggleGuard - PROACTIVE fix (static
analysis, not a real-device crash log).** After fix #43 shipped the user
asked how many more test/fix cycles this could take
("mennyi ilyen lesz meg?"); told honestly the number can't be predicted,
they pushed for a broader scan instead of one-crash-per-test
("kitepem a maradek hajam ha meg 200 teszt lesz" - I'll pull out what's
left of my hair if there's 200 more tests). Rather than waiting for the
next real-device test, `InputSchemeBasic::Init(GameWorld*)` was
disassembled in full to understand fixes #42/#43's own root cause more
precisely: the two touch D-pad forwarder fields they guard
(`this->[80]`, `this->[92]`) are in fact constructed in `Init()`, but
only inside a block gated on a "touch capable" flag read once near the
top of the function (`Tier::GetInstance()`'s field+4, bit 1) - false on
this gamepad-only device, so those two fields are skipped and keep
whatever garbage the allocator left (`1` and `0x1000`, matching the real
crash logs exactly). The same sweep also checked `IsMovement()`'s and
`ResetPointer()`'s unguarded-looking reads of the `InputRegionBox`/
`InputForwarderGroup` fields, and the third touch D-pad forwarder field
at `this->[68]` - all three turned out to be safe, since
`InputSchemeBasic::UpdateRegions()` and `Init()` construct those fields
completely unconditionally, with no such gate.

Searching for every OTHER direct read of `this->[80]`/`this->[92]`
(not just fixes #42/#43's own call site) turned up
`InputSchemeBasic::OnEvent(im::Event const&)`, which writes through both
of these SAME still-empty fields completely unguarded, at `0x124360` and
`0x124374` (`this->[N]->[4] = someFlag`, toggling what looks like a
"sticky fire" setting alongside an `IsInCover()`/`UpdateRegions()` call -
consistent with a cover/aim input event). On this device that's a write
through address `0x5` or `0x1004` - a guaranteed SIGSEGV the moment that
particular event fires, which this user's playthrough simply hasn't
triggered yet on any build tested so far.

Fixed as fix #44 (`ElfMassEffectOnEventTouchDPadToggleNullGuardPatcher`,
0.44-onEventTouchToggleGuard): both call sites patch the exact same
4-byte instruction (`strb r3,[r6,#4]`) and, since it's byte-identical at
both sites, share a single cave - `cmp r6,#0x1000` / `bls skip` / `strb
r3,[r6,#4]` / `skip: bx lr` - reusing fixes #42/#43's own `<=0x1000`
threshold. Verified via `arm-linux-gnueabi-as`/`ld` + kotlinc harness +
`objdump` + whole-file byte-diff (21 bytes differ: 4+4 at the two call
sites, 13 of the cave's 16 bytes) + a combined re-verification with
fixes #38-#43's own adjacent trampolines confirming all seven coexist
with no interference (132 total bytes differ across all seven fixes
combined, exactly matching each fix's own independently-verified count).
**Shipped proactively, alongside fix #43's own still-pending
confirmation - not itself triggered by any crash log seen so far.**

**Round 43: 0.45-stateTransitionListenerGuard REAL-DEVICE TESTED - fix
#45.** 0.44 (fix #43 + the proactive fix #44) confirmed working - both
touch D-pad forwarder crash sites are gone - and a genuinely new,
EIGHTEENTH native crash surfaces one call further in, landing exactly
where fix #41's own guard resumes execution:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
Cause: null pointer dereference
#00 Hud::StateTransition(Hud::State)+720
#01 Hud::InitWorld(GameWorld*)+712
#02 LayerGameWorld::LoadLevel(LoadTransition*)+76
```
(`Hud::InitWorld(GameWorld*)+712` is `0x114cd0` - the exact address fix
#41's cave branches to on its guard path, confirming fix #41 keeps
working correctly; `InitWorld()` simply calls straight into
`StateTransition()` from there.) `StateTransition()` iterates a vector of
raw pointers (12-byte stride) and calls a virtual method through every
element with no null check - this device has a NULL entry in that
vector, and `r0`/`r3`/the fault address are all exactly `0` in the
register dump (a genuine NULL, not one of fixes #42-#44's small-sentinel
values).

Fixed as fix #45 (`ElfMassEffectHudStateTransitionListenerNullGuardPatcher`,
0.45-stateTransitionListenerGuard): the virtual call's return value is
never read afterwards, so a NULL element's call can simply be skipped -
the cave has two exits: a real element replicates the crashing load and
returns normally into the original inlined call; a NULL element branches
straight past the whole call to the loop's own post-call continuation,
leaving the loop's bookkeeping and every other element untouched. Reuses
fixes #38-#44's code-cave technique in the same free `.rodata` block
(eighth adjacent trampoline). Verified via `arm-linux-gnueabi-as`/`ld` +
kotlinc harness + `objdump` + whole-file byte-diff (17 bytes differ: 4 at
the call site, 13 of the cave's 16 bytes) + a combined re-verification
with fixes #38-#44's own adjacent trampolines confirming all eight
coexist with no interference (149 total bytes differ across all eight
fixes combined, exactly matching each fix's own independently-verified
count). **Real-device tested in 0.45 - confirmed applied, but the guard's
threshold turned out to be too narrow; see Round 44/45 below.**

**Round 44: 0.46-stateTransitionListenerGuardWidened REAL-DEVICE
TESTED - fix #45 revised (NINETEENTH crash, same site as the
eighteenth), but 0.46 itself shipped a regression - see Round 45.**
0.45's patch log confirmed fix #45 applied, but the very next test
crashed again, at a new address that pinpoints exactly what was wrong:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000021
#00 pc 00995994  libMassEffect.so
```
`0x995994` is fix #45's own cave, 8 bytes in - its `ldr r3,[r3]`
instruction. The exact-NULL check (`cmp r0,#0`) correctly let this
element through (`r0` was `0x21`, not `0`), but `0x21` is the same kind
of small-integer "never constructed" sentinel fixes #42-#44 already had
to guard against elsewhere in this input scheme (those saw `1` and
`0x1000`; this vector slot's sentinel happens to be `0x21`) - not a real
heap pointer, but not exactly zero either, so 0.45's narrower check
missed it.

0.46 widened the check to `cmp r0,#0x1000` (correct), but its `bls` was
mistakenly assembled with a *local* branch target (the cave's own
trailing `bx lr`, 12 bytes in) instead of 0.45's *external* `beq
0x114638` (straight to the loop's post-call continuation, past the whole
inlined virtual call). Both of 0.46's cave paths converged on the same
`bx lr`, which resumes the original call either way - so the widened
check executed but had zero effect on control flow. **This was a real
bug in the shipped 0.46 build**, only caught by the next real-device
test - see Round 45.

**Round 45: 0.47-stateTransitionListenerGuardFixed REAL-DEVICE TESTED -
fix #45 corrected (TWENTIETH crash, same site as the eighteenth and
nineteenth).** 0.46's patch log confirmed the (broken) fix applied, and
the next test crashed again - a new crash *type*, one instruction
further in, that pinpoints exactly what 0.46 got wrong:
```
Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x000001e1
#00 pc 00114634  libMassEffect.so  (Hud::StateTransition(Hud::State)+728)
```
`0x114634` is `ldr pc,[r3,#448]` - the virtual dispatch the guard was
supposed to prevent for this element - and `0x1e1 - 0x1C0 (448) = 0x21`,
the exact same sentinel element as 0.45/0.46's crash, reaching the exact
same eventual dereference because 0.46's branch never actually diverted
it (a SIGBUS instead of SIGSEGV here only because `r3+448` happened to
be misaligned for this sentinel value - incidental, not a different
bug).

Fixed by restoring 0.45's branch target (`beq 0x114638` -> `bls
0x114638`, i.e. only the condition-code nibble changes, `0x0A`->`0x9A`)
paired with 0.46's wider `cmp r0,#0x1000` - of the cave's 16 bytes, only
that one nibble differs from what 0.45 originally shipped, besides the 2
`cmp`-immediate bytes 0.46 already changed correctly. Verified via
`arm-linux-gnueabi-as`/`ld` + kotlinc harness + `objdump` (confirms the
branch resolves to `0x114638`, not a local cave offset this time) +
whole-file byte-diff (19 bytes differ from pristine - two more than
0.45's 17, only because the wider `cmp` and the branch's changed
condition nibble both happen to turn a couple of pristine-zero bytes
non-zero; same byte *positions*) + a combined re-verification with fixes
#38-#44's own adjacent trampolines confirming all eight still coexist
with no interference (151 total bytes differ across all eight fixes
combined - two more than 0.45's 149, same reason, not a correctness
issue).

**Round 46: 0.48-stateTransitionBeginGuard REAL-DEVICE TESTED - fix #46
(TWENTY-FIRST crash, a NEW site in the same function as fixes #45/45r,
one call earlier).** The user proposed a controlled A/B test to finally
settle a long-open question from this project's early asset-loading
investigation (Round 30/31): whether the game behaves differently with
vs. without the ~900MB of downloaded level content present on the
device. Two back-to-back real-device runs:
- **With** the content present: crashed at `Hud::StateTransition
  (Hud::State)+704` (`0x11461c`), fault addr exactly `0x0`.
- **Without** it: the first run crashed at the exact same address (down
  to the same PID/timestamp - the two captured logs overlapped the
  identical event), and a second run (relaunched under the same no-data
  condition) got further before hitting a *different*, harder problem
  inside fix #45's own cave - a wild non-null pointer (`0xff100e00`,
  `SEGV_ACCERR`) that the existing `<= 0x1000` threshold can't catch
  since it isn't small. That one is **not fixed by this patch** - see
  the note below.

The real finding: presence or absence of the downloaded content makes
**no difference** to the `0x11461c` crash. That settles the Round 30/31
mystery in the negative - it was never about missing external files
reaching this code path.

`0x11461c` is one call *earlier* than fix #45's own guard, in the same
loop: the size check that's supposed to skip an empty listener vector
compares `end - begin` against `11`, but when `begin` is `NULL` and
`end` holds some other non-null leftover value, that subtraction still
comes out looking like a plausible size, so the loop is entered anyway
and immediately dereferences the NULL `begin` pointer on its very first
read. Fixed as fix #46 (`ElfMassEffectHudStateTransitionBeginNullGuardPatcher`,
0.48-stateTransitionBeginGuard): reuses the function's own existing
"nothing to iterate" exit (`0x114668`) - if `begin` is implausibly small
(`<= 0x1000`, same threshold as fixes #42-#45), the whole loop is
skipped via that same exit instead of ever being entered. Ninth adjacent
trampoline in the same free `.rodata` block. This time the branch's
*resolved target address* was explicitly checked with `objdump` before
shipping (`0x114668`, not a local cave offset) - the exact class of
mistake that slipped into 0.46 - plus a combined re-verification
alongside fixes #38-#45's own trampolines confirming all nine coexist
with no interference.

**Still open: the `0xff100e00` wild-pointer crash inside fix #45's
cave.** Not every piece of garbage in this listener vector is a small,
near-zero sentinel the way fixes #42-#45 assumed - this one was a full,
essentially-random-looking 32-bit value, which no fixed threshold can
distinguish from a real pointer. It's possible fixing the container-
level bug above (fix #46) makes this moot in practice (if the listener
vector is empty, no element - garbage or otherwise - is ever read), but
that's not yet confirmed; if it recurs after 0.48, a different technique
(such as bounds-checking against the library's own loaded address range,
computed at runtime via the cave's own PC rather than a hardcoded
threshold) will be needed rather than another magic-number guard.

**Round 47: 0.49-abortSniperGuard REAL-DEVICE TESTED - fix #47
(TWENTY-SECOND crash, a genuinely NEW site).** 0.48 confirmed real
progress: the crash log's backtrace now shows a live virtual dispatch
through a real listener element - `Hud::StateTransition()` ->
`Playable::OnBioticSelectBegin()` -> `Playable::AbortSniper()` - not
another guard-machinery crash inside the loop itself. (The still-open
`0xff100e00` wild-pointer case from Round 46 did not recur in this run.)
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000022e
#00 Playable::AbortSniper()+80
#01 Playable::OnBioticSelectBegin()+12
#02 Hud::StateTransition(Hud::State)+1164
```
`Playable::AbortSniper()` reads an optional "current sniper weapon"
field and guards it against exact NULL only:
```
ldr   r0, [r4, #1192]   ; r0 = this->[0x4a8]
cmp   r0, #0
beq   <skip>
ldrb  r3, [r0, #525]    ; <-- CRASH: r0 is 0x21, not a real pointer
```
`r0` was `0x21` - the exact same small-sentinel value fix #45's
*original* crash used, now hit at a completely different call site.
`0x21 + 525 = 0x22e`, matching the fault address exactly.

Fixed as fix #47 (`ElfMassEffectAbortSniperNullGuardPatcher`,
0.49-abortSniperGuard): widened the existing check in place - `cmp
r0,#0` -> `cmp r0,#0x1000`, `beq` -> `bls`, same offset, same target,
same two 4-byte instructions, **no code cave needed this time**. Assembled
and linked at the real address, verified with `objdump` explicitly
checking the branch's resolved target (`0x1f73ac`, matching the
original exactly) plus a whole-file byte-diff (only 3 bytes differ: 2 in
the `cmp` immediate, 1 in the branch's condition nibble) and a combined
re-verification alongside fixes #38-#46 confirming zero interference.

**Round 48: 0.50-stateTransitionListenerWideRange REAL-DEVICE TESTED -
fix #45 widened again (TWENTY-THIRD crash, the still-open `0xff......`
case from Round 46 recurring, now confirmed real).** 0.49 made genuine
progress on a different crash site (fix #47), but this run hit the
exact spot flagged as "still open" back in Round 46 - a second,
independent occurrence:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xff0a0900
#00 pc 00995994  libMassEffect.so
r0  ff0a0900  r3  ff0a0900
```
Same crash site as fix #45's own cave, same shape as Round 46's
`0xff100e00` (also `SEGV_ACCERR`, also unmapped/inaccessible rather than
simply absent) - two independent occurrences of a `0xff......`-prefixed
value confirm this isn't a fluke. Every real object pointer visible in
this device's register dumps sits in the `0xd8000000`-`0xdf000000`
range; the `<= 0x1000` threshold (correct since 0.47) only ever excluded
values *near zero* and was structurally incapable of catching a large,
wild pointer like this one.

Fixed by widening the SAME check to a two-sided range: reject `<=
0x1000` as before, and now also reject `>= 0xF0000000` (comfortably
above every real pointer this project has seen, comfortably below both
observed `0xff......` values). Two comparisons don't fit in the original
16-byte cave, so it moved to a fresh 24-byte slot right after fix #46's
own cave - the callsite's `bl` target changes to match, and the old
16-byte slot is simply abandoned (stays at pristine zero, harmless).
Verified via the full toolchain pipeline, explicitly objdump-confirming
BOTH branches resolve to the correct external target (`0x114638`), plus
a combined re-verification alongside all nine other fixes in the same
free `.rodata` block confirming zero interference.

**Round 49: 0.51-bioticSelectCallGuard REAL-DEVICE TESTED - fix #48, a
brand new call site (TWENTY-FOURTH crash).** 0.50 confirmed all three of
0.49's guards held (fix #45's wide range, fix #46, fix #47 - none of
their crashes recurred), and got further into the same function before
hitting a genuinely new one:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x73617052
#00 pc 001f7300  Playable::AbortSniper()+8
#01 pc 001fc7b0  Playable::OnBioticSelectBegin()+12
#02 pc 001147e8  Hud::StateTransition(Hud::State)+1164
r0  73616c62  r4  73616c62
```
`0x73616c62` decodes as ASCII `"blas"` - not a wild pointer at all, but
the front of a string literal (almost certainly `"blast"`) sitting in
`.rodata`, handed to `OnBioticSelectBegin()` as if it were a `Playable*`.
Disassembly confirmed `AbortSniper()` receives `r0` completely unchanged
from `OnBioticSelectBegin()`'s own entry, so the corruption traces back to
the call into `OnBioticSelectBegin()` itself - a **different, plain
(non-virtual) call**, not the vtable dispatch fixes #45/#46 already guard:
```
ldr   r0, [r4, #4]
cmp   r0, #0
ldrne r0, [r0, #452]
bl    Playable::OnBioticSelectBegin()   ; unconditional, even when r0 stayed 0
```
Neither of fix #45's existing thresholds (`<= 0x1000` / `>= 0xF0000000`)
catches this mid-range value. Rather than add a third blacklist band, this
fix flips the logic around: every real heap object pointer seen anywhere
in this whole project's crash dumps has landed in the
`0xd8000000`-`0xdf000000` range, so a single whitelist floor (`>=
0xC0000000`, with headroom) accepts every genuine pointer seen so far and
rejects NULL, small sentinels, and this new string-literal case in one
shot. Since `OnBioticSelectBegin()`'s return value is discarded, the fix
skips the call outright when the check fails - a tail-call cave (`cmp
r0,#0xC0000000` / `blo skip` / `b OnBioticSelectBegin` / `skip: bx lr`)
right after fix #46's cave in the same shared `.rodata` block. Verified
via the full toolchain pipeline, explicitly objdump-confirming both branch
targets (`blo` -> the `bx lr` two instructions later, `b` -> the real
function address), plus a combined re-verification alongside all ten
other fixes in the same free `.rodata` block confirming zero interference
(198 total byte-diffs, up from 182 by exactly the expected 16).

**Round 50: 0.52-stateTransitionListenerVtableGuard REAL-DEVICE TESTED -
fix #45's 5th revision (TWENTY-FIFTH crash, same site as Rounds
43/44/45/48, new failure mode).** 0.51 (fix #48) confirmed real progress
on its own new call site, and this site (fix #45's own cave) stayed quiet
- until a crash with a register dump none of the first four rounds here
can explain:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xdd0aaa18
Cause: trying to execute non-executable memory.
r0  dce90160  r3  dcea8920
#00 pc 003aaa18  <anonymous:dcd00000>
#01 pc 00114634  libMassEffect.so (Hud::StateTransition(Hud::State)+728)
```
The element (`r0`) is `0xdce90160` - comfortably inside the
`0xd8000000`-`0xdf000000` band this project treats as "a real heap
pointer", so it passes every existing check cleanly. The crash is one
step later: `r3` (`element->vtable`, loaded by the cave's own `ldr
r3,[r3]`) is *also* `0xdc......` - but a genuine vtable for this library
lives at a fixed, low, in-library address (this library loads with zero
bias on this device, confirmed by every backtrace offset in this whole
project matching static `objdump` addresses exactly), nowhere near
`0xC0000000`. `0xdcea8920` is more of the same stale/uninitialized heap
data this project keeps finding - just sitting where a vtable pointer
should be. Dispatching through it loads yet more heap garbage
(`0xdd0aaa18`) and jumps to it as code, landing in non-executable heap
memory - `SEGV_ACCERR` rather than a plain null-deref.

Fixed by extending the same cave with one more check: after loading `r3`,
reject it too if `>= 0xC0000000` (looks heap-like rather than
library-like). The existing 24-byte cave had no room for an 8-byte
addition, and the only contiguous free space left (29 bytes after fix
#48's cave) was one byte short of the 32 needed. Rather than hunt for a
new free region, this fix chains two small trampolines with a plain `b`
(lr untouched throughout, so the eventual return address is unaffected):
the callsite's `bl` target is unchanged (`0x9959AC`) and now holds just
the two existing element checks (20 bytes) ending in an unconditional
branch to a second fragment at `0x9959D4` (right after fix #48's cave)
holding the vtable load, the new check, and the real return (16 bytes).
Verified via the full toolchain pipeline, explicitly objdump-confirming
all three branches' resolved targets, plus a combined re-verification
alongside all eleven other fixes confirming zero interference (208 total
byte-diffs, up from 198 by exactly the expected 10).

**Round 51: 0.53-stateTransitionListenerVtableRangeGuard REAL-DEVICE
TESTED - fix #45's 6th revision (TWENTY-SIXTH crash, same site again,
heuristic replaced with a structural check).** 0.52 real-device tested;
same PC as every round so far (`Hud::StateTransition()+728`), but a
register dump Round 5's heuristic doesn't catch:
```
signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x00dfaab1
r0  dfc85701  r3  00dfa8f1
```
The element (`r0`) again passes cleanly (inside the heap band). The
vtable pointer read through it (`r3 = 0x00dfa8f1`) is neither in the heap
band Round 5 checks for, nor a real vtable - it's past the end of this
library's own mapped image and misaligned (`SIGBUS` rather than
`SIGSEGV`). Round 5's `>= 0xC0000000` check was only ever a heuristic
for the one bad pattern seen at the time; there's no reason every future
garbage value shares it.

Rather than add a seventh one-off threshold, this round replaces the
heuristic with the structural answer: every real vtable in this binary
lives in one specific section, `.data.rel.ro` (`0xA372A0`-`0xA80D8C` per
the library's own section headers) - nowhere else in the whole address
space is a legitimate vtable pointer ever going to point. The check
becomes a two-sided range test against that section (widened slightly to
the nearest ARM-immediate-encodable values, `0xA30000`/`0xA90000`, since
the exact bounds aren't themselves encodable in a single `cmp`). This is
a strict superset of Round 5's check - it rejects both the new value and
the old one. Cave part 2 grew from 16 to 24 bytes in place (the free tail
had just enough room); the callsite and cave part 1 are unchanged since
Round 4. Verified via the full toolchain pipeline, explicitly
objdump-confirming both new branches resolve to `0x114638`, plus a
combined re-verification alongside all eleven other fixes (216 total
byte-diffs, up from 208 by exactly the expected 8).

**Round 52: 0.54-weaponOnAbortCallGuard REAL-DEVICE TESTED - fix #49, a
brand new call site (TWENTY-SEVENTH crash).** 0.53 got PAST the
StateTransition vtable-dispatch site entirely for the first time - no
crash there at all - and instead crashed one call further down the same
chain fix #48 already guards the entry to:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000259
Cause: null pointer dereference
r0  00000021
#00 pc 002f8814  libMassEffect.so (Weapon::OnAbort()+4)
#01 pc 001fc7c0  libMassEffect.so (Playable::OnBioticSelectBegin()+28)
```
`r0 = 0x21` - the same small "never constructed" sentinel fixes #45/#47
have hit before, this time in a THIRD call inside
`Playable::OnBioticSelectBegin()` (past `AbortSniper()`, which fix #47
already guards): `ldr r0,[r4,#1192]` (a "current weapon" field, not
always populated) / `bl Weapon::OnAbort()` - called unconditionally, and
`Weapon::OnAbort()` immediately dereferences its `this` with no null
check (`ldr r3,[r0,#568]`, matching the fault address exactly:
`0x21+0x238=0x259`).

Fixed identically to fix #48 - the return value is discarded, so the call
is skipped outright when the weapon pointer doesn't look like a real heap
object (`< 0xC0000000`). Reuses fix #45's now-abandoned Round 1-3 cave
slot (`0x99598C`, unused and still pristine since Round 4 relocated that
guard), keeping the shared `.rodata` block's real estate tight instead of
hunting for new free space. Verified via the full toolchain pipeline,
explicitly objdump-confirming both branch targets, plus a combined
re-verification alongside all twelve other fixes (232 total byte-diffs,
up from 216 by exactly the expected 16).

**Round 53: 0.55-bioticSelectVtableGuard REAL-DEVICE TESTED - fix #50,
a brand new call site (TWENTY-EIGHTH crash).** 0.54 got past
`Weapon::OnAbort()` cleanly and crashed one instruction further into the
same function:
```
Fatal signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x0000014e
r0  dba88014  r1  00000001  r2  00004001  r3  00000096
r4  dba88014
#00 pc 001fc7d0  libMassEffect.so (Playable::OnBioticSelectBegin()+44)
#01 pc 001147e8  libMassEffect.so (Hud::StateTransition(Hud::State)+1164)
```
Unlike every earlier crash in this function, `this` (`r4`) is a
perfectly valid heap pointer - it's `this`'s OWN vtable pointer, loaded
fresh into `r3` right before a virtual dispatch, that's garbage
(`r3=0x96`, `0x96+0xB8(184)=0x14E` matching the fault address exactly).
This is a type-check idiom: dispatch a virtual "get type name" call,
then `strcmp()` the returned name against a literal. That breaks the
"just skip the call" technique fixes #48/#49 used, because here the
call's return value is fed straight into `strcmp()` - skipping only the
call would pass stale/garbage data to `strcmp()` as a string pointer,
trading one crash for another.

Fixed by validating the vtable pointer itself (same structural
`.data.rel.ro`-bounds check as fix #45 Round 6, `~0xA30000-0xA90000`)
before the dispatch, and on failure skipping the ENTIRE type-check block
- both virtual dispatches and both `strcmp()` calls - straight to the
block's own natural fallback continuation, rather than just skipping one
call. The shared `.rodata` trampoline block used by every earlier fix
was down to 5 free bytes, not enough for this 28-byte cave; a systematic
scan of the whole `.rodata` section for zero-byte runs not claimed by
any symbol in the library's own symbol table found exactly one safe
28-byte-or-larger gap (`0x99CFBC`, right after the 3-byte string literal
`"ID3"`), cross-checked against `readelf -r` to confirm no relocation
points into it. Verified via the full toolchain pipeline, explicitly
objdump-confirming all three branch targets (`bl` to the new cave, and
both `b` targets out of it), plus a combined re-verification alongside
all thirteen other fixes (260 total byte-diffs, up from 232 by exactly
the expected 28).

**Round 54: 0.56-endTapToAimGuards - fix #51, a brand new call site
(TWENTY-NINTH crash), shipped, not yet real-device tested.** 0.55 got past
`OnBioticSelectBegin()`'s type-check block entirely and crashed in a
sibling function, `Playable::EndTapToAim(bool)`:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000025
r0  ddfc4884  r3  00000021
#00 pc 000a1de4  (eastl::shared_ptr<GameObject,...>::~shared_ptr()+12)
#01 pc 000a6660  (eastl::shared_ptr<GameObject,...>::reset()+196)
#02 pc 001fc630  (Playable::EndTapToAim(bool)+116)
```
`r3 = 0x21` - the same small "never constructed" sentinel this project
keeps finding, this time inside a `shared_ptr<GameObject>` MEMBER rather
than a raw pointer: `EndTapToAim()` calls `reset()` on its weapon
`shared_ptr` member unconditionally, and `reset()` itself unconditionally
releases the member's OLD value by dereferencing its control-block
pointer with no null check (this build's `~shared_ptr()` always assumes
a real, non-null control block - by design there's no null case to
guard against in normal operation). The member was never properly
constructed, leaving raw heap garbage (`0x21`) in that field.

Fixed with the same whitelist-floor technique as fixes #48/#49
(`>= 0xC0000000`), guarding the member's control-block pointer before
`reset()` is called on it. `.rodata` is essentially out of contiguous
free space after fix #50 (the only run big enough turned out to be a
genuine binary lookup table, not padding), so this fix introduces a new
technique: **3-way chained trampolines across small, verified-safe
gaps** (three separate 8-byte zero-runs, each confirmed - by inspecting
the actual bytes on both sides, not just "outside any symbol's declared
size" - to sit between two adjacent string literals, the same
alignment-padding pattern as every prior cave). Also uses a new
ARM trick to shrink each link: a conditional `bxlo lr` combines the
"is this invalid?" branch and the "return early" instruction into one,
letting a terminal 8-byte fragment hold both the invalid-path return
and the valid-path tail-call.

Shipped alongside a PROACTIVE fix (fix #52, no crash report yet):
`EndTapToAim()` also has its own independent, unconditional call to
`Weapon::OnAbort()` - the exact same call/field fix #49 already guards,
just at a second call site fix #49 never reached. Found by inspection
while disassembling `EndTapToAim()` for fix #51; fixed identically
(2-way chained trampoline, same technique). Both fixes' every branch
(2 callsite `bl`s, 3 inter-fragment `b`s, 2 `bxlo`/`b` terminal pairs)
objdump-confirmed individually, plus a combined re-verification
alongside all fifteen fixes (301 total byte-diffs).

**Round 55: 0.57-subtitleWidgetGuard - fix #53, a brand new crash chain
(THIRTIETH crash), shipped, not yet real-device tested.** 0.56 got past
both `EndTapToAim()` guards cleanly and crashed earlier in the same
level-load sequence, in a call chain that has nothing to do with the
`Hud::StateTransition()`/`Playable` saga fixes #45-#52 cover:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
r1  00000000
#00 pc 00302120  (UIWidget::GetWidget<Subtitles>(Symbol const&)+12)
#01 pc 00302284  (GameWorld::GetSubtitleWidget()+52)
#02 pc 00316d04  (LayerGameWorld::LoadLevel(LoadTransition*)+88)
```
A plain NULL dereference (fault addr exactly `0x0`) - `GetSubtitleWidget
()` reads the UI system's root-widget pointer from a Hud field and
passes it, unchecked, into `GetWidget<Subtitles>()`, which immediately
dereferences it to make a virtual call. That field simply isn't built
yet at this point in level loading.

Fixed with the same whitelist-floor technique as fixes #48/#49/#51/#52,
guarding this one call site; a 2-way chained trampoline again (same
`.rodata` space situation as Round 54). Every branch objdump-confirmed,
plus a combined re-verification alongside all seventeen other fixes
(318 total byte-diffs).

**Round 56: 0.58-externalLogDir - not a game fix, an app infrastructure
change.** While testing 0.57 via BlueStacks (an Android emulator on PC,
used as a fast go/no-go smoke test before copying a build to the real
tablet - see below), the in-app "save log to Downloads" button failed
there ("Could not save the log to Downloads"). That button uses Android's
MediaStore API, which works fine on the real tablet but is incomplete/
unreliable on at least this BlueStacks setup - not an app bug. Trying to
pull the log file directly via `adb` instead ran into the same kind of
emulator-specific unreliability from a different angle: the file lived
in the app's *internal* cache dir, which needs either `adb root` or
`run-as` to read from outside the app, and neither worked reliably on
this BlueStacks instance (`run-as` failed with `setgid(AID_PACKAGE_INFO)
failed: Operation not permitted`; `adb root` reported success but left
the shell running as `shell`, not `root`).

Fixed at the source: `Logger.kt` now writes the log file to the app's
*external* app-specific directory (`getExternalFilesDir("logs")`, i.e.
`/storage/emulated/0/Android/data/com.apkcompatpatcher.android/files/logs/`)
instead of the internal cache dir. That path needs no runtime permission
(same as before - it's still scoped to this app) but, unlike internal
storage, it's reachable with a plain `adb pull` using the ordinary `shell`
adb user - no root, no `run-as`, on any device or emulator. Falls back to
the old internal-cache-dir behavior only if external storage is ever
unavailable.

**Round 57: 0.59-endTapToAimTouchFlagGuard - fix #54, a THIRD unguarded call site inside the
SAME `Playable::EndTapToAim(bool)` fixes #51/#52 already guard.** Found in the 0.57 test after a
BlueStacks smoke-test pass (see below) confirmed the build got past its EA/loading sequence and
into actual gameplay rendering (the first time this project ever saw in-game visuals), then the
real tablet test crashed a few instructions past fix #51/#52's call sites, still inside the same
function:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xff000082
#00 pc 001fc648  (Playable::EndTapToAim(bool)+140)
#01 pc 001147e8  (Hud::StateTransition(Hud::State)+1164)
#02 pc 00114cd0  (Hud::InitWorld(GameWorld*)+712)
```
`0xff000082 - 130 (0x82) = 0xff000000` - this project's recurring "0xff......-prefixed wild
pointer" pattern (first seen in fix #45 Round 4), from an optional touch-input-state field that
gets an unconditional flag-byte write (`strb r2,[r3,#130]`) with no validity check. Notably, the
plain `< 0xC0000000` whitelist floor most fixes here use does NOT catch this value, since
`0xff000000` is numerically above that floor - fixed instead by reusing the exact two-sided range
(`<= 0x1000` OR `>= 0xF0000000`) fix #45 Round 4 already validated for this same wild-pointer
pattern.

No single free `.rodata` region anywhere in the library was big enough for a contiguous cave (a
fresh full-library scan found one promising 66-byte run, `0x9dfd0e`, that turned out to be the
unused tail of `EA::Json::JsonInternal::gCharStateMap`'s own declared size - a real data table,
not padding, rejected the same way `0x99e252` was during fix #45 Round 3) - built as a 7-fragment
chained trampoline across seven 8-byte gaps instead, using the fact that ARM condition flags
survive an unconditional `b` so a `cmp` and the conditional branch that consumes it don't need to
be in the same or adjacent fragment. Every one of the eight branches objdump-confirmed
individually, plus a combined re-verification alongside all seventeen other fixes (359 total
byte-diffs, exactly +41 over Round 55's 318 as expected). Also verified with this project's new
`qemu-arm` micro-harness (see "Verifying a fix without a real device" below): both the exact real
crash register value (`r3 = 0xff000000`) and a synthetic valid heap pointer (`r3 = 0xd8000010`,
with the guarded write actually landing in mapped memory) resolve to the correct outcome. Shipped,
not yet real-device tested.

**Round 58: 0.60-endTapToAimVtableGuard - fix #55, the SAME "two type-check virtual dispatches
through one vtable pointer" idiom fix #50 already fixed once, found a second time in a different
function.** Found in the 0.59 test - fixes #51/#52/#54's call sites inside
`Playable::EndTapToAim(bool)` all held, and the game crashed a few instructions later, still in
the same function:
```
signal 7 (SIGBUS), code 1 (BUS_ADRALN), fault addr 0x000000de
#00 pc 001fc668  (Playable::EndTapToAim(bool)+172)
#01 pc 001147e8  (Hud::StateTransition(Hud::State)+1164)
```
`0xde - 0xb8 (184) = 0x26` - a small, unaligned garbage value sitting where `this`'s own vtable
pointer should be, dispatched through with no validity check (`ldr r3,[r4]` / ... /
`ldr pc,[r3,#184]`, then a second dispatch through the same pointer after a `strcmp()`) - `this`
itself is valid (every other field access on it in this function already succeeded), only the
vtable pointer field reads back as uninitialized garbage here. Fixed by reusing fix #50's exact
`.data.rel.ro` bounds check (`~0xA30000-0xA90000`) on this second, independent call site: if
invalid, skip both dispatches and the `strcmp()` between them together, straight to the function's
epilogue - the same "skip the whole type-check block" strategy fix #50 already validated. Another
7-fragment chained trampoline (no contiguous free space left anywhere). Every branch
objdump-confirmed, combined re-verification alongside all eighteen other fixes (399 byte-diffs,
+40 as expected), and harness-verified: both the exact crash value (`r3 = 0x26`) and a valid
in-bounds value (`r3 = 0xA50000`) land correctly, with `lr` resolving to exactly `0x1fc66c` in
both cases (confirming the arithmetic `lr` fix-up, same signature fix #50's own harness test
produced). **Real-device confirmed by the 0.60 test: the crash moved out of `EndTapToAim()`
entirely, into a brand new function - fix #55 held.**

**Round 59: 0.61-loadLevelSubtitleWidgetGuard - fix #56, a brand new crash in a brand new
function, on the very first level load.** Found in the 0.60 test:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3e4cccd1
    r2 d8da9430  r3 3e4ccccd
#00 pc 00316d28  (LayerGameWorld::LoadLevel(LoadTransition*)+124)
#01 pc 0012d8dc  (LoadTransitionTask::Run()+564)
```
`fault addr = r3 + 4` - a plain `ldr r2,[r3,#4]` where `r3` itself is garbage, not a small offset
like the vtable-guard fixes. Root cause: `Hud::InitWorld()` assigning its subtitle widget
`eastl::shared_ptr` for the first time on level load - `GetSubtitleWidget()`'s underlying member
isn't fully constructed yet, so the "ctrl/refcount" half of the shared_ptr it returns comes back
as leftover garbage (`0x3e4ccccd`) even though the "ptr" half looks like a perfectly plausible heap
pointer (`r2 = 0xd8da9430`) - only one field of an otherwise-valid-looking struct is still
uninitialized, the same pattern this whole project keeps finding, this time hitting a shared_ptr's
control block instead of a vtable pointer. Fixed by reusing this project's proven `>=0xC0000000`
heap-floor check (already used by fixes #51/#52) on the reload of the ctrl pointer: if invalid,
skip the entire increment/swap/destruct block straight to the function's own "nothing to do" merge
point - exactly where the existing self-assignment (`beq`) shortcut already jumps to. A 3-fragment
chained trampoline (fresh free `.rodata` gaps, re-scanned against all fourteen of fixes #54/#55's
claimed fragments). Every branch objdump-confirmed, combined re-verification alongside all twenty
other fixes (424 byte-diffs, +25 as expected, no region collisions), and harness-verified: both the
exact crash value (`r3 = 0x3e4ccccd`, injected via the harness's new `MEM` directive) and a valid
heap-range value (`r3 = 0xd8da9430`, the real "ptr" half from this very crash dump) resolve to the
correct outcome. **Real-device confirmed by the 0.61 test - but the SAME fault address showed up
again a few instructions later in the same function (see Round 60 immediately below).**

**Round 60: 0.62-loadLevelSubtitleDtorGuard - fix #57, the SAME garbage pointer fix #56 already
guards once, hitting a SECOND, unconditional use site in the same function.** Found in the 0.61
test:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x3e4cccd1
#00 pc 000b2930  (eastl::shared_ptr<...Subtitles...>::~shared_ptr()+12)
#01 pc 00316d64  (LayerGameWorld::LoadLevel(LoadTransition*)+184)
```
Identical fault address to fix #56's crash - fix #56's guard works (the refcount-increment crash
at `0x316d28` no longer happens), but the same garbage ctrl pointer, still sitting untouched at
`[sp,#16]`, gets read a second time later in the same function: when the local temporary
`shared_ptr` `GetSubtitleWidget()` originally returned goes out of scope, its destructor runs
unconditionally (`mov r0,r5` / `bl ~shared_ptr()`) on every path through the function - including
the very self-assignment shortcut fix #56's own guard lands on - and that destructor dereferences
the same invalid pointer internally. Fixed with the exact same `>=0xC0000000` heap-floor check,
applied a second time right before this destructor call: if invalid, skip the call entirely (there
was never a real refcounted allocation to free) straight to the function's stack teardown; if
valid, relocate the original `mov r0,r5` and resume the untouched original `bl`. A 4-fragment
chained trampoline (fresh free `.rodata` gaps, re-scanned against fix #56's three claimed
fragments too). Every branch objdump-confirmed, combined re-verification alongside all twenty-one
other fixes (453 byte-diffs, +29 as expected, no region collisions), and harness-verified: both the
exact crash value (`r3 = 0x3e4ccccd`) and a valid heap-range value (`r3 = 0xd8da9430`, with `r0`
confirmed correctly set to `r5`) resolve to the correct outcome. **Real-device confirmed by the
0.62 test - the crash moved past level loading entirely, into actual rendering (see Round 61
immediately below).**

**Round 61: 0.63-getSunFlareNullGuard - fix #58, a brand new crash, past level loading, during
rendering.** Found in the 0.62 test:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000a4
Cause: null pointer dereference
#00 pc 002fc20c  (Environment::GetSunFlare(int, Hud const*)+4)
#01 pc 0000004c  <unknown>
```
A plain NULL-plus-small-offset dereference of the function's OWN second argument (`Hud const*`),
right on its second instruction (`ldrb ip,[r1,#164]`), with no NULL check anywhere before it - the
caller is an unresolvable `<unknown>` frame (likely a render-callback dispatch), and whatever calls
this sometimes does so before a `Hud` exists yet. This huge function has exactly ONE shared
epilogue in its entire body, reached by many different internal early-out conditions, always
returning the input `int` (flare index) completely unchanged - a "nothing to do" convention the
function's own logic already relies on repeatedly elsewhere. Fixed by guarding the parameter
before dereferencing it: since the guard sits before the stack is even adjusted or `r0` saved
anywhere, the equivalent bail-out is simply popping the same registers the prologue just pushed
and returning immediately with `r0` untouched - byte-for-byte the same observable result as this
function's own other "return unchanged" paths. A 4-fragment chained trampoline (fresh free
`.rodata` gaps, re-scanned against all eighteen of fixes #54-#57's claimed fragments). Every branch
objdump-confirmed, combined re-verification alongside all twenty-two other fixes (479 byte-diffs,
+26 as expected, no region collisions), and harness-verified with a synthetic call frame (a fake
stack + marker return address, since the guard runs before the function's own register push):
both a NULL `Hud*` (the exact real crash condition) and a valid heap-range `Hud*`
(`r1 = 0xd8da9430`) resolve to the correct outcome. **Real-device confirmed by the 0.63 test - the
crash moved to a totally different, unrelated site (see Round 62 immediately below), one that
turned out to be an old friend.**

**Round 62: 0.64-stateTransitionFuncPtrGuard - fix #45's SEVENTH round, at its own long-running
crash site.** Found in the 0.63 test, with a backtrace that couldn't even resolve a symbol:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00065b3b
r0 00065b3b  r3 00065b3b
1 total frames
#00 pc 009959d4  libMassEffect.so
```
No frame #01, no function name - `pc` itself had jumped into `.rodata` (`0x9959d4`, coincidentally
close to this very patcher's own cave addresses but unrelated to them). Working backward from the
raw device `pc`/`lr` pair confirmed the actual caller: `lr` translates to `0x114630` - immediately
after fix #45 Round 6's own cave returns, at `Hud::StateTransition()`'s long-running vtable-dispatch
crash site (six previous rounds, fully chronicled in
`ElfMassEffectHudStateTransitionListenerNullGuardPatcher`'s own doc comment). Round 6 validates
that the element's vtable pointer falls inside `.data.rel.ro` - the section real vtables live in -
but says nothing about what's stored INSIDE that vtable. A real, correctly-located vtable can still
have a stale/uninitialized value in the one slot this loop calls through (offset `0x1c0`), and the
original code jumps to whatever's there with zero validation. Round 7 adds a third check: after
loading the vtable slot's function pointer, verify it falls inside this library's own executable
region (`.plt`+`.text`, `~0x97f78-0x924684`, widened to `0x90000`/`0x930000`) before ever letting
the original code jump through it. Round 6's cave had no room left, so this chains five more
8-byte fragments off its own end (fresh free `.rodata` gaps) - the same "1 instruction + hop"
technique used throughout this project - with the cave's own trailing `bx lr` (unchanged size)
repurposed into the branch that starts the chain. Every branch objdump-confirmed, combined
re-verification alongside all twenty-two other fixes (510 byte-diffs, +31 as expected, no region
collisions), and harness-verified entering at the real callsite with a fully synthetic
element/vtable/slot chain in memory: both the exact real crash's bogus slot value (`0x9959d4`) and
a valid in-`.text` slot value (`0x114638`) resolve to the correct outcome - the loop's post-call
continuation for the invalid case, and a confirmed real dispatch for the valid case. **Real-device
confirmed by the 0.64 test - no recurrence of this site; the crash moved to a completely different,
brand-new area of the game (see Round 63 immediately below).**

**Round 63: 0.65-validateTextureImageNullGuard - fix #60, a brand new crash, deep in actual texture
upload/rendering - never reached before.** Found in the 0.64 test:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000da
#00 pc 003f897c  (m3g::Image2D::IsInvalid() const)
#01 pc 004239b0  (m3g::OpenGLESRenderer::ValidateTexture(eastl::intrusive_ptr<m3g::Texture2D>
                   const&, bool)+44)
#02 pc ...        (LoadTransitionTask::UploadTextures(...)+156)
```
`IsInvalid()`'s very first instruction is `ldr r3, [r0, #68]` - fault addr `0xda` minus the field
offset `0x44` is `0x96`, this project's familiar small-garbage/sentinel `this` pointer, not a real
NULL. `ValidateTexture()` calls `Texture2D::GetImage()` TWICE on the same texture: the FIRST result
is properly null-checked (`cmp r0,#0` / `beq 0x423a40`, the function's own "texture invalid" bail
target), but the SECOND, entirely redundant call's result is fed straight into `IsInvalid()` with no
check at all. The fix gates that second result with the same `<= 0x1000` bounds check used
throughout this project, before the original `bl Image2D::IsInvalid` even runs: a bad pointer bails
to the function's own existing `0x423a40` target (same convention as fix #58 and everything before
it), a good one falls through to a 3-fragment cave that makes the real `IsInvalid()` call itself
(via a nested `bl`, safe because this cave resumes with a plain `b`, never `bx lr`) and resumes the
original flow immediately afterward with the real return value in `r0`, unchanged from stock
behavior. Every branch objdump-confirmed, combined re-verification alongside all twenty-two other
fixes (533 byte-diffs, +23 as expected, no region collisions), and harness-verified entering at the
real callsite: both the exact real crash value (`r0 = 0x96`) and a valid heap-range pointer
(`r0 = 0xd8da9430`, with synthetic `Image2D` fields so the real `IsInvalid()` body runs to
completion) resolve to the correct outcome - the function's own "texture invalid" bail for the
invalid case, and a confirmed resume right after the callsite with a real `IsInvalid()` return value
for the valid case. **The 0.66 test showed the guard's floor was too low - see Round 64 immediately
below.**

**Round 64: 0.66-validateTextureImageFloorGuard - fix #60's SECOND round, same call site, wider
floor.** The 0.65 test's crash log showed `IsInvalid()` faulting again, still reached through this
very cave (`#01 pc 00953c10` - proof the Round 1 guard let a bad pointer through, not that it did
nothing):
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000307ba
#00 pc 003f897c  (m3g::Image2D::IsInvalid() const)
#01 pc 00953c10  libMassEffect.so
```
Fault addr `0x307ba` minus the field offset `0x44` is `r0 = 0x30776` - comfortably above Round 1's
`0x1000` sentinel-only floor (so it sailed through unguarded), but nowhere near a real heap pointer
either (every real object this project has seen on this device is `>= 0xC0000000`). A mid-range
garbage value, not a low sentinel - exactly the category of value every OTHER guard in this project
already screens for with the shared `< 0xC0000000` real-heap-floor check (fixes #49, #51, #53, #56,
#57 and more). Round 2 simply widens F1/F2 to that same, already-proven floor - `cmp r0, #0xC0000000`
/ `blo` instead of `cmp r0, #0x1000` / `bls` - same two instructions, same 8-byte cave slots, only
the threshold and branch condition change; the callsite and the terminal fragment (F3, the real
`IsInvalid()` call) are untouched. Every branch objdump-confirmed, combined re-verification alongside
all twenty-two other fixes (533 byte-diffs, unchanged from Round 1 as expected since only the
threshold changed, no region collisions), and harness-verified entering at the real callsite: both
the exact real Round 2 crash value (`r0 = 0x30776`) and a valid heap-range pointer (`r0 =
0xd8da9430`) resolve to the correct outcome - the function's own "texture invalid" bail for the
invalid case, and a confirmed resume right after the callsite with a real `IsInvalid()` return value
for the valid case. **Real-device confirmed by the 0.66 test - `IsInvalid()` no longer faults at all;
the crash moved one level deeper in the same texture-upload subsystem (see Round 65 immediately
below).**

**Round 65: 0.67-uploadTexturesRefcountGuard - fix #62, one level deeper in the same texture-upload
subsystem, a different failure mode entirely.** The 0.66 test's crash log showed a NEW crash, with a
signal code this project hadn't seen before:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xe5845014
    r0  e12e3158  r1  e12e3150  r2  4b152351  r3  e5845010
#00 pc 0010c7c0  (eastl::shared_ptr<im::TextureImage,...>::~shared_ptr()+24)
#01 pc 0010c800  (eastl::shared_ptr<im::TextureImage,...>::~shared_ptr()+88)
#02 pc 0012d210  (LoadTransitionTask::UploadTextures(...)+164)
```
`SEGV_ACCERR`, not the usual `SEGV_MAPERR` - the faulting address IS mapped, just not writable (the
crashing instruction, `str r2,[r3,#4]`, is writing a decremented refcount back through a
control-block pointer, `r3 = 0xe5845010`, that happens to land on some other, read-only mapped page).
Frame #01 is this same shared_ptr destructor calling itself recursively for an embedded shared_ptr
member of the `TextureImage` payload - the crash is in that inner call, meaning this `TextureImage`'s
embedded member was never properly initialized before being destructed. Crucially, `r3` here is
numerically ABOVE this project's usual `0xC0000000` real-heap floor, so the pointer-magnitude guard
technique used everywhere else wouldn't have caught it. Instead, the fix validates the thing actually
being trusted: the refcount VALUE itself. `r2 = 0x4b152351` in the register dump is already
POST-decrement, so the value read from `[r3,#4]` was `0x4b152352` - nowhere close to a plausible
strong-reference count (always a tiny positive integer for a real object). The callsite's `sub
r2,r2,#1` becomes a `bl` into a 4-fragment cave (fresh `.rodata` gaps, re-scanned against all prior
fixes) that computes the decrement into a scratch register first, checks it falls in `[0, 0x10000)`
via a single unsigned comparison (which, as a bonus, also catches the destructor's own latent `r2==0`
underflow-to-`0xFFFFFFFF` case), and only commits the real decrement and resumes original flow if
it's plausible - otherwise bailing to the function's own existing "nothing more to do" epilogue,
exactly what it already does when a real object's refcount legitimately reaches zero with no weak
references left. Since this destructor is a template instantiation specific to `im::TextureImage`,
the change can't affect any other `shared_ptr<T>` used elsewhere in the game. Every branch
objdump-confirmed, combined re-verification alongside all twenty-three other fixes (563 byte-diffs,
+30 as expected, no region collisions), and harness-verified entering at the real callsite: both the
exact real crash's pre-decrement value (`r2 = 0x4b152352`) and a plausible real refcount (`r2 = 2`)
resolve to the correct outcome - the function's own "nothing more to do" bail for the invalid case,
and a confirmed resume with the correctly decremented value (`r2 = 1`) for the valid case. **The 0.67
test's crash log showed no recurrence of this refcount-write crash - the next crash was a completely
different, unrelated one (a symbol-less, one-frame native crash inside RapidXML's whitespace lookup
table, left unresolved pending a second data point), which is itself weak evidence the guard held -
and, separately, a brand new crash during level LOADING itself (see Round 66 immediately below).**

**Round 66: 0.68-musicStartLevelNullGuard - fix #63, a crash that fires automatically during level
loading, before the player does anything at all.** The 0.67 test's crash log showed:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000000b4
Cause: null pointer dereference
    r0  ddf10cd4  r1  ddf2a16c  r2  000000b0  r3  00000000
#00 pc 0026dfa0  (MusicManager::StartLevel(eastl::basic_string<char,...> const&)+116)
#01 pc 00315cb8  (LayerGameWorld::OnEvent(im::Event const&)+1220)
#02 pc 0012cff4  (LoadTransition::UpdateFromGameThread(int)+992)
```
Unlike most of this project's earlier finds, `MusicManager::StartLevel` is a real, non-stripped
symbol - its mangled name resolves directly in the crash log, so no manual reverse-engineering of an
anonymous function was needed this time. Disassembly showed `LevelIndexData::GetLevel(levelName)`
returning null (the requested level name wasn't found in the level index) and `StartLevel()` then
unconditionally dereferencing that result to read the level's associated music-track name at offsets
`0xB0`/`0xB4` of the (non-existent) record - `r3 = 0` in the register dump, so the first read
(`ldr r2,[r3,#0xB4]`) faults at address `0xB4`, exactly matching `fault addr 0x000000b4`. This runs
as part of the game's own automatic music-kickoff on every level load, which is why it can fire
before the player has done anything interactive - matching the report that the game "didn't even
start." The callsite's `add r2,r3,#0xB0` becomes a `b` into a 3-fragment cave (fresh `.rodata` gaps,
re-scanned against all prior fixes) that checks `r3` for null first: if null, it bails straight to
the function's own existing epilogue (which simply falls into `MusicManager::ResumeLevelMusic()` -
the exact same path already taken today when a few-instructions-later self-assignment guard trips),
otherwise it re-runs the original relocated instruction and resumes normal flow unchanged. Every
branch objdump-confirmed, combined re-verification alongside all twenty-three other fixes (585
byte-diffs, +22 as expected, no region collisions), and harness-verified entering at the real
callsite: the exact real crash's value (`r3 = 0`) resolves to landing on the bail target without ever
reaching the original crashing instruction, and a plausible real level-record pointer (`r3 =
0xddf10cd4`, the actual non-null value seen in `r0`/`r4` in this same crash log) resolves to resuming
exactly at the original callsite's continuation with `r2` correctly computed. **The 0.68 test's
crash log showed no recurrence of the level-load music crash - instead, the long-running
`Hud::StateTransition()` saga resurfaced at its OWN cave (see Round 67 immediately below).**

**Round 67: 0.69-stateTransitionElementFloorGuard - fix #45's EIGHTH round, at its own cave this
time, not the original callsite.** The 0.68 test's crash log showed:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x80000035
    r0  80000035  r1  aaaa7f0f  r2  e2ec8014  r3  80000035
#00 pc 009959d4  libMassEffect.so
```
A single frame, no caller resolved - `0x9959d4` sits inside this library's own `.rodata`, so the
unwinder can't name a function there. Translating the raw `pc`/`lr` through this library's (zero)
load bias gives `lr = 0x114630` - exactly the return address `bl 0x9959AC` (cave part 1's own
callsite, in place since Round 4) pushes. This is a call into this patcher's OWN cave, not a wild
jump into RapidXML data as originally suspected. `r0`/`r3` (the element pointer) are both
`0x80000035`, a mid-range garbage value that Round 4's cave-part-1 check - `r0 <= 0x1000` OR
`r0 >= 0xF0000000` - simply doesn't cover, so it sailed through into cave part 2's `ldr r3,[r3]`,
which faults dereferencing it. The exact same fault addr and translated `lr` had already shown up
once before (the 0.67 test), confirming a deterministic bug at this call site rather than a one-off
race. The fix collapses cave part 1's two old checks into the single, already-proven-elsewhere
`< 0xC0000000` real-heap-floor check (used by every guard added from fix #48 onward) - same 20-byte
fragment size, the two freed instruction slots filled with `nop`. Cave parts 2 and 3 untouched.
Objdump-confirmed, combined re-verification alongside all twenty-three other fixes (581 byte-diffs,
no region collisions), and harness-verified entering at the real callsite: the exact real crash value
(`r0 = r3 = 0x80000035`) now resolves to the loop's post-call continuation without ever reaching cave
part 2, and a plausible real element pointer (`r0 = r3 = 0xddf10cd4`) resolves to landing exactly on
cave part 2 as before. **The 0.69 test's crash log showed the same call site faulting a THIRD
time (see Round 68 immediately below) - Round 8's fix was incomplete.**

**Round 68: 0.70-stateTransitionElementRangeGuard - fix #45's NINTH round, same cave, Round 8
dropped the ceiling.** The 0.69 test's crash log:
```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0xff0b0a00
    r0  ff0b0a00  r1  aaaa7db9  r2  e3b48014  r3  ff0b0a00
#00 pc 009959d4  libMassEffect.so
```
Same landing site, same translated `lr = 0x114630` as both prior occurrences - `0xff0b0a00` is
comfortably above Round 8's `0xC0000000` floor, so it sailed straight through. Round 8 had collapsed
Round 4's old two-sided check (`<= 0x1000` OR `>= 0xF0000000`) down to a one-sided floor check,
silently dropping the upper-bound protection that would have caught this value
(`0xff0b0a00 >= 0xF0000000`) along the way - a real regression, not just an incomplete fix. (`r1`'s
`0xaaaa`-prefixed value - a classic uninitialized-memory poison pattern - has now shown up in all
three crash logs at this site, confirming this vector slot reads genuinely uninitialized memory
rather than one specific corrupted pointer.) Round 9 restores a proper two-sided range check -
`0xC0000000 <= r0 < 0xF0000000` - matching the exact style cave part 2 and cave part 3 already use
elsewhere in this same file, now using all five of cave part 1's instruction slots productively (no
padding needed). Objdump-confirmed, combined re-verification alongside all twenty-three other fixes
(585 byte-diffs, no region collisions), and harness-verified: both real crash values (`0x80000035`
and `0xff0b0a00`) now resolve to the loop's post-call continuation without ever reaching cave part 2,
and a plausible real element pointer (`r0 = r3 = 0xddf10cd4`) still resolves to landing exactly on
cave part 2 as before. **The 0.70 test's crash log showed no recurrence of the StateTransition
crash - instead, a genuinely new crash surfaced, and one that doesn't fit this project's usual
"garbage pointer magnitude" pattern at all (see Round 69 immediately below).**

**Round 69: 0.71-validateTextureFirstGetImageGuard - fix #64, a dangling pointer that LOOKS
completely normal.** The 0.70 test's crash log:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0xe3a03130
    r0  e3a03000  r1  e8363150  r2  00000001  r3  e8959b90
#00 pc 00404348  (m3g::Texture2D::GetImage() const+0)
#01 pc 0042399c  (m3g::OpenGLESRenderer::ValidateTexture(...)+24)
```
Same function fix #60 already guards, but the FIRST `Texture2D::GetImage()` call this time, not the
second. `GetImage()` is a two-instruction accessor (`ldr r0,[r0,#0x130]` / `bx lr`) and faults on its
very first instruction - the `this` pointer itself (`0xe3a03000`) is what's unmapped, not anything it
reads. Every prior "garbage pointer" fix in this project has relied on the value's magnitude looking
obviously wrong; `0xe3a03000` doesn't - it sits squarely inside the normal `0xC0000000`-`0xF0000000`
heap range every valid pointer in this project's logs has occupied, so a pure floor/ceiling check
would wave it through. What IS unusual: it's exactly 4096-byte (page) aligned, a shape no live
`Texture2D` from this game's own small-object heap has reason to have - far more consistent with a
pointer into memory that's since been unmapped (a plain dangling-pointer/use-after-free) than with
any legitimately-live object seen elsewhere in this project. The fix rejects a page-aligned pointer
(via `lsls ip, r0, #20` testing whether the low 12 bits are all zero, using `ip` as scratch so `r2`/
`r6` - needed a few instructions later by the untouched original code - are never disturbed) in
addition to - not instead of - the usual floor/ceiling check, bailing to the function's own existing
"texture invalid" path either way. This is a lower-confidence, best-effort heuristic based on a
single occurrence, flagged as such to the user, since page-alignment can't be proven a universal
signature of "dangling" the way a magnitude check can be proven a signature of "uninitialized."
Objdump-confirmed (seven branch targets), combined re-verification alongside all twenty-four other
fixes (632 byte-diffs, no region collisions), and harness-verified: the exact real crash value
(page-aligned `0xe3a03000`) and an ordinary sub-floor garbage value (`0x30776`, fix #60's own earlier
crash value, kept as a regression check) both resolve to the bail, and a plausible non-page-aligned
real pointer (`0xddf10cd4`) resolves to resuming normally with `r2` untouched. Shipped, not yet
real-device tested.

**Round 70: 0.72-layerGameWorldOnDrawNullGuard - fix #66, a brand new crash found only in landscape
orientation.** With every fix through #65 in place, the game was reported to render actual gameplay
frames (CPU usage matched `surfaceflinger`'s, and a screenshot proved the display was genuinely
compositing, not frozen) but with a solid black screen - and only in **portrait** orientation did
the black screen NOT crash; switching to **landscape** crashed reliably, every time. To rule out
Android's own orientation-compat framework as the cause, the three `am compat` override flags
already applied to this package (`OVERRIDE_ANY_ORIENTATION`, `OVERRIDE_ANY_ORIENTATION_TO_USER`,
`FORCE_RESIZE_APP`) were disabled and the test repeated - the identical crash reproduced byte-for-
byte, confirming this is a genuine native engine bug, not a side effect of that compat setting. The
crash log:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000188
#00 pc 0031491c  (LayerGameWorld::OnDraw(eastl::intrusive_ptr<m3g::Renderer> const&)+244)
```
Disassembly around the fault site shows a short chain, `ldr r3,[r4,#24]` then `ldr r2,[r3,#0x1c4]`
then the crash itself, `ldr r2,[r2,#0x188]` - a camera/lens-flare-related sub-object read straight
out of the per-frame world/camera state, dereferenced with no NULL check, feeding a short run of
`vdiv.f32`/`vsub.f32`/`vcmpe.f32` aspect-ratio math a few instructions later. Whatever normally
populates this sub-object evidently isn't set up (or isn't recreated after an orientation change)
for this tablet's landscape 1920x1200 mode. The fix doesn't invent a new fallback: `OnDraw()` already
has one, a few instructions further down - its own `bmi 0x314c18` (taken when the aspect-ratio
comparison goes the other way) lands at code that loads a neutral `0.0` flare-intensity constant and
rejoins normal flow. The guard now sends the NULL case there too: callsite `0x31491c` becomes a
branch into a 3-fragment cave (cmp r2,#0 / a two-branch fragment bailing to `0x314c18` on NULL or
continuing otherwise / the relocated original `ldr` plus a branch back to `0x314920`), using three
fresh, individually-confirmed-free `.rodata` gaps cross-checked against every cave address every
prior fix in this project already claims. Objdump-confirmed (every branch target resolves to exactly
the intended address, and the relocated `ldr` reassembles to the exact original 4 bytes), combined
re-verification alongside all thirty-six other native fixes against the real, fully-patched
1.0.58 `libMassEffect.so` (25 byte-diffs total, zero region collisions - every other fix correctly
recognized its own bytes as already-applied and skipped, proving this fix's four target regions
belong to no prior fix), and harness-verified: the exact real crash condition (`r2 = 0`) takes the
bail path with `r2` left completely untouched (no dereference, no crash), and a valid pointer
performs the exact original dereference and produces the exact original result, unchanged from
stock. Shipped, not yet real-device tested.

**Round 71: 0.73-gameObjectGetNodeNullGuard - fix #67, one call earlier than any previous fix has
reached.** The 0.72 test (every fix through #66, including the landscape `OnDraw()` guard, already
in place) hit a brand new crash:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000028
    r0  00000000  r1  00000000  r2  00000000  r3  00000000
#00 pc 001de274  (GameObject::GetNode() const+4)
#01 pc 00102ee6  [anon:stack_and_tls:...]
```
`r0 == 0` (the `this` pointer itself) and `fault addr = 0x28` (40) - `GetNode()` dereferences its
OWN `this` pointer completely unconditionally, on its very second instruction (`ldr r4,[r0,#40]`),
with no NULL check anywhere before it. Like fix #58's `GetSunFlare()`, the caller is an
unresolvable/anonymous stack frame - some render or game-object-list callback dispatch this
project's static analysis can't trace - that sometimes invokes this on a NULL or not-yet-constructed
`GameObject`. `GetNode()` already treats "nothing to give back" as a normal, expected outcome
elsewhere in its own body (several internal checks already return NULL through its usual epilogue
when its `ActorData`/node fields aren't set), so a NULL `this` is just one more case of that. Because
the crash sits BEFORE the function's own stack-frame adjustment (`sub sp,sp,#12`) - only the initial
`push {r4,r5,lr}` has executed - the correct bail isn't the normal epilogue (which assumes that
adjustment happened) but a direct `pop {r4,r5,pc}`, unwinding exactly what already ran; since `r0`
is already `0` at the guard (that's the condition being tested), no explicit "set return value" step
is even needed. Callsite `0x1de274` becomes a branch into a 3-fragment cave plus a one-instruction
bail slot (cmp r0,#0 / a two-branch fragment bailing to the single `pop {r4,r5,pc}` on NULL or
continuing otherwise / the relocated original `ldr` plus a branch back to `0x1de278`), using four
fresh, individually-confirmed-free `.rodata` gaps cross-checked against every cave address every
prior fix - including Round 70's three - already claims. Objdump-confirmed (every branch resolves to
exactly the intended address, the relocated `ldr` reassembles to the exact original 4 bytes),
combined re-verification alongside all thirty-seven other native fixes against the real, fully-
patched 1.0.58 `libMassEffect.so` (51 byte-diffs total, zero region collisions), and harness-
verified with a real function-call simulation (the function's own actual `push {r4,r5,lr}` prologue,
then into the guard exactly where the real callsite sits): the exact real crash condition (`r0 = 0`)
takes the bail path and returns with `r0` still `0` - the same "no node" result callers already
handle - and a valid struct pointer performs the exact original dereference, returning the exact
original value read from `[this+40]`, unchanged from stock. Shipped, not yet real-device tested.

**Round 72: 0.74-gameWorldOnRenderGroupNullGuard - fix #68, one call further UP the same chain fix
#67 just made safe.** The 0.73 test hit a brand new crash - this time with a full, symbolized
backtrace three frames deep into the same per-frame render call chain Round 70 already guards one
crash in:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000134
    r0  00000000  r1  00000000  r2  00000000  r3  007951b8
#00 pc 0030374c  (GameWorld::OnRender(...)+168)
#01 pc 002ea320  (Composer::OnDraw(...)+2384)
#02 pc 00314a1c  (LayerGameWorld::OnDraw(...)+500)
```
`GameWorld::OnRender()` calls `GameObject::GetGroup()` (which internally calls `GetNode()` - fix
#67's target - and already null-checks it correctly, returning NULL rather than crashing when
`GetNode()` does), then immediately dereferences that result completely unconditionally one
instruction later (`ldrb r8,[r0,#308]`) with no check of its own. Fix #67 made a group-less
`GameObject` survivable one level down; this call site was the one place that forgot to check for
the NULL it can legitimately produce. The loaded byte is used two instructions later purely as a
boolean gate (`cmp r8,#0` / `ldrbne r8,[r0,#309]` - a second byte, through the same pointer, only
read when the first was non-zero), so defaulting `r8 = 0` on the NULL path isn't just a safe
placeholder - it also makes that very next conditional read skip its own use of the same NULL
pointer, with no second guard needed anywhere. Callsite `0x30374c` becomes a branch into a
3-fragment cave plus a "default to false" bail fragment, using four fresh, individually-confirmed-
free `.rodata` gaps cross-checked against every cave address every prior fix - including Rounds 70
and 71's - already claims. Objdump-confirmed (every branch resolves to exactly the intended
address, the relocated `ldrb` reassembles to the exact original 4 bytes), combined re-verification
alongside all thirty-eight other native fixes against the real, fully-patched 1.0.58
`libMassEffect.so` (78 byte-diffs total, zero region collisions), and harness-verified: the exact
real crash condition (`r0 = 0`) takes the bail path and leaves `r8 = 0` with no dereference, and a
valid pointer performs the exact original dereference, returning the exact original byte value,
unchanged from stock. Shipped, not yet real-device tested.

**Round 73: 0.75-allowIndicatorsNullGuard - fix #69, a different branch of the same per-frame HUD
call chain.** The 0.74 test hit a brand new crash - not deeper in the same call stack as Rounds
70-72, but a sibling branch of it:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000584
    r0  00000000  r1  e10d1740  r2  e10e6d20  r3  00000000
#00 pc 001f536c  (Playable::AllowIndicators() const+0)
#01 pc 00115d30  (Hud::DrawIndicators(im::SpriteGraphics&)+32)
#02 pc 001177a8  (Hud::OnDraw(...)+908)
#03 pc 00314ae0  (LayerGameWorld::OnDraw(...)+696)
```
`r0 == 0` (the `this` pointer itself), `fault addr = 0x584` (1412) - `AllowIndicators()`
dereferences its OWN `this` pointer completely unconditionally, on its VERY FIRST instruction. It's
a pure leaf accessor with no stack frame at all (`ldr r0,[r0,#1412]` / `rsbs r0,r0,#1` /
`movcc r0,#0` / `bx lr`) that returns `true` only when a per-instance suppression field is `0`, and
`Hud::DrawIndicators()` calls it on whatever `Playable` it has without checking first - the same
"player object not yet/no longer available" situation already behind several earlier
`Playable::`-prefixed fixes (#48-#57). Because the function has no frame to unwind, the bail is as
simple as the crash: return `false` directly (`mov r0,#0` / `bx lr`), the same outcome this
function already produces for the ordinary "currently suppressed" case. Callsite `0x1f536c` becomes
a branch into a 3-fragment cave plus that direct-return bail, using four fresh, individually-
confirmed-free `.rodata` gaps cross-checked against every cave address every prior fix - including
Rounds 70-72's - already claims. Objdump-confirmed (every branch resolves to exactly the intended
address, the relocated `ldr` reassembles to the exact original 4 bytes), combined re-verification
alongside all thirty-nine other native fixes against the real, fully-patched 1.0.58
`libMassEffect.so` (104 byte-diffs total, zero region collisions), and harness-verified across
three cases: the exact real crash condition (`r0 = 0`) takes the bail path and returns `false` with
no dereference; a valid object with its suppression field at `0` returns `true` (allowed); and one
with the field at `1` returns `false` (not allowed) - proving the guard changes nothing about the
function's real logic, only adds the missing NULL check. Shipped, not yet real-device tested.

**Round 74: 0.76-proactiveAccessorNullGuards - fixes #70-#74, five PROACTIVE guards, not
triggered by a real crash log.** After Rounds 70-73 turned out to be four crashes in a row in the
same per-frame render/HUD call chain, each one only found by testing the previous fix, the user
asked to cut down the number of remaining one-crash-at-a-time real-device test rounds if at all
possible. Agreed trade-off: statically scan the same `GameObject::Get*() const` accessor family
that fix #67 (`GetNode()`) and fix #69 (`Playable::AllowIndicators()`) already proved buggy - a
tiny leaf accessor that dereferences its own `this` pointer completely unconditionally, on its
very first instruction, with no NULL check anywhere - and proactively guard every OTHER accessor
sharing that exact shape, even without an individual crash log for each one. The honest cost,
stated to the user up front: some of these guards may turn out to be unnecessary (if that
accessor's `this` is in fact never null in practice for this game), in which case they're
permanent no-ops with zero behavioral effect - but if any of them IS reachable with a null
`this`, this round pre-empts what would otherwise have been yet another isolated real-device test
cycle.

A disassembly sweep of every small `GameObject::Get*() const` accessor in `libMassEffect.so`
turned up five leaf functions sharing this exact "unchecked `this`" shape:

```
_ZNK10GameObject19GetCameraControllerEv:   ldr r3,[r0,#12] / ldr r0,[r3,#52]  / bx lr
_ZNK10GameObject9GetPlayerEv:              ldr r3,[r0,#12] / ldr r0,[r3,#452] / bx lr
_ZNK10GameObject11GetM3GWorldEv:           ldr r3,[r0,#12] / ldr r0,[r3,#24]  / bx lr
_ZNK10GameObject16GetDynamicsWorldEv:      ldr r3,[r0,#12] / ldr r0,[r3,#4]   / bx lr
_ZNK10GameObject10GetGravityEv:            ldr r0,[r0,#88] / add r0,r0,#416  / bx lr
```

Each got its own patcher (`ElfMassEffectGetCameraControllerNullGuardPatcher`,
`ElfMassEffectGetPlayerNullGuardPatcher`, `ElfMassEffectGetM3GWorldNullGuardPatcher`,
`ElfMassEffectGetDynamicsWorldNullGuardPatcher`, `ElfMassEffectGetGravityNullGuardPatcher`) using
the exact same 3-fragment-cave-plus-bail shape as fix #69: the risky first instruction becomes a
`b` into a cave that checks `r0` (`this`), bails to `mov r0,#0 / bx lr` (returns NULL, the safe
default every other accessor's "object doesn't exist" path already uses) if invalid, or relocates
the original instruction and resumes the untouched original code one instruction later if valid.
Twenty fresh, individually-confirmed-free `.rodata` gaps (four per fix), all cross-checked against
every cave address every prior fix in this project - including Rounds 70-73's - already claims, so
there is no collision with any of the ~65 other fixes. Every one of the 20 branches
objdump-confirmed (resolves to exactly the intended address, each relocated instruction
reassembles to the exact original 4 bytes), combined re-verification alongside all forty-five other
native fixes against the real, fully-patched 1.0.58 `libMassEffect.so` (zero region collisions),
and - since there is no crash log to test against - harness-verified as the primary evidence this
round: both an invalid-`this` (`r0 = 0`) and a valid heap-range `this` case for all five fixes,
confirming each correctly bails to a null return on the invalid path and correctly falls through to
the exact original, untouched code on the valid path. Shipped, not yet real-device tested - and
unlike every other round in this project, some or all of these five may simply never trigger in
practice, which is the accepted trade-off for testing fewer isolated crashes one at a time.

**Round 75: 0.77-gameWorldOnUpdatePlayerFlagNullGuard - fix #75, the first crash ever seen on the
game's UPDATE (logic) tick rather than its render/HUD chain.** The 0.76 test (all five of Round
74's proactive guards correctly applying, none of them yet confirmed necessary) hit a brand new
crash on a different side of the engine entirely:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x000007fc
    r0  e1e64d30  r1  00000014  r2  000001f4  r3  00000000
#00 pc 0030da6c  (GameWorld::OnUpdate(int)+52)
#01 pc 0031cd00  (LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180)
#02 pc 00315e10  (LayerGameWorld::OnEvent(im::Event const&)+1564)
#03 pc 003d99c4  (im::LayerStack::OnEvent(im::Event const&)+124)
#04 pc 00329250  (Application::OnUpdate()+972)
```
`r3 == 0`, `fault addr = 0x7fc` (2044) - `GameWorld::OnUpdate()` reads its own player-`GameObject`
field (`this->player`, the same field fix #71's `GameObject::GetPlayer()` accessor reads, just
accessed directly here instead of through that function) and immediately reads a one-shot flag
byte off of it, completely unconditionally: the player hasn't spawned yet at this point in a
level's very first update tick. The exact same "player object not yet available" situation as
several earlier fixes, just a different accessor and a different side of the engine (logic tick,
not render). Because the checked register and the loaded register are the SAME register here
(`ldrb r3, [r3, #2044]` - `r3` is both base and destination), defaulting to "flag not set" needs no
explicit write at all: on the invalid path `r3` is already `0`, exactly the value the very next
`cmp r3,#0` already expects for "off" - so this fix needed only a 3-fragment cave (no separate bail
fragment, unlike every other guard in this project), using three fresh, individually-confirmed-free
`.rodata` gaps cross-checked against every cave address every prior fix - including all of Round
74's five - already claims. Objdump-confirmed (every branch resolves to exactly the intended
address, the relocated `ldrb` reassembles to the exact original 4 bytes), combined re-verification
alongside all fifty other native fixes against the real, fully-patched 1.0.58 `libMassEffect.so`
(278 byte-diffs total, zero region collisions), and harness-verified: the exact real crash
condition (`r3 = 0`) resumes immediately with `r3` still `0` and no dereference, and a valid
pointer performs the exact original dereference, returning the exact original byte value, unchanged
from stock. Shipped, not yet real-device tested.

**Round 76: 0.78-updateSoundLevelsPlayerVtableGuard - fix #76, the same never-yet-spawned player
`GameObject` pointer as fix #75, hit from a sibling function.** The 0.77 test (fix #75 confirmed
working - `GameWorld::OnUpdate()` no longer crashes on its own player-flag read) progressed to a new
crash one call frame away, in a function that runs right alongside `OnUpdate()`:
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
    r0  00000000  r1  00000000  r2  00000009  r3  00000000
#00 pc 00300b60  (GameWorld::UpdateSoundLevels(int)+76)
#01 pc 0030dac4  (GameWorld::OnUpdate(int)+140)
#02 pc 0031cd00  (LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180)
```
`r3 == 0`, fault addr `0x0` - `GameWorld::UpdateSoundLevels()` reads the exact same `this->player`
field fixes #71 and #75 already had to guard (`GameWorld+452`), but this time immediately performs a
vtable read through it (`ldr r3,[r3]`) to set up a virtual dispatch, with no NULL check anywhere:
```
300b54: ldr r3, [r4, #452]   ; this->player (same field as fix #71/#75)
300b5c: mov r0, r3
300b60: ldr r3, [r3]         ; <-- CRASH: player is NULL, vtable read
300b64: mov lr, pc           ; untouched by this fix
300b68: ldr pc, [r3, #0x178] ; untouched - tail-call through vtable slot 376
```
This fix guards only the one vtable-read instruction, and on the valid path branches straight back
into the function's own UNTOUCHED `mov lr, pc` instruction one address later - since that instruction
is never replaced, it computes its own return address correctly with no special handling needed, a
cleaner design than fix #50's manual `lr` arithmetic had to use for a similar situation. On the
invalid path, the guard sets `r0 = 0` (false) and skips straight past the entire virtual-dispatch
tail-call, matching how the caller already treats a "no sound-level change" result. Objdump-confirmed
(every branch resolves to exactly the intended address), combined re-verification alongside all
fifty-one other native fixes against the real, fully-patched 1.0.58 `libMassEffect.so` (zero
unexpected region collisions - only this fix's own four regions, at `0x300b60`/`0x9541c8`/
`0x9541d8`/`0x954268`/`0x954278`, differ from the pre-#66 baseline), and harness-verified: the exact
real crash condition (`r3 = 0`) resumes at `0x300b6c` with `r0 = 0` and no dereference, and a valid
player pointer resumes at the untouched `0x300b64` with the vtable pointer correctly loaded into
`r3`, proceeding exactly as designed. Shipped, not yet real-device tested.

**Round 77: 0.79-unstructuredFieldNullGuard - fix #77, a PROACTIVE fix found by reading
`v-atamanenko/masseffect-vita`'s loader source directly, not from a real-device crash log.** The user
pointed at that same repository a second time; its `loader/patch/deserialization_bug.c` reveals a
bug this project hasn't hit yet: three `im::serialization::Database` methods -
`GetUnstructuredObjectField(ObjectHeaderDefinition*, int, FieldDefinition**)` (`0x003B7C78`),
`AddUnstructuredObjectField(ObjectHeaderDefinition*, int, FieldType const&)` (`0x003BB158`), and
`RemoveUnstructuredObjectField(ObjectHeaderDefinition*, FieldDefinition*)` (`0x003B620C`) - all
dereference their `ObjectHeaderDefinition* param_1` unconditionally, the moment each function's
(multi-register) prologue finishes. Their own README documents the user-visible symptom this
prevents: "After you die or quit to menu and resume game, you may see the long loading screen...
This is a side effect of fixing another, game-breaking bug" - on Vita, recovering from this NULL
deref means a full relaunch of the game process (there's no clean way to unwind a native SIGSEGV
there), disguised behind an artificial loading screen. This project's own established "skip and
return a safe default" in-process technique needs no such workaround: `GetUnstructuredObjectField`
returns `0` ("field not found"), `AddUnstructuredObjectField` returns `NULL` ("field couldn't be
added"), and the `void` `RemoveUnstructuredObjectField` simply returns immediately (removing a field
that doesn't exist is already correctly a no-op). Because the guard sits at each function's true
entry point (before its own prologue even runs), the invalid path is a plain `bx lr` straight back to
the real caller's own return address - no stack to unwind, no `lr` arithmetic needed anywhere. All
three addresses were cross-checked against this project's own extracted 1.0.58 `libMassEffect.so`
symbol table before writing the patch (see [ElfMassEffectOnlineStubPatcher]'s doc comment for the
original SHA1 match that first confirmed this is byte-for-byte the same build the Vita port targets)
- every address lands exactly on the demangled C++ symbol the Vita loader's own comments say it
does. Objdump-confirmed (every branch resolves to exactly the intended target), combined
re-verification alongside all fifty-two other native fixes against the real, fully-patched 1.0.58
`libMassEffect.so` (zero unexpected region collisions - only this fix's own fifteen regions, across
all three sites, differ from the pre-#66 baseline), and harness-verified for all three sites: an
invalid (`r1 = 0`) case returns cleanly to a synthetic caller address with the documented safe default
in `r0`, and a valid (`r1` = a real pointer) case falls through to the exact original, untouched
instruction sequence with every register preserved. Shipped, not yet real-device tested - and unlike
every other fix in this project, this one targets a bug this project hasn't actually observed yet
(most likely tied to dying or returning to the menu, which this project's testing hasn't reached),
so it may sit dormant for a while before (if ever) it's the thing that actually stops a crash.

A second link the user found the same session, `EapRules/masseffect-native-arm` (a native-ARM-Linux
handheld port of the same v1.0.58 build, confirmed via its own published SHA1), was also reviewed -
its documented progress is still stuck earlier than this project's own (menus and initial rendering
only, no confirmed full playthrough yet), and it doesn't publish any patch/hook source discussing the
player-spawn or online-service crash families this project has been chasing. Nothing actionable found
there yet; worth re-checking if that project's own status updates.

**Round 78: 0.80-teamGoalsOnUpdateNullGuard - fix #78, found in the 0.79 real-device test.** Fixes
#75/#76 both confirmed working - `GameWorld::OnUpdate()` no longer crashes on either of its own two
previously-guarded sites - and the game progressed further into the SAME function, hitting a new
crash 316 bytes later (`+368` vs. fix #75's `+52`):
```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000004
Cause: null pointer dereference
    r0  daf314a8  r1  00000006  r4  daf314a8  r7  00000000
#00 pc 00165418  (TeamGoals::OnUpdate(int)+56)
#01 pc 0030dba8  (GameWorld::OnUpdate(int)+368)
```
`GameWorld::OnUpdate()` reads its own `this->[1180]` field (a `TeamGoals*` member) and unconditionally
calls `TeamGoals::OnUpdate(int)` through it with no NULL check - that sub-object was simply never
constructed for this session (a team-based objectives tracker plausibly absent outside a specific game
mode), the same recurring "engine subsystem not built for this player/session, but called into
unconditionally anyway" bug family as fixes #66-#77. Rather than guard just the one caller found so
far, this guards `TeamGoals::OnUpdate(int)`'s own entry point directly, covering every caller at once
- the same "leaf function, checked-pointer-null" cave shape used throughout this project. The guard
sits before the function's own (9-register) prologue runs, so the invalid path is a plain `bx lr`
straight back to the real caller (no stack to unwind, no `lr` arithmetic - same clean-entry-point
style as fix #77's `RemoveUnstructuredObjectField` guard); the function is `void`, so nothing needs
computing on that path. Objdump-confirmed (every branch resolves to exactly the intended target, the
relocated `push` reassembles to the exact original 4 bytes), combined re-verification alongside all
fifty-three other native fixes against the real, fully-patched 1.0.58 `libMassEffect.so` (zero
unexpected region collisions - only this fix's own five regions differ from the pre-#66 baseline),
and harness-verified: the exact real crash condition (`r0 = 0`) returns immediately to a synthetic
caller address untouched, and a valid `this` pointer falls through to the exact original, untouched
instruction sequence with every register preserved. Shipped, not yet real-device tested.

**Round 79: 0.81-nodeSetVisibleNullGuard - fix #79, a PROACTIVE fix found by reading
`v-atamanenko/masseffect-vita`'s loader source, not from a real-device crash log.** Its
`loader/patch/info_menu.c` hooks `im::scene2d_new::Node::SetVisible(bool)` with a one-line comment:
"Fix crash on INFO => ABOUT menu open" - a main-menu screen this project's own testing hasn't reached
yet. Their fix is deliberately generic - a blanket `if (!this) return;` on the function itself, not
on any one caller - since `SetVisible()` is called from a great many places throughout this engine's
whole scene-graph/widget system (this project's own earlier fixes #35-#37 already fought a different,
unrelated `SetVisible()`-adjacent bug inside `Hud::InitLayer()`). Guarding the callee once, the same
way, is far cheaper than waiting to discover and guard every individual caller one crash log at a
time. Same "leaf function, checked-pointer-null" cave shape as fix #78's `TeamGoals::OnUpdate` guard:
the guard sits before the function's own (4-register) prologue runs, so the invalid path is a plain
`bx lr` straight back to the real caller - no stack to unwind, no `lr` arithmetic; the function is
`void`, so nothing needs computing on that path. Objdump-confirmed (every branch resolves to exactly
the intended target, the relocated `push` reassembles to the exact original 4 bytes), combined
re-verification alongside all fifty-four other native fixes against the real, fully-patched 1.0.58
`libMassEffect.so` (zero unexpected region collisions - only this fix's own five regions differ from
the pre-#66 baseline), and harness-verified: the exact crash condition (`r0 = 0`) returns immediately
to a synthetic caller address untouched, and a valid `this` pointer falls through to the exact
original, untouched instruction sequence with every register preserved. Shipped, not yet real-device
tested - like fix #77, this targets a bug this project hasn't actually observed yet (a specific menu
screen its own testing hasn't opened), so it may sit dormant for a while.

**Round 80: 0.82-doneGlyphClazzNullGuard - fix #80, found in the 0.81 real-device test - the FIRST
crash log this whole project has ever seen from actual gameplay/loading-screen rendering**, reached
only after fixes #16-21 (EA download-SDK skip) and fix #30 (Google Play Billing implicit-Intent
guard) finally let the game get past every earlier blocker:

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000c, tid (GLThread)
#00 FT_Done_Glyph+16
#01 im::FreeTypeFont::MeasureCharBoundingBox(wchar_t) const+188
#02 im::GlyphBuffer::PrepareGlyph(...)+120
#03 im::GlyphBuffer::GetGlyphs(...)+164
#04 im::SpriteGraphics::GetGlyphs(...)+68
#05 im::scene2d_new::Text::OnDraw(im::SpriteGraphics&)+332
#06 im::scene2d_new::Group::OnDraw(im::SpriteGraphics&)+84
#07 Splash::OnDraw / #08 SplashScreens::OnDraw / #09 LayerLoading::OnDraw ...
```

Objdump-disassembling `FT_Done_Glyph` (upstream FreeType's own, otherwise-correct implementation)
shows it DOES check `glyph` itself for NULL first, exactly like real FreeType source - the crash is
one step deeper: right after that check it reads `r2 = glyph->library`, `r3 = glyph->clazz`, then at
the exact faulting instruction does `ldr r3,[r3,#12]` (`glyph->clazz->done`) without ever checking
`glyph->clazz` itself for NULL. When `clazz` is NULL this reads offset 12 from address 0 - exactly
the observed fault address (`0x0000000c`), not a coincidence. A few instructions later the function
already has a `cmp r3,#0 / beq <skip the done() call>` guard for exactly this "class has no done()
callback" case - it was simply never reached, because the read that would produce `r3` already
faults first when the class pointer itself is absent. Same "im::FreeTypeFont font-driver saga"
pattern as fixes #27-#31: this game's FreeType glyph-cache hookup produces a glyph object without
fully initializing it in some path `im::GlyphBuffer::PrepareGlyph()` takes; FreeType itself is
shared, unmodified upstream code, so - same discipline as the rest of that saga - the fix guards the
one unguarded read rather than trying to fix FreeType "properly".

The fix is a single-instruction relocation guard, the same shape as fix #76: the one unsafe
`ldr r3,[r3,#12]` at `0x005F9B4C` is replaced with a branch to a freshly-found, verified-unused
12-byte `.rodata` cave (`0x0098C8CC`) holding `cmp r3,#0 / ldrne r3,[r3,#12] / b <resume>`, where
`<resume>` is the function's own untouched next instruction (`0x005F9B50`). On the invalid path `r3`
is simply left at 0 and control returns there, flowing straight into the function's OWN pre-existing
`cmp r3,#0 / beq` guard, which now correctly takes the "no done() callback" path it was always meant
to handle. No `lr` arithmetic, no stack changes - not even a function-entry guard, just the one
unsafe instruction relocated behind a check. Objdump-confirmed (every branch resolves to exactly the
intended target) and harness-verified with two `qemu-arm` test vectors against the real, patched
`libMassEffect.so`: with `r3=0` (clazz NULL) on entry to the cave, execution reaches the resume
address with `r3` still `0` and no fault; with `r3` pointing at a real clazz struct whose `+12` field
holds a marker value, execution reaches the same resume address with `r3` now holding that exact
marker value, confirming the guarded read still executes correctly on the valid path. Shipped -
real-device tested for this specific crash (the 0.81 build's own crash log is what this fix was
built from).

**Round 81: 0.83-deserializerCopyCtorNullGuard - fix #81, found in the 0.82 real-device test - the
FIRST crash this project has ever seen on a BACKGROUND asset-loading thread** ("Thread-9") rather
than the render thread (GLThread) every prior crash here came from:

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000, tid 26720 (Thread-9)
Cause: null pointer dereference
#00 im::serialization_old::Deserializer::Deserializer(im::serialization_old::Deserializer const&)+0
#01 ParticleSystem::CacheSystem(eastl::basic_string<char,...> const&, float, unsigned int, im::m3gext::ModelCache*)+376
#02 LoadTaskCache::Run()+8916
#03 im::RunFunction(void*)+12
#04 RunnableFunctionInternal(void*)+452
```

Objdump-disassembling `ParticleSystem::CacheSystem(...)` shows the chain: it calls
`Deserializer::GetStructArray(name)` to look up a named struct-array block inside the currently-
loading particle-effect definition, then unconditionally copy-constructs a local `Deserializer`
from the result - when the named block simply isn't present in this particular effect's data,
`GetStructArray()`'s result slot is left NULL, and that NULL is passed straight through as the
copy constructor's `other` reference. The copy constructor's own first instruction
(`ldr r2,[r1]`, offset+0 in the backtrace - matches exactly) then dereferences it unconditionally -
fault addr `0x0` confirms a NULL reference precisely.

`Deserializer::Deserializer(Deserializer const&)` is itself small, shared, and leaf (no stack
frame, calls nothing else) - used at 16 separate call sites throughout the library, not just this
one. Rather than guard only the one observed caller, this fix guards the copy constructor's own
entry point once, the same "shared function, guard universally" choice as fixes #77-#79 (fix #77
touches a namesake-but-unrelated `im::serialization::Database` class - `serialization` vs this
fix's `serialization_old` - a different, coincidentally similar-looking bug in a sibling system).

The fix: on entry, check `r1` (`other`) for NULL. Non-NULL: completely unchanged - the original
first instruction runs exactly as before, then resumes at the function's own next instruction.
NULL: skip the dereference entirely and instead zero the three fields the constructor would have
copied (offsets 0, 4, 12) - using `r1` itself as the zero source, since it's already known to be
exactly 0 on this path (saves a `mov r2,#0`) - then return immediately via the function's own
original `bx lr`, skipping the refcount-increment block that would otherwise dereference the
now-known-absent field. Produces a validly-constructed, empty/inert `Deserializer` rather than
crashing or leaving the new object at whatever stack garbage was already there. Objdump-confirmed
(every branch resolves to exactly the intended target) and harness-verified with two `qemu-arm`
test vectors: `r1=0` reaches the function's own original `bx lr` with all three fields zeroed and
no fault, and `r1` pointing at real memory reaches the function's own original next instruction
with `r2` holding the exact value read from `[r1]`, confirming the guarded read still executes
correctly on the valid path. Shipped - real-device tested for this specific crash (the 0.82
build's own crash log is what this fix was built from).

**Round 82: 0.84-gameObjectAccessorBatch - fixes #82-#88, a batch of 7 PROACTIVE `GameObject`
accessor guards, found by a whole-binary static scan rather than any specific crash log** - the
same fatigue-driven fallback as Round 74 (fixes #70-74), this time scripted end-to-end instead of
scanned by hand: disassemble the whole `.text` section once, keep every function belonging to
`GameObject` (by far the class with the most confirmed real crashes of this shape - fixes
#67/#69/#70-74/#77-81 all touch it), filter to small (<=64 byte) leaf-style functions whose very
first real instruction is an unguarded `ldr/str [r0, #N]` with no preceding `cmp/cmn/tst/teq` on
`r0`, and cross-reference against every address already claimed by an existing patcher. That
narrowed ~4400 raw hits project-wide down to 138 genuinely new candidates, then to 10
`GameObject`-specific finalists after hand-triage by size/simplicity, then to the 7 shipped here
after excluding two as unsafe to blindly default:

- `GetPos()` - excluded. Returns a reference into a sub-object (`this->field + 64`), not a
  checkable pointer - defaulting `this` to NULL would still produce a non-NULL but bogus address
  (`0x40`) that the caller would then blindly dereference, an actively WORSE failure than the
  original crash.
- `GetRatings()` - excluded. Tail-calls straight into `GameWorld::GetRatings()` - guarding this
  function alone doesn't establish whether that callee is itself safe against whatever this guard
  would let through, an unverified cascading risk not worth taking on without its own dedicated
  look.

The 7 that shipped, and the guard shape each one got:

- `GetRigidBodyPtr()`, fix #82 - simplest case, just `ldr r0,[r0,#88]` / `bx lr`. Guard: `cmp r0,#0`
  / `ldrne r0,[r0,#88]` / `b` back to the original `bx lr`. Returns NULL on a NULL `this`.
- `IsVisible()` (fix #83), `IsTapTarget()` (fix #84), `IsCloaked()` (fix #85), `IsHidden()`
  (fix #86) - same guard shape as #82, but each has 1-2 more downstream instructions (`lsr`/`and`
  bit-extraction, or a `subs`/`movne` non-null test) between the load and the return. All of them
  are safe to leave completely untouched by the guard: injecting `r0 = 0` at the load and letting
  it fall through unchanged still yields the correct `false` result, because a bitwise op on `0`
  stays `0`, and comparing/subtracting `0` from `0` still reads as "equal"/"non-positive". This
  does NOT hold for arithmetic ops like `add`, only bitwise-or-comparison-only ones - verified by
  hand for each function's exact downstream instructions, not assumed.
- `Select()`, fix #87 - the one `void` mutator in the batch, and the only one that dereferences
  `this` TWICE (read the select-counter, increment, write it back) - the "let zero propagate"
  trick doesn't apply here, since a zeroed counter would still crash on the write-back. Needed a
  real "skip the whole body" guard: `cmp r0,#0` / `beq` straight to the function's own existing
  final `bx lr` (left completely untouched, reused directly as the bail target since there's no
  return value to zero out) / otherwise relocate the load and resume the original
  increment-and-store unmodified.
- `GetPlayerObj()`, fix #88 - byte-for-byte structurally identical to `GameObject::GetPlayer()`,
  already guarded by fix #71: same two-level `this -> field_12 -> field_452` chain, same exact
  instruction encodings, just 12 bytes further into the same `.text` region. Guards only the outer
  `this == NULL` case, matching fix #71's already-shipped, already-field-tested scope exactly
  rather than silently widening protection beyond precedent. Implemented more compactly than fix
  #71's original 4-fragment trampoline (built when that was the only free space available) using
  ARM's conditional execution in a single self-contained 20-byte cave instead.

All 7 land in newly-found `.rodata` gaps, cross-checked against every cave address any patcher in
this project already claims - and, after several false starts, against the binary's actual
section layout (`.rodata` genuinely ends at `0x9fb000`; several tempting 100-300 byte "gaps"
further out at `0xa8xxxx` turned out to be live X.509 certificate DER data and floating-point
tables in `.data.rel.ro`, not padding, and were rejected once recognized as such - same discipline
as the UTF-16 sparse-table false positives Round 81 already caught). One originally-scoped 8th
candidate, `IsBioticSelectTarget(Biotic*)`, was dropped from this batch for lack of a suitably
free, 4-byte-aligned gap once ARM's alignment requirement was accounted for - deferred to a future
round rather than forced into a low-confidence placement.

Every fix in this batch is objdump-verified (every branch resolves to its intended target) and
harness-verified with `qemu-arm` twice each (an `r0 = 0` invalid path and a valid heap-pointer
path, confirming the correct value is still produced when `this` is real) - 14 test vectors total,
all passing. Combined-verified clean against all 64 patchers in the project and the pristine
v1.0.58 `.so` (63/64 matched; the one miss is `HudDebugNodeDestructorGuardPatcher`'s own
already-documented pre-existing quirk, unrelated to this batch). Same accepted trade-off as Round
74: none of these 7 has a confirmed real-device crash yet, so some or all of them may turn out to
be permanent no-ops - the point is cutting down on one-crash-at-a-time test rounds, not proving
each one necessary in advance.

**Round 83: 0.85-cacheSystemEmptyDeserializerGuard - fix #89, a REAL FIX for a NEW crash found in
the 0.84 real-device test, and a direct, traceable consequence of this project's OWN fix #81** -
the first time a shipped fix in this project has caused a new downstream crash rather than fixing
an engine bug outright. Fix #81 correctly stops `Deserializer`'s copy constructor from crashing on
an invalid `other` reference by constructing a safe "empty/inert" object instead (zeroing fields at
offsets 0, 4, 12) - but the one caller that matters here, `ParticleSystem::CacheSystem()`, was never
written to expect an empty `Deserializer` back. A few instructions after calling the now-guarded
copy constructor, it unconditionally reads the new object's field-12 pointer and dereferences IT
too:

```
1b7b94: ldr r3, [sp, #256]   ; r3 = (the just-constructed Deserializer)[12] - zeroed by fix #81's
                             ; guard when the copy source was invalid
1b7b98: ldr r3, [r3, #16]    ; <-- NEW crash here (fault addr 0x10) when r3 == 0
1b7b9c: ldr r2, [r3, #24]    ; immediately re-dereferences the SAME register - rules out the "let
                             ; zero propagate" shortcut Round 82's batch used; a real guard is
                             ; required here
```

Fix #81 didn't eliminate the original crash's failure mode, it shifted it one level downstream -
proof that "make the constructor safe" and "make every caller safe against what the constructor now
produces" are two different guarantees, and this project had only established the first one.

The fix reuses `CacheSystem`'s own pre-existing cleanup/early-return block (`0x1b7bf0` - already
destructs four `shared_ptr`s and jumps to the function's shared epilogue) as the bail target,
same "reuse an existing downstream instruction" technique fix #87 (Round 82) introduced: `cmp r3,#0`
/ `beq` straight to `0x1b7bf0` / otherwise relocate the load and resume `0x1b7b9c` unmodified. Lands
in a manually-vetted 16-byte `.rodata` gap at `0x991c30` - confirmed genuine NUL padding between two
unrelated Bullet Physics RTTI type-name strings (`...SphereTriangleDetector\0`, then an unrelated
small integer table starting immediately after), not a table interior. Roughly 80 other candidate
gaps were scanned and rejected first: several matched this project's now-familiar false-positive
shapes (periodic small-integer/RLE tables complete with an embedded XML/UTF-8-BOM resource blob,
float-constant tables, dense byte-fill regions immediately preceding otherwise-legitimate string
tables) - none were assumed safe from the automated quality score alone, every candidate actually
shortlisted was checked by eye against wide hex context first.

Objdump-confirmed (both branches resolve to their intended targets, patch bytes match the assembled
output exactly) and harness-verified with two `qemu-arm` test vectors: `r3 = 0` reaches the bail
block without any further dereference, and `r3` pointing at a real test buffer confirms the
relocated `ldr r3,[r3,#16]` still loads the correct value and resumes normally. Combined-verified
clean against all 65 patchers in the project (64/65 patched against the pristine v1.0.58 `.so`; the
one skip is `HudDebugNodeDestructorGuardPatcher`'s same already-documented pre-existing quirk from
every prior round, unrelated to this fix) - no collisions with any of the other 64.

**Round 84: 0.86-deserializationEngineSharedPtrDtorGuard - fix #90, a REAL FIX for a crash found in
the 0.85 real-device test, and the SECOND downstream consequence of fix #81 found this way (the
first was fix #89, Round 83, already confirmed gone in this same test - this crash is one call
deeper than that one, not a recurrence of it)** - fix #89 correctly stopped `CacheSystem()` itself
from crashing on the empty `Deserializer`'s field-12 pointer, bailing cleanly into `CacheSystem`'s
own existing cleanup block at `0x1b7bf0`. That block's very next instruction, though, destructs an
`eastl::shared_ptr<im::serialization_old::DeserializationEngine>` sub-object embedded inside the
same empty `Deserializer` (at offset 0) - and that destructor was never written to expect a null
control-block pointer either:

```
eastl::shared_ptr<...DeserializationEngine...>::~shared_ptr():
  e78a4: ldr r3, [r0, #4]    ; r3 = this->mpRefCount - zeroed by fix #81's empty-object guard
  e78a8: mov r4, r0
  e78ac: ldr r2, [r3, #4]    ; <-- NEW crash here (fault addr 0x4) when r3 == 0
```

Confirmed by the 0.85 crash log's own backtrace: frame #00 is this exact destructor, +12 bytes in -
precisely `0xe78ac`; frame #01 is `CacheSystem()+616` (`0x1b7bf4`, the `bl` right after fix #89's
bail target) - proof fix #89 worked exactly as designed and the crash moved one call deeper, not
that it failed. Root cause: under normal EASTL usage a `shared_ptr`'s control-block pointer is
apparently never null even for a nominally "empty" instance (a real, if empty, control block always
gets allocated) - so this destructor was never written with a null check. Fix #81's approach of
building an "empty" `Deserializer` by zeroing raw memory (rather than properly default-constructing
each embedded member) produces exactly the one `shared_ptr` state this destructor doesn't handle.

Guarding this destructor's own entry (rather than reworking fix #81, which would touch all 16 of its
already-validated call sites) is both the smaller change and the more useful one - it now protects
every OTHER caller of this same destructor project-wide against the same latent assumption, not just
this one call site. Same "reuse an existing downstream instruction as a bail target" technique as
fixes #87/#89: on a null control block, jumps straight to the function's own existing return
sequence (`0xe78d0`, `mov r0,r4` / `pop {r4,r5,r6,pc}` - `r4` already holds `this` from the
unconditional `mov r4,r0` two instructions earlier, no extra setup needed).

Lands in a genuinely spacious `.rodata` gap this time - `0x98dbf8`, 296 bytes available (only 16
used) - found on a widened rescan after fix #89 had already claimed the one previously-available
16-byte gap. Manually confirmed safe by checking far past both edges: an unrelated `m3g::Group`
integer table ends cleanly well before it, and completely unrelated RTTI type-name strings for
`m3g::Image2D`/`m3g::IndexBuffer`/`m3g::KeyframeSequence` begin fresh well after it - not a table
interior. Of ~20 other candidates checked this round, every other one matched an already-known
false-positive shape (mid-table gaps that resume with more real entries further out, float/constant
tables, X.509 certificate DER data - including one containing a literal RSA OID sequence - and the
same UPnP/hash-table region flagged in Round 83).

Objdump-confirmed (both branches resolve to their intended targets, patch bytes match the assembled
output exactly) and harness-verified with two `qemu-arm` test vectors: `r3 = 0` reaches the
function's own return sequence without dereferencing anything further, and `r3` pointing at a real
test buffer confirms the relocated `ldr r2,[r3,#4]` still loads the correct value and resumes the
normal refcount-decrement logic. Combined-verified clean against all 66 patchers in the project
(65/66 patched against the pristine v1.0.58 `.so`; the one skip is
`HudDebugNodeDestructorGuardPatcher`'s same already-documented pre-existing quirk from every prior
round, unrelated to this fix) - no collisions with any of the other 65.

**Round 85: 0.87-forceResizableOrientation - not a crash fix, a new opt-in ADVANCED SETTING**
requested directly: a manifest-only toggle that bakes in the documented equivalent of `adb shell am
compat enable OVERRIDE_ANY_ORIENTATION_TO_USER`/`FORCE_RESIZE_APP` (free rotation + forced
resizable/multi-window support), since an ordinary installed app has no runtime permission to flip
those compat-framework overrides for another package - see "Force free rotation + resizable window"
above for the full writeup. Implementation: `ManifestPatcher.widenResizabilityAndOrientation()`,
using ARSCLib for real attribute insertion (the same approach `addUsesLibrary` already uses, since -
unlike `screenOrientation`, near-universally already present in a decade-old game's manifest -
`resizeableActivity` didn't exist as an attribute yet when these games were built, so there's nothing
to overwrite in place). `resizeableActivity`'s resource id (`0x010104f6`) was pulled from ARSCLib's
own bundled `android-24.apk` framework resource table (the same mechanism this project's
`ID_required` constant already relies on) rather than guessed, then cross-checked by confirming the
same extraction method reproduces `screenOrientation`'s already-known id exactly.

Verified against real, uploaded manifests from all four of this project's games (Mass Effect:
Infiltrator, Imperium Galactica 2, Colin McRae Rally, Haegemonia: Legions of Iron) - all four
produced the intended two-attribute change with zero exceptions. Independently cross-checked with
`androguard` (a completely separate AXML parser from ARSCLib) parsing both the original and patched
manifest of each game and diffing them attribute-by-attribute: in every case the only difference is
exactly `resizeableActivity` and `screenOrientation` on the intended elements - nothing else in the
manifest changed. Off by default (see the "Using it" section) - IG2/Haegemonia/CMR are unaffected
unless this box is explicitly ticked for a given patch run.

**Round 86: 0.88-splitOrientationResizableToggles - real-device feedback on Round 85's checkbox: it
starts, but the game doesn't go fullscreen** with the box ticked. Root cause identified: it's
specifically the resizability half causing it - on the project's own Honor/Magic UI test tablet,
declaring an activity `resizeableActivity="true"` makes the OEM launcher open it in a smaller
floating/windowed view by default rather than fullscreen, an OS/launcher-level trade-off of
declaring an app resizable, not a bug in the patch itself. Fix: split the one combined function
(`ManifestPatcher.widenResizabilityAndOrientation`) into two fully independent ones
(`widenResizability`/`widenOrientation`), each with its own opt-in checkbox in Advanced Settings -
"Free rotation" and "Resizable / multi-window support" - so either can be applied without the other.
This lets someone who only wants free rotation (the more commonly wanted half) avoid the
windowed-view side effect entirely by leaving resizability off. Also documented two further real
limits while at it: `screenOrientation="user"` only rotates if the device's own auto-rotate is on,
and can still be overridden at runtime by a game that calls `setRequestedOrientation()` itself
(unlike the real `am compat` override, a baked-in manifest default isn't OS-enforced). Verified: both
split functions, called independently or together, produce byte-for-byte the same result as the
original combined function against all 4 games' manifests (Mass Effect, IG2, CMR, Haegemonia) -
re-ran the same real-manifest + androguard cross-check as Round 85, this time also confirming each
checkbox in isolation changes only its own one attribute. Shipped versionCode 88.

**Round 87: 0.89-requestLegacyExternalStorage - NOT an advanced setting, applied unconditionally**
(like `addUsesLibrary`, not like the two Round 86 opt-in toggles) since it doesn't change how a game
looks or behaves, only how it's allowed to touch storage. Found by directly comparing this project's
canonical Mass Effect: Infiltrator manifest against a third-party repack of the same game that starts
without any SoA patching at all - among the manifest differences, that repack already ships with
`android:requestLegacyExternalStorage="true"`. Bakes in the same thing: opts the app back into
pre-Scoped-Storage direct file access (Scoped Storage, introduced Android 10/API 29, is exactly the
kind of restriction a decade-old game's own save/cache/download code wasn't written to expect).
Resource id (`0x01010603`) pulled from ARSCLib's own bundled `android-29.apk` framework table (the
API level that introduced the attribute), same method as `ID_resizeableActivity` in Round 84.
Documented caveat: per Android's own docs this flag only has any effect when the app's own
targetSdkVersion is exactly 29 - on Android 11+/targetSdk 30+ (this app's own default, and this
project's own Android 16 test tablet) it's a harmless no-op. Applied anyway since it costs nothing
where it doesn't apply and helps where it does (an Android 10 device, or a lower `--target-sdk`
override). Verified against real manifests from all 4 of the project's games with the same
real-manifest + androguard cross-check as Rounds 85/86 - exactly the one intended attribute added in
each, nothing else touched. Shipped versionCode 89.

**Round 88: 0.90-orientationAllActivities - real correctness bug found and fixed in "Free rotation"**
(the Round 86 checkbox): it was silently a no-op on 3 of this project's 4 games. Root cause: found by
directly comparing this project's own canonical manifest against the actual XDA-sourced "Fix RU
Adreno" build the user tests with (xdaforums.com/t/game-4-0-mass-effect-infiltrator-v1-0-58-
fix.4769388/) - that build injects its own wrapper/splash launcher activity
(`com.dataManager.MainActivity`) in front of the real `.MassEffectActivity`, each with its OWN
independent `screenOrientation` (Android doesn't let a non-launcher activity inherit one). The old
`widenOrientation()` only ever touched `block.mainActivity` (the launcher) - on this shape, that
patches the wrapper/splash screen and never reaches the real gameplay activity's own orientation
lock, which is indistinguishable from "the checkbox doesn't work" from the outside. Checking the
project's OTHER games for the same shape found it's not just Mass Effect: **IG2** has
`SGDownloaderActivity` (launcher) *and* a separate `ig2psActivity` with its own orientation;
**Haegemonia** has `SGDownloaderActivity` (launcher) *and* a separate `LaunchActivity`; only Colin
McRae Rally's four Unity/PlayHaven activities were already fully covered (multiple activities, but
all already matched by the "already declares screenOrientation" case). Fix: `widenOrientation()` now
targets every `<activity>` that's EITHER the main/launcher activity OR that already declares its own
`screenOrientation` - covers both the simple single-activity case (add if missing) and the
wrapper-launcher-plus-real-activity case (override every activity that already locks its own
orientation, launcher or not). Verified against real manifests from all 4 games plus the actual XDA
repack manifest, cross-checked with androguard: in every case exactly the intended activities (and
only those) got their `screenOrientation` updated, nothing else touched - including confirming IG2's
and Haegemonia's second, previously-missed activity now gets the fix too. Shipped versionCode 90.

**Round 89: 0.91-performanceCompatibilityFlags - NOT an advanced setting, applied unconditionally**
(same category as Round 87's `requestLegacyExternalStorage`): the same real 3rd-party repack that
prompted Rounds 87/88 also already ships with four more `<application>`-level boolean flags this
project didn't set - `android:largeHeap`, `android:extractNativeLibs`, `android:hardwareAccelerated`,
`android:isGame`. All four now baked in unconditionally, `ManifestPatcher.addPerformanceCompatibilityFlags()`:
`largeHeap` gives real headroom against OutOfMemoryError on these texture/asset-heavy old games;
`extractNativeLibs` forces native `.so` files to be extracted at install rather than mmap'd directly
out of this app's own repackaged/resigned APK (removes one possible load-failure source on a
repacked file whose zip alignment this pipeline doesn't control); `hardwareAccelerated` is likely a
no-op in practice (already the default at targetSdkVersion >= 14, which every patch already clears)
but set explicitly anyway; `isGame` is cosmetic/OS-integration only (app-drawer categorization, some
OEMs' own game-mode features). Resource ids (`largeHeap` 0x0101035a, `extractNativeLibs` 0x010104ea,
`hardwareAccelerated` 0x010102d3, `isGame` 0x010103f4) all pulled from ARSCLib's own bundled
android-23.apk framework table (the earliest one available - an attribute present at API 23 was
introduced at or before it, and platform attribute ids never change once assigned). Verified against
real manifests from all 4 of the project's games with the same real-manifest + androguard
cross-check as the prior rounds - exactly the four intended attributes added in each, nothing else
touched. Shipped versionCode 91.

**Round 90: 0.92-fallbackFontPath - fix #91 (`FT_Done_Glyph` glyph->library NULL guard) and the
likely ROOT CAUSE fix behind this whole FreeType saga (fixes #27-31, #80, #91), found from a
real-device 0.91 test:**

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000, tid (GLThread)
#00 FT_Done_Glyph+20
#01 im::FreeTypeFont::MeasureCharBoundingBox(wchar_t) const+188
#02 im::GlyphBuffer::PrepareGlyph(...)+120
#03 im::GlyphBuffer::GetGlyphs(...)+164
... (identical remainder to Round 80's crash - same call chain, same loading-screen text path)
```

One instruction past Round 80's own `clazz`-null guard, in the very same `FT_Done_Glyph`: it also
unconditionally reads through `glyph->library` (`ldr r5,[r2]` at `0x005F9B50`) right after the
now-guarded `glyph->clazz->done` read, without ever checking `library` itself for NULL either.
Round 80's own doc comment had assumed that read was "unaffected either way" since `r2` itself was
safely populated by the preceding `ldm` - true for the register, not for the NULL *value* now
sitting in it. Same shape as Round 80, same fault-address-matches-struct-offset signature (`0x0`).
Fix: the callsite is replaced with a branch to a 4-word cave (`0x009FADE0`, DirtySDK's own dead
UPnP string-table padding, same region family as prior caves) holding `cmp r2,#0 / ldrne r5,[r2] /
bne <resume 0x005F9B54> / b <the function's own existing "nothing to free" exit, 0x005F9B70>` -
reuses fix #80's exact two-exit pattern, just guarding the sibling field. Objdump-confirmed and, per
the harness convention above, `qemu-arm`-harness-verified with two vectors against the real, patched
`libMassEffect.so` (`tests/fix91_invalid.cfg`, `tests/fix91_valid.cfg`): `r2=0` (library NULL)
reaches the "nothing to free" exit (`0x005F9B70`) with no fault; `r2` pointing at real memory
reaches the function's own next instruction (`0x005F9B54`) with `r2` preserved and no fault,
confirming the guarded read still executes correctly on the valid path.

**Separately, and likely the actual ROOT CAUSE of the entire FreeType saga**: comparing the real
1.0.58 APK against a working 3rd-party repack ("Fix RU Adreno",
xdaforums.com/t/game-4-0-mass-effect-infiltrator-v1-0-58-fix.4769388/) surfaced a one-string native
diff in `libMassEffect.so` itself - the game's hardcoded CJK/fallback font path,
`/system/fonts/DroidSansFallback.ttf` (0x0098E7D8, right next to the primary font path at
0x0098E7C0), doesn't exist on any Android release since roughly 4.3/5.0 (dropped in favor of Noto).
`FT_New_Face()`'s return value is never checked (already root-caused in fixes #27-31), so every
downstream FreeType call in this saga has been operating on a face object that silently failed to
open - consistent with every crash this family has guarded one field at a time. A real-device check
(Hihonor tablet, Android 16, Magic UI) confirmed `/system/fonts/DroidSans.ttf` - the same file the
*primary* font path already opens successfully - is still present, so the fallback path is now
redirected to it: straight 36-byte string-slot replacement
(`ElfMassEffectFallbackFontPathPatcher.kt`), no branch/cave needed. Does NOT replace any of the
existing null-guards (defense in depth - this only makes the common case stop hitting the failure
path those guards exist for). Caught and fixed one real bug of its own during this round: an
off-by-one in the replacement slot size (`SLOT_SIZE` was briefly `37`, one byte too many for the
actual 35-char-plus-NUL string, caught by a Python byte-layout simulation before it ever reached
device code) - re-verified afterward with no collateral damage to the adjacent string table.

Both fixes touch only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes
check, so neither can affect IG2/Haegemonia/Colin McRae Rally by construction. `versionCode` bumped
to 92. **Not yet built into an installable APK** - this environment currently has no network path to
`dl.google.com`/`repo1.maven.org`/Gradle's own distribution server (org policy denial, not retried
per this environment's own guidance), so both fixes exist only as harness/simulation-verified source
pending a future session/environment where an actual Gradle build can run.

**Round 91: 0.93-updateViewCullingNullGuard - fix #92, the FIRST crash found past the entire
FreeType saga (fixes #27-31, #80, #91, the fallback-font-path fix), on a real-device 0.92 test - plus
fix #93, a proactive companion:**

```
Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0, tid (GLThread)
#00 GameWorld::UpdateViewCulling()+20
#01 GameWorld::OnUpdate(int)+436
#02 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
#03 LayerGameWorld::OnEvent(im::Event const&)+1564
#04 im::LayerStack::OnEvent(im::Event const&)+124
#05 Application::OnUpdate()+972
... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
```

Both 0.92 fixes worked - the game got all the way past loading-screen text rendering into the actual
gameplay update loop for the first time. Objdump-disassembling `GameWorld::UpdateViewCulling()`
(`0x002FD334`) shows it reads `this->m_culling` (a `Culling*` - base class of `PortalCulling`/
`QuadtreeCulling`, at offset `0x41c`) and unconditionally dereferences it (`ldr r3,[r3]` at
`0x002FD348`, reading its vtable pointer) before two virtual dispatches through it - the crash
register dump confirms `r0`/`r3`/`ip` are all `0x0` at the fault, i.e. `m_culling` itself is NULL, not
a wild pointer. Same "some GameWorld/GameObject sub-system pointer isn't always constructed by the
time a per-frame caller assumes it is" shape as the fixes #67-74/#82-88 GameObject-accessor family,
just on a different member. Fix: since the function's return value is never used and both virtual
calls are through the same now-known-NULL object, the guard skips both entirely on NULL and goes
straight to the function's own existing epilogue (`pop {r4,pc}`, `0x002FD368`) - exactly as if there
were nothing to cull this frame. Cave (`0x0098DC08`) reuses the first 16 of the ~280 still-unclaimed
bytes in fix #90's own already-vetted 296-byte `.rodata` gap (`0x0098DBF8`) - re-confirmed
zero/relocation-free before being claimed, no fresh gap-hunting needed. Objdump/capstone-confirmed and
`qemu-arm` harness-verified with two vectors (`tests/fix92_invalid.cfg`, `tests/fix92_valid.cfg`):
`r3=0` reaches the function's own epilogue with no fault; `r3` pointing at real memory reaches the
function's own next instruction with `r3` holding the exact value read from `[r3]`.

**Proactively also guarded `GameWorld::FinalizeCulling()`** (fix #93, `0x002FD318`, immediately
preceding `UpdateViewCulling()` in the library) - it reads the exact same `m_culling` field the exact
same unguarded way, just with one virtual dispatch instead of two. Not yet itself seen on a real
device, guarded anyway per this project's established "cover every sibling instance of an
already-confirmed bug shape together" practice (same as the GameObject-accessor batches). Cave
(`0x0098DC18`) is the next 16 bytes in the same fix #90/#92 gap (still ~264 bytes free afterward).
Same verification: objdump/capstone-confirmed, `qemu-arm` harness-verified with two vectors
(`tests/fix93_invalid.cfg`, `tests/fix93_valid.cfg`), identical pass pattern to fix #92.

Both fixes touch only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes
check, so neither can affect IG2/Haegemonia/Colin McRae Rally by construction. `versionCode` bumped to
93. **Not yet built into an installable APK in this environment** for the same reason as Round 90 -
still no network path to `dl.google.com`/`repo1.maven.org` here; the user builds the actual APK
themselves in Android Studio from the full project source. Not yet combined-verified against all 4
games' real APKs in one pass (time-boxed this round; the exact-byte-match + filename-scoped guard
already makes the structural risk to the other 3 games zero) - not yet real-device tested either, as
of shipping.

**Round 92: 0.94-stateTransitionBeginGuardWidened - fix #46 revised (a 2nd time), same shared
`.rodata` cave, real crash on a background thread ("Thread-10") in the 0.93 real-device test.**
0.93 (fixes #92/#93) real-device tested clean - the `UpdateViewCulling`/`FinalizeCulling` crash chain
is closed - but a NEW crash turned up, this time `SEGV_ACCERR` (not the project's usual
`SEGV_MAPERR`) at a suspicious non-null fault addr `0x72757401`, only 1 backtrace frame, and a
raw `pc` register that didn't obviously match the backtrace's `pc` column. The plain `adb logcat`
capture that solved every crash so far wasn't enough to move on this one - Android only mirrors an
abbreviated tombstone to logcat (header, registers, 1-frame backtrace), dropping the memory dumps
needed to actually disassemble the fault. The user pulled a full `adb bugreport` instead, whose
embedded `tombstone_01` included the "memory near pc/lr/r1/r5/ip/sp" hex dumps and the full memory
map logcat always drops - the missing piece for every question this crash raised:

```
signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 0x72757401
r2  72757401   r6  00000000   pc  7f2169a4 (static 0x9959a4)
memory near pc: ... e7923006  ldr r3, [r2, r6]   <- the actual faulting instruction
memory map: 714f7000-74600fff ---  [anon:dalvik-non moving space]   <-- fault addr here
```

Cross-referencing the static address against this project's own source tree (not just the reference
`.so`) identified it immediately: `0x9959a4` is fix #46's own cave (`0x99599C`, 8 bytes in) -
`ldr r3,[r2,r6]`, the exact original instruction fix #46 displaced from `Hud::StateTransition()`'s
`0x11461C`, replayed after its guard. Fix #46's guard only ever checked `begin` (`r2`) `<= 0x1000` -
this time `begin` was `0x72757401`, comfortably above that floor, so the guard let it straight
through to the real load. The memory map confirms `0x72757401` lands inside
`[anon:dalvik-non moving space]`, mapped with **no access at all** - an ART/Dalvik heap region
reserved but currently unmapped, not a real `Hud`-owned allocation; same "container never
constructed, begin/end hold whatever leftover bits were there" story fix #46's own original doc
comment already named, just a different leftover bit pattern than the `NULL` case it already caught.

Rather than add yet another ad-hoc blacklist band, this revision applies the whitelist convention
Round 49 (fix #48) already established project-wide after an equally "plausible-looking" wild value
(`0x73616c62`, a string literal's own bytes) cleared fix #45's *two* existing bands at once: every
real heap object pointer this whole project has ever seen in a crash dump lands in `0xd8000000`-
`0xdf000000`, so `>= 0xC0000000` (with headroom) accepts every genuine pointer and rejects NULL,
small sentinels, string literals, and this `0x72757401` ART-heap-gap value alike, in one check. Fix
#46's cave keeps its exact 16 bytes and exact branch target (`0x114668`, re-confirmed by objdump) -
only the `cmp r2,#0x1000` immediate and the `bls`->`blo` branch condition change. Capstone-confirmed
instruction-by-instruction against both old and new cave bytes, and `qemu-arm` harness-verified with
two vectors (`tests/fix46rev2_invalid.cfg`: `r2=0x72757401` correctly lands on the `0x114668`
empty-exit PAD without ever reaching the load; `tests/fix46rev2_valid.cfg`: `r2=0xd8000000` falls
through, executes the real `ldr`, and returns via `bx lr` with `r3` holding the exact probe value
written to that address).

Touches only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes check (still
the ORIGINAL pristine `EXPECTED_ORIGINAL_CAVE`/`EXPECTED_ORIGINAL_CALLSITE` from Round 46 - only the
*patched* bytes changed, so this still only ever applies to a freshly-supplied original APK, never a
previously-patched one), so IG2/Haegemonia/Colin McRae Rally are unaffected by construction.
`versionCode` bumped to 94. Delivered as a fresh full-project source zip per the established
delivery convention; not yet real-device tested as of shipping.

**Round 93: 0.95-updateObjectsPostCameraNullGuard - fix #94, the FIRST crash found past the entire
`Hud::StateTransition()` wild-pointer saga (fixes #45-#51), on a real-device 0.94 test.** 0.94 (fix
#46 revision 2, the widened `>= 0xC0000000` begin-pointer guard) real-device tested clean - the
`0x72757401`/`SEGV_ACCERR`/Thread-10 crash didn't recur - and the game got further into its normal
per-frame update tick before hitting a brand new, perfectly ordinary crash:

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
Cause: null pointer dereference
#00 GameWorld::UpdateObjectsPostCamera(int)+132
#01 GameWorld::OnUpdate(int)+448
#02 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
```

Back to this project's most common shape after the last two rounds' harder, thin-evidence
detective work: a plain `SEGV_MAPERR` (Android even labels it explicitly, `Cause: null pointer
dereference`) with a full symbolized backtrace, straight from `adb logcat` - no `adb bugreport`
needed this time. Objdump on `GameWorld::UpdateObjectsPostCamera(int)` (`0x0030350C`) found the
exact same "sub-object pointer not always constructed, unconditionally dereferenced for its vtable
before a virtual call" shape as fixes #92/#93 (`m_culling`), just on a different, not-yet-named
field at `this`+452: `ldr r3,[r3]` (`0x303590`) reads its vtable unconditionally, then dispatches
through vtable+0xf4; the instruction right after the dispatch (`vldr s14,[r7,#968]`) doesn't touch
`r0`/`r3`, confirming the call's return value is discarded, so skipping the dispatch entirely on a
bad pointer is safe.

Fix guards the read with `cmp r3,#0xC0000000` / `blo <skip to the post-dispatch instruction>` /
`ldr r3,[r3]` (replay) / `b <the function's own untouched 'mov lr,pc'>` - deliberately using the
`>= 0xC0000000` whitelist from the start (not a narrower `== 0`-only check) rather than waiting for
the Round 92 lesson to repeat on a new call site. Cave (`0x0098DC28`) is the next 16 bytes in the
same fix #90/#92/#93 `.rodata` gap (still plenty free afterward). Objdump/capstone-confirmed and
`qemu-arm` harness-verified with two vectors (`tests/fix94b_invalid.cfg`, `tests/fix94b_valid.cfg`)
- the valid-path test round-trips a full fake vtable + function pointer + `bx lr`, confirming not
just the guard but the whole "branch back into the function's own `mov lr,pc`" return-address
technique lands the callee's return exactly where the original code would have.

Touches only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes check, so
IG2/Haegemonia/Colin McRae Rally are unaffected by construction. `versionCode` bumped to 95.
Delivered as a fresh full-project source zip per the established delivery convention; not yet
real-device tested as of shipping.

**Round 94: 0.96-vfxUpdateFacingNullGuard - fix #95, found on a real-device 0.95 test.** 0.95 (fix
#94) real-device tested clean - `GameWorld::UpdateObjectsPostCamera` didn't recur, and the backtrace
proves genuine progress (`GameWorld::OnUpdate(int)+472` this time vs. `+448` before) - before hitting
a new, equally ordinary `SEGV_MAPERR`:

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
Cause: null pointer dereference
#00 VFX::Update(int)+260
#01 GameWorld::OnUpdate(int)+472
... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
```

Objdump on `VFX::Update(int)` (`0x002F0938`) found the exact same `this->[4]->[452]` field fix #94
already guards (same `0x1c4` offset, reached through a different owning object here) - but this time
the NULL `GameObject*` (`r7`) is used FOUR separate times before the function moves on: the vtable
dispatch (`ldr r3,[r7]` at `0x2F0A3C`, the immediate crash site), then three direct calls into
`GameObject::GetFacing()`/`GetUp()`/`GetSide()` (none known to guard a NULL `this` themselves),
building VFX's per-frame orientation basis. Guarding only the first read wouldn't have been enough -
the very next call would still crash on the same NULL pointer. Since none of the fields this block
writes (`this->[80..140]`) are touched again before the function's own next, unrelated section (a
per-element list loop at `0x2F0AA0`), the fix skips the WHOLE orientation-basis block together on a
bad pointer - `cmp r7,#0xC0000000` / `blo 0x2F0AA0` / `ldr r3,[r7]` (replay) / `b 0x2F0A40` (branch
back into the function's own untouched next instruction). Cave (`0x0098DC38`) is the next 16 bytes
in the same fix #90/#92/#93/#94 `.rodata` gap. Objdump/capstone-confirmed and `qemu-arm`
harness-verified with two vectors (`tests/fix95_invalid.cfg`, `tests/fix95_valid.cfg`).

Touches only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes check, so
IG2/Haegemonia/Colin McRae Rally are unaffected by construction. `versionCode` bumped to 96.
Delivered as a fresh full-project source zip per the established delivery convention; not yet
real-device tested as of shipping.

**Round 95: 0.97-allowHudElementNullGuard - fix #96, found via one proactive batch scan (not a
real-device crash), then stopped per the user's explicit request.** After three fixes in a row
(Rounds 92-94) each found only from a fresh real-device crash, the user asked for one big proactive
round covering the whole bug pattern at once instead of continuing to chase these one crash at a
time. Ran a systematic disassembly scan of the entire `.text` section for the same confirmed shape
(`ldr Ra,[Rb,#452]` immediately followed by an unconditional dereference of `Ra`, the field fixes
#94/#95 already guard) and found 18 additional candidate sites beyond the two already fixed.

Triaged all 18 by hand rather than patching blindly, since offset `0x1c4`/452 is also used 980 times
in this binary for completely unrelated struct fields:

- **One false positive, left untouched**: `Hud::OnUpdate(int)` at `0x00117BE8` re-reads `this->[452]`
  into `r3`, but an *earlier* instruction in the same block (`0x117B90`) already loaded the identical
  field into `r2` and branched away on `cmp r2,#0 / beq 0x117C84` - since nothing between the two
  reads writes to that field or calls anything, `r3` at `0x117BE8` is provably the same, already
  non-NULL value. No fix needed or made here.
- **One real, fixed this round**: `GameWorld::AllowHudElement(im::Symbol const&)` (`0x002FF4EC`)
  reads `this->[452]` FIVE separate times across one contiguous block with no NULL check anywhere
  before it - three vtable dispatches (`0x2FF624`/`0x2FF64C`/`0x2FF674`, each followed by a `strcmp`
  against a different symbol name) plus two direct non-virtual calls (`Playable::IsRunning()`/
  `IsRolling()` at `0x2FF69C`/`0x2FF6AC`) that pass the same pointer straight through as `this` with
  no guard at all. Since nothing in this span writes to `this->[452]` or to `r6`, a single guard at
  the FIRST use (`0x2FF62C`) covers all five: `cmp r3,#0xC0000000` / `blo 0x2FF6BC` (skip the entire
  block, landing on the function's own next, unrelated section) / `ldr r3,[r3]` (replay) /
  `b 0x2FF630` (branch back into the function's own untouched next instruction). On a NULL sub-object
  this section can only ever *disallow* the hud element based on conditions that can't be true for a
  nonexistent object, so skipping it entirely is exactly what the original logic implies. Cave
  (`0x0098DC48`) is the next 16 bytes in the same fix #90/#92/#93/#94/#95 `.rodata` gap.
  Objdump/capstone-confirmed and `qemu-arm` harness-verified with two vectors
  (`tests/fix96_invalid.cfg`, `tests/fix96_valid.cfg`).
- **14 candidates found but deliberately NOT fixed this round**, to keep this batch reviewable and
  because none of them have ever actually been observed to crash on a real device: `0x157f24`,
  `0x163874`, `0x1e25a0`, `0x23ad5c`, `0x2fd268`, `0x300b54`, `0x30388c`, `0x303938`, `0x30dca8`,
  `0x314554`, `0x31cd74`, `0x31ce60`, `0x894a28`, `0x89c0d8`. These either overlap with more complex
  control flow than the two sites above, need more careful per-site tracing before a guard can be
  designed safely, or weren't reachable from any real-device test seen so far. Recorded here so a
  future session (or a future real-device crash at one of these exact addresses) has a head start,
  without this round turning into an unbounded rewrite of the whole binary.

Touches only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes check, so
IG2/Haegemonia/Colin McRae Rally are unaffected by construction. `versionCode` bumped to 97.
Delivered as a fresh full-project source zip per the established delivery convention; not yet
real-device tested as of shipping (unlike every earlier fix in this family, this one was never
observed to crash for real - it's preventative, verified with the same capstone+qemu-arm rigor as
every other fix here). Per the user's explicit choice, this is the end of this proactive round -
no further Mass Effect: Infiltrator crash-hunting planned unless asked again.

**Round 96: 0.98-onUpdateSubsystemDispatchNullGuard - fix #97, found on a real-device 0.97 test.**
0.97 (fix #96) real-device tested clean - the `AllowHudElement` block didn't crash - before hitting a
new, ordinary `SEGV_MAPERR`:

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x00000000
Cause: null pointer dereference
#00 GameWorld::OnUpdate(int)+504
#01 LayerGameWorld::OnUpdateEvent(im::UpdateEvent const&)+180
... (im::M3GApplication::OnRunLoopTick() - the game's normal per-frame update tick)
```

Unlike fixes #92-#96 (each inside a callee reached FROM `OnUpdate`), this crash is inline in
`GameWorld::OnUpdate(int)` (`0x0030DA38`) itself, between the `VFX::Update`/`UpdateTriggers` calls and
`UpdateEnvironments`: `ldr r3,[r4,#76]` reads a GameWorld sub-object pointer at `this`+76 (`0x4c` - a
DIFFERENT field from the `this`+452 one fixes #94/#95/#96 guard), then `ldr r3,[r3]` unconditionally
reads its vtable before a virtual dispatch (vtable+0x14). Register dump confirms `r0`/`r2`/`r3` are all
`0x0` - `this`+76 is NULL, same "per-frame subsystem pointer not always constructed" shape as every fix
since #92. The next instruction (`mov r0,r4`) overwrites the dispatch's return value unconditionally, so
the fix skips just the dispatch on a bad pointer and resumes right there: `cmp r3,#0xC0000000` /
`blo 0x30DC3C` / `ldr r3,[r3]` (replay) / `b 0x30DC34` (branch back into the function's own untouched
`mov lr,pc`). Cave (`0x0098DC58`) is the next 16 bytes in the same fix #90/#92-#96 `.rodata` gap (~184B
still free after this). Objdump/capstone-confirmed and `qemu-arm` harness-verified with two vectors
(`tests/fix97_invalid.cfg`, `tests/fix97_valid.cfg`, both PASS).

Touches only `libMassEffect.so` by filename, guarded by the usual exact-original-bytes check, so
IG2/Haegemonia/Colin McRae Rally are unaffected by construction. `versionCode` bumped to 98. Delivered
as a fresh full-project source zip per the established delivery convention; not yet real-device tested
as of shipping.

**Round 97: 0.99-ig2DirectVideoFolder - Imperium Galactica 2, cutscene/video playback from a plain
external folder (not a crash fix - a new feature).**

IG2's Android port starts and runs fine but never plays any cutscene/intro video - the classic Google
Play "expansion file" (`.obb`) video-delivery mechanism these old ports relied on is dead today (no
Play Store install, no downloader). The user already owns dubbed (Hungarian) `.mp4` files for every
clip and wanted a way to make the game play them without needing to touch the game's own encrypted
`.obb` at all.

Traced the full pipeline: despite Bink decode functions being present in the binary, the Android port
does NOT play raw Bink - `command_OpenBink(ig2_command_t&)` (`0x0009A358`) strips a requested clip
name's extension and rebuilds it as `"anim/mpeg4/" + basename + ".mp4"`, then hands that to
`ig2screen::openAnim` -> `videoentity::initvideo()` -> `Loader_Video_Entity` ->
`AssetHelper::OpenStream`/`OpenRawFile` - the exact same generic asset-loading pipeline used for every
other game asset. Actual playback is standard MP4/H.264 via `libSDL_ffmpeg.so`, not Bink.

`main.140.com.digitalreality.ig2.obb` turned out not to be a plain ZIP - a custom package format, and
empirically high-entropy (~8.0 bits/byte) essentially end-to-end (two apparent plaintext-extension
hits from an initial scan turned out, on closer byte-level inspection, to be coincidental noise, not a
readable directory), confirming real AES-256 encryption (LibTomCrypt/Rijndael,
`StringEncoder::aes256_encdec` present in `libig2ps.so`) rather than a plaintext-directory layout.
Extracting the actual key with no guarantee it's even static was judged not worth attempting.

Instead, disassembly of `AssetHelper::OpenRawFile(std::string const&, char const*)` (`0x00222FAC` -
the function every package lookup ultimately goes through) revealed the game's package system already
has a built-in escape hatch: if the requested path starts with the literal 8-character prefix
`":direct:"` (confirmed string literal at `0x002D2046`), the function skips the encrypted-package
search entirely, strips that prefix (`path.substr(8)`), and calls plain `fopen()` on the remainder -
no decryption involved, by design already present in the shipped binary.

The fix changes exactly ONE 4-byte literal-pool word: `command_OpenBink` builds its video path from a
prefix string reference (`ldr r1,[pc,#180]` at `0x0009A3B6` + `add r1,pc` at `0x0009A3BC`, originally
resolving to `"anim/mpeg4/"` at `0x002D1DE7`) and a suffix reference (`".mp4"`, left untouched). The
literal-pool word at `0x0009A46C` is changed so the same, completely unmodified `add r1,pc`
instruction resolves instead to a new string placed in a code cave:
`":direct:/storage/emulated/0/Android/obb/com.digitalreality.ig2/videos/"`. A clip requested as
`"intro_human.bik"` now resolves to
`:direct:/storage/emulated/0/Android/obb/com.digitalreality.ig2/videos/intro_human.mp4`, which the
game's own unmodified `:direct:` handling opens via a plain
`fopen("/storage/emulated/0/Android/obb/com.digitalreality.ig2/videos/intro_human.mp4", "rb")`. Apps
can always read/write their own `Android/obb/<pkg>/` folder regardless of scoped storage, so no extra
permission is needed - the user just creates a `videos` subfolder inside the game's existing obb
folder and drops flat-named `.mp4` files into it. Any clip not present there fails `fopen()` exactly
like the current "not found" case does today, so untouched clips keep today's exact (silent,
non-crashing) behavior. The `.obb` itself is never read, written, or even opened by this patch.

Cave lives at `0x002FA2B8`, a 222-byte all-zero, non-relocated gap inside `.rodata` (confirmed zero
`.rel.dyn` relocations land in it); only 71 of those 222 bytes are claimed (70-character string + NUL).
Verified with a standalone Python re-implementation of the exact `ldr [pc,#n]` + `add r,pc` PIC address
computation this project has used throughout for every literal-pool trace, confirming the patched word
resolves to exactly the cave address, and by applying the patch logic to the real extracted
`libig2ps.so` and reading back the resulting path string end-to-end.

Touches only `libig2ps.so` by filename, guarded by the usual exact-original-bytes check, so Mass
Effect: Infiltrator/Haegemonia/Colin McRae Rally are unaffected by construction. `versionCode` bumped
to 99. Delivered as a fresh full-project source zip per the established delivery convention. Not yet
real-device tested as of shipping - the user still needs to build/install this version and copy her
44 Hungarian `.mp4` files into a `videos` folder inside the game's `Android/obb/com.digitalreality.ig2/`
folder on the tablet.

**Round 98: 1.00-haegVideoFolder-ig2Fallback-downloadApk - Haegemonia gets the same video-folder
feature, IG2's version gets a fallback-to-original safety net, and the "download APK" button is
back.**

Three changes shipped together this round, requested in the same conversation as follow-ups to Round
97's IG2 video patch:

*Haegemonia: Legions of Iron - external "videos" folder (new feature).* Same underlying engine as IG2
(confirmed via matching `AssetHelper`, `StringEncoder::aes256_encdec`, `rijndael_*`, and `:direct:`
symbols in `libhaegemonia_base.so`), and the same dead `.obb`-video problem. Its video pipeline is
different though: `cGuiHelper::PlayBink(CString)` appends `.mp4` via a custom `CString` class
(`operator+=`, `GetBuffer()`, etc. - likely MFC-style, ref-counted) and checks existence before handing
a plain `std::string` name to `hgmscreen::playAnim`, which stashes it in a pending-clip vector for the
next frame rather than building any single clean, patchable path expression the way IG2's
`command_OpenBink` does. Traced as far as `videoentity::initvideo()`/`Loader_Video_Entity` reading the
clip name from `[videoentity_obj + 0x1b8]`, but couldn't pin down exactly where that field gets
assigned, and the lifetime/ownership semantics of a by-value `CString` parameter under the ARM Itanium
ABI (no destructor call found on the parameter itself inside `PlayBink`) were genuinely unclear - not
a foundation to build a patch on.

Pivoted to something both safer and more general: patch `AssetHelper::OpenRawFile(std::string const&,
char const*)` itself (`0x00378C38`) - the common chokepoint every asset load in the game ultimately
goes through, Bink/video or otherwise. The patch replaces the function's very first instruction (a
`b.w` into a cave) with self-contained logic that replicates the original 4-instruction prologue, then
tries a plain external folder via a REAL `fopen()`: builds `/storage/emulated/0/Android/obb/`
`com.microids.google.HaegemoniaFull/videos/<name>` (reading the incoming path's `char*` data straight
out of the `std::string` object - no C++ method calls, no `CString` involved at all) and calls the
same `fopen@plt`/`SDL_RWFromFP@plt` this function already calls elsewhere. On success, jumps into the
function's own existing epilogue to return the wrapped result. On failure (the common case - file not
present), undoes its own extra stack allocation and jumps back into the ORIGINAL function body right
after the replicated prologue, with every register/stack byte in exactly the state the real prologue
would have left them - a total no-op for every asset that isn't overridden. Cave (`0x0068560C`,
200 bytes) lives in a 2048-byte all-zero, relocation-free `.rodata` gap.

Because this patch calls real libc `fopen()` - unlike every earlier fix in this project, which only
needed the existing register-state harness - the usual `qemu-arm` harness (raw `mmap`'d pages, no real
PLT/dynamic-linker resolution) couldn't exercise it end-to-end. Instead built a standalone, fully real
`arm-linux-gnueabi-gcc -static` binary that extracts just the string-building+`fopen()` logic into a
callable test function, run under `qemu-arm` against both a missing file (buffer content verified byte
for byte, `fopen()` correctly returned NULL) and a real one it created on disk (sanity open succeeded).
Also confirmed via `readelf -l` that `.rodata` shares the same single `R E` (readable + executable)
`PT_LOAD` segment as `.text` in this binary, so cave code placed there is genuinely executable at
runtime, not just readable. Touches only `libhaegemonia_base.so` by filename, exact-original-bytes
guarded as usual. The user's 8 original Haegemonia `.bik` files still need converting to `.mp4` (same
as she already did for IG2's 44 files) before this has any visible effect - separate, user-side prep
work.

*Imperium Galactica 2 - reworked with a fallback (bug fix to Round 97).* Round 97's redirect was
unconditional: if a clip's `.mp4` wasn't yet present in the external `videos` folder, `fopen()` just
failed with no fallback to the ORIGINAL `"anim/mpeg4/"` package-relative path - flagged by the user as
a real gap. Reworked using the same "try external, else fully original behavior" idea just built for
Haegemonia, but placed where it's cheapest for this engine: `command_OpenBink` loads its prefix
pointer via `ldr r1,[pc,#180]` (`0x0009A3B6`) + `mov r2,r7` (`0x0009A3B8`, 4 bytes total, exactly a
`b.w`) right before the `std::operator+(prefix, basename)` call that builds the path (basename already
computed a few instructions earlier by `stringhelper::remove_extension`, sitting in `r7`). The patch
replaces those 4 bytes with a branch into a cave that: builds `/storage/emulated/0/Android/obb/`
`com.digitalreality.ig2/videos/<basename>.mp4` on the stack (reading `basename`'s `char*` data
straight from the `std::string` object in `r7`); probes it with a real `fopen(path,"rb")` (the same
`fopen@plt` `OpenRawFile` already calls elsewhere), `fclose()`-ing it right away since this is only an
existence check; sets `r1` to the Round-97 `:direct:/storage/.../videos/` string if found, or to the
address of the untouched original `"anim/mpeg4/"` literal (`0x002D1DE7`) if not; turns that into the
same PC-relative offset the very next, completely untouched instruction (`add r1,pc` at `0x0009A3BC`)
already expects; replicates the `mov r2,r7` it overwrote; and jumps back into `command_OpenBink` right
at `0x0009A3BA` - everything downstream (both `operator+` calls, `fixup`, `openAnim`, the whole
`AssetHelper` pipeline) is the exact original code, just fed a different prefix constant. Net effect:
clips the player has supplied still play exactly as Round 97 made them; clips she hasn't now fall back
to stock behavior instead of silently failing. Cave (`0x002F41EC`, 296 bytes) lives in a fresh 498-byte
`.rodata` gap - Round 97's old cave/literal-word patch is fully superseded and no longer applied.

Verified the same two ways as Haegemonia above (`objdump`/`capstone` static decode of a proper
`-Ttext=<addr>`-linked cave, plus a real `qemu-arm` execution test of the extracted probe-and-decide
logic against a missing and an existing file). That `qemu-arm` test caught a real bug before it shipped:
an early draft of the cave forgot to back up over the basename's NUL terminator before appending
`.mp4`, which would have silently truncated every rebuilt path down to just the basename - fixed and
re-verified before being written into the shipping patcher.

*"Download APK" button - restored (UI feature).* A `[Optional] Download APK` button sits next to
`[Step 3] Install` now, enabled the same way (once a patch run finishes successfully). It saves the
just-patched APK straight into the device's public Downloads folder via the same `MediaStore`
scoped-storage API `downloadLog()` already used (Android 10+, no storage permission needed) - useful
for keeping a copy, moving it to another device, or sending it on without going through the installer.

All three changes are guarded/scoped independently (game-filename checks for the two native patches,
a plain UI addition for the button), so none can affect the others or any other game.  `versionCode`
bumped to 100. Delivered as a fresh full-project source zip per the established delivery convention.
Not yet real-device tested as of shipping.

**Round 99: 1.01-videoFolderAslrFix - both Round 98 video-folder caves crashed on the real device;
root cause was a systemic PIE/ASLR addressing bug, fixed in both, plus a new emulation-based
verification technique that would have caught it before shipping.** Real-device testing of the 1.00
build crashed both games immediately: IG2's tombstone showed `SIGSEGV`/`SEGV_MAPERR` at
`libig2ps.so+0x2f41f2`, fault address `0x002f4248`; Haegemonia's showed the same signal at
`libhaegemonia_base.so+0x68561c`, fault address `0x00685664`. Both fault addresses landed exactly on
the very first string-address-load instruction of each new cave - and both PCs matched the caves'
link-time addresses unshifted by any runtime load bias, which pointed straight at the root cause.

`libig2ps.so` and `libhaegemonia_base.so` are `ET_DYN` shared objects: Android loads them at a
randomized runtime address (ASLR/PIE), never at their link-time address. Both Round 98 caves used GNU
`as`'s `ldr rX,=SYMBOL` pseudo-op to load the addresses of their own embedded strings and, for IG2,
the address of `orig_epilogue`/`orig_resume`-style jump targets - `=SYMBOL` bakes the raw *link-time*
address into a literal-pool word with no bias correction at all. Every other patch in this project
only ever repoints an EXISTING compiler-generated PC-relative pair (`ldr rX,[pc,#N]; add rX,pc`),
which is inherently bias-safe because the CPU's `pc` value read by `add rX,pc` at runtime is itself
already bias-adjusted - Round 98's caves were the first to compute a brand-new runtime address from
scratch, and that's exactly where the always-safe convention got missed. A second, compounding bug
surfaced while re-deriving the fix: a few named `.word` literal-pool constants (`fopen_plt_addr: .word
0x0008f628`) were referenced the same way (`ldr rX,=fopen_plt_addr`), which loads the *address of the
label*, not its stored numeric content - wrong regardless of ASLR.

Fixed by rewriting both caves so every address that gets dereferenced or jumped to at runtime uses
`ldr rX,[pc,#N]; add rX,pc` (via a `.equ NAME, label - (add_instr + 4)` offset computed at assembly
time), and every PLT call became a direct `blx #<link-time-address>` immediate instead of loading the
PLT address into a register first - architecturally position-independent by construction, and exactly
how the original compiled code already calls these same stubs elsewhere in both functions. One
addressing pattern was deliberately left alone: IG2's cave feeds a link-time constant into the
subtraction that produces the operand for the ORIGINAL, untouched `add r1,pc` instruction it resumes
into (`0x0009A3BC`) - both sides of that specific subtraction are link-time constants, so the bias
cancels out algebraically and no `+pc` fixup belongs there. Two smaller issues came up while rebuilding
the fix: GNU `as`'s `.equ` can't fold an absolute link-time constant against an unresolved
same-object cross-symbol label in one step (fails with "invalid operands (*ABS* and .text sections)
for `-`") - worked around with a `.word 0xDEADBEEF` placeholder, resolved to the correct offset in
Python after linking, and patched directly into the assembled cave bytes. IG2's cave shrank
296 -> 272 bytes and Haegemonia's 200 -> 184 bytes (both net smaller, since a direct `blx` immediate
is more compact than loading a PLT address into a register first).

Verification got a real upgrade this round, specifically to close the gap that let this ship in the
first place: installed the `unicorn` CPU emulation engine and used it to run the actual, fully patched
`.so` file's real deployed cave bytes - not extracted/standalone logic - mapped at a deliberately
nonzero simulated ASLR load bias (`0x40000000` for IG2, `0x50000000` for Haegemonia), starting
execution from the real entry-patch address, with PLT calls intercepted via `UC_HOOK_CODE` register
hooks (writing real Thumb-encoded fake stub bytes doesn't work here - a `blx` immediate always
switches the CPU to ARM state at its target, so hand-placed Thumb bytes get misdecoded as ARM) and
landing addresses checked via hooks that capture and verify register/stack state before stopping
emulation. This is a materially stronger test than the `qemu-arm`-on-extracted-logic approach used for
Round 98 and everything before it: that older method only ever ran fragments with directly-passed
pointers/registers, so it could never have exercised - and therefore never could have caught - a cave's
own broken literal-pool address computation at a genuinely non-zero bias. Both caves now pass this test
for every code path (IG2: probe-found and probe-not-found; Haegemonia: fopen-succeeds and
fopen-fails), with register/stack state cross-checked against what the real untouched original
prologue/resume points would themselves produce. This nonzero-bias unicorn test is now standing
practice for any future cave that computes a runtime address of its own, on top of the existing
`objdump`/`capstone` static decode check. `versionCode` bumped to 101. Delivered as a fresh full-project
source zip per the established delivery convention.

**Round 100: 1.02-videoDebugTrace-TEMP - temporary diagnostic build, not a fix.** Real-device
retesting after Round 99 (following a full uninstall/reinstall - an update-install over the old
build left stale native code in place and reproduced the exact old crash signature, which is why a
full reinstall is now standing practice for every native-code round) confirmed the ASLR fix itself
is solid: both games launch with no crash. But no video plays at all - not even the games' own
original bundled intro video, immediately at launch, where it should appear before the main menu -
and `logcat` shows nothing at all during the attempt: no crash, no permission/AVC denial, no
fopen-related log line, because the existing cave performs no logging on either success or failure
of its file probe. Since passive observation had nothing left to observe, both caves got a
temporary debug trace added: after the existing `fopen()` probe (found or not), the cave now also
opens `.../videos/soa_debug.txt` in append mode and writes one line - `F:<probed path>` or
`M:<probed path>` - reusing the same already-resolved `fopen`/`fputs`/`fclose` PLT stubs and the
same bias-safe `ldr rX,[pc,#N]; add rX,pc` addressing established in Round 99, so this adds a
side-channel file write without touching the verified control flow at all. Both caves grew within
their existing free-space gap (IG2 272 -> 496 bytes, Haegemonia 184 -> 420 bytes) and were
re-verified with the Round 99 nonzero-bias `unicorn` technique, extended to a 2x2 matrix per cave
(probe found/not-found x debug-file-opens/fails) confirming the debug write never disturbs the
original resume/epilogue landing state even when the debug file itself can't be opened. One new
GNU `as` gotcha surfaced while assembling these: with many `.equ`-based `ldr rX,=EXPR` constants,
the automatic literal-pool flush isn't guaranteed to land before an explicit `cave_end` label -
fixed with an explicit `.ltorg` placed right before the first string-literal section, forcing a
single predictable, size-accountable flush point. `versionCode` bumped to 102, `versionName` tagged
`-TEMP` to make clear on-device that this build is diagnostic-only. **This is not intended to ship
long-term**: once `soa_debug.txt` (pulled via `adb pull`, not visible in `logcat`) shows what path
is actually being probed and whether it's found, both caves get reverted to their clean Round 99
form plus whatever real fix the log reveals, and the version bump above gets superseded by that
fix's own.

**Round 101: 1.03-sdlFfmpegRelink-UNVERIFIED - found the real reason no video has EVER played in
either game, going back to before this whole video-folder feature existed, and a first attempt at
fixing it.** The Round 100 debug trace's results came back with both games' 3 launch cutscenes
(`start`/`intro`/`logo`) correctly placed in `videos/fmv/` and found by `fopen()` every time
(`F:` lines) - yet still nothing played. That's the tell: fix #2 (see "What it fixes" above)
unconditionally replaces ANY bundled `lib/armeabi*/libSDL_ffmpeg.so` with a fully no-op clean stub,
for every game this patcher touches, no exceptions - a safe trade-off for Mass Effect: Infiltrator
(where nothing in-game depends on it) that was never revisited once IG2/Haegemonia's own cutscenes
turned out to decode through this exact library. The video-folder feature's file-discovery logic
was never broken; the decoder behind it has been an empty stub this whole time, independent of
where the video files live or how they're named.

Un-stubbing it safely turned out to be its own small research project (`ElfSdlFfmpegRelinkPatcher.kt`,
new). Running the real `libSDL_ffmpeg.so` pulled from both games' APKs through the existing
`ElfTextRelPatcher` logic showed the same `DT_TEXTREL` refusal as any old-NDK library, but neither
game's problem relocations match the "dead `__cxa_finalize` cleanup trampoline" pattern that patcher
safely neutralizes - inspection (`R_ARM_RELATIVE`, values pointing into `.text`) showed a real,
15-word function-pointer table (almost certainly an internal codec/format registration list) in
both files, live code, not something that can just be voided. `ElfTextRelPatcher`'s only other
option, making that segment writable, was already proven to hard-crash `dlopen()` on this exact
tablet/Android version once before (Colin McRae Rally's Unity libraries, W^X refusal - see
`ElfTextRelPatcher`'s own doc comment) - so applying it here would trade silent no-video for an
outright crash, unacceptable for two top-priority games.

The actual fix: ELF segments only need page alignment, not section alignment, so the single
combined code+rodata `PT_LOAD` segment both files use can be split at a page boundary into two -
everything before the split stays exactly as it was (still executable, still covers all real code),
and everything from the split onward (confirmed, via each file's own section header table, to be
`.rodata` the whole way - never flagged `SHF_EXECINSTR`) becomes a NEW segment marked writable but
NOT executable. The problem relocations end up physically inside memory that's writable but never
executable, so they write successfully with no TEXTREL refusal needed, and no segment is ever both
writable and executable, so the separate W^X check has nothing to object to either - structurally
the same trick modern NDK toolchains already use on purpose (`GNU_RELRO` segments), applied by hand
to this one old library's one problem region. Adding a 9th program header needs more room than the
existing header table has (packed with zero slack against the next segment in both files), so the
whole 9-entry table is rebuilt and appended past the original end of the file instead, with
`e_phoff`/`e_phnum` updated to point at it - safe, since the dynamic linker reads program headers
via a direct file read before setting up any memory mappings at all.

Verified so far: the Kotlin patcher's output is byte-for-byte identical to an independent Python
prototype of the same logic, for both games' real files; post-patch, a from-scratch check of the
result confirms zero relocations land outside a writable segment, zero `PT_LOAD` segments are
simultaneously writable and executable, and no segment ranges overlap - the exact three structural
conditions bionic's refusal logic (both the TEXTREL gate and the separate W^X gate) actually checks
for. `ElfSdlFfmpegRelinkPatcher` only ever applies when every one of its safety gates passes
(single touched segment, independently re-verified code-free via the section header table, expected
`p_offset`/`p_vaddr`/`p_filesz`/`p_memsz` shape); anything else falls straight back to the old,
already-safe stub replacement, so this can never regress a game this technique doesn't cleanly
apply to. **What's NOT yet verified: this environment has no way to actually invoke bionic's
`dlopen()`**, so whether the real device's dynamic linker accepts this patched library the way the
structural analysis predicts is unconfirmed - `versionName` tagged `-UNVERIFIED` for exactly that
reason. `versionCode` bumped to 103. If this works, the game should get its very first real
cutscene playback ever on this device; if `dlopen()` rejects it for some reason this analysis
missed, the expected failure mode is the game failing to LAUNCH at all (not just silent no-video),
so real-device testing after this build is the load-bearing next step, watched closely.

**Round 102: 1.04-videoPlaybackConfirmed - real-device confirmation for both games, two more
real-world bugs found and fixed along the way, and the diagnostic scaffolding cleaned back out.**
Round 101's segment-split un-stub was confirmed working on the real device for Haegemonia first: a
video actually decoded and played for the first time ever on this tablet - the original English
cutscene, not yet the player's Hungarian dub. That gap turned out to be two separate, unrelated
bugs stacked on top of each other, found and fixed one at a time by working through the evidence
rather than guessing:

First, a file-discovery bug, not a decoder bug. `adb shell ls -la` on the game's `videos` folder
showed the folder itself owned by a different UID (`u0_a271`) than the `fmv` subfolder and every
file inside it (`u0_a98`) - the mandatory full uninstall/reinstall this native fix needs (see Round
99's own note on why an update-install isn't enough) had assigned Haegemonia a new UID, but the
`videos` folder itself was apparently NOT wiped by the uninstall and stayed locked
(`drwxrws---`, no access outside its original owner/group) to the now-defunct old UID - blocking
even a `readdir()`/`open()` from the new install's process regardless of what was correctly placed
inside. `chmod 755` on that one folder confirmed the fix immediately (soa_debug.txt's `fmv/*.bik.mp4`
lines went from consistently `M:` to consistently `F:`). Worth watching for again: any future
uninstall/reinstall of either game could in principle repeat this if the OS reuses the old `videos`
folder rather than recreating it - not yet seen a second time, but not structurally ruled out either.

Second, once file-discovery was fixed, the player's own Hungarian-dubbed clips played back with no
audio (or badly desynced audio/video) - looked at first like a codec-compatibility question for the
old bundled decoder, but turned out to be much simpler: the free web-based Bink-to-mp4 converter
she'd used silently truncated every output to ~30 seconds (confirmed by matching file sizes against
clearly `_part`-suffixed leftovers in her Downloads folder), regardless of the source clip's real
length. The fix didn't need any new patcher code - her PC's own `ffmpeg` (reached directly through
the linked-device shell for this app) has a native Bink demuxer/decoder built in (`bink`/`binka`),
so all 8 of her original `.bik` files were re-encoded straight from the source, full length, with
`libx264` (`-profile:v baseline -level 3.0 -pix_fmt yuv420p`) video and AAC-LC audio (`-c:a aac -ar
44100 -ac 2`) - Constrained Baseline chosen deliberately for maximum compatibility with an
~2013-era decoder rather than for size or quality. Both games' cutscenes now play correctly, in
Hungarian, with matched audio, end to end on the real device - confirmed separately for Haegemonia
and then for Imperium Galactica 2 (which needed nothing more than re-copying its already-correct,
years-old 43-file `videos/` folder back onto the tablet after the same reinstall).

With both games fully confirmed, this round also reverts the temporary diagnostic scaffolding:
`ElfHaegemoniaDirectVideoFolderPatcher.kt` and `ElfImperiumGalactica2DirectVideoFolderPatcher.kt`
are back to their clean, pre-Round-100 form (no `soa_debug.txt` trace-writing - that was always
meant to be temporary, and Haegemonia's especially could grow multi-megabyte over a longer play
session), and `versionName` drops the `-UNVERIFIED` tag Round 101 shipped with, now that real-device
confirmation is complete for both top-priority games. `versionCode` bumped to 104. This closes the
entire video-playback saga that ran from Round 97 through here.

**Round 103: 1.05-saveLoadCrashFix - Haegemonia save/load crash, fix #105, found on a real-device
1.04 test.** With video playback confirmed working for both games, the next real-device test
surfaced a different crash: saving the game and then loading that very save crashed it every time -
reproduced even without fully exiting the app first, on at least one save slot (slot 5), with other
slots suspected too. A full Logcat native tombstone (package filter + `tag:DEBUG`, horizontal-scrolled
to recover the truncated fault address and backtrace) pinned the fault to
`cSaveGame::SetGameState(cGameInfo*, cWorld*)+492`, `SIGSEGV`/`SEGV_MAPERR`, fault addr `0x0`, "null
pointer dereference".

Objdump-disassembling `SetGameState` around the fault showed the function checking a record-type tag
(`cmp r0,#1` / `bne.w 0x29e1a0` - its own existing "this record doesn't apply, skip it" exit for a
non-matching type), then, when the type DOES match, loading a sub-record pointer off `cGameInfo`
(`ldr r0,[r4,#0]`) and immediately dereferencing it (`ldr r5,[r0,#0]`) with no NULL check at all. The
crash register dump confirmed `r0 == 0x0` at the fault - this specific sub-record is legitimately
absent for whatever save format or game state produced the crashing saves, same family as this
project's long-running "a sub-object pointer isn't always populated when a caller assumes it always
is" pattern seen throughout the Mass Effect fixes, just in Haegemonia's own save/load code this time.

The fix (`ElfHaegemoniaSaveGameNullGuardPatcher.kt`) redirects the two instructions right after the
type check into a small cave that replicates them, adds the missing NULL check, and on NULL reuses
the function's own pre-existing type-mismatch exit (`0x29e1a0`) as the safe bail-out - exactly the
same "skip this record" behavior the function already has for a type that doesn't match, just now
also for a type that matches but carries no data. Cave lives 18 bytes into the same `.rodata` gap
(`0x006856c4`) the Round 98 Haegemonia video-folder fix already vetted, claiming the next free bytes
after that fix's own 184. Since the safe-exit target sits a little over 4MB from the cave - outside
Thumb-2's +-1MB conditional-branch range - the cave uses the standard short-conditional-branch
skipping a long unconditional one trick rather than a single `beq.w`/`bne.w`. Every branch involved
is a plain PC-relative Thumb-2 branch (no absolute-address literal anywhere), so the fix is ASLR-safe
by construction, same discipline as every fix since Round 99's ASLR bug.

Verified two ways against the real, extracted `libhaegemonia_base.so`: objdump-disassembled both the
redirect and the full cave after patching (every branch resolves to exactly the intended target), and
- new for this fix - run under the `unicorn` CPU emulator directly against the patched binary, mapped
at a deliberately nonzero simulated load bias, entering at the real faulting instruction for both a
NULL and a non-NULL scenario: the NULL case reaches the function's safe exit with no fault, and the
non-NULL case reaches the resume point with the guarded value correctly loaded, confirming the
original behavior is unchanged whenever this NULL case doesn't occur. `versionCode` bumped to 105.
Still needs real-device confirmation: save in the previously-crashing slot (and ideally another slot
too, since the root cause isn't slot-specific) and confirm loading it no longer kicks out of the game.

**Round 104: 1.06-saveDebugTrace-TEMP - temporary diagnostic build, not a fix.** A new bug thread
opened after 1.05 shipped: Haegemonia loses a save after a FULL force-close + reopen (not just
backgrounding) - reproduces every time, slot shows completely empty, confirmed distinct from the
already-fixed Round 103 load crash. The user then reported Imperium Galactica 2 has what looks like
the same symptom, and separately noted she doesn't see any save file get created for IG2 even by
looking directly on the tablet - though on Android 11+, Android hides another app's own
`Android/data/<pkg>` directory from every other process (file managers, `adb shell` without root,
Device Explorer) by design, so "I don't see it" there doesn't yet distinguish "never written" from
"written, but invisible to anything except the owning app".

Disassembling `DataManager::GetDataDir(DataManager::StorageTypeType)` - the function both games' save
code calls with `StorageType=1` - in both `libhaegemonia_base.so` and `libig2ps.so` found the two
games share byte-identical logic here (same underlying Digital Reality engine): it resolves its
directory via `Environment.getExternalStorageDirectory()` + `File.getPath()` (confirmed by literal
class/method-name strings in both binaries: `android/os/Environment`, `getExternalStorageDirectory`,
`java/io/File`, `getPath`), then appends `"/Android/data/" + <package name> + "/files"` - i.e. the
correct, scoped-storage-safe, no-permission-needed app-private directory, not some risky raw
shared-storage path. That directory is normally as durable as internal storage, so a code-level
explanation for saves vanishing on a cold restart isn't obvious from reading `GetDataDir` alone, and
logcat is no help (this project's established finding: neither game's file I/O logs anything).

Shipped a TEMPORARY diagnostic build instead, same technique as the earlier `videoDebugTrace-TEMP`
round: `ElfHaegemoniaSaveDebugTracePatcher.kt` and `ElfImperiumGalactica2SaveDebugTracePatcher.kt`
(new) hook the real player-triggered save/load entry points in each game (Haegemonia:
`cSaveGame::saveData`/`loadData`; IG2's save system is plain C-style rather than a class, so the
hooks sit on `SaveScenario`/`LoadSavedGame` instead, calling the existing `GetSavePath()` wrapper).
Each hook replicates its target's real original first instruction, preserves all four incoming
argument registers across its own work with a balanced push/pop, calls the completely untouched
`GetDataDir`/`GetSavePath` itself, and appends one line - `"SAVE dir=<path>"` or `"LOAD dir=<path>"`
- to `.../Android/obb/<pkg>/soa_save_debug.txt` (the `Android/obb/<pkg>` location, unlike
`Android/data/<pkg>`, is already confirmed visible/writable by the player's own file manager from
the earlier video-folder work) before rejoining the original function unchanged. A debug file that
can't be opened is a pure no-op either way - real save/load logic is unaffected.

Verified with the same discipline as every fix since Round 99: every instruction in all four new
caves decodes cleanly under `capstone`, and all four hooks were run end-to-end under `unicorn` with
each whole patched module mapped at a deliberately nonzero simulated load bias, entering at the real
redirected entry point, for both a "debug file opens" and "debug file fails to open" scenario per
hook (8 scenarios total) - in every case execution lands exactly on the target function's own
untouched next instruction with all four argument registers and the stack pointer in exactly the
state the real original prologue alone would have left them. `GetDataDir`/`GetSavePath` themselves
are not re-verified (completely unmodified, already proven by every save that has ever worked within
a session) - the emulation stubs their entry to isolate and verify only the new code this patch
adds. Caves claim fresh, independently-confirmed all-zero, non-relocated `.rodata` space in each
binary (Haegemonia: the remainder of the `0x006856c4`-adjacent 2048-byte gap Round 98/103 already
use, two new hooks totalling 362 bytes, ~1480 bytes still free; IG2: a brand-new 471-byte gap at
`0x002eb843`, distinct from the Round 98 video-folder gap, two new hooks totalling 329 bytes).
`versionCode` bumped to 106, `versionName` tagged `-TEMP`.

**Not intended to ship long-term.** Once `soa_save_debug.txt` (pulled per-game, won't show in
`logcat`) shows what `GetDataDir` actually resolves to across a save and a post-force-close-reopen
load for both games, both diagnostic patchers get removed and superseded by whatever real fix (or,
if the logged path turns out to be consistent both times, whatever different investigation thread)
the logs point to.

## Verifying a fix without a real device

Every native-code fix from this point on is checked two ways before being shipped: the existing
`objdump`-based static check (every branch's resolved target address confirmed by hand), and a
small `qemu-arm`-based test harness (ARM user-mode CPU emulation - no Android emulator, no KVM/GPU
needed) that loads the actual patched bytes at their real addresses, sets CPU registers to match
either a real crash log or a synthetic valid-pointer test, jumps in, and confirms execution reaches
the intended outcome with no fault. This catches things static reading alone can miss (a
mis-encoded branch, a clobbered register) but does not replace real-device testing - it can only
check a fix's own machine-code correctness against a known or plausible input, not discover a new
crash site the game hasn't reached yet.

## Testing on an emulator (BlueStacks) before the real device

Copying every build to the tablet to test is slow, so BlueStacks (a PC
Android emulator) can be used as a first, fast filter: if a build doesn't
even launch or dies immediately there, that's useful signal without a
device round-trip. Once it runs cleanly on BlueStacks, switch to the
tablet for the real test - **BlueStacks results don't reliably transfer
to the tablet for the native-crash chasing this project is mostly about**,
because several fixes (#48 onward) key off address ranges empirically
observed on the tablet specifically (its heap pointers, its
`.data.rel.ro` section bounds, its zero-ASLR-bias load address) - a
different device/emulator will very likely have different addresses
there, so a BlueStacks crash's exact registers aren't a reliable source
to fix from, only the tablet's are.

## What it fixes

Same fixes as the PC tool (see its
[`docs/case-study.md`](../apk-compat-patcher/docs/case-study.md) for the
full background on all of these):

1. **targetSdkVersion too low to install at all** on modern Android -
   raised to 30 via the same minimal, in-place binary manifest patch
   (only the 4-byte integer touched, nothing else in the manifest
   changes).
2. **A bundled `libSDL_ffmpeg.so`** (32-bit ARM) with ELF text
   relocations, which the linker refuses to load once targetSdkVersion is
   23+ - replaced with the same clean, position-independent stub used by
   the PC tool. Intro/cutscene videos are skipped; gameplay does not
   depend on this library.
3. **The bundled Moga game-controller SDK** using a forbidden implicit
   Intent - wrapped in a try/catch, same as the PC tool.
4. **The known `AdManager.getMessage()` ad-SDK crash chain** - wrapped in
   a try/catch around the whole method, same as the PC tool.
5. **Any other background-thread crash from a dead/legacy SDK** (ad
   networks, analytics, anything from 2011-2015 calling into classes
   modern Android removed) that fix #4 doesn't specifically cover - a
   silent default `Thread.UncaughtExceptionHandler` is installed as early
   as possible (the entry Activity's `<clinit>`, right where the
   `SDL_ffmpeg` fix already hooks in) that swallows exceptions on any
   thread *except* `main`, so a background SDK dying doesn't take the
   whole app down with it, while a real bug on the main thread still
   crashes normally and stays visible. Same mechanism as every other dex
   fix here: a new `.smali` file, `SilentUncaughtHandler.smali`, is
   written straight into the decoded tree alongside the ones baksmali
   produced, and `smali` reassembles the whole tree together - no
   low-risk/unproven dexlib2 builder-API code needed, since this pipeline
   already round-trips through smali text either way.
6. **ELF text relocations in ANY native library, not just `libSDL_ffmpeg.so`**
   (`ElfTextRelPatcher.kt`, new/beyond the PC tool) - the same "linker
   refuses to load this on targetSdkVersion 23+" problem as fix #2, but
   fixed in place rather than replaced, since this covers libraries that
   can't be stubbed out - most importantly a game's own engine code (e.g.
   Unity's `libmain.so`/`libunity.so`/`libmono.so`). Recognizes the
   specific dead C++ cleanup trampoline that causes this in practice and
   disables just that (`bx lr` + voided relocations), never marking a code
   segment writable - so it doesn't trip Android's separate "writable and
   executable segment" refusal the way a naive fix would. Falls back to
   the older make-it-writable approach, with a clear warning, for any
   relocation that doesn't match the recognized pattern. See "Round 6"
   above for the real crash this closes and the full safety argument.
7. **A broken `DT_NEEDED` entry pointing at a dead absolute build-machine
   path instead of a plain library name** (`ElfNeededPathPatcher.kt`,
   new/beyond the PC tool) - rewrites the string in the library's ELF
   string table down to just its basename (e.g. `libc.so`), in place, so
   the linker can actually find and load the real system library it was
   always supposed to mean. See "Round 7" above for the real crash this
   closes.
8. **An activity that gets destroyed and recreated by a modern
   config-change-happy device shortly after launch**, because its own
   `android:configChanges` declaration predates config aspects (screen
   layout, UI mode, smallest-screen-size, density, layout direction) that
   didn't exist as concepts when the game was built
   (`ManifestPatcher.widenActivityConfigChanges()`, new/beyond the PC
   tool) - ORs those bits into whatever `configChanges` value an activity
   already declares, in place. Only applies to activities that already
   declare *some* `configChanges` value; doesn't add the attribute to ones
   that declare none at all. See "Round 7" above for the real symptom this
   targets and why it's a hypothesis rather than a confirmed fix.
9. **An old EA "Plasma" SDK unconditionally binding to the Samsung Galaxy
   Apps IAP service** via a forbidden implicit `Intent` - crashes on
   Android 5.0+, and always on a non-Samsung device
   (`DexPatcher.kt`'s `initializeIAP()` wrap, new/beyond the PC tool) -
   wraps the whole method in a try/catch, matched by method name (not a
   fixed file path) so it applies to any game bundling this same SDK. See
   "Round 7" above for the real crash this targets.
10. **An `android.max_aspect` meta-data value left over from a 2011-2013
    phone-era aspect-ratio ceiling**, which a modern device's platform
    code can use to letterbox/pillarbox the app's window down to a small,
    centered box instead of filling the screen
    (`ManifestPatcher.widenMaxAspect()`, new/beyond the PC tool) - raises
    the declared ratio to effectively "no limit," in place, the same
    overwrite-an-existing-value technique as fix #8. Only applies to
    activities/apps that already declare this meta-data; doesn't add it
    to ones that don't. See "Round 7" above for the real logcat evidence
    behind this, the `TYPE_FLOAT`-not-a-string wrinkle in how it's
    stored, and why this is an evidence-backed hypothesis rather than a
    confirmed complete fix (an OEM compatibility layer may also be
    involved, independent of anything patchable in the APK). **Retested
    on the real device and NOT sufficient on its own** - see "Round 7"
    for what the retest showed and where the investigation stands.
11. **EA's shared "eamobile" download/DLC-check SDK reading the device's
    IMEI**, which modern Android (10+) permanently refuses to any
    non-system app
    (`DexPatcher.kt`'s `com/eamobile/download/DownloadActivity.init()`
    wrap, new/beyond the PC tool) - wraps the whole method in a
    try/catch, scoped to that fixed shared-SDK file path like the Moga
    and AdManager fixes. See "Round 7" above for the real crash this
    targets.
12. **That same EA "eamobile" download SDK missing an HTTP read
    timeout**, which can hang the manifest-fetch request forever if the
    old download server's domain still resolves/connects but never
    answers (`DexPatcher.kt`'s `patchEaHttpPostReadTimeout()`, new/beyond
    the PC tool) - inserts the missing `HttpConnectionParams.setSoTimeout(...)`
    call next to the existing `setConnectionTimeout(...)` call, unscoped
    like the ffmpeg/`initializeIAP()` fixes since this is shared EA SDK
    code. Does not make missing game content download successfully -
    turns an infinite silent hang into a fast, visible failure instead.
    See "The real cause: this game isn't fully inside the APK at all"
    above for the full evidence chain.
13. **The EA download SDK's `getAndroidUniqueId()` calling
    `TelephonyManager.getDeviceId()` with no try/catch of its own** -
    the actual, confirmed cause of the Mass Effect hang, superseding
    fix #11 (which wrapped the wrong thread and never actually caught
    this) (`DexPatcher.kt`'s `wrapWholeMethod(text,
    "getAndroidUniqueId\\(", "androidid")`, new/beyond the PC tool) -
    unscoped/method-name-matched like `initializeIAP()`. See "Round 8"
    above for the full trace that pinned this down (a `debug.enable`
    marker file that unlocks the SDK's own silent logging, plus a real
    crash log showing the exact call chain and the background thread
    it runs on).
14. **The same EA download SDK's use of the pre-API-28 Apache HTTP
    client, which Android removes from the classpath once
    `targetSdkVersion` is raised to 28+** (a regression this project's
    own fix #1 introduces for any old SDK still using it) - throws
    `NoClassDefFoundError` on `DefaultHttpClient` the moment
    `sendHttpPost()` actually runs, which is what fix #12 was written
    to reach but couldn't (`ManifestPatcher.kt`'s `addUsesLibrary()`,
    new/beyond the PC tool) - inserts `<uses-library
    android:name="org.apache.http.legacy" android:required="false"/>`
    into `<application>`, via a real AXML-writing library (ARSCLib)
    rather than this file's own hand-rolled in-place-only byte patcher.
    Added unconditionally (harmless no-op for any APK that doesn't use
    these classes). See "Round 9" above for the crash log and the
    verification (two real manifests, cross-checked with `apktool`).
15. **That same EA download SDK insisting on contacting a now-DNS-dead
    server on every launch, even when complete local content is
    already present** (`DexPatcher.kt`'s new `forceReturnTrue()`,
    forcing `shouldSkipUpdateCheck()` to always return `true` - new/
    beyond the PC tool, and the first fix in this whole project that's
    a genuine behavior change rather than a crash guard) - narrowly
    scoped so it only ever takes effect after local assets are already
    confirmed present, per the SDK's own decompiled logic. See "Round
    10" above for the `UnknownHostException` that proved the server is
    permanently gone, and the kotlinc-harness + full `apktool b`
    reassembly verification.
16. **`AssetManager.assetsFoundLocally()` forced to always return `true`**
    (fix #20, same `forceReturnTrue()` primitive as #15 above) - after
    real-device diagnostics (fixes #16/#18/#19, all superseded by this
    one) confirmed it returns `false` with no exception logged and never
    even reaches the per-file check, despite every one of 1180 listed
    files independently confirmed present via `adb shell` and surviving
    a full device reboot, this stops chasing the exact remaining cause
    and just trusts already-placed local content directly - the same
    "let the game load what it can already see on disk" outcome fix #15
    forces one step later, forced one step earlier. See "Round 14" above
    for the full real-device evidence chain. Trade-off: unlike fix #15,
    this also bypasses the actual per-file completeness check, so it
    assumes the local content really is complete - true here
    (independently verified) but not guaranteed for every user.
17. **`AssetManager.isAssetVersionCompatible(String)` forced to always
    return `true`** (fix #21, same `forceReturnTrue()` primitive again) -
    fix #20 unblocked the download screen but the very next real-device
    log showed `getLocalAssetVersion()` returning `null` for the same
    underlying reason `assetsFoundLocally()` did, which then crashed
    `isVersionLower()`/`normalisedVersion()` with a
    `NullPointerException` on every single rendered frame (this whole
    check re-runs from the game's own render loop), surfacing as a
    permanent freeze at the EA splash screen instead of a one-time
    crash. Forcing the version-compatibility verdict itself to `true` -
    confirmed, by reading `checkLocalAssetVersion()` and its caller, to
    be exactly the branch success already takes - sidesteps the crash
    entirely without needing to hardcode a version string that could be
    wrong for a different game/build. See "Round 16" above for the full
    real-device evidence chain.
18. **`libMassEffect.so`'s own EA Origin/DirtySDK online-service layer
    stubbed out at the native-code level** (fix #22,
    `ElfMassEffectOnlineStubPatcher.kt`, new/beyond the PC tool, and the
    first fix in this whole project that edits compiled ARM machine code
    rather than decompiled Java bytecode text) - forces `ProtoSSLConnect`/
    `SocketCreate`/`SocketControl` and the EA Origin UI/"Galaxy at War"
    widget entry points to report "no network"/never run, mirroring the
    exact same 8 call sites a real, working PS Vita native port of this
    exact game version (confirmed via matching SHA1) already had to
    neutralize to get it running at all. **Real-device tested and this
    theory is now DISPROVEN** (see "Round 20"): the exact same native
    SIGSEGV traced in "Round 18" still happens, byte-for-byte identical,
    with all 8 sites confirmed patched - so whatever posts the internal
    "exit" message `6` is unrelated to networking/EA Origin/Galaxy-at-War
    entirely. Fix kept anyway (harmless, correctly targeted, matches a
    working reference implementation) but does not fix this game's
    remaining native crash. Gated per-site on the exact original bytes
    already matching, on top of the filename check, so it can never
    affect any other game or a different build of this same game.

**Also simplified vs. the PC tool for `libSDL_ffmpeg.so` specifically:**
no ELF detection needed for that one file - any `lib/armeabi*/libSDL_ffmpeg.so`
found is replaced with the stub unconditionally, since replacing a clean
one with the stub is harmless (you just lose intro-video playback you'd
lose anyway if the crash fix weren't needed). Every *other* native library
does get real ELF-level detection now, via `ElfTextRelPatcher.kt` (fix #6)
- there's no `readelf` on a stock Android device, so that's a from-scratch
ELF32 program-header/dynamic-section parser rather than a shelled-out
tool. Also, unlike the PC tool's per-output keystore, this app generates
**one signing key, once**, in Android's own secure KeyStore, and reuses it
for every APK you ever patch with it - so patching an updated version of a
game you already installed just overwrites it, no uninstall needed.

## Building it

1. Install [Android Studio](https://developer.android.com/studio) (any
   recent version).
2. **File > Open** and pick this folder
   (`apk-compat-patcher-android`). Let it sync - this is the step that
   downloads `smali`/`baksmali`/`apksig`/AndroidX from Google's and Maven
   Central's servers, so it needs a normal internet connection (the one
   this project was written *without*).
3. If sync reports a missing/wrong dependency version, that's a quick fix -
   send the exact error text back.
4. Plug in your tablet (enable Developer Options > USB debugging), or use
   an emulator, and hit **Run**. Or **Build > Build Bundle(s) / APK(s) >
   Build APK(s)** to get a plain `.apk` file to copy over and install by
   hand (Android Studio's Run button already signs it with a debug key
   automatically, which is enough to test with - it's separate from the
   in-app AndroidKeyStore signing key this app generates for the *patched
   games* it produces).

## Using it

1. Open the app, tap **APK kiválasztása**, pick your own game's `.apk`
   file.
2. Tap **Patchelés** and watch the log. This can take a little while on a
   big APK - dex decode/re-encode is not instant.
3. When it finishes, tap **Telepítés** - this hands the patched APK to
   Android's normal installer (you'll need "install unknown apps" allowed
   for this app, same as any sideloading).
4. If your device already has an older copy of the same game installed
   with a *different* signature (e.g. the original Play Store version, or
   one patched by the PC tool with a different keystore), you'll need to
   uninstall that first - Android refuses to install over a
   differently-signed app.
5. If anything goes wrong at any step, tap **Napló letöltése** (it saves
   the log to your Downloads folder) and send the log along with a
   description of what happened.

**Checking which build is actually installed:** every fix from #16 on
bumps `versionCode`/`versionName` in `app/build.gradle.kts` (see that
file's comment). To confirm the app on your device is the build you
think it is - useful after re-testing a fresh fix - open **Settings >
Apps > Save old APK > App info**, which shows the installed
`versionName` without needing to rebuild or dig through logs.

The output file is named after your original APK plus a `-rebuilded`
suffix (e.g. `com.digitalreality.ig2_1.40.apk` -> patching produces
`com.digitalreality.ig2_1.40-rebuilded.apk`), not a generic name - so if
you patch more than one game you can always tell which output belongs to
which input.

The app's UI is English-only. It used to also have an in-app
Hungarian/English switch, but that per-app locale override
(`AppCompatDelegate.setApplicationLocales()`) turned out to intermittently
not apply on real hardware - some launches still came up in Hungarian
despite the "default to English" logic having run. Rather than keep
chasing that, the fix is structural: there's only one string resource set
now (`res/values-hu/` was removed entirely), so there's no "wrong"
language left for any resource-resolution timing hiccup to fall into,
regardless of device locale.

Ticking **Advanced settings** reveals four optional, advanced fields
that mirror the PC tool's `--target-sdk` and `--keystore` flags, plus two
independent opt-in behavior toggles: a targetSdkVersion override (leave
blank for the default, 30), a "use my own keystore" toggle that lets you
pick an existing `.jks` / `.keystore` / `.p12` file plus its alias and
password instead of the app's own auto-generated signing key - useful if
you want a patched game signed with a specific identity you already use
elsewhere - and **"Free rotation"** / **"Resizable / multi-window
support"** (see "Free rotation / resizable window" below for what these
actually do, why they're two separate toggles, and why both are off by
default). Leave all four alone for the normal, one-tap flow.

### Free rotation / resizable window (opt-in, both off by default)

These two advanced-settings checkboxes each bake into the patched APK's
manifest the same practical effect as running one of these `adb`
commands against an already-installed game:

```
adb shell am compat enable OVERRIDE_ANY_ORIENTATION <package>
adb shell am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER <package>   # "Free rotation" checkbox
adb shell am compat enable FORCE_RESIZE_APP <package>                   # "Resizable / multi-window" checkbox
```

Those are Android's own per-app compatibility-framework overrides - free
rotation that follows the device instead of the game's own orientation
lock, and forced resizable/multi-window support even if the game never
declared itself resizable. They work great over `adb`, but they're gated
at the platform level (`IPlatformCompat`) to shell/ADB, device
manufacturers, or the user via Settings on Android 14+ - there is no API
an ordinary installed app (including this one) can call at runtime to
flip them for *another* package, on any device, rooted or not. So SoA
can't literally run those commands for you from inside the app.

What it CAN do - and what these checkboxes do - is bake in the documented
manifest-level equivalent directly, so the effect is there from first
launch with no `adb` involved at all: **Free rotation** sets
`android:screenOrientation="user"` on the game's main/launcher activity;
**Resizable / multi-window support** sets
`android:resizeableActivity="true"` on `<application>`. Both are generic
- they work the same way for Mass Effect: Infiltrator, Dead Space, or any
other game you patch, not just one specific title.

Originally shipped (0.87) as a single combined checkbox; **split into two
independent toggles in 0.88** after real-device testing found that
"resizable" specifically - not "free rotation" - is what can make some
OEM launchers (confirmed on this project's own Honor/Magic UI test
tablet) open the game in a smaller floating/windowed view by default
instead of fullscreen. If that happens to you: try leaving **Resizable /
multi-window support** off and using only **Free rotation** - that alone
covers the "let the game follow the tablet's rotation" ask without
opting into the OS's own resizable-window handling.

Two more real limits worth knowing, since these are baked-in manifest
defaults rather than the OS-enforced overrides they mimic: **Free
rotation** only actually rotates if the device's own auto-rotate setting
is turned on (a rotation-locked device stays locked regardless), and a
game that calls `setRequestedOrientation()` itself at startup or in a
menu can still override the manifest value back at runtime - the real
`am compat OVERRIDE_ANY_ORIENTATION_TO_USER` override is enforced by the
OS and can ignore those calls, but a manifest default can't.

Both are off by default and, unlike every other fix in this app, are a
deliberate choice to leave to you rather than applied automatically: they
change how the game actually looks and behaves rather than fixing a
crash or a compatibility gap, and not every game's UI/HUD was designed
with that in mind.

(Also worth knowing: Android 16 (API 36) grants large-screen devices a
similar automatic exemption on its own, but only for apps that themselves
target API 36+, and explicitly *not* for anything declaring itself a game
- so raising this app's `--target-sdk` further doesn't get any game this
project touches that behavior for free. The manifest attributes above are
the only lever that actually reaches a game.)

## Project layout

- `ManifestPatcher.kt` - binary AXML `targetSdkVersion` patch (validated,
  see above).
- `DexPatcher.kt` - baksmali-decode / text-patch / smali-reassemble for
  each `classes*.dex` (patch logic validated; library call surface is the
  main build-risk area).
- `ApkPatcher.kt` - orchestration: reads the original APK's zip, patches
  what needs patching, reassembles a new zip from the ORIGINAL with only
  the touched entries swapped (everything else byte-identical to your
  source APK, same strategy as the PC tool).
- `ApkSigningUtil.kt` - AndroidKeyStore self-signed key + `apksig`
  v1/v2/v3 signing.
- `ElfTextRelPatcher.kt` - from-scratch ELF32 program-header/dynamic-section
  parser and in-place patcher, fixing the "text relocations" `dlopen()`
  refusal in any native library that needs it (see "Round 6" above).
- `MainActivity.kt` - the whole UI: file picker, patch button, log view,
  install button, download-log button.
- `Logger.kt` / `PatcherApplication.kt` - the log file itself, plus a
  crash handler for this app (not the games it patches) so that even an
  unexpected failure leaves a readable file behind instead of just
  vanishing.

## License

MIT - same as the PC tool. The app and its bundled stub library are
original work; they contain no code or assets from any game.

## Round 107 (1.13-haegemoniaSaveDirTest) - EXPERIMENT: Haegemonia save directory moved to Android/obb

Real-device evidence: a save made in a session appears in the in-game Load list (the list file is re-read from disk on every call), but is gone after fully exiting and swiping the game away. GetDataDir(1) = getExternalStorageDirectory() + "/Android/data/" + pkg + "/files". ElfHaegemoniaSaveDirPatcher changes only that .rodata literal to "/Android/obb//" (same length; the doubled slash is a valid path), so saves land in Android/obb/<pkg>/files, visible to adb. No code touched. UI: the "Clear OBB folder" section is now behind an "advanced" checkbox (it also deletes the saves in this build). IG2 unchanged.

Round 107b (1.13-b, same experiment): the user's real-device test showed the 1.13 literal patch IS present in the installed game (verified by pulling the installed APK) but the game never creates Android/obb/<pkg>/files, nor save/ or campaignsave/ (the game expects these subfolders: cStart::InitGame -> DataManager::CreateDir("save"/"campaignsave"), StorageType 1), even after a manually pre-created files/ folder - i.e. the native mkdir/fopen fail silently while the in-session save list (a persistent in-memory set refilled from the index file) still shows the slot. 1.13-b adds org.soa.StorageDiag (hand-written smali, assembled-checked offline), called once per process from SDLActivity.onResume for Haegemonia only: it creates obb/<pkg>/files/save + campaignsave from Java, test-writes a byte into each, calls getExternalFilesDir(null), and writes the outcome to Android/obb/<pkg>/soa_diag.txt.

Round 107c (1.13c-haegemoniaStorageFix): the 1.13b diagnostic showed the app cannot write inside Android/obb at all, so the obb save-dir literal patch (2Z28) is DISABLED again (saves go back to Android/data/<pkg>/files). org.soa.StorageDiag now calls getExternalFilesDir(null) (system creates Android/data/<pkg>/files with correct ownership), mkdirs save/ and campaignsave/ (the sub-folders cStart::InitGame expects), test-writes, and logs the result under logcat tag SoADiag. Hypothesis: native mkdir of these folders was failing silently, so every save write failed while the in-session list (persistent in-memory set) still showed the slot.

**Round 108: 1.14-saveInObb (versionCode 116)** - Save persistence fix for Haegemonia AND Imperium Galactica 2.
Findings: native mkdir of Android/data/<pkg> fails silently on Android 11+ (saves live only in memory; the
Load list is a persistent in-memory set), and the app cannot create directories inside Android/obb either -
but FILE creation inside a folder the user created with a file manager works (proven by the old
videos/soa_debug.txt). Fix: re-enabled the 1.13 literal patch (`/Android/data/` -> `/Android/obb//`, same length)
for libhaegemonia_base.so and libig2ps.so (identical `\0/Android/data/\0/files\0` literal, exactly one hit in each).
Saves go to `Android/obb/<pkg>/files/save` and `.../files/campaignsave`, which the USER creates once with a file
manager (not adb - adb-created folders belong to the shell uid). StorageDiag dex hook switched off
(STORAGE_DIAG_ENABLED=false). The "Clear OBB folder" button also deletes these saves.

**Round 109: 1.15-saveFolderFix (versionCode 117)** - 1.14's obb idea did not work: the real-device `ls` showed the
folders made with a file manager are owned by another uid (u0_a29, group ext_obb_rw, mode 771) so the game
(uid u0_a296) cannot write into them; the logcat also showed the follow-up load crash (cWorld::GetActSolarSystem+12,
NULL world after loading a save that never reached the disk). Same ownership logic explains why adb-created folders
never worked either. Fix: back to the game's ORIGINAL path (Android/data/<pkg>/files/save + campaignsave) but the
folders are created by the app itself through the system API: org.soa.StorageDiag (dex, hooked into
SDLActivity.onResume, smali assembly-checked offline with apktool 2.0.3) calls getExternalFilesDir(null) and mkdirs
save + campaignsave, for Haegemonia AND IG2 (package check extended). The obb literal patch (2Z28) is switched off
(obbSaveDirEnabled=false). No manual folders needed. Result is also logged under logcat tag SoADiag.
