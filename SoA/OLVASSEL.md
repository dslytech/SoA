# SoA 0.1a (Save old APK) - magyarul

Android app, ami megpróbálja megjavítani a **saját példányodat** egy régi Androidos játékból, hogy a mai Androidon is
települjön és fusson. Teljesen a készüléken működik: kiválasztod az APK-t, megnyomod a patch gombot, telepíted.
PC és root nem kell.

English version: [`README.md`](README.md) - a [SoA tároló](../OLVASSEL.md) része

## Melyik eszköz kell nekem?

Ez az eszköz akkor jó, ha a CPU-d tud **32 bites appot** futtatni (32 bites vagy vegyes 32+64 bites). Ha a CPU-d **csak 64 bites**,
használd helyette a [`../SoA32to64`](../SoA32to64/) mappát. Az ellenőrzés módja (AIDA64): lásd a [fő README-t](../OLVASSEL.md#melyik-eszköz-kell-nekem).

## Mi nem ez

- **Nem tartalmaz játékfájlt** (APK, obb, videó), és nem is tölt le semmit.
- Csak azt az APK-t módosítja, amit te választasz ki, és javított másolatot készít. Javított APK-t és obb-t ne oszd meg, csak az eszközt.
- Nem ígér semmit: **megpróbálja** megjavítani bármelyik régi APK-t, de néhány játék nem fog működni.

## Mit csinál

1. Túl alacsony `targetSdkVersion` -> megemeli (alapértelmezés 30, a haladó beállításokban módosítható).
2. Régi natív könyvtárak ELF text relocation hibái -> a helyén kijavítja.
3. Halott SDK-k (reklám, kontroller, letöltő) okozta összeomlások -> a Java kódban védi.
4. Opcionális: szabad képernyőforgatás, saját keystore az aláíráshoz.
5. Játékspecifikus javítások, csak ha a megfelelő könyvtár megvan (mentések, külső videómappa, videólejátszás, ...).

## Tesztelt játékok

**Honor Pad X8a** (32+64 bites CPU), Android 16 készüléken tesztelve.

| Játék | Állapot |
|---|---|
| Imperium Galactica 2 (`com.digitalreality.ig2`) | működik, a mentésekkel és a saját videókkal együtt |
| Haegemonia: Legions of Iron (`com.microids.google.HaegemoniaFull`) | működik, a mentésekkel és a saját videókkal együtt |
| Colin McRae Rally (`com.codemasters.cmrally`) | működik |
| Carmageddon (`com.stainlessgames.carmageddon`) | működik |
| Dead Space (`com.eamobile.deadspace_full_azn`) | működik |
| Mass Effect: Infiltrator (`com.ea.games.meinfiltrator_gamepad`) | működik - **az adatfájlok az `Android/obb`-ba kerülnek, nem az `Android/data`-ba** (lásd lent) |

Más játékok működhetnek, de nem biztos.

## Használat

1. Nyisd meg ezt a mappát (`SoA`) Android Studióban, és futtasd a készüléken (vagy építsd meg az APK-t). Kell hozzá: Android Studio, SDK 34, minSdk 26.
2. Az appban válaszd ki a saját játék-APK-dat -> Patch -> telepítsd az eredményt.
3. Ha a játéknak kell `.obb` fájl, másold ide: `Android/obb/<játék csomagneve>/` (például
   `Android/obb/com.microids.google.HaegemoniaFull/`).
4. Hiba esetén használd az app napló gombját, és őrizd meg a naplót.

### Mass Effect: Infiltrator - FONTOS: az adatfájlokat az OBB-ba kell tenni, NEM a DATA-ba

A játék az adatfájljait eredetileg az `Android/data/com.ea.games.meinfiltrator_gamepad` mappában tartja. A mai Android ebben a
mappában már sem neked, sem a játéknak nem enged fájlt létrehozni, ezért a játék sosem találta meg az adatait - ez volt
a gyökéroka a korábbi próbálkozások összes összeomlásának. A javított játék ezért az adatait az **OBB** mappában keresi:

1. Javítsd meg a saját Mass Effect APK-dat a SoA-val, és telepítsd az eredményt (előtte töröld a régebbi Mass Effect
   telepítést - az aláírás más). Úgy települ, mint bármelyik normál app, nem kell hozzá `adb` és PC.
2. Indítsd el egyszer a játékot, majd lépj ki. Ekkor az app létrehozza a saját mappáit.
3. Zárt játék mellett másold a játék adatfájljait ide: **`Android/obb/com.ea.games.meinfiltrator_gamepad/`**
   (ugyanazokat a fájlokat és almappákat, amik egyébként az `Android/data/com.ea.games.meinfiltrator_gamepad/`
   mappába kerülnének). **Nem** az `Android/data`-ba!
4. Indítsd el a játékot.

Az adatfájlok nem részei a projektnek. A mentések a játék szokásos `Android/data/<csomag>/files` mappájába kerülnek (ezt az
app maga hozza létre); a mentések tartósságát ennél a játéknál még nem teszteltük részletesen.

### Opcionális: csere / eredeti videók (Haegemonia, Imperium Galactica 2)

E két játék obb-jében lévő cutscene-ek már nem játszhatók le, ezért az átvezető videók csak akkor szólnak, ha a saját
`.mp4` fájlaidat adod meg. Hogy milyen nyelvű / hangú lesz, azon múlik, melyik fájlokat teszed oda (például az eredetieket
a saját játékodból átalakítva, vagy a saját magyar változataidat). A videók **nem** részei ennek a projektnek.

Ide kell tenni őket (a `videos` mappát kézzel hozd létre a készüléken, fájlkezelővel):

- **Haegemonia:** `Android/obb/com.microids.google.HaegemoniaFull/videos/fmv/NÉV.bik.mp4`
- **Imperium Galactica 2:** `Android/obb/com.digitalreality.ig2/videos/` - a videók sima `.mp4` fájlok, almappa nélkül.

A `NÉV.bik` fájlokból `NÉV.bik.mp4`-et a **Haegemonia-bik.mp4converter.exe** csinál (Windows, a
[Releases](https://github.com/dslytech/SoA/releases) oldalról; forrás: [`../tools/Haegemonia-bik.mp4converter`](../tools/Haegemonia-bik.mp4converter)): kiválasztod a `.bik`
fájlokat tartalmazó mappát, bepipálod az **SoA**-t, és megnyomod az **Átalakítás** gombot; létrehozza a(z) `Android/obb/com.microids.google.HaegemoniaFull/videos/fmv` útvonalat, az
`Android` mappát másold az eszköz tárhelyének gyökerébe. Részletek, SmartScreen-megjegyzés és licenc: [fő README](../OLVASSEL.md#a-videókonverter).

### Ha újratelepítés után nem indul a játék

Egy régi, árván maradt obb-mappa blokkolhatja az új telepítést (rossz tulajdonos). Az appban nyisd meg a **Clear OBB folder
(advanced)** részt, töröld a játék obb-mappáját, majd hozd létre újra **kézzel** a készüléken, és másold bele az obb
fájlt (és a `videos` mappát, ha használsz ilyet). A fájlkezelővel létrehozott mappát a játék olvasni tudja, írni nem.

## Dokumentáció

- `docs/DEVLOG.md` - a hosszú fejlesztési napló (minden javítás, minden valódi készüléken végzett teszt). Az ottani „1.xx"
  számok belső buildek, az első nyilvános verzió a **0.1** volt; a **0.1a** hozzáadja a Mass Effect: Infiltratort.

## Köszönet

Fejlesztve az Anthropic Claude segítségével.

Köszönet **ZailoxTT**-nek a **[ZettaBridge](https://github.com/ZailoxTT/ZettaBridge)** nagyszerű projektért, ami a csak 64 bites
vonalat (SoA32to64) lehetővé teszi. Használt könyvtárak: lásd [`../NOTICE.md`](../NOTICE.md).

## Licenc

[PolyForm Noncommercial 1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0) - lásd [`../LICENSE`](../LICENSE). Szabadon használható,
tanulmányozható, módosítható és megosztható személyes / nem kereskedelmi célra, kereskedelmi célra nem. Csak olyan játékhoz használd, ami a tiéd.
