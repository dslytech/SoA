package com.apkcompatpatcher.android

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * SoA32to64: everything that turns an APK already prepared by the normal SoA pipeline into an
 * APK the ZettaBridge plugin launcher (a separate project by its own author, https://github.com/ZailoxTT/ZettaBridge)
 * can run on a 64-bit-only phone. Only two games are supported, because every step below was
 * worked out and tested on those two real games:
 *
 *  - Haegemonia: Legions of Iron (com.microids.google.HaegemoniaFull)
 *  - Imperium Galactica 2 (com.digitalreality.ig2)
 *
 * Nothing here contains ZettaBridge code or any game content - it only changes bytes in the
 * user's own APK. The steps (each one was found by a real failure on a real phone):
 *
 *  1. dex: PendingIntent.getActivity/getBroadcast/... go through org.soa.PI (adds FLAG_IMMUTABLE).
 *  2. dex: the Google expansion downloader (Helpers) uses getObbDir() for the obb folder and
 *     a hard-coded game package for the obb file name, because under ZettaBridge
 *     getPackageName() is the launcher's package, not the game's.
 *  3. libSDL2.so: the old SDL2 port loads libGLESv1_CM.so (DT_NEEDED and a dlopen() string).
 *     The guest runtime only has libGLESv2.so, so both are pointed there and the GLES1-only
 *     functions become weak symbols (the game keeps starting; the SDL window is created through
 *     EGL on the Java side anyway).
 *  4. game lib: the external video folder baked in by SoA is moved to
 *     /storage/emulated/0/Android/obb/com.zettabridge.launcher/hv/ (Haegemonia) or .../iv/ (IG2).
 *     Strings are overwritten in place (shorter text, NUL padded) - never relocated.
 */
object ZbPatcher {

    enum class Game(
        val title: String,
        val pkg: String,
        val gameLibSuffix: String,
        val videoFolder: String,
        val oldVideoPrefix: String,
        val directPrefixToo: Boolean
    ) {
        HAEGEMONIA(
            "Haegemonia: Legions of Iron",
            "com.microids.google.HaegemoniaFull",
            "libhaegemonia_base.so",
            "hv",
            "/storage/emulated/0/Android/obb/com.microids.google.HaegemoniaFull/videos/",
            false
        ),
        IG2(
            "Imperium Galactica 2",
            "com.digitalreality.ig2",
            "libig2ps.so",
            "iv",
            "/storage/emulated/0/Android/obb/com.digitalreality.ig2/videos/",
            true
        )
    }

    private const val LAUNCHER_PKG = "com.zettabridge.launcher"

    fun detectGame(entryNames: List<String>): Game? {
        for (g in Game.values()) {
            if (entryNames.any { it.startsWith("lib/") && it.endsWith("/" + g.gameLibSuffix) }) return g
        }
        return null
    }

    // ------------------------------------------------------------------ native libs

    /**
     * Applies the libSDL2.so and game-lib patches to [libReplacements] (reading the original
     * entry from [zin] when an earlier SoA step did not already replace it).
     */
    fun patchLibs(
        context: Context,
        game: Game,
        entryNames: List<String>,
        zin: ZipFile,
        libReplacements: MutableMap<String, ByteArray>,
        changes: MutableList<String>
    ) {
        val v2names = context.assets.open("zb/glesv2_exports.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        var sdlDone = 0
        var videoDone = 0
        for (name in entryNames) {
            if (!name.startsWith("lib/")) continue
            val isSdl = name.endsWith("/libSDL2.so")
            val isGameLib = name.endsWith("/" + game.gameLibSuffix)
            if (!isSdl && !isGameLib) continue
            val bytes = (libReplacements[name] ?: zin.getInputStream(zin.getEntry(name)).readBytes()).copyOf()
            if (isSdl) {
                val note = fixSdl2(bytes, v2names)
                libReplacements[name] = bytes
                Logger.log("  [zb] $name: $note")
                changes.add("$name: $note")
                sdlDone++
            } else {
                val note = patchVideoPrefix(game, bytes)
                libReplacements[name] = bytes
                Logger.log("  [zb] $name: $note")
                changes.add("$name: $note")
                videoDone++
            }
        }
        if (sdlDone == 0) throw ApkPatcher.PatchException("no lib/*/libSDL2.so found - this is not the expected game APK")
        if (videoDone == 0) throw ApkPatcher.PatchException("game library ${game.gameLibSuffix} not found")
    }

    private class Sec(val type: Int, val offset: Int, val size: Int, val link: Int, val entSize: Int)

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    private fun cstr(b: ByteArray, o: Int): String {
        var e = o
        while (e < b.size && b[e].toInt() != 0) e++
        return String(b, o, e - o, Charsets.ISO_8859_1)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int = 0, to: Int = hay.size): Int {
        var i = from
        val last = to - needle.size
        while (i <= last) {
            var k = 0
            while (k < needle.size && hay[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i++
        }
        return -1
    }

    private val GLES1 = "libGLESv1_CM.so"
    private val GLES2 = "libGLESv2.so"

    /** Modifies [d] in place. Returns a human-readable summary. */
    private fun fixSdl2(d: ByteArray, v2names: Set<String>): String {
        if (d.size < 0x34 || d[0].toInt() != 0x7f || d[1].toInt() != 'E'.code || d[4].toInt() != 1 || d[5].toInt() != 1) {
            throw ApkPatcher.PatchException("libSDL2.so is not a 32-bit little-endian ELF file")
        }
        val shoff = u32(d, 0x20)
        val shentsize = u16(d, 0x2e)
        val shnum = u16(d, 0x30)
        val secs = (0 until shnum).map { i ->
            val o = shoff + i * shentsize
            Sec(u32(d, o + 4), u32(d, o + 16), u32(d, o + 20), u32(d, o + 24), u32(d, o + 36))
        }
        val dynamic = secs.firstOrNull { it.type == 6 } ?: throw ApkPatcher.PatchException("libSDL2.so: no .dynamic section")
        val dynsym = secs.firstOrNull { it.type == 11 } ?: throw ApkPatcher.PatchException("libSDL2.so: no .dynsym section")
        val dynstr = secs[dynsym.link]

        // 1. DT_NEEDED libGLESv1_CM.so -> libGLESv2.so
        val v2bytes = (GLES2 + "\u0000").toByteArray(Charsets.ISO_8859_1)
        var v2off = indexOf(d, v2bytes, dynstr.offset, dynstr.offset + dynstr.size)
        var neededPatched = 0
        var i = 0
        while (i + 8 <= dynamic.size) {
            val o = dynamic.offset + i
            val tag = u32(d, o)
            if (tag == 0) break
            if (tag == 1) { // DT_NEEDED
                val sOff = u32(d, o + 4)
                if (cstr(d, dynstr.offset + sOff) == GLES1) {
                    if (v2off < 0) {
                        // libGLESv2.so is not a string in .dynstr yet: overwrite this very entry in place.
                        val old = (GLES1 + "\u0000").toByteArray(Charsets.ISO_8859_1)
                        val at = dynstr.offset + sOff
                        val rep = ByteArray(old.size)
                        System.arraycopy(v2bytes, 0, rep, 0, v2bytes.size)
                        System.arraycopy(rep, 0, d, at, rep.size)
                    } else {
                        put32(d, o + 4, v2off - dynstr.offset)
                    }
                    neededPatched++
                }
            }
            i += 8
        }

        // 2. undefined GLES1-only gl* functions -> weak
        val weak = mutableListOf<String>()
        val n = dynsym.size / 16
        for (k in 0 until n) {
            val o = dynsym.offset + k * 16
            val shndx = u16(d, o + 14)
            if (shndx != 0) continue
            val name = cstr(d, dynstr.offset + u32(d, o))
            if (!name.startsWith("gl") || name in v2names) continue
            val info = d[o + 12].toInt() and 0xff
            if ((info shr 4) != 1) continue // only GLOBAL -> WEAK
            d[o + 12] = ((2 shl 4) or (info and 0xf)).toByte()
            weak.add(name)
        }

        // 3. dlopen("libGLESv1_CM.so") string in .rodata (every occurrence outside .dynstr)
        val old = (GLES1 + "\u0000").toByteArray(Charsets.ISO_8859_1)
        val rep = ByteArray(old.size)
        System.arraycopy(v2bytes, 0, rep, 0, v2bytes.size)
        var strPatched = 0
        var from = 0
        while (true) {
            val at = indexOf(d, old, from)
            if (at < 0) break
            from = at + old.size
            if (at >= dynstr.offset && at < dynstr.offset + dynstr.size) continue
            System.arraycopy(rep, 0, d, at, rep.size)
            strPatched++
        }
        if (neededPatched == 0 && strPatched == 0) {
            throw ApkPatcher.PatchException("libSDL2.so does not reference libGLESv1_CM.so - unexpected build")
        }
        return "GLES1 -> libGLESv2.so (DT_NEEDED x$neededPatched, dlopen string x$strPatched, ${weak.size} weak symbols)"
    }

    /** Overwrites SoA's external video folder strings in place. */
    private fun patchVideoPrefix(game: Game, d: ByteArray): String {
        val newPrefix = "/storage/emulated/0/Android/obb/$LAUNCHER_PKG/${game.videoFolder}/"
        val pairs = mutableListOf<Pair<String, String>>()
        if (game.directPrefixToo) pairs.add((":direct:" + game.oldVideoPrefix) to (":direct:" + newPrefix))
        pairs.add(game.oldVideoPrefix to newPrefix)
        var total = 0
        for ((o, nw) in pairs) {
            val oldB = (o + "\u0000").toByteArray(Charsets.ISO_8859_1)
            val newB = nw.toByteArray(Charsets.ISO_8859_1)
            if (newB.size + 1 > oldB.size) throw ApkPatcher.PatchException("internal: new video path longer than the old one")
            var from = 0
            while (true) {
                val at = indexOf(d, oldB, from)
                if (at < 0) break
                java.util.Arrays.fill(d, at, at + oldB.size, 0.toByte())
                System.arraycopy(newB, 0, d, at, newB.size)
                from = at + oldB.size
                total++
            }
        }
        if (total == 0) {
            throw ApkPatcher.PatchException(
                "the SoA video folder string was not found in ${game.gameLibSuffix} - " +
                    "this tool needs the normal SoA pipeline's output state (use the original, unpatched game APK)"
            )
        }
        return "video folder -> Android/obb/$LAUNCHER_PKG/${game.videoFolder}/ (x$total)"
    }

    // ------------------------------------------------------------------ dex / smali

    class SmaliResult(val text: String, val changed: Boolean, val piCalls: Int, val helpers: List<String>)

    private val PI_RE = Regex(
        "(invoke-static(?:/range)? [^\\n]*?), Landroid/app/PendingIntent;->(getActivity|getBroadcast|getService|getActivities)\\("
    )

    fun patchSmali(game: Game, relPath: String, text: String): SmaliResult {
        var t = text
        var changed = false
        var pi = 0
        val helpers = mutableListOf<String>()
        if (!relPath.startsWith("org/soa/") && t.contains("Landroid/app/PendingIntent;->")) {
            t = PI_RE.replace(t) { m -> pi++; m.groupValues[1] + ", Lorg/soa/PI;->" + m.groupValues[2] + "(" }
            if (pi > 0) changed = true
        }
        if (relPath.endsWith("com/google/android/vending/expansion/downloader/Helpers.smali")) {
            val r1 = patchHelpersObbDir(t)
            if (r1 != null) { t = r1; helpers.add("getSaveFilePath -> getObbDir()"); changed = true }
            val r2 = patchHelpersPackage(t, game.pkg)
            if (r2 != null) { t = r2; helpers.add("getExpansionAPKFileName -> ${game.pkg}"); changed = true }
        }
        return SmaliResult(t, changed, pi, helpers)
    }

    private fun methodRange(lines: List<String>, headerContains: String): IntRange? {
        val s = lines.indexOfFirst { it.startsWith(".method ") && it.contains(headerContains) }
        if (s < 0) return null
        var e = s + 1
        while (e < lines.size && !lines[e].startsWith(".end method")) e++
        return s..e
    }

    private fun patchHelpersObbDir(text: String): String? {
        if (text.contains(":soa_orig")) return null
        val lines = text.split("\n").toMutableList()
        val r = methodRange(lines, "getSaveFilePath(Landroid/content/Context;)Ljava/lang/String;") ?: return null
        var ins = -1
        for (i in r.first + 1 until r.last) {
            val tr = lines[i].trim()
            if (tr.isEmpty() || tr.startsWith(".") || tr.startsWith(":")) continue
            ins = i
            break
        }
        if (ins < 0) return null
        val locals = lines.subList(r.first, r.last).firstOrNull { it.trim().startsWith(".locals") }
            ?.trim()?.removePrefix(".locals")?.trim()?.toIntOrNull() ?: return null
        if (locals < 1) return null
        val snippet = listOf(
            "    invoke-virtual {p0}, Landroid/content/Context;->getObbDir()Ljava/io/File;",
            "",
            "    move-result-object v0",
            "",
            "    if-eqz v0, :soa_orig",
            "",
            "    invoke-virtual {v0}, Ljava/io/File;->getAbsolutePath()Ljava/lang/String;",
            "",
            "    move-result-object v0",
            "",
            "    return-object v0",
            "",
            "    :soa_orig",
            ""
        )
        lines.addAll(ins, snippet)
        return lines.joinToString("\n")
    }

    private fun patchHelpersPackage(text: String, pkg: String): String? {
        val lines = text.split("\n").toMutableList()
        val r = methodRange(lines, "getExpansionAPKFileName(Landroid/content/Context;ZI)Ljava/lang/String;") ?: return null
        for (i in r.first + 1 until r.last) {
            val tr = lines[i].trim()
            if (tr != "invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;") continue
            var j = i + 1
            while (j < r.last && lines[j].trim().isEmpty()) j++
            val mv = lines[j].trim()
            if (!mv.startsWith("move-result-object v")) return null
            val reg = mv.removePrefix("move-result-object ").trim()
            lines[i] = "    const-string $reg, \"$pkg\""
            lines.removeAt(j)
            return lines.joinToString("\n")
        }
        return null
    }

    val PI_SMALI = """.class public Lorg/soa/PI;
.super Ljava/lang/Object;

.method private static fix(I)I
    .locals 2

    const/high16 v0, 0x2000000

    and-int v1, p0, v0

    if-nez v1, :keep

    const/high16 v0, 0x4000000

    or-int/2addr p0, v0

    :keep
    return p0
.end method

.method public static getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .locals 1

    invoke-static {p3}, Lorg/soa/PI;->fix(I)I

    move-result p3

    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;

    move-result-object v0

    return-object v0
.end method

.method public static getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .locals 1

    invoke-static {p3}, Lorg/soa/PI;->fix(I)I

    move-result p3

    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getBroadcast(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;

    move-result-object v0

    return-object v0
.end method

.method public static getService(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
    .locals 1

    invoke-static {p3}, Lorg/soa/PI;->fix(I)I

    move-result p3

    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getService(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;

    move-result-object v0

    return-object v0
.end method

.method public static getActivities(Landroid/content/Context;I[Landroid/content/Intent;I)Landroid/app/PendingIntent;
    .locals 1

    invoke-static {p3}, Lorg/soa/PI;->fix(I)I

    move-result p3

    invoke-static {p0, p1, p2, p3}, Landroid/app/PendingIntent;->getActivities(Landroid/content/Context;I[Landroid/content/Intent;I)Landroid/app/PendingIntent;

    move-result-object v0

    return-object v0
.end method

.method public static getActivities(Landroid/content/Context;I[Landroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/PendingIntent;
    .locals 1

    invoke-static {p3}, Lorg/soa/PI;->fix(I)I

    move-result p3

    invoke-static {p0, p1, p2, p3, p4}, Landroid/app/PendingIntent;->getActivities(Landroid/content/Context;I[Landroid/content/Intent;ILandroid/os/Bundle;)Landroid/app/PendingIntent;

    move-result-object v0

    return-object v0
.end method
"""
}
