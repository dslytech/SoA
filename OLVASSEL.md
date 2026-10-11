# SoA - Save old APK

Két kis, ingyenes (nem kereskedelmi) eszköz **régi Androidos játékok** életben tartásához - a **saját példányod** javításához:

English version: [`README.md`](README.md)

| Mappa | Mi ez | Melyik CPU-hoz |
|---|---|---|
| [`SoA/`](SoA/) | Android app, ami a készüléken javít meg egy régi APK-t (PC és root nélkül). | olyan CPU, ami tud **32 bites appot** futtatni (32 bites vagy vegyes 32+64 bites) |
| [`SoA32to64/`](SoA32to64/) | Android app, ami az APK-t a [ZettaBridge](https://github.com/ZailoxTT/ZettaBridge) launcherhez készíti elő. | **csak 64 bites** CPU |
| [`tools/Haegemonia-bik.mp4converter/`](tools/Haegemonia-bik.mp4converter/) | Windowsos program (`Haegemonia-bik.mp4converter.exe`, a Releases oldalról), ami a saját Haegemonia `.bik` videóidat alakítja `.bik.mp4`-re. | bármelyik |

Mindkét app egyszerű Android Studio projekt: töltsd le a tárolót (**Code -> Download ZIP**), csomagold ki, és az Android Studióban
a `SoA` vagy a `SoA32to64` **mappát** nyisd meg (SDK 34, minSdk 26), hogy megépítsd az appot. `git clone` nem kell. Jelenlegi verzió: mindkettőnél **0.1**.

## Melyik eszköz kell nekem?

Egyetlen dolog számít: **mit tud futtatni a készüléked CPU-ja.** Ellenőrizd AIDA64-gyel (ingyenes Android app): nyisd meg az
**AIDA64 -> CPU** fület, és nézd meg az utasításkészletet és a támogatott ABI-kat:

| Amit látsz | Jelentés | Eszköz |
|---|---|---|
| `armeabi-v7a` (ARMv7, 32 bites) | 32 bites CPU | **SoA** |
| `arm64-v8a` **és** `armeabi-v7a` | 32+64 bites CPU | **SoA** |
| csak `arm64-v8a` (ARMv8-A / ARMv9-A, 64 bites) | csak 64 bites CPU | **SoA32to64** |

Az „ARMv8-A" név önmagában kevés a döntéshez: van ARMv8-A CPU, ami tud 32 bites appot futtatni, és van, ami nem. Az ABI-listát nézd.
Az újabb ARMv9-A CPU-k általában csak 64 bitesek. A régi játékok 32 bitesek, ezért az számít, hogy a CPU-d tud-e 32 bites kódot futtatni.

## Tesztelt játékok

| Játék | SoA (Honor Pad X8a, 32+64 bites CPU) | SoA32to64 (Honor 400, csak 64 bites CPU) |
|---|---|---|
| Haegemonia: Legions of Iron | működik | működik |
| Imperium Galactica 2 | működik | működik |
| Colin McRae Rally | működik | még nem (ZettaBridge korlát) |
| Carmageddon | működik | még nem (ZettaBridge korlát) |
| Dead Space | működik | még nem (ZettaBridge korlát) |
| Mass Effect: Infiltrator | **szünetel** - sok próbálkozás ellenére sem indult el | - |

A SoA32to64 csak a működő játékokat fogadja el (Haegemonia, Imperium Galactica 2). Más játékok a SoA-val működhetnek vagy sem, ígéret nincs.
A részletek a mappák READMEjében: [`SoA/OLVASSEL.md`](SoA/OLVASSEL.md), [`SoA32to64/OLVASSEL.md`](SoA32to64/OLVASSEL.md).

## Mi nem ez

- Nincs benne játékfájl (APK, `.obb`, videó), és nem is tölt le semmit.
- Az eszközök csak azt az APK-t módosítják, amit **te** választasz ki, és javított másolatot készítenek. **Javított APK-t és obb-t soha ne oszd meg** - csak az eszközöket.
- Nem ígér semmit: néhány játék egyszerűen nem fog működni.

## A videókonverter

A Haegemonia és az Imperium Galactica 2 obb-jében lévő átvezető videók már nem játszhatók le, ezért csak akkor szólnak, ha a saját
`.mp4` fájlaidat adod meg (az eredetieket a saját játékodból átalakítva, vagy a saját magyar változataidat). A videók **nem** részei
a projektnek, és ide soha nem kerülnek fel.

- Imperium Galactica 2: a videók eleve sima `.mp4` fájlok - nem kell átalakítani, másold őket (almappa nélkül) a
  `videos/` (SoA) vagy az `iv/` (SoA32to64) mappába.
- Haegemonia: a videók Bink (`.bik`) fájlok, ezeket `NÉV.bik.mp4`-re kell alakítani. Erre való a
  **Haegemonia-bik.mp4converter.exe** (Windows, a [Releases](https://github.com/dslytech/SoA/releases) oldalról tölthető le; forrás:
  [`tools/Haegemonia-bik.mp4converter`](tools/Haegemonia-bik.mp4converter)). Egyetlen fájl, mást nem kell telepíteni: kiválasztod a `.bik` fájlokat
  tartalmazó mappát, bepipálod az **SoA**-t vagy az **SoA32to64**-et, és megnyomod az **Átalakítás** gombot. A kimeneti mappában
  létrehozza a teljes `Android/obb/<csomagnév>/videos/fmv` (SoA) vagy `Android/obb/com.zettabridge.launcher/hv/fmv` (SoA32to64)
  útvonalat; az `Android` mappát másold az eszköz tárhelyének gyökerébe. ADB-vel (bekapcsolt USB-hibakeresés mellett) közvetlenül
  a csatlakoztatott eszközre is tudja másolni. Az eredetikhez nem nyúl. A felület angolul indul, a nyelvválasztóval magyarra váltható.

Az `.exe` nincs kódaláírással ellátva, ezért a Windows SmartScreen az első indításkor figyelmeztethet („ismeretlen kiadó"): kattints a
**További információ -> Futtatás mindenképp** gombra. A program csak olvassa a `.bik` fájlokat, és a kimeneti mappába ír; a forráskódja
ebben a tárolóban van. Egy csökkentett FFmpeg-et (GPL) tartalmaz, lásd [`NOTICE.md`](NOTICE.md).

## Köszönet

Fejlesztve az Anthropic Claude segítségével.

Köszönet **ZailoxTT**-nek a **[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge)** nagyszerű projektért, ami a csak 64 bites
vonalat (SoA32to64) lehetővé teszi. Ez a tároló független a ZettaBridge-től, és nem tartalmaz belőle kódot.
Használt könyvtárak és beépített harmadik féltől származó szoftverek: lásd [`NOTICE.md`](NOTICE.md).

## Licenc

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) - lásd [`LICENSE`](LICENSE). Szabadon használható,
tanulmányozható, módosítható és megosztható személyes / nem kereskedelmi célra, kereskedelmi célra nem. Csak olyan játékhoz használd, ami a tiéd.
A ZettaBridge saját licencét (PolyForm Noncommercial + Perimeter) tartsd be.
