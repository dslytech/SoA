# Third-party components

This repository (the `SoA` and `SoA32to64` Android projects and the `tools/` converter) is licensed under PolyForm Noncommercial 1.0.0 (see `LICENSE`). The Android projects use these libraries through Gradle (not bundled in the repository):

- AndroidX / Material Components - Apache License 2.0
- smali / baksmali / dexlib2 (`com.android.tools.smali`) - BSD-3-Clause / Apache 2.0
- apksig (`com.android.tools.build:apksig`) - Apache License 2.0
- ARSCLib (`io.github.reandroid:ARSCLib`) - MIT License

No game files, no game code and no code from other projects are included in this repository.
Game names and trademarks belong to their owners; this tool only edits APK files that the user already owns.

## Haegemonia-bik.mp4converter (separate program, `tools/Haegemonia-bik.mp4converter/`)

The Windows converter bundles a reduced build of FFmpeg 7.1.1 with x264 (GNU GPL v2 or later, https://ffmpeg.org and
https://www.videolan.org/developers/x264.html). It is started as a separate program. Source links and the exact build
recipe: `tools/Haegemonia-bik.mp4converter/NOTICE-ffmpeg.txt` and `BUILD.md`. The finished `.exe` is published under Releases, not in
the repository.

## ZettaBridge

`SoA32to64` prepares APKs for the separate ZettaBridge plugin launcher by ZailoxTT (https://github.com/ZailoxTT/ZettaBridge,
PolyForm Noncommercial + Perimeter). No ZettaBridge code is included in this repository; this project is independent and not
affiliated with it.

## Development

Developed with the assistance of Anthropic Claude.
