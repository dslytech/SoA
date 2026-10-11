# SoA - Save old APK

Two small, free (noncommercial) tools for keeping **old Android games** playable - repairing **your own copy** of the game:

Hungarian version: [`OLVASSEL.md`](OLVASSEL.md)

| Folder | What it is | For which CPU |
|---|---|---|
| [`SoA/`](SoA/) | An Android app that repairs an old APK on the device itself (no PC, no root). | CPU that can run **32-bit apps** (32-bit or mixed 32+64-bit) |
| [`SoA32to64/`](SoA32to64/) | An Android app that prepares the APK for the [ZettaBridge](https://github.com/ZailoxTT/ZettaBridge) launcher. | **64-bit-only** CPU |
| [`tools/Haegemonia-bik.mp4converter/`](tools/Haegemonia-bik.mp4converter/) | Windows program (`Haegemonia-bik.mp4converter.exe`, from Releases) that converts your own Haegemonia `.bik` videos to `.bik.mp4`. | any |

Both apps are plain Android Studio projects: download the repository (**Code -> Download ZIP**), unzip it, and open the
`SoA` or the `SoA32to64` **folder** in Android Studio (SDK 34, minSdk 26) to build the app. No `git clone` needed. Current version: **0.1** for both.

## Which tool do I need?

Only one thing matters: **what your device's CPU can run.** Check it with AIDA64 (free Android app): open
**AIDA64 -> CPU** and look at the instruction set and the supported ABIs:

| You see | Meaning | Use |
|---|---|---|
| `armeabi-v7a` (ARMv7, 32-bit) | 32-bit CPU | **SoA** |
| `arm64-v8a` **and** `armeabi-v7a` | 32+64-bit CPU | **SoA** |
| only `arm64-v8a` (ARMv8-A / ARMv9-A, 64-bit) | 64-bit-only CPU | **SoA32to64** |

"ARMv8-A" alone is not enough to decide: some ARMv8-A CPUs can run 32-bit apps and some cannot. Look at the ABI list.
Newer ARMv9-A CPUs are usually 64-bit only. The old games are 32-bit, so what counts is whether your CPU can run 32-bit code.

## Tested games

| Game | SoA (tested on Honor Pad X8a, 32+64-bit CPU) | SoA32to64 (tested on Honor 400, 64-bit-only CPU) |
|---|---|---|
| Haegemonia: Legions of Iron | works | works |
| Imperium Galactica 2 | works | works |
| Colin McRae Rally | works | not yet (ZettaBridge limit) |
| Carmageddon | works | not yet (ZettaBridge limit) |
| Dead Space | works | not yet (ZettaBridge limit) |
| Mass Effect: Infiltrator | **on hold** - despite many attempts it never started | - |

SoA32to64 accepts only the working games (Haegemonia, Imperium Galactica 2). Other games may or may not work with SoA, no promises.
Details in the folder READMEs: [`SoA/README.md`](SoA/README.md), [`SoA32to64/README.md`](SoA32to64/README.md).

## What this is NOT

- No game files (no APK, no `.obb`, no videos) are included, nothing is downloaded.
- The tools only edit an APK that **you** pick and write a patched copy. **Never share patched APKs or obb files** - share only the tools.
- No promises: some games simply will not work.

## The video converter

The cutscenes inside the obb of Haegemonia and Imperium Galactica 2 cannot be played any more, so cutscenes only play if
you supply your own `.mp4` files (the original ones converted from your own game, or your own translated versions). The
videos are **not** part of this project and are never uploaded here.

- Imperium Galactica 2: the videos are already plain `.mp4` files - no conversion needed, copy them (no subfolder) into
  `videos/` (SoA) or `iv/` (SoA32to64).
- Haegemonia: the videos are Bink (`.bik`) files and must be converted to `NAME.bik.mp4`. Use
  **Haegemonia-bik.mp4converter.exe** (Windows, download it from the [Releases](https://github.com/dslytech/SoA/releases) page; source:
  [`tools/Haegemonia-bik.mp4converter`](tools/Haegemonia-bik.mp4converter)). It is one file, nothing else to install: choose the folder
  with your `.bik` files, tick **SoA** or **SoA32to64**, press **Convert**. It builds the whole
  `Android/obb/<package>/videos/fmv` (SoA) or `Android/obb/com.zettabridge.launcher/hv/fmv` (SoA32to64) path inside the output
  folder; copy the `Android` folder to the root of the device storage. It can also copy straight to a connected device with
  ADB (USB debugging on). The originals are not touched. The interface starts in English; the language box switches to Hungarian.

The `.exe` is not code-signed, so Windows SmartScreen may warn on first start ("unknown publisher"): click **More info -> Run anyway**.
The program only reads your `.bik` files and writes the output folder; its source code is in this repository. It contains a reduced
FFmpeg build (GPL), see [`NOTICE.md`](NOTICE.md).

## Credits

Developed with the assistance of Anthropic Claude.

Many thanks to **ZailoxTT** for **[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge)**, the great project that makes the
64-bit-only line (SoA32to64) possible. This repository is independent of ZettaBridge and contains none of its code.
Libraries and bundled third-party software: see [`NOTICE.md`](NOTICE.md).

## License

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) - see [`LICENSE`](LICENSE). Free to use, study,
modify and share for personal / noncommercial purposes; commercial use is not permitted. Use it with games you own.
ZettaBridge has its own license (PolyForm Noncommercial + Perimeter); follow it.
