# Haegemonia-bik.mp4converter (magyarul)

English version: [`README.md`](README.md)

Egy kis **Windowsos program** (`Haegemonia-bik.mp4converter.exe`, egyetlen fájl, kb. 4,8 MB, nem kell telepíteni), ami a *Haegemonia: Legions of Iron*
**saját példányod** Bink (`.bik`) átvezető videóit `NÉV.bik.mp4` fájlokká alakítja, és az [`SoA`](../../SoA/) és [`SoA32to64`](../../SoA32to64/) apphoz való
mappaszerkezetbe teszi. Az Imperium Galactica 2-höz nem kell: annak a videói eleve `.mp4` fájlok.

Az `.exe` a **[Releases](https://github.com/dslytech/SoA/releases)** oldalon található. Ebben a mappában a forráskódja van.

## Használat

1. Töltsd le a `Haegemonia-bik.mp4converter.exe` fájlt a Releases oldalról, és indítsd el. A felület angolul indul, a nyelvválasztóval (jobb felül) magyarra váltható.
2. **1. lépés:** válaszd ki a `.bik` videókat tartalmazó mappát (az almappákat is beleveszi), vagy húzd rá a fájlokat / mappákat az ablakra.
3. **2. lépés:** pipáld be, melyik apphoz készül:
   - **SoA** -> `Android/obb/com.microids.google.HaegemoniaFull/videos/fmv/`
   - **SoA32to64** -> `Android/obb/com.zettabridge.launcher/hv/fmv/`
4. **3. lépés:** válassz kimeneti mappát a PC-n. Ebben jön létre a teljes `Android/obb/...` útvonal.
5. Nyomd meg az **Átalakítás** gombot. Ha kész, az `Android` mappát a kimeneti mappából másold az eszköz tárhelyének gyökerébe
   (egyesítés, ha rákérdez). Vagy pipáld be az **ADB-s másolást**, és közvetlenül a csatlakoztatott eszközre küldi (USB-hibakeresés bekapcsolva, az `adb.exe` a program mellett vagy a PATH-ban).

Az eredeti `.bik` fájlokhoz soha nem nyúl, a már meglévő kimeneti fájlokat kihagyja, és semmit nem tölt fel sehova.

## Mit csinál

Minden `.bik` fájlra a beépített FFmpeg-et futtatja ezekkel a beállításokkal:

```
-c:v libx264 -profile:v baseline -level 3.0 -pix_fmt yuv420p -preset medium -crf 20 -c:a aac -b:a 160k -ar 44100 -ac 2 -movflags +faststart
```

(H.264 baseline videó, AAC sztereó hang, az indexet elöl tartalmazó MP4.) Az FFmpeg be van építve az `.exe`-be, nem kell mást letölteni.

## Megjegyzések

- **SmartScreen:** az `.exe` nincs kódaláírással ellátva, ezért a Windows SmartScreen az első indításkor figyelmeztethet („ismeretlen kiadó"). Kattints a **További információ -> Futtatás mindenképp** gombra.
  A program csak olvassa a `.bik` fájlokat, és a kimeneti mappába ír. A forráskód ebben a mappában van.
- **Csak a saját videóid.** Játékvideó nem része a tárolónak. Csak a saját játékpéldányod videóit használd.
- **Licenc:** a program PolyForm Noncommercial 1.0.0 alatt van (lásd [`../../LICENSE`](../../LICENSE)). A beépített csökkentett FFmpeg 7.1.1 x264-gyel
  GNU GPL 2. vagy újabb verzió alatt van; külön programként fut. Forráslinkek: [`NOTICE-ffmpeg.txt`](NOTICE-ffmpeg.txt).
- **Saját építés:** [`BUILD.md`](BUILD.md) (a csökkentett FFmpeg receptje és a C# fordítási parancs).
- A mappa fájljai: `Program.cs` (a program), `BUILD.md`, `NOTICE-ffmpeg.txt`.

A kiadott 0.1-es fájl SHA-256 értéke: `546d359e316b8a9bf83a20764d9e51798c775158b8077e0a00aed254a16786f1`

Fejlesztve az Anthropic Claude segítségével.
