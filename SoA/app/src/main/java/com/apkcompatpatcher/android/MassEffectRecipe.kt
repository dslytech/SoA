package com.apkcompatpatcher.android

/**
 * Mass Effect: Infiltrator (com.ea.games.meinfiltrator_gamepad) - the whole game-specific recipe,
 * as pure text transforms on the baksmali output (no Android dependencies, so it can be tested
 * stand-alone against a real decoded APK).
 *
 * ## Why this is so small
 *
 * For a very long time this project patched Mass Effect's native code, crash after crash
 * (null guards, FreeType, HUD...). All of those were SYMPTOMS of one root cause: the game could
 * not see its data files. It looks for them in `Android/data/<package>` - a folder modern Android
 * will not let anything except the app itself create - so the game ran without its data and
 * everything downstream crashed.
 *
 * The fix is to have the game use `Android/obb/<package>` for its data instead (the user puts the
 * data files there, exactly like an ordinary OBB), which costs four small changes:
 *
 *  1. `config.properties` DATA_FOLDER and the two Java constants "/Android/data/" -> "/Android/obb/"
 *     (the first is done in [ApkPatcher], the constants here).
 *  2. [FOLDER_INIT_SMALI] - called first thing in `MassEffectActivity.onCreate`, so the system itself
 *     creates the app's `files`, `cache` and obb folders (the same getExternalFilesDir trick the
 *     Haegemonia/IG2 save-folder fix uses).
 *  3. [SAFE_TEL_SMALI] - every `TelephonyManager.getDeviceId/getSubscriberId/getSimSerialNumber/
 *     getLine1Number` call goes through a try/catch helper (Android 10+ throws a SecurityException
 *     for device identifiers, which killed the game's download SDK on its first frame).
 *  4. [SAFE_BIND_SMALI] - every `bindService`/`startService` call goes through a try/catch helper
 *     (the dead Google-Play-Billing / Samsung IAP code builds implicit service Intents, which
 *     Android 5+ rejects with IllegalArgumentException).
 *
 * Together with the generic fixes the pipeline already applies to every APK (targetSdk, text
 * relocations, broken DT_NEEDED path, org.apache.http.legacy ...) the original, unmodified native
 * code of the game runs.
 */
object MassEffectRecipe {

    const val ACTIVITY_SMALI = "com/ea/games/meinfiltrator_gamepad/MassEffectActivity.smali"
    const val UNZIP_SMALI = "com/ea/games/meinfiltrator_gamepad/ContentUnzip.smali"

    data class FileResult(val text: String, val notes: List<String>)

    private val DATA_DIR_CONST_RE = Regex("(const-string v\\d+, )\"/Android/data/\"")

    private val TEL_METHODS = listOf("getDeviceId", "getSubscriberId", "getSimSerialNumber", "getLine1Number")

    private val BIND_RE = Regex(
        "invoke-virtual \\{([pv]\\d+), ([pv]\\d+), ([pv]\\d+), ([pv]\\d+)\\}, L[\\w/\\$]+;->bindService" +
            "\\(Landroid/content/Intent;Landroid/content/ServiceConnection;I\\)Z"
    )

    private val START_RE = Regex(
        "invoke-virtual \\{([pv]\\d+), ([pv]\\d+)\\}, L(?:android/content/Context|android/app/Activity);->startService" +
            "\\(Landroid/content/Intent;\\)Landroid/content/ComponentName;"
    )

    private val ON_CREATE_RE = Regex("(?m)^\\.method public onCreate\\(Landroid/os/Bundle;\\)V[ \\t]*$")

    /** Classes to add to the dex when [patchFile] changed anything: relative smali path -> text. */
    val EXTRA_CLASSES: Map<String, String> by lazy {
        mapOf(
            "org/soa/FolderInit.smali" to FOLDER_INIT_SMALI,
            "org/soa/SafeTel.smali" to SAFE_TEL_SMALI,
            "org/soa/SafeBind.smali" to SAFE_BIND_SMALI
        )
    }

    /** True for the one file that identifies this game inside a decoded dex. */
    fun isGameMarker(rel: String): Boolean = rel.endsWith(ACTIVITY_SMALI)

    fun patchFile(rel: String, original: String): FileResult {
        var text = original
        val notes = mutableListOf<String>()

        if (rel.endsWith(ACTIVITY_SMALI) || rel.endsWith(UNZIP_SMALI)) {
            val replaced = DATA_DIR_CONST_RE.replace(text) { m -> m.groupValues[1] + "\"/Android/obb/\"" }
            if (replaced != text) {
                text = replaced
                notes.add("$rel: data folder constant \"/Android/data/\" -> \"/Android/obb/\"")
            }
        }

        if (rel.endsWith(ACTIVITY_SMALI)) {
            val (t, ok) = injectFolderInit(text)
            if (ok) {
                text = t
                notes.add("$rel: onCreate() now creates the app folders through the system API (org.soa.FolderInit)")
            }
        }

        var telCount = 0
        for (m in TEL_METHODS) {
            val re = Regex(
                "invoke-virtual \\{([pv]\\d+)\\}, Landroid/telephony/TelephonyManager;->$m\\(\\)Ljava/lang/String;"
            )
            text = re.replace(text) { mr ->
                telCount++
                "invoke-static {${mr.groupValues[1]}}, Lorg/soa/SafeTel;->$m(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"
            }
        }
        if (telCount > 0) notes.add("$rel: $telCount TelephonyManager device-identifier call(s) made exception-safe")

        var bindCount = 0
        text = BIND_RE.replace(text) { mr ->
            bindCount++
            "invoke-static {${mr.groupValues[1]}, ${mr.groupValues[2]}, ${mr.groupValues[3]}, ${mr.groupValues[4]}}, " +
                "Lorg/soa/SafeBind;->bind(Landroid/content/Context;Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z"
        }
        var startCount = 0
        text = START_RE.replace(text) { mr ->
            startCount++
            "invoke-static {${mr.groupValues[1]}, ${mr.groupValues[2]}}, " +
                "Lorg/soa/SafeBind;->start(Landroid/content/Context;Landroid/content/Intent;)Landroid/content/ComponentName;"
        }
        if (bindCount + startCount > 0) {
            notes.add("$rel: $bindCount bindService + $startCount startService call(s) made exception-safe")
        }

        return FileResult(text, notes)
    }

    /**
     * Inserts `invoke-static {p0}, Lorg/soa/FolderInit;->run(Landroid/content/Context;)V` as the very
     * first instruction of onCreate(Bundle): after the directive lines (.locals/.registers/.param
     * with their annotations/.prologue) that open the method body.
     */
    private fun injectFolderInit(text: String): Pair<String, Boolean> {
        val m = ON_CREATE_RE.find(text) ?: return text to false
        if (text.contains("Lorg/soa/FolderInit;")) return text to false
        val lines = text.substring(m.range.last + 1).split("\n")
        // lines[0] is the remainder of the .method line (empty) - start after it
        var idx = 1
        var offset = lines[0].length + 1
        var inAnnotation = false
        while (idx < lines.size) {
            val t = lines[idx].trim()
            val skip = when {
                inAnnotation -> { if (t.startsWith(".end annotation")) inAnnotation = false; true }
                t.isEmpty() -> true
                t.startsWith(".annotation") -> { inAnnotation = true; true }
                t.startsWith(".locals") || t.startsWith(".registers") || t.startsWith(".param") ||
                    t.startsWith(".end param") || t.startsWith(".prologue") -> true
                else -> false
            }
            if (!skip) break
            offset += lines[idx].length + 1
            idx++
        }
        if (idx >= lines.size) return text to false
        val insertAt = m.range.last + 1 + offset
        val snippet = "    invoke-static {p0}, Lorg/soa/FolderInit;->run(Landroid/content/Context;)V\n\n"
        return text.substring(0, insertAt) + snippet + text.substring(insertAt) to true
    }

    /** Creates files/, cache/ and obb folders through the system API (so they get the right owner). */
    private val FOLDER_INIT_SMALI = """
        |.class public Lorg/soa/FolderInit;
        |.super Ljava/lang/Object;
        |
        |.method public static run(Landroid/content/Context;)V
        |    .locals 2
        |
        |    :try_start_0
        |    const/4 v0, 0x0
        |
        |    invoke-virtual {p0, v0}, Landroid/content/Context;->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;
        |
        |    move-result-object v1
        |
        |    if-eqz v1, :skip_0
        |
        |    invoke-virtual {v1}, Ljava/io/File;->mkdirs()Z
        |
        |    :skip_0
        |    invoke-virtual {p0}, Landroid/content/Context;->getObbDir()Ljava/io/File;
        |
        |    move-result-object v1
        |
        |    if-eqz v1, :skip_1
        |
        |    invoke-virtual {v1}, Ljava/io/File;->mkdirs()Z
        |
        |    :skip_1
        |    invoke-virtual {p0}, Landroid/content/Context;->getExternalCacheDir()Ljava/io/File;
        |    :try_end_0
        |    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
        |
        |    :catch_0
        |    return-void
        |.end method
        |""".trimMargin()

    private fun safeTelSmali(): String {
        val sb = StringBuilder(".class public Lorg/soa/SafeTel;\n.super Ljava/lang/Object;\n\n")
        for (m in TEL_METHODS) {
            sb.append(
                """
                |.method public static $m(Landroid/telephony/TelephonyManager;)Ljava/lang/String;
                |    .locals 1
                |
                |    :try_start_0
                |    invoke-virtual {p0}, Landroid/telephony/TelephonyManager;->$m()Ljava/lang/String;
                |
                |    move-result-object v0
                |    :try_end_0
                |    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
                |
                |    return-object v0
                |
                |    :catch_0
                |    const-string v0, ""
                |
                |    return-object v0
                |.end method
                |
                |""".trimMargin()
            )
        }
        return sb.toString()
    }

    private val SAFE_TEL_SMALI: String by lazy { safeTelSmali() }

    private val SAFE_BIND_SMALI = """
        |.class public Lorg/soa/SafeBind;
        |.super Ljava/lang/Object;
        |
        |.method public static bind(Landroid/content/Context;Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z
        |    .locals 1
        |
        |    :try_start_0
        |    invoke-virtual {p0, p1, p2, p3}, Landroid/content/Context;->bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z
        |
        |    move-result v0
        |    :try_end_0
        |    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
        |
        |    return v0
        |
        |    :catch_0
        |    const/4 v0, 0x0
        |
        |    return v0
        |.end method
        |
        |.method public static start(Landroid/content/Context;Landroid/content/Intent;)Landroid/content/ComponentName;
        |    .locals 1
        |
        |    :try_start_0
        |    invoke-virtual {p0, p1}, Landroid/content/Context;->startService(Landroid/content/Intent;)Landroid/content/ComponentName;
        |
        |    move-result-object v0
        |    :try_end_0
        |    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
        |
        |    return-object v0
        |
        |    :catch_0
        |    const/4 v0, 0x0
        |
        |    return-object v0
        |.end method
        |""".trimMargin()
}
