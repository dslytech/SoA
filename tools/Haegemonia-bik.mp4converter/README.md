# Haegemonia-bik.mp4converter

Hungarian version: [`OLVASSEL.md`](OLVASSEL.md)

A small **Windows program** (`Haegemonia-bik.mp4converter.exe`, one file, about 4.8 MB, nothing to install) that converts the Bink
(`.bik`) cutscene videos of **your own copy** of *Haegemonia: Legions of Iron* to `NAME.bik.mp4`, and puts them into the right folder
structure for the [`SoA`](../../SoA/) and [`SoA32to64`](../../SoA32to64/) apps. Imperium Galactica 2 does not need it: its videos are already `.mp4`.

The `.exe` is published on the **[Releases](https://github.com/dslytech/SoA/releases)** page. This folder contains its source code.

## How to use

1. Download `Haegemonia-bik.mp4converter.exe` from Releases and start it. The interface starts in English; the language box (top right) switches it to Hungarian.
2. **Step 1:** choose the folder with your `.bik` videos (subfolders are included), or drop files / folders on the window.
3. **Step 2:** tick which app it is for:
   - **SoA** -> `Android/obb/com.microids.google.HaegemoniaFull/videos/fmv/`
   - **SoA32to64** -> `Android/obb/com.zettabridge.launcher/hv/fmv/`
4. **Step 3:** choose an output folder on the PC. The full `Android/obb/...` path is created inside it.
5. Press **Convert**. When it is done, copy the `Android` folder from the output folder to the root of the device storage
   (merge if asked). Or tick **copy with ADB** to push it straight to a connected device (USB debugging on, `adb.exe` next to the program or in PATH).

The original `.bik` files are never touched, files that already exist in the output are skipped, and nothing is uploaded anywhere.

## What it does

For every `.bik` it runs the bundled FFmpeg with:

```
-c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -preset medium -crf 20 -c:a aac -b:a 160k -ar 44100 -ac 2 -movflags +faststart
```

(H.264 baseline video, AAC stereo audio, MP4 with the index at the front.) FFmpeg is embedded in the `.exe`, so nothing else has to be downloaded.

## Notes

- **SmartScreen:** the `.exe` is not code-signed, so Windows SmartScreen may warn on first start ("unknown publisher"). Click **More info -> Run anyway**.
  The program only reads your `.bik` files and writes the output folder. The source code is in this folder.
- **Your own videos only.** No game videos are part of this repository. Use videos from your own copy of the game.
- **License:** the program is under PolyForm Noncommercial 1.0.0 (see [`../../LICENSE`](../../LICENSE)). The embedded reduced FFmpeg 7.1.1 build
  with x264 is under the GNU GPL v2 or later; it runs as a separate program. Source links: [`NOTICE-ffmpeg.txt`](NOTICE-ffmpeg.txt).
- **Build it yourself:** [`BUILD.md`](BUILD.md) (the reduced FFmpeg recipe and the C# compile command).
- Files here: `Program.cs` (the program), `BUILD.md`, `NOTICE-ffmpeg.txt`.

SHA-256 of the released 0.1 file: `546d359e316b8a9bf83a20764d9e51798c775158b8077e0a00aed254a16786f1`

Developed with the assistance of Anthropic Claude.
