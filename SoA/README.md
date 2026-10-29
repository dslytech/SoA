# SoA 0.1a (Save old APK)

An Android app that tries to repair **your own copy** of an old Android game so it installs and runs on modern Android.
It runs entirely on the device: pick the APK, tap patch, install. No PC, no root.

Hungarian version: [`OLVASSEL.md`](OLVASSEL.md) - part of the [SoA repository](../README.md)

## Which tool do I need?

This is the right tool if your CPU can run **32-bit apps** (32-bit or mixed 32+64-bit). If your CPU is **64-bit only**, use
[`../SoA32to64`](../SoA32to64/) instead. How to check (AIDA64): see the [root README](../README.md#which-tool-do-i-need).

## What it is NOT

- It contains **no game files** (no APK, no .obb, no videos) and does not download any.
- It only edits an APK that **you** pick and writes a patched copy. Do not share patched APKs or obb files, share only the tool.
- It makes no promise: it **tries** to fix any old APK, and some games simply will not work.

## What it does

Typical problems of old APKs (roughly 2011-2015) on modern Android, and what SoA does in the copy it writes:

1. `targetSdkVersion` too old -> raised (default 30, changeable in the advanced settings).
2. ELF text relocations in old native libraries (new Android refuses to load them) -> repaired in place.
3. Crashes caused by dead SDKs (ads, controller libraries, download stubs) -> guarded in the Java code.
4. Optional: free screen rotation, your own keystore for signing.
5. Game specific fixes, applied only when the matching library is found (saves, external video folder, video playback, ...).

## Tested games

Tested on an **Honor Pad X8a** (32+64-bit CPU), Android 16.

| Game | State |
|---|---|
| Imperium Galactica 2 (`com.digitalreality.ig2`) | works, incl. saves and your own videos |
| Haegemonia: Legions of Iron (`com.microids.google.HaegemoniaFull`) | works, incl. saves and your own videos |
| Colin McRae Rally (`com.codemasters.cmrally`) | works |
| Carmageddon (`com.stainlessgames.carmageddon`) | works |
| Dead Space (`com.eamobile.deadspace_full_azn`) | works |
| Mass Effect: Infiltrator (`com.ea.games.meinfiltrator_gamepad`) | works - **data goes into `Android/obb`, not `Android/data`** (see below) |

Other games may or may not work.

## How to use

1. Open this folder (`SoA`) in Android Studio and run it on the device (or build the APK). Requirements: Android Studio, SDK 34, minSdk 26.
2. In the app pick your own game APK -> Patch -> install the result.
3. If the game needs an `.obb` file, copy it to `Android/obb/<game package>/` (for example
   `Android/obb/com.microids.google.HaegemoniaFull/`).
4. If something fails, use the in-app log button and keep the log.

### Mass Effect: Infiltrator - IMPORTANT: put the game data into OBB, NOT into DATA

The game normally keeps its data files in `Android/data/com.ea.games.meinfiltrator_gamepad`. Modern Android does not let
you (or the game) create files in that folder any more, so the game never finds its data - that was the root cause of all the
crashes in the earlier attempts. The patched game therefore looks for its data in the **OBB** folder instead:

1. Patch your own Mass Effect APK with SoA and install the result (remove any older Mass Effect install first - the
   signature differs). It installs like any normal app, no `adb` and no PC needed.
2. Start the game once and close it. This makes the app create its own folders.
3. With the game closed, copy the game's data files into **`Android/obb/com.ea.games.meinfiltrator_gamepad/`**
   (the same files and sub-folders that would otherwise be in `Android/data/com.ea.games.meinfiltrator_gamepad/`).
   **Not** into `Android/data`!
4. Start the game.

The data files are not part of this project. Saves are written to the game's normal `Android/data/<package>/files` folder
(created by the app itself); save persistence for this game has not been tested in detail.

### Optional: replacement / original videos (Haegemonia, Imperium Galactica 2)

The cutscenes inside the obb of these two games cannot be played any more, so cutscenes only play if you supply your own
`.mp4` files. Which language/audio you get depends on the files you put there (for example the original ones
converted from your own game, or your own translated versions). The videos are **not** part of this project.

Put them here (create the `videos` folder by hand, on the device, with a file manager):

- **Haegemonia:** `Android/obb/com.microids.google.HaegemoniaFull/videos/fmv/NAME.bik.mp4`
- **Imperium Galactica 2:** `Android/obb/com.digitalreality.ig2/videos/` - the videos are plain `.mp4` files, no subfolder.

To make `NAME.bik.mp4` from your own `NAME.bik` files use **Haegemonia-bik.mp4converter.exe** (Windows, from the
[Releases](https://github.com/dslytech/SoA/releases) page; source: [`../tools/Haegemonia-bik.mp4converter`](../tools/Haegemonia-bik.mp4converter)): choose the folder with
your `.bik` files, tick **SoA**, press **Convert**; it builds the `Android/obb/com.microids.google.HaegemoniaFull/videos/fmv` path, copy the `Android` folder to the root of the
device storage. Details, SmartScreen note and license: [root README](../README.md#the-video-converter).

### If the game does not start after a reinstall

An old, orphaned obb folder can block the new install (wrong owner). In the app, open **Clear OBB folder (advanced)**, delete
the game's obb folder, then create the folder again **by hand** on the device and copy the obb file (and the `videos`
folder, if you use one) back into it. Folders created by a file manager can be read by the game; the game cannot write into them.

## Documentation

- `docs/DEVLOG.md` - the long development log (every fix, every real-device test). The "1.xx" numbers there are internal
  builds; the first public version was **0.1**; **0.1a** adds Mass Effect: Infiltrator.

## Credits

Developed with the assistance of Anthropic Claude.

Many thanks to **ZailoxTT** for **[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge)**, the great project that makes the
64-bit-only line (SoA32to64) possible. Libraries: see [`../NOTICE.md`](../NOTICE.md).

## License

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) - see [`../LICENSE`](../LICENSE). Free to use, study,
modify and share for personal / noncommercial purposes; commercial use is not permitted. Use it with games you own.
