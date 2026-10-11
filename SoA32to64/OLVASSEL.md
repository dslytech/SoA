# SoA32to64 0.1 (magyarul)

Kísérleti, nem kereskedelmi segédprogram: a **saját példányodból** csinál ZettaBridge-kompatibilis APK-t régi 32 bites
játékokhoz, hogy **csak 64 bites CPU-n** is fussanak, a különálló
[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge) launcheren keresztül (a készítőjének köszönet!).

English version: [`README.md`](README.md) - a [SoA tároló](../OLVASSEL.md) része

## Melyik eszköz kell nekem?

Ez az eszköz akkor jó, ha a CPU-d **csak 64 bites** (nem tud 32 bites appot futtatni). Ha a CPU-d tud 32 bites appot futtatni,
használd helyette a [`../SoA`](../SoA/) mappát (sima, a készüléken futó APK-javítás, ZettaBridge nem kell hozzá). Az ellenőrzés módja
(AIDA64): lásd a [fő README-t](../OLVASSEL.md#melyik-eszköz-kell-nekem).

## Mi nem ez

- **Nem tartalmaz játékfájlt** (APK, obb, videó) és **ZettaBridge-kódot** sem.
- Nem tölt le semmit. Minden a készüléken történik, a kiválasztott APK-n.
- Az eredmény (`...-zb.apk`) **nem telepíthető Android-app** (szándékosan nincs aláírva), csak a ZettaBridge launcherbe
  importálásra való.
- **Soha ne oszd meg a javított APK-kat és az obb fájlokat.** Csak az eszközt / a forrását oszd meg.

## Támogatott játékok

**Honor 400** (csak 64 bites CPU), Android 16, ZettaBridge 0.1.0 mellett tesztelve.

| Játék | Állapot |
|---|---|
| Haegemonia: Legions of Iron (`com.microids.google.HaegemoniaFull`) | **működik** |
| Imperium Galactica 2 (`com.digitalreality.ig2`) | **működik** |
| Colin McRae Rally, Dead Space, Carmageddon | **egyelőre nem működik** - magában a ZettaBridge-ben akadnak el (lásd lent) |

**A SoA32to64 csak a működő játékokat fogadja el** (Haegemonia, Imperium Galactica 2). Minden más APK-t üzenettel elutasít.

Miért nem működik ma a többi (a ZettaBridge 0.1.0 korlátai, nem az APK hibái):

- Colin McRae Rally (Unity): a ZettaBridge gazdafolyamata röviddel a játékmotor indulása után megszakad.
- Dead Space: OpenGL ES 1 átengedés kellene, a ZettaBridge csak OpenGL ES 2-t ad.
- Carmageddon: `NativeActivity` támogatás és OpenGL ES 1 kellene.

Ha egy újabb ZettaBridge-kiadás ezeket javítja, egy későbbi verzióban hozzáadható a támogatás.

## Használat

1. Telepítsd a ZettaBridge 0.1.0 launchert (a készítőjétől) - az app első gombja megnyitja a letöltési linket - és olvasd el a saját README-jét.
2. Építsd meg / telepítsd a SoA32to64-et (nyisd meg ezt a mappát, a `SoA32to64`-et, Android Studióban, Run a készüléken).
3. SoA32to64: **1.** az **eredeti, módosítatlan** játék-APK kiválasztása -> **2.** ZettaBridge APK készítése ->
   **3.** a `...-zb.apk` mentése a Letöltésekbe. (Hiba esetén: „Save log", és őrizd meg a naplót.)
4. Importáld a `...-zb.apk`-t a ZettaBridge launcherben.
5. A játék saját `.obb` fájljait (a saját példányodból) ebbe a mappába tedd, a játék csomagnevével:

   `Android/obb/com.zettabridge.launcher/<játék csomagneve>/`

   Ha az `Android/obb/com.zettabridge.launcher` mappa még nem létezik, **hozd létre kézzel** (és benne a `<játék csomagneve>`
   mappát) fájlkezelővel. Csak az `.obb` fájlok kerülnek ide:
   - Haegemonia: `Android/obb/com.zettabridge.launcher/com.microids.google.HaegemoniaFull/main.100....obb`
   - Imperium Galactica 2: `Android/obb/com.zettabridge.launcher/com.digitalreality.ig2/` (`main.140....obb` és `patch.141....obb`)

### Opcionális: csere / eredeti videók

E két játék obb-jében lévő cutscene-ek már nem játszhatók le, ezért az átvezető videók csak akkor szólnak, ha a saját `.mp4`
fájlaidat adod meg. Hogy milyen nyelvű / hangú lesz, azon múlik, melyik fájlokat teszed oda (például az eredetieket a saját
játékodból átalakítva, vagy a saját magyar változataidat). A videók **nem** részei ennek a projektnek.

A mappát hozd létre kézzel, és tedd bele a fájlokat:

- **Haegemonia:** `Android/obb/com.zettabridge.launcher/hv/fmv/NÉV.bik.mp4`
- **Imperium Galactica 2:** `Android/obb/com.zettabridge.launcher/iv/` - a videók sima `.mp4` fájlok, almappa nélkül.

A `NÉV.bik` fájlokból `NÉV.bik.mp4`-et a **Haegemonia-bik.mp4converter.exe** csinál (Windows, a
[Releases](https://github.com/dslytech/SoA/releases) oldalról; forrás: [`../tools/Haegemonia-bik.mp4converter`](../tools/Haegemonia-bik.mp4converter)): kiválasztod a `.bik`
fájlokat tartalmazó mappát, bepipálod az **SoA32to64**-t, és megnyomod az **Átalakítás** gombot; létrehozza a(z) `Android/obb/com.zettabridge.launcher/hv/fmv` útvonalat, az
`Android` mappát másold az eszköz tárhelyének gyökerébe. Részletek, SmartScreen-megjegyzés és licenc: [fő README](../OLVASSEL.md#a-videókonverter).

## Mit módosít az eszköz az APK-ban

Mindazt, amit a sima [`../SoA`](../SoA/) is megcsinál ezeknél a játékoknál, plusz:

1. Java: a `PendingIntent` hívások `FLAG_IMMUTABLE`-t kapnak (Android 12+).
2. Java: a régi Google expansion downloader kód az obb-t a `getObbDir()`-ben keresi, és az obb fájlnévhez a beégetett játék-
   csomagnevet használja (a ZettaBridge alatt a `getPackageName()` a launcher csomagneve).
3. `libSDL2.so`: a régi SDL2 OpenGL ES 1-et (`libGLESv1_CM.so`) kér; a függőség és a `dlopen` sztring `libGLESv2.so`-ra
   mutat, a csak GLES1-es függvények gyenge szimbólumok lesznek.
4. Játék-könyvtár: a külső videómappa sztringje helyben felülíródik a fenti `hv/` / `iv/` mappára.

## Ismert korlátok

- Korai kísérlet: maga a ZettaBridge is 0.1.0. A hosszú játék, a teljesítmény és a hang csak rövid kipróbálással lett ellenőrizve.
- Csak ezzel a két játékkal, egy készüléken tesztelve.
- A készüléken futó javító a SoA-ból származik (sok, más játékokhoz tartozó kód még a fában van, és csak akkor fut, ha a
  megfelelő könyvtár megvan).

## Köszönet

Fejlesztve az Anthropic Claude segítségével.

Hatalmas köszönet **ZailoxTT**-nek, a **[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge)** fejlesztőjének a projekt
nagyszerű munkájáért - nélküle ez csak 64 bites CPU-n nem futna. Ez az eszköz független segéd, nem része a ZettaBridge-nek,
és nem tartalmaz belőle kódot.

## Licenc / jogi

Ez az eszköz: PolyForm Noncommercial 1.0.0, lásd [`../LICENSE`](../LICENSE) és [`../NOTICE.md`](../NOTICE.md) - szabadon használható, módosítható és megosztható
személyes / nem kereskedelmi célra, kereskedelmi célra nem. Csak olyan játékhoz használd, ami a tiéd. A ZettaBridge saját
licencét (PolyForm Noncommercial + Perimeter) tartsd be. Az eszköz csak a saját fájljaidat módosítja.
