# SoA32to64 0.1

Experimental, noncommercial helper that prepares **your own copy** of old 32-bit Android games so they can run on a
**64-bit-only CPU**, through the separate [ZettaBridge](https://github.com/ZailoxTT/ZettaBridge) plugin launcher
(by its own author, thank you!).

Hungarian version: [`OLVASSEL.md`](OLVASSEL.md) - part of the [SoA repository](../README.md)

## Which tool do I need?

This is the right tool if your CPU is **64-bit only** (it cannot run 32-bit apps). If your CPU can run 32-bit apps, use
[`../SoA`](../SoA/) instead (plain on-device APK repair, no ZettaBridge needed). How to check (AIDA64): see the
[root README](../README.md#which-tool-do-i-need).

## What it is NOT

- It contains **no game files** (no APK, no .obb, no videos) and **no ZettaBridge code**.
- It does not download anything. Everything happens on the device, on the APK you pick.
- The result (`...-zb.apk`) is **not an installable Android app** (it is not signed on purpose).
  It is only meant to be imported in the ZettaBridge launcher.
- **Never share the patched APKs or the obb files.** Share only this tool / its source.

## Supported games

Tested on an **Honor 400** (64-bit-only CPU), Android 16, ZettaBridge 0.1.0.

| Game | State |
|---|---|
| Haegemonia: Legions of Iron (`com.microids.google.HaegemoniaFull`) | **works** |
| Imperium Galactica 2 (`com.digitalreality.ig2`) | **works** |
| Colin McRae Rally, Dead Space, Carmageddon | **not working yet** - they fail inside ZettaBridge itself (see below) |

**SoA32to64 accepts only the working games** (Haegemonia, Imperium Galactica 2). Any other APK is refused with a message.

Why the others do not work today (ZettaBridge 0.1.0 limits, not problems of the APK):

- Colin McRae Rally (Unity): the ZettaBridge host process aborts shortly after the game engine starts.
- Dead Space: needs OpenGL ES 1 passthrough, ZettaBridge only provides OpenGL ES 2.
- Carmageddon: needs `NativeActivity` support and OpenGL ES 1.

If a newer ZettaBridge release fixes these, support can be added in a later version.

## How to use

1. Install ZettaBridge (release 0.1.0 plugin launcher) from its author - the first button in the app opens the download link - and read its own README.
2. Build/install SoA32to64 (open this folder, `SoA32to64`, in Android Studio, Run on the device).
3. Open SoA32to64 -> **Step 1** pick the **original, unpatched** game APK -> **Step 2** make the ZettaBridge APK ->
   **Step 3** save `...-zb.apk` to Downloads. (If something fails: "Save log" and keep the log.)
4. Import the `...-zb.apk` in the ZettaBridge launcher.
5. Put the game's own `.obb` file(s) (from your own copy of the game) into this folder, named after the game package:

   `Android/obb/com.zettabridge.launcher/<game package>/`

   If the folder `Android/obb/com.zettabridge.launcher` does not exist yet, **create it by hand** (and the `<game package>`
   folder inside it) with a file manager. Only the `.obb` files go there:
   - Haegemonia: `Android/obb/com.zettabridge.launcher/com.microids.google.HaegemoniaFull/main.100....obb`
   - Imperium Galactica 2: `Android/obb/com.zettabridge.launcher/com.digitalreality.ig2/` (`main.140....obb` and `patch.141....obb`)

### Optional: replacement / original videos

The cutscenes inside the obb of these two games cannot be played any more, so cutscenes only play if you supply your own
`.mp4` files. Which language/audio you get depends on the files you put there (for example the original ones converted
from your own game, or your own translated versions). The videos are **not** part of this project.

Create the folder by hand and put the files there:

- **Haegemonia:** `Android/obb/com.zettabridge.launcher/hv/fmv/NAME.bik.mp4`
- **Imperium Galactica 2:** `Android/obb/com.zettabridge.launcher/iv/` - the videos are plain `.mp4` files, no subfolder.

To make `NAME.bik.mp4` from your own `NAME.bik` files use **Haegemonia-bik.mp4converter.exe** (Windows, from the
[Releases](https://github.com/dslytech/SoA/releases) page; source: [`../tools/Haegemonia-bik.mp4converter`](../tools/Haegemonia-bik.mp4converter)): choose the folder with
your `.bik` files, tick **SoA32to64**, press **Convert**; it builds the `Android/obb/com.zettabridge.launcher/hv/fmv` path, copy the `Android` folder to the root of the
device storage. Details, SmartScreen note and license: [root README](../README.md#the-video-converter).

## What the tool changes in your APK

Everything the normal [`../SoA`](../SoA/) pipeline already does for these games, plus:

1. Java: `PendingIntent` calls get `FLAG_IMMUTABLE` (Android 12+).
2. Java: the old Google expansion downloader code finds the obb in `getObbDir()` and uses the hard-coded game
   package for the obb file name (under ZettaBridge `getPackageName()` is the launcher's package).
3. `libSDL2.so`: the old SDL2 port wants OpenGL ES 1 (`libGLESv1_CM.so`); the dependency and the `dlopen` string
   are pointed at `libGLESv2.so` and the GLES1-only functions become weak symbols.
4. Game library: the external video folder string is overwritten in place with the `hv/` / `iv/` folder above.

## Known limits

- Early experiment: ZettaBridge itself is version 0.1.0. Long-play stability, performance and sound were only checked by playing briefly.
- Tested only with these two games, on one device.
- The on-device patcher is taken from SoA (a lot of unrelated per-game code for other games is still in the tree and
  only runs when a matching library is found).

## Credits

Developed with the assistance of Anthropic Claude.

Huge thanks to **ZailoxTT**, the author of **[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge)**, for the great
work on that project - without it none of this would run on a 64-bit-only CPU. This tool is an independent helper
and is not affiliated with ZettaBridge. It contains none of its code.

## License / legal

This tool: PolyForm Noncommercial 1.0.0, see [`../LICENSE`](../LICENSE) and [`../NOTICE.md`](../NOTICE.md) - free to use, modify and share for personal /
noncommercial purposes, commercial use is not permitted. Use it with games you own. ZettaBridge has its own license
(PolyForm Noncommercial + Perimeter); follow it. This tool only edits your own files.

Changelog: 0.1 - first version (SoA pipeline + ZettaBridge steps, single `-zb.apk` output).
