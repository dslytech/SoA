# Building Haegemonia-bik.mp4converter

`Haegemonia-bik.mp4converter.exe` is one C# (.NET Framework / WinForms) program. It embeds a reduced `ffmpeg.exe` as a resource
and extracts it to `%TEMP%\Haegemonia-bik.mp4converter\` on first use. The finished `.exe` is published under **Releases**, it is
not stored in the repository.

## 1. The reduced ffmpeg.exe (cross-compiled on Linux with mingw-w64)

```
# x264 (static)
git clone https://github.com/mirror/x264.git      # commit c24e06c2e184345ceb33eb20a15d1024d9fd3497
cd x264
./configure --host=x86_64-w64-mingw32 --cross-prefix=x86_64-w64-mingw32- --prefix=$PWD/../prefix \
  --enable-static --disable-cli --disable-opencl --disable-avs --disable-swscale --disable-lavf \
  --disable-ffms --disable-gpac --disable-lsmash
make -j8 && make install
cd ..

# FFmpeg 7.1.1
git clone --depth 1 --branch n7.1.1 https://github.com/FFmpeg/FFmpeg.git ffmpeg
cd ffmpeg
export PKG_CONFIG_PATH=$PWD/../prefix/lib/pkgconfig PKG_CONFIG_LIBDIR=$PWD/../prefix/lib/pkgconfig
./configure --arch=x86_64 --target-os=mingw32 --cross-prefix=x86_64-w64-mingw32- \
  --pkg-config=pkg-config --pkg-config-flags=--static \
  --enable-gpl --enable-libx264 --extra-cflags=-I$PWD/../prefix/include \
  --extra-ldflags="-L$PWD/../prefix/lib -static" \
  --disable-everything --disable-doc --disable-debug --disable-network --disable-ffplay --disable-ffprobe \
  --enable-ffmpeg --disable-autodetect \
  --enable-demuxer=bink --enable-decoder=bink --enable-decoder=binkaudio_dct --enable-decoder=binkaudio_rdft \
  --enable-encoder=libx264 --enable-encoder=aac --enable-muxer=mp4 --enable-protocol=file \
  --enable-filter=scale --enable-filter=format --enable-filter=aresample --enable-filter=null \
  --enable-filter=anull --enable-filter=aformat --enable-filter=fps \
  --enable-parser=aac --enable-bsf=aac_adtstoasc --enable-small --optflags=-O2
make -j8
x86_64-w64-mingw32-strip ffmpeg.exe        # about 4.7 MB
```

Used command line (the same one the old `.bat` used):

```
ffmpeg -hide_banner -loglevel error -y -i NAME.bik -c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p \
  -preset medium -crf 20 -c:a aac -b:a 160k -ar 44100 -ac 2 -movflags +faststart NAME.bik.mp4
```

## 2. The program

Windows (any machine with the .NET Framework 4 compiler, `csc.exe`, which ships with Windows):

```
copy ffmpeg.exe here, then:
%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe /target:winexe /out:Haegemonia-bik.mp4converter.exe ^
  /r:System.Windows.Forms.dll /r:System.Drawing.dll /resource:ffmpeg.exe,ffmpeg.exe Program.cs
```

Linux (Mono):

```
mcs -codepage:utf8 -target:winexe -out:Haegemonia-bik.mp4converter.exe -r:System.Windows.Forms.dll \
  -r:System.Drawing.dll -resource:ffmpeg.exe,ffmpeg.exe Program.cs
```
