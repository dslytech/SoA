package com.apkcompatpatcher.android

import android.content.Context
import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * On-device equivalent of the smali-text patch functions in the PC tool's
 * `patch_apk.py` (`patch_ffmpeg_loadlibrary`, `wrap_whole_method`). Same
 * overall strategy, ported to run without apktool: baksmali decodes one dex
 * file to a tree of .smali TEXT files, this class applies patches to that
 * text, then `smali` re-assembles the tree back into a .dex.
 *
 * The Moga controller fix deliberately does NOT port the PC tool's
 * `patch_moga_controller` (an exact literal-text find/replace lifted from
 * one specific decompile) - that was too brittle in practice, since the
 * precise smali bytecode for the same source line (register numbers,
 * whether debug `.line`/`.local` pragmas are even present) can differ
 * between builds and between baksmali configurations. Instead it wraps the
 * whole `Controller.init()` method in try/catch via [wrapWholeMethod], the
 * same general mechanism already used for the AdManager fix - this works
 * regardless of the exact bytecode shape.
 *
 * Fix #5 (a global background-thread uncaught-exception handler, installed
 * as the first thing the game's entry Activity's <clinit> does - the same
 * place the SDL_ffmpeg loadLibrary() call lives, so it's guaranteed to run
 * as early as possible) IS ported here, as [installUncaughtHandler] plus
 * the [SILENT_HANDLER_CLASS_SMALI] class written straight into the smali
 * tree as one more plain .smali file before reassembly - see README.md for
 * why this turned out to be much lower-risk than first assumed (no
 * dexlib2 low-level builder API needed at all, since the whole pipeline
 * here already round-trips through smali TEXT).
 *
 * NOTE FOR WHOEVER BUILDS THIS: this file is the single most likely place
 * to hit a compile error in Android Studio, because the exact field/method
 * names on `BaksmaliOptions`/`SmaliOptions` were written from memory of the
 * dexlib2/smali API shape (this sandbox cannot download the library to
 * check against the real sources - see README.md for why). If Android
 * Studio reports "cannot find symbol" here, paste the exact error back -
 * it will point at one specific wrong name to fix, not a design problem.
 */
object DexPatcher {

    /** 1.15: org.soa.StorageDiag creates Android/data/<pkg>/files/save + campaignsave through the system API (Haegemonia + IG2). */
    private const val STORAGE_DIAG_ENABLED = true

    data class Result(val changes: List<String>, val dexFiles: Map<String, ByteArray>)

    private val FFMPEG_LOADLIBRARY_RE = Regex(
        "( {4}const-string v(\\d+), \"SDL_ffmpeg\"\\n\\n" +
            " {4}invoke-static \\{v\\2\\}, Ljava/lang/System;->loadLibrary\\(Ljava/lang/String;\\)V\\n)"
    )

    private fun patchFfmpegLoadLibrary(text: String): Pair<String, Boolean> {
        val m = FFMPEG_LOADLIBRARY_RE.find(text) ?: return text to false
        val v = m.groupValues[2]
        val block = m.groupValues[1]
        val replacement = buildString {
            append("    :try_start_ffmpeg\n")
            append(block)
            append("    :try_end_ffmpeg\n")
            append("    .catch Ljava/lang/Throwable; {:try_start_ffmpeg .. :try_end_ffmpeg} :catch_ffmpeg\n")
            append("    goto :goto_ffmpeg\n\n")
            append("    :catch_ffmpeg\n")
            append("    move-exception v$v\n\n")
            append("    :goto_ffmpeg\n")
        }
        val r = m.range
        return (text.substring(0, r.first) + replacement + text.substring(r.last + 1)) to true
    }

    /**
     * The EA "eamobile" download SDK's `sendHttpPost()` (used by
     * `DownloadActivityInternal.updateDownloadFilesData()` to fetch the manifest of files the
     * game needs, before it ever downloads a single byte of actual game content) sets an Apache
     * `HttpClient` *connect* timeout - `HttpConnectionParams.setConnectionTimeout(params, 10000)`
     * - but never sets the matching *socket read* timeout (`setSoTimeout`). Confirmed by
     * decompiling the real Mass Effect: Infiltrator APK the user sent (2026-09-04): this is the
     * exact reason the "checking for updates" step can hang forever with no dialog, no crash, no
     * ANR (the whole call runs on a plain background `Thread`, so Android's ANR watchdog - which
     * only watches the main thread and broadcast/service timeouts - can't see it either):
     *   1. `DefaultHttpClient.execute()` opens a TCP connection to `DOWNLOAD_URL`
     *      (`http://gam.eamobile.com/` for this game - a server that's been dead for over a
     *      decade). The 10s connect timeout only bounds *that* step.
     *   2. If the connect succeeds at all - e.g. the domain now resolves to a parked-domain
     *      page, an ad network, or anything else that accepts the TCP handshake but never sends
     *      a proper HTTP response - `execute()` then blocks on *reading* the response, and
     *      there is no code-level limit on that wait at all. It can hang indefinitely.
     * This is a real, general bug in EA's own SDK (not a game-specific edge case), so - like
     * [patchFfmpegLoadLibrary] - it's matched by the exact library API call anywhere in the
     * smali tree rather than scoped to one file: wherever `setConnectionTimeout(...)` is called
     * on an `HttpParams` object, this inserts a `setSoTimeout(...)` call right after it, reusing
     * the same two registers (the `HttpParams` object and the already-loaded timeout constant
     * are both still valid at that exact point - nothing in between touches them). The result:
     * a server that never answers now fails with a `SocketTimeoutException` after the same ~10s
     * instead of hanging forever - caught by `sendHttpPost()`'s own existing try/catch, which
     * logs it and returns null/retries once, letting the app reach a real error screen
     * (`STATE_SERVER_ERROR` / `ServerErrorView`) instead of freezing silently. It does NOT make
     * the missing game content download successfully - the server is still dead - but it turns
     * an unrecoverable infinite hang into a fast, visible failure.
     */
    private val EA_HTTP_CONNECT_TIMEOUT_RE = Regex(
        "( {4}invoke-static \\{(v\\d+), (v\\d+)\\}, Lorg/apache/http/params/HttpConnectionParams;" +
            "->setConnectionTimeout\\(Lorg/apache/http/params/HttpParams;I\\)V\\n)"
    )

    private fun patchEaHttpPostReadTimeout(text: String): Pair<String, Boolean> {
        var changed = false
        val patched = EA_HTTP_CONNECT_TIMEOUT_RE.replace(text) { m ->
            changed = true
            val paramsReg = m.groupValues[2]
            val timeoutReg = m.groupValues[3]
            m.groupValues[1] + "\n    invoke-static {$paramsReg, $timeoutReg}, " +
                "Lorg/apache/http/params/HttpConnectionParams;->setSoTimeout(Lorg/apache/http/params/HttpParams;I)V\n"
        }
        return patched to changed
    }

    /**
     * A small new class, written verbatim as smali text into the tree (exactly like every
     * other .smali file baksmali produced - `Smali.assemble()` picks up the whole directory,
     * so one more hand-written file in it is no different from the decoded ones). Ported from
     * the PC tool's `patch_apk.py` `SILENT_HANDLER_CLASS` almost unchanged (only the `$` in
     * `Thread$UncaughtExceptionHandler` needs escaping here, since it's a Kotlin string).
     * Installed as the process's default [Thread.UncaughtExceptionHandler]: any *background*
     * thread crash is swallowed (logged via `printStackTrace()`, not rethrown) instead of
     * killing the whole process, while a crash on the *main* thread is passed through to
     * whatever handler was previously registered - so a real, user-visible bug on the main
     * thread still surfaces normally, only "some background ad-SDK thread nobody asked for
     * broke" gets silenced. See README.md, "Fixed via real-device testing" - this is fix #5
     * from the PC tool's case-study.md, Round 4: the specific gap it closes is a background
     * thread crashing somewhere `AdManager.getMessage()`'s own try/catch doesn't cover (e.g.
     * an ad SDK's own background thread calling straight into removed `org.apache.http.*`
     * classes without going through AdManager at all).
     */
    private val SILENT_HANDLER_CLASS_SMALI = """
        |.class public Lapkcompatpatcher/SilentUncaughtHandler;
        |.super Ljava/lang/Object;
        |.implements Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
        |
        |
        |# instance fields
        |.field private final previous:Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
        |
        |
        |# direct methods
        |.method public constructor <init>()V
        |    .locals 1
        |
        |    .prologue
        |    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
        |
        |    invoke-static {}, Ljava/lang/Thread;->getDefaultUncaughtExceptionHandler()Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
        |
        |    move-result-object v0
        |
        |    iput-object v0, p0, Lapkcompatpatcher/SilentUncaughtHandler;->previous:Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
        |
        |    return-void
        |.end method
        |
        |
        |# virtual methods
        |.method public uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V
        |    .locals 2
        |    .param p1, "t"    # Ljava/lang/Thread;
        |    .param p2, "e"    # Ljava/lang/Throwable;
        |
        |    .prologue
        |    invoke-virtual {p1}, Ljava/lang/Thread;->getName()Ljava/lang/String;
        |
        |    move-result-object v0
        |
        |    const-string v1, "main"
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        |
        |    move-result v0
        |
        |    if-eqz v0, :cond_1
        |
        |    iget-object v0, p0, Lapkcompatpatcher/SilentUncaughtHandler;->previous:Ljava/lang/Thread${'$'}UncaughtExceptionHandler;
        |
        |    if-eqz v0, :cond_0
        |
        |    invoke-interface {v0, p1, p2}, Ljava/lang/Thread${'$'}UncaughtExceptionHandler;->uncaughtException(Ljava/lang/Thread;Ljava/lang/Throwable;)V
        |
        |    :cond_0
        |    return-void
        |
        |    :cond_1
        |    invoke-virtual {p2}, Ljava/lang/Throwable;->printStackTrace()V
        |
        |    return-void
        |.end method
        |""".trimMargin()

    /**
     * Installs the handler above as the very first thing the entry Activity's `<clinit>` does
     * (the same class whose `<clinit>` calls `System.loadLibrary("SDL_ffmpeg")` - guaranteed to
     * classload as early as any code in the app runs). Only needs a spare `v0`, which is safe
     * to clobber unconditionally since this snippet runs before anything else in the method
     * body: whatever the method does with v0 afterwards simply reassigns it, and nothing before
     * our snippet has read from it yet. Falls back to inserting right after `.locals` (like
     * [wrapWholeMethod] does) when `.prologue` isn't present, for the same reason.
     */
    private fun installUncaughtHandler(text: String): Pair<String, Boolean> {
        val clinitStartRe = Regex("(?m)^\\.method static constructor <clinit>\\(\\)V\\s*$")
        val startMatch = clinitStartRe.find(text) ?: return text to false
        val methodStart = startMatch.range.first

        val endMethodRe = Regex("(?m)^\\.end method\\s*$")
        val afterStart = text.substring(methodStart)
        val endMatch = endMethodRe.find(afterStart) ?: return text to false
        val methodEnd = methodStart + endMatch.range.last + 1
        val methodText = text.substring(methodStart, methodEnd)

        val localsRe = Regex("(?m)^\\s*\\.locals (\\d+)\\s*$")
        val localsMatch = localsRe.find(methodText) ?: return text to false
        if (localsMatch.groupValues[1].toInt() < 1) return text to false // no spare v0 to use

        val prologueRe = Regex("(?m)^\\s*\\.prologue\\s*$")
        val prologueMatch = prologueRe.find(methodText)
        val insertAt = methodStart + (prologueMatch?.let { it.range.last + 1 } ?: (localsMatch.range.last + 1))

        val snippet = "\n    new-instance v0, Lapkcompatpatcher/SilentUncaughtHandler;\n\n" +
            "    invoke-direct {v0}, Lapkcompatpatcher/SilentUncaughtHandler;-><init>()V\n\n" +
            "    invoke-static {v0}, Ljava/lang/Thread;->setDefaultUncaughtExceptionHandler(Ljava/lang/Thread${'$'}UncaughtExceptionHandler;)V\n"

        return text.substring(0, insertAt) + snippet + text.substring(insertAt) to true
    }

    /**
     * EXPERIMENT (1.13c): Haegemonia storage fix + diagnostic. Runs once per process from
     * SDLActivity.onResume (no-op for every package except Haegemonia): calls
     * getExternalFilesDir(null) (makes the system create Android/data/<pkg>/files with correct
     * ownership), creates save/ and campaignsave/ inside it, test-writes a byte into each and
     * logs the outcome (tag SoADiag) via android.util.Log. One try/catch(Throwable) around it.
     */
    private val STORAGE_DIAG_CLASS_SMALI = """
        |.class public Lorg/soa/StorageDiag;
        |.super Ljava/lang/Object;
        |
        |.field private static done:Z
        |
        |.method public static run(Landroid/content/Context;)V
        |    .locals 8
        |
        |    :try_start_0
        |    sget-boolean v0, Lorg/soa/StorageDiag;->done:Z
        |
        |    if-nez v0, :end_0
        |
        |    const/4 v0, 0x1
        |
        |    sput-boolean v0, Lorg/soa/StorageDiag;->done:Z
        |
        |    invoke-virtual {p0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;
        |
        |    move-result-object v1
        |
        |    const-string v0, "com.microids.google.HaegemoniaFull"
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        |
        |    move-result v0
        |
        |    if-nez v0, :pkg_ok
        |
        |    const-string v0, "com.digitalreality.ig2"
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        |
        |    move-result v0
        |
        |    if-eqz v0, :end_0
        |
        |    :pkg_ok
        |
        |    new-instance v2, Ljava/lang/StringBuilder;
        |
        |    invoke-direct {v2}, Ljava/lang/StringBuilder;-><init>()V
        |
        |    const-string v0, "SoADiag v1 "
        |
        |    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    const/4 v0, 0x0
        |
        |    invoke-virtual {p0, v0}, Landroid/content/Context;->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;
        |
        |    move-result-object v5
        |
        |    const-string v0, "extFilesDir="
        |
        |    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    invoke-virtual {v2, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
        |
        |    const-string v0, " | "
        |
        |    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    if-eqz v5, :skip_ext
        |
        |    invoke-static {v5}, Lorg/soa/StorageDiag;->probe(Ljava/io/File;)Ljava/lang/String;
        |
        |    move-result-object v0
        |
        |    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    new-instance v6, Ljava/io/File;
        |
        |    const-string v0, "save"
        |
        |    invoke-direct {v6, v5, v0}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
        |
        |    invoke-static {v6}, Lorg/soa/StorageDiag;->probe(Ljava/io/File;)Ljava/lang/String;
        |
        |    move-result-object v0
        |
        |    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    new-instance v6, Ljava/io/File;
        |
        |    const-string v0, "campaignsave"
        |
        |    invoke-direct {v6, v5, v0}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
        |
        |    invoke-static {v6}, Lorg/soa/StorageDiag;->probe(Ljava/io/File;)Ljava/lang/String;
        |
        |    move-result-object v0
        |
        |    invoke-virtual {v2, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    :skip_ext
        |    const-string v0, "SoADiag"
        |
        |    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
        |
        |    move-result-object v3
        |
        |    invoke-static {v0, v3}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
        |
        |    :try_end_0
        |    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
        |
        |    :end_0
        |    return-void
        |
        |    :catch_0
        |    move-exception v0
        |
        |    const-string v1, "SoADiag"
        |
        |    const-string v2, "diag failed"
        |
        |    invoke-static {v1, v2, v0}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I
        |
        |    return-void
        |.end method
        |
        |.method private static probe(Ljava/io/File;)Ljava/lang/String;
        |    .locals 5
        |
        |    new-instance v0, Ljava/lang/StringBuilder;
        |
        |    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
        |
        |    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
        |
        |    const-string v1, " exists0="
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    invoke-virtual {p0}, Ljava/io/File;->exists()Z
        |
        |    move-result v1
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;
        |
        |    const-string v1, " mkdirs="
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    invoke-virtual {p0}, Ljava/io/File;->mkdirs()Z
        |
        |    move-result v1
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;
        |
        |    const-string v1, " isDir="
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    invoke-virtual {p0}, Ljava/io/File;->isDirectory()Z
        |
        |    move-result v1
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;
        |
        |    :try_start_0
        |    new-instance v1, Ljava/io/File;
        |
        |    const-string v2, "soa_test.bin"
        |
        |    invoke-direct {v1, p0, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V
        |
        |    new-instance v2, Ljava/io/FileOutputStream;
        |
        |    invoke-direct {v2, v1}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V
        |
        |    const/16 v3, 0x41
        |
        |    invoke-virtual {v2, v3}, Ljava/io/FileOutputStream;->write(I)V
        |
        |    invoke-virtual {v2}, Ljava/io/FileOutputStream;->close()V
        |
        |    invoke-virtual {v1}, Ljava/io/File;->delete()Z
        |
        |    const-string v1, " write=ok"
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    :try_end_0
        |    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :catch_0
        |
        |    :after_0
        |    const-string v1, " | "
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
        |
        |    move-result-object v1
        |
        |    return-object v1
        |
        |    :catch_0
        |    move-exception v1
        |
        |    const-string v2, " write=FAIL "
        |
        |    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        |
        |    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;
        |
        |    goto :after_0
        |.end method
        |""".trimMargin()

    private fun injectStorageDiagCall(text: String): Pair<String, Boolean> {
        val startRe = Regex("(?m)^\\.method [a-z ]*onResume\\(\\)V\\s*$")
        val startMatch = startRe.find(text) ?: return text to false
        val methodStart = startMatch.range.first
        val endRe = Regex("(?m)^\\.end method\\s*$")
        val endMatch = endRe.find(text.substring(methodStart)) ?: return text to false
        val methodEnd = methodStart + endMatch.range.last + 1
        val methodText = text.substring(methodStart, methodEnd)
        if (methodText.contains("Lorg/soa/StorageDiag;")) return text to false
        val localsMatch = Regex("(?m)^\\s*\\.locals (\\d+)\\s*$").find(methodText) ?: return text to false
        val prologueMatch = Regex("(?m)^\\s*\\.prologue\\s*$").find(methodText)
        val insertAt = methodStart + (prologueMatch?.let { it.range.last + 1 } ?: (localsMatch.range.last + 1))
        val snippet = "\n    invoke-static/range {p0 .. p0}, Lorg/soa/StorageDiag;->run(Landroid/content/Context;)V\n"
        return text.substring(0, insertAt) + snippet + text.substring(insertAt) to true
    }

    /**
     * The catch handler this generates has to return *something* matching the method's own
     * declared return type, or the dex bytecode verifier rejects the whole class at load time
     * (a `return-void` inside a method declared to return `Z`/boolean, for instance, is a
     * verification failure, not just "wrong behavior" - the class would fail to load at all).
     * [methodDeclLine] is the method's own `.method ...(...)X` line, whose trailing `X` says
     * what to return; `null` means a wide type (J/D), which needs a register pair this helper
     * doesn't allocate for - callers should treat that as "can't safely wrap this method".
     */
    private fun returnInstructionFor(methodDeclLine: String, reg: String): String? {
        val paren = methodDeclLine.lastIndexOf(')')
        if (paren < 0 || paren + 1 >= methodDeclLine.length) return null
        val ret = methodDeclLine.substring(paren + 1).trim()
        return when {
            ret == "V" -> "    return-void\n"
            ret.length == 1 && ret[0] in "ZBSCIF" -> "    const/4 $reg, 0x0\n    return $reg\n"
            ret.startsWith("L") || ret.startsWith("[") -> "    const/4 $reg, 0x0\n    return-object $reg\n"
            else -> null // J or D (wide) - needs a register pair, not supported here
        }
    }

    private fun wrapWholeMethod(text: String, methodSignatureRe: String, tag: String): Pair<String, Boolean> {
        val methodStartRe = Regex("(?m)^\\.method .*$methodSignatureRe.*$")
        val startMatch = methodStartRe.find(text) ?: return text to false
        val methodStart = startMatch.range.first
        val methodDeclLine = startMatch.value

        val endMethodRe = Regex("(?m)^\\.end method\\s*$")
        val afterStart = text.substring(methodStart)
        val endMatch = endMethodRe.find(afterStart) ?: return text to false
        val methodEnd = methodStart + endMatch.range.last + 1
        var methodText = text.substring(methodStart, methodEnd)

        val localsRe = Regex("(?m)^\\s*\\.locals (\\d+)\\s*$")
        val localsMatch = localsRe.find(methodText) ?: return text to false
        val nLocals = localsMatch.groupValues[1].toInt()
        val excReg = "v$nLocals"
        val newLocalsLine = "    .locals ${nLocals + 1}"
        // Computed against methodText AFTER the substitution below (the new line's length can
        // differ from the old one's, e.g. "9" -> "10"), so this is where the new locals line
        // actually ends in the text we go on to search/insert into - not the old offset.
        val localsLineEnd = localsMatch.range.first + newLocalsLine.length
        methodText = methodText.substring(0, localsMatch.range.first) +
            newLocalsLine +
            methodText.substring(localsMatch.range.last + 1)

        // .prologue is a debug-info marker baksmali only emits when the dex's debug table has
        // a "prologue end" entry for this method - not guaranteed (release-mode/optimized dex
        // builds can lack it even when other debug info, like line numbers, is present). Prefer
        // inserting right after .prologue when it's there (skips past any .param/.annotation
        // debug pragmas baksmali puts between .locals and the code); otherwise fall back to
        // right after the .locals line itself, which every method has - a label there is valid
        // smali regardless of what debug pragmas follow it.
        val prologueRe = Regex("(?m)^\\s*\\.prologue\\s*$")
        val prologueMatch = prologueRe.find(methodText)
        val insertAt = prologueMatch?.let { it.range.last + 1 } ?: localsLineEnd
        methodText = methodText.substring(0, insertAt) + "\n    :try_start_$tag" + methodText.substring(insertAt)

        val returnInstr = returnInstructionFor(methodDeclLine, excReg) ?: return text to false

        val endMethodMatch2 = endMethodRe.find(methodText) ?: return text to false
        val handler = "\n    :try_end_$tag\n" +
            "    .catch Ljava/lang/Throwable; {:try_start_$tag .. :try_end_$tag} :catch_$tag\n\n" +
            "    :catch_$tag\n" +
            "    move-exception $excReg\n\n" +
            returnInstr
        methodText = methodText.substring(0, endMethodMatch2.range.first) + handler +
            methodText.substring(endMethodMatch2.range.first)

        return text.substring(0, methodStart) + methodText + text.substring(methodEnd) to true
    }

    /**
     * Replaces a whole method's body with an unconditional `return true` - a much blunter
     * tool than [wrapWholeMethod] (which only ever adds a safety net around what a method
     * already does), used only where the actual goal is "always take the branch this
     * method's true case leads to" - a real, intentional behavior change, not a guard
     * against a crash. Refuses to touch a method whose own declared return type isn't `Z`
     * (boolean), rather than emit bytecode for the wrong type.
     *
     * ## Why this exists: Mass Effect: Infiltrator's dead-server dead end
     *
     * Fix #14 (the `org.apache.http.legacy` `<uses-library>` fix) let the EA download SDK's
     * `sendHttpPost()` genuinely run for the first time - and a real-device log then showed
     * the actual, final, external cause of this game's "please download 440-900MB" screen:
     * `UnknownHostException: Unable to resolve host "gam.eamobile.com"` - EA's own domain no
     * longer exists in DNS at all, not just "down". This is unfixable by anything in this
     * app; no APK patch makes a deleted DNS record resolve again.
     *
     * The user had already sourced and correctly placed the game's ~440-900MB of real content
     * locally (`Downloaded.indicate` = `COMPLETE`, confirmed via `AssetManager.assetsFoundLocally()`
     * returning true - see fix #13's doc comment), but the game still insists on contacting
     * the server every single launch and never falls back to the already-complete local copy.
     * Decompiling `DownloadActivityInternal.init()` shows exactly why: whether it goes to
     * `STATE_CONTACTING_SERVER` (the path that leads to this dead-server dead end) or straight
     * to `STATE_SUCCESS` (which just calls `IDownloadActivity.onResult()` with the local asset
     * path - i.e. "load the game now") depends on two things being true together: local assets
     * are found (they are), AND `shouldSkipUpdateCheck()` returns `true`. That method's own
     * logic (confirmed by decompiling it) is: if `NUMBER_OF_HOURS_TO_UPDATE_CHECKING <= 0`,
     * return `false` unconditionally, logging "NUMBER_OF_HOURS_TO_UPDATE_CHECKING not defined
     * or invalid." - and this game's own `config.properties` sets that value to exactly `0`.
     * In 2012, with EA's server alive, "always re-check for a newer version before playing"
     * was a reasonable default. In 2026, with that server's domain deleted, it's a permanent,
     * unconditional dead end for a game whose local content is already complete and correct.
     *
     * Forcing `shouldSkipUpdateCheck()` to always return `true` is deliberately narrow: per
     * `init()`'s own decompiled logic, this method's return value is only ever consulted
     * *after* `assetsFoundLocally()` has already returned `true` - if local assets are missing,
     * `init()` goes to `STATE_CONTACTING_SERVER` regardless of this method, so this patch has
     * zero effect on the normal "please download the content" flow for anyone who hasn't
     * already placed complete local data. It only changes what happens in the one case where
     * the game would otherwise ask a server that can never answer whether content it can
     * already see on disk is still current.
     *
     * Not scoped to one fixed file path (same reasoning as `initializeIAP()`/
     * `getAndroidUniqueId()` above): `shouldSkipUpdateCheck` is a distinctive enough name that
     * matching it anywhere in the smali tree is safe, and this is shared EA SDK code, not
     * something tied to this one game's package name.
     */
    private fun forceReturnTrue(text: String, methodSignatureRe: String): Pair<String, Boolean> {
        val methodStartRe = Regex("(?m)^\\.method .*$methodSignatureRe.*$")
        val startMatch = methodStartRe.find(text) ?: return text to false
        val methodStart = startMatch.range.first
        val methodDeclLine = startMatch.value
        if (!methodDeclLine.trimEnd().endsWith("Z")) return text to false // not boolean-returning - don't guess

        val endMethodRe = Regex("(?m)^\\.end method\\s*$")
        val afterStart = text.substring(methodStart)
        val endMatch = endMethodRe.find(afterStart) ?: return text to false
        val methodEnd = methodStart + endMatch.range.last + 1

        val newBody = "$methodDeclLine\n    .locals 1\n\n    const/4 v0, 0x1\n\n    return v0\n.end method"
        return (text.substring(0, methodStart) + newBody + text.substring(methodEnd)) to true
    }

    /**
     * **Superseded by fix #20** (see the call site below, which now calls [forceReturnTrue] on
     * this same method instead) - kept in the codebase in case a future case needs the exception/
     * return-value visibility this gave rather than fix #20's blunter "just trust local content"
     * approach. No longer called from the main patch loop.
     *
     * Diagnostic-only instrumentation for `AssetManager.assetsFoundLocally()` (fix #16) - adds
     * zero behavior change, only logging, to find out WHY this method keeps returning `false`
     * for Mass Effect: Infiltrator even after the user re-placed a complete, independently
     * verified copy of the game's local content (`Downloaded.indicate` = "COMPLETE",
     * `Downloaded2.indicate` listing 1180 files, every single one confirmed present on the
     * device via a direct `adb shell` existence check - real-device evidence chain, 2026-09-05).
     *
     * The real crash log (`d5b16c88`) shows the flow going straight to `STATE_CONTACTING_SERVER`
     * with **zero** `"Checking file: ... (OK)"`/`"(NOT FOUND)"` lines anywhere in it - meaning
     * `checkFiles()` (which logs exactly one such line per entry in `Downloaded2.indicate`) was
     * never even reached. Decompiling `assetsFoundLocally()` shows it can return `false` without
     * ever calling `checkFiles()` in exactly two ways: `Downloaded.indicate` doesn't exist, or
     * its content doesn't contain "COMPLETE" - both independently ruled out here (the user's own
     * `adb shell cat` of that exact file, taken right after the failing run, shows it intact,
     * unmodified, and containing "COMPLETE"). The one thing consistent with all of this: the
     * method's own `try/catch` (wrapping the whole `Downloaded.indicate` /
     * `Downloaded2.indicate` / `checkFiles()` sequence) silently swallows ANY exception and just
     * returns `false` - unlike this SDK's other methods (e.g. `getLocalAssetVersion()`), which
     * all log `"[ERROR] ..."` plus a stack trace on their own exceptions, this one method logs
     * nothing at all on failure. That silence is exactly why nothing in the crash log explains
     * the `false` result - so this patch's only job is to make that exception (if there is one)
     * visible, and to log the method's actual return value either way, so the next log capture
     * says definitively what's happening instead of leaving it a guess.
     *
     * Both insertions are made immediately before the method's single physical exit point
     * (`return v12`, which every code path - normal completion, the "no Downloaded2.indicate"
     * shortcut, and the exception handler - funnels through via `goto`, regardless of which
     * label the decompiler gave that shared spot) or inside the exception handler that leads
     * straight there. That placement is what makes this safe to splice in by text match rather
     * than real bytecode surgery: nothing after either insertion point depends on any register
     * except `v12` itself (the return value, never touched by either insertion), so any of this
     * method's other declared locals can be freely reused to build the log strings without
     * disturbing anything the method still needs.
     *
     * Matched against this one method in this one class (not a generic name-matched primitive
     * like [forceReturnTrue]/[wrapWholeMethod]), because it edits two specific interior snippets
     * rather than a method's signature or whole body. **Fix #18 update (2026-09-05)**: fix #16
     * originally matched by hardcoded exact text (`:catch_0` / `:cond_2` / `:goto_1`) taken from
     * this sandbox's apktool-decoded copy of the method - and fix #17's fallback then proved
     * (via the user's own patch log, real on-device text dumped in full) that the actual
     * dexlib2/baksmali library running on the phone gives the *same instructions and the same
     * register numbers* but completely different label names (`:catch_8d` / `:cond_6f` /
     * `:goto_6f`, i.e. raw bytecode-offset hex instead of apktool's sequential decimal labels).
     * Register numbers come from the dex bytecode itself and don't vary between decompilers;
     * label names are purely a decompiler's own invention for naming branch targets. So this
     * version matches on registers/instructions/strings only and treats every label name as a
     * wildcard, captured verbatim from whatever this file's text actually says, rather than
     * assuming either tool's naming scheme - verified against both the sandbox's apktool text
     * and the real on-device text captured 2026-09-05 (both produce a clean apktool round-trip).
     * Aborts (no-op, returns the text unchanged) rather than guessing if the surrounding
     * instructions don't match at all, or if the shared return point isn't unique, e.g. because
     * a different EA SDK build has genuinely different code here.
     */
    private fun addAssetsFoundLocallyDiagnostics(text: String): Pair<String, Boolean> {
        val methodStartRe = Regex("(?m)^\\.method public assetsFoundLocally\\(\\)Z\\s*$")
        val startMatch = methodStartRe.find(text) ?: return text to false
        val methodStart = startMatch.range.first
        val endMethodRe = Regex("(?m)^\\.end method\\s*$")
        val afterStart = text.substring(methodStart)
        val endMatch = endMethodRe.find(afterStart) ?: return text to false
        val methodEnd = methodStart + endMatch.range.last + 1
        var body = text.substring(methodStart, methodEnd)

        // Fix #19 (2026-09-05): logs the actual resolved absolute path being checked for
        // Downloaded.indicate. Added after a real-device retest (post fix #18, reboot included
        // to rule out a stale scoped-storage/FUSE view) kept showing `returning: false` with NO
        // exception, while `adb shell cat` of that exact path on the same device, moments later,
        // showed the file present with byte-correct "COMPLETE" + 15 chunk lines - a contradiction
        // that can only mean the game's own process is checking a DIFFERENT path than what adb
        // shell was shown, or something more subtle. This makes that directly observable instead
        // of assumed. No labels involved here (straight-line code, no branch), and the exact
        // instructions/registers (v7 for the path, v13 briefly free) are confirmed identical
        // between apktool and the real on-device dump, so this one is safe to match literally.
        val pathOld = "    const-string v13, \"Downloaded.indicate\"\n\n" +
            "    invoke-virtual {p0, v13}, Lcom/eamobile/download/AssetManager;->getFilePath(Ljava/lang/String;)Ljava/lang/String;\n\n" +
            "    move-result-object v7\n\n" +
            "    new-instance v3, Ljava/io/File;\n"
        if (!body.contains(pathOld)) return text to false
        val pathNew = "    const-string v13, \"Downloaded.indicate\"\n\n" +
            "    invoke-virtual {p0, v13}, Lcom/eamobile/download/AssetManager;->getFilePath(Ljava/lang/String;)Ljava/lang/String;\n\n" +
            "    move-result-object v7\n\n" +
            "    new-instance v14, Ljava/lang/StringBuilder;\n\n" +
            "    invoke-direct {v14}, Ljava/lang/StringBuilder;-><init>()V\n\n" +
            "    const-string v13, \"[SoA-diag] assetsFoundLocally() checking: \"\n\n" +
            "    invoke-virtual {v14, v13}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n" +
            "    move-result-object v14\n\n" +
            "    invoke-virtual {v14, v7}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n" +
            "    move-result-object v14\n\n" +
            "    invoke-virtual {v14}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n\n" +
            "    move-result-object v14\n\n" +
            "    invoke-static {v14}, Lcom/eamobile/download/Logging;->DEBUG_OUT(Ljava/lang/String;)V\n\n" +
            "    new-instance v3, Ljava/io/File;\n"
        body = body.replaceFirst(pathOld, pathNew)

        // The exception handler: ":catch_XXX / move-exception v2 / (blank) / goto :XXX" - label
        // names captured as-is (group 1 keeps the catch label + move-exception line, group 2
        // keeps the goto line verbatim) so this works under either decompiler's naming scheme.
        val catchRe = Regex(
            "(?m)^(\\s*:catch_\\S+\\s*\\n\\s*move-exception v2\\s*\\n\\s*\\n)(\\s*goto :\\S+\\s*\\n)"
        )
        val catchMatch = catchRe.find(body) ?: return text to false
        val newCatchMiddle = "    const-string v13, \"[SoA-diag] assetsFoundLocally() caught an exception - " +
            "returning false. Stack trace follows:\"\n\n" +
            "    invoke-static {v13}, Lcom/eamobile/download/Logging;->DEBUG_OUT(Ljava/lang/String;)V\n\n" +
            "    invoke-static {v2}, Lcom/eamobile/download/Logging;->DEBUG_OUT_STACK(Ljava/lang/Exception;)V\n\n"
        body = body.substring(0, catchMatch.range.first) +
            catchMatch.groupValues[1] + newCatchMiddle + catchMatch.groupValues[2] +
            body.substring(catchMatch.range.last + 1)

        // The method's single physical "return v12" - must be unique, or this aborts rather than
        // guessing which one is the real shared exit point.
        val returnRe = Regex("(?m)^(\\s*)return v12\\s*$")
        val returnMatches = returnRe.findAll(body).toList()
        if (returnMatches.size != 1) return text to false
        val returnMatch = returnMatches[0]
        val indent = returnMatch.groupValues[1]
        val newReturnPrefix = "${indent}invoke-static {v12}, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;\n\n" +
            "${indent}move-result-object v13\n\n" +
            "${indent}new-instance v14, Ljava/lang/StringBuilder;\n\n" +
            "${indent}invoke-direct {v14}, Ljava/lang/StringBuilder;-><init>()V\n\n" +
            "${indent}const-string v0, \"[SoA-diag] assetsFoundLocally() returning: \"\n\n" +
            "${indent}invoke-virtual {v14, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n" +
            "${indent}move-result-object v14\n\n" +
            "${indent}invoke-virtual {v14, v13}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n\n" +
            "${indent}move-result-object v14\n\n" +
            "${indent}invoke-virtual {v14}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n\n" +
            "${indent}move-result-object v14\n\n" +
            "${indent}invoke-static {v14}, Lcom/eamobile/download/Logging;->DEBUG_OUT(Ljava/lang/String;)V\n\n"
        body = body.substring(0, returnMatch.range.first) + newReturnPrefix +
            "${indent}return v12" + body.substring(returnMatch.range.last + 1)

        return (text.substring(0, methodStart) + body + text.substring(methodEnd)) to true
    }

    /**
     * Safety net for the next round of guessing, in case [wrapWholeMethod] still fails for some
     * other reason after the `.locals`/`.registers` fix above: pulls out the first ~14 lines of
     * the matched method (declaration line down through where `.locals`/`.prologue` should be)
     * so a failure logs the literal on-device baksmali text instead of just "didn't match".
     * Returns null if even the method itself couldn't be found (nothing useful to show).
     */
    private fun methodSnippetForLog(text: String, methodSignatureRe: String, maxLines: Int = 14): String? {
        val methodStartRe = Regex("(?m)^\\.method .*$methodSignatureRe.*$")
        val startMatch = methodStartRe.find(text) ?: return null
        val rest = text.substring(startMatch.range.first)
        val lines = rest.lineSequence().take(maxLines).toList()
        return lines.joinToString("\n") { "      | $it" }
    }

    /**
     * @param classesDexFiles map of dex entry name ("classes.dex",
     *   "classes2.dex", ...) to its raw bytes, exactly as read from the
     *   input APK's zip.
     * @param workDir a scratch directory the caller owns and can delete
     *   afterwards (baksmali/smali both need real files on disk, not
     *   in-memory streams).
     */
    fun patchDexFiles(context: Context, classesDexFiles: Map<String, ByteArray>, workDir: File): Result {
        val changes = mutableListOf<String>()
        val outDexFiles = mutableMapOf<String, ByteArray>()
        val opcodes = Opcodes.getDefault()

        for ((dexName, dexBytes) in classesDexFiles) {
            Logger.log("dex: decoding $dexName to smali...")
            val smaliDir = File(workDir, "smali_" + dexName.removeSuffix(".dex")).apply { mkdirs() }
            val tempDexFile = writeTemp(workDir, dexName, dexBytes)
            val dexFile: DexBackedDexFile = BufferedInputStream(FileInputStream(tempDexFile)).use { ins ->
                DexBackedDexFile.fromInputStream(opcodes, ins)
            }

            val bakOptions = BaksmaliOptions().apply {
                apiLevel = opcodes.api
                // THE ACTUAL BUG (found 2026-09-03 after two failed real-device test rounds):
                // BaksmaliOptions()'s own default is localsDirective = false, which makes
                // baksmali emit ".registers N" (total register count, params included, all
                // referred to as vN) instead of ".locals N" (non-parameter locals only, with
                // params referred to as p0/p1/...). wrapWholeMethod() below specifically looks
                // for a ".locals" line - with the library default, that regex never finds one,
                // so the wrap silently no-ops on *every* method, which is exactly the symptom
                // seen on-device ("found Controller.smali/AdManager.smali but ... did NOT
                // match" for both). The SDL_ffmpeg fix kept working throughout because it never
                // touches .locals/.registers at all - that's what made this one hide behind two
                // rounds of unrelated robustness fixes (.prologue optionality, offset math,
                // endsWith path matching) before the real cause turned up.
                //
                // apktool's baksmali *CLI* defaults --use-locals to on, which is why a plain
                // apktool decode - the ground truth this patch logic was originally validated
                // against - always showed ".locals" and never exposed this: the CLI's default
                // and the raw BaksmaliOptions() library default are simply different. Setting
                // this explicitly makes on-device output match what was actually verified.
                localsDirective = true
            }
            Baksmali.disassembleDexFile(dexFile, smaliDir, 1, bakOptions)

            // Mass Effect: Infiltrator gets its own, minimal recipe (see MassEffectRecipe) and none of
            // the generic per-file fixes below - those were written for other games, and the recipe
            // alone is what was verified on a real device.
            val isMassEffect = smaliDir.walkTopDown().any {
                it.isFile && MassEffectRecipe.isGameMarker(it.relativeTo(smaliDir).invariantSeparatorsPath)
            }
            var massEffectChanged = false

            var anyChange = false
            var controllerSmaliFound = false
            var adManagerSmaliFound = false
            var eaDownloadSmaliFound = false
            var marketBillingSmaliFound = false
            var handlerInstalled = false
            var storageDiagInjected = false
            smaliDir.walkTopDown().filter { it.isFile && it.extension == "smali" }.forEach { f ->
                var text = f.readText()
                val original = text

                val relPath = f.relativeTo(smaliDir).path

                if (isMassEffect) {
                    val relMe = f.relativeTo(smaliDir).invariantSeparatorsPath
                    var meText = text
                    val meResult = MassEffectRecipe.patchFile(relMe, meText)
                    meText = meResult.text
                    for (n in meResult.notes) {
                        Logger.log("  [fix] $n")
                        changes.add(n)
                    }
                    val (tI, cI) = wrapWholeMethod(meText, "initializeIAP\\(", "iap")
                    if (cI) {
                        meText = tI
                        Logger.log("  [fix] $relMe: initializeIAP() wrapped in try/catch (Samsung IAP implicit-Intent crash)")
                        changes.add("$relMe: initializeIAP() wrapped in try/catch (Samsung IAP bindService crash)")
                    }
                    if (meText != original) {
                        f.writeText(meText)
                        anyChange = true
                        massEffectChanged = true
                    }
                    return@forEach
                }

                val (t1, c1) = patchFfmpegLoadLibrary(text)
                text = t1
                if (c1) {
                    Logger.log("  [fix] SDL_ffmpeg loadLibrary() guarded with try/catch in $relPath " +
                        "(prevents a crash when the device has no ffmpeg native lib)")
                    changes.add("SDL_ffmpeg loadLibrary guarded in $relPath")
                    anyChange = true

                    // Same file: this is the entry Activity whose <clinit> loads SDL_ffmpeg,
                    // guaranteed to classload as early as any code in the app runs - the right
                    // place to install the background-thread safety net (fix #5, see README.md).
                    val (t1b, c1b) = installUncaughtHandler(text)
                    text = t1b
                    if (c1b) {
                        Logger.log("  [fix] background-thread crash handler installed in $relPath's " +
                            "<clinit> (catches crashes on other threads that no specific fix here " +
                            "covers - e.g. an ad SDK's own background thread - without hiding real " +
                            "crashes on the main thread)")
                        changes.add("Background-thread uncaught-exception handler installed in $relPath")
                        handlerInstalled = true
                    } else {
                        Logger.log("  [!] found $relPath's SDL_ffmpeg <clinit> but installing the " +
                            "background-thread crash handler did NOT match - please send the log/apk " +
                            "back, this needs a closer look")
                        methodSnippetForLog(text, "static constructor <clinit>\\(\\)V")?.let {
                            Logger.log("      -- raw smali for <clinit> (first 14 lines) --\n$it")
                        }
                    }
                }

                val rel = f.relativeTo(smaliDir).invariantSeparatorsPath

                // Whole-method try/catch wrap instead of an exact-text patch: the Moga SDK's
                // Controller.init() builds and starts an implicit Intent (forbidden since
                // Android 5.0 - see README/case-study.md), but the *exact* smali bytecode for
                // that (register numbers, whether debug .line/.local pragmas are present) can
                // differ from build to build and from whatever baksmali produces on this device
                // vs. whatever tool the reference text was extracted with. A literal string
                // match is brittle against that; wrapping the whole method (the same technique
                // used for AdManager.getMessage() below) works regardless of the exact bytecode
                // shape, as long as the method is still named "init" and still throws.
                //
                // endsWith() rather than == : some decompile/repackage tools nest per-dex output
                // under an extra leading folder (e.g. "classes/com/bda/...") - matching on the
                // suffix means this still finds the file regardless of what, if anything, comes
                // before it.
                if (STORAGE_DIAG_ENABLED && rel.endsWith("org/libsdl/app/SDLActivity.smali")) {
                    val (tD, cD) = injectStorageDiagCall(text)
                    if (cD) {
                        text = tD
                        storageDiagInjected = true
                        anyChange = true
                        Logger.log("  [fix] save-folder creation (getExternalFilesDir + save/campaignsave) hooked into SDLActivity.onResume")
                        changes.add("Save folders created through the system API in SDLActivity.onResume (Haegemonia + IG2)")
                    } else {
                        Logger.log("  [skip] SDLActivity.onResume storage diagnostic hook did not match")
                    }
                }

                if (rel.endsWith("com/bda/controller/Controller.smali")) {
                    controllerSmaliFound = true
                    val (t2, c2) = wrapWholeMethod(text, "(?<![A-Za-z0-9_<])init\\(", "moga")
                    text = t2
                    if (c2) {
                        Logger.log("  [fix] com/bda/controller/Controller.init() wrapped in try/catch " +
                            "(guards the Moga controller implicit-Intent crash - modern Android " +
                            "blocks implicit service Intents)")
                        changes.add("Moga controller Controller.init() wrapped in try/catch")
                        anyChange = true
                    } else {
                        Logger.log("  [!] found $rel but the init() method wrap did NOT match - " +
                            "please send the log/apk back, this needs a closer look")
                        methodSnippetForLog(text, "(?<![A-Za-z0-9_<])init\\(")?.let {
                            Logger.log("      -- raw smali for init() (first 14 lines) --\n$it")
                        }
                    }
                }

                if (rel.endsWith("org/libsdl/app/AdManager.smali")) {
                    adManagerSmaliFound = true
                    val (t3, c3) = wrapWholeMethod(text, "static getMessage\\(Ljava/lang/String;\\)V", "admanager")
                    text = t3
                    if (c3) {
                        Logger.log("  [fix] org/libsdl/app/AdManager.getMessage() wrapped in try/catch " +
                            "(guards against a dead/legacy ad-network crash)")
                        changes.add("org/libsdl/app/AdManager.getMessage() wrapped in try/catch")
                        anyChange = true
                    } else {
                        Logger.log("  [!] found $rel but the getMessage() method wrap did NOT match - " +
                            "please send the log/apk back, this needs a closer look")
                        methodSnippetForLog(text, "static getMessage\\(Ljava/lang/String;\\)V")?.let {
                            Logger.log("      -- raw smali for getMessage() (first 14 lines) --\n$it")
                        }
                    }
                }

                // fix #106 (2026-10-05, Haegemonia: Legions of Iron, real-device logcat -
                // hm_crash.txt): FATAL EXCEPTION: main / java.lang.NullPointerException -
                // "Attempt to invoke virtual method 'java.lang.Object
                // android.content.Context.getSystemService(java.lang.String)' on a null object
                // reference", at org.libsdl.app.AdManager.onResume(AdManager.java:1713), called
                // from SDLActivity.onResume -> IAPActivity.onResume. Same dead/legacy ad-SDK
                // class as fix #106's sibling getMessage() guard just above (AdManager holds its
                // Context in a field set during an earlier init call this old SDK no longer
                // reaches reliably on current Android - exactly the "dead ad network SDK that
                // outlived the servers/flow it was written for" situation getMessage() was
                // already guarded against), just a second, independently-crashing entry point
                // into the same class. Unlike getMessage() (called from deep inside ad-display
                // logic, reached only if ads are actually shown), onResume() runs on EVERY
                // foreground resume of the activity - including the very first one after
                // launch - so this crash is immediate and total: the process dies with signal 9
                // right after LaunchActivity starts, which is what reads to the user as
                // "Haegemonia says it's not compatible with this Android version" even though
                // nothing in this trace is actually an incompatibility - it's a null-field bug
                // in bundled glue code the game ships with. Same wrapWholeMethod() technique,
                // same file, same class: wrap the whole method body in try/catch(Throwable) so a
                // null Context here is swallowed instead of killing the process - the game's own
                // SDLActivity.onResume()/rendering path does not depend on this call succeeding.
                if (rel.endsWith("org/libsdl/app/AdManager.smali")) {
                    val (t4, c4) = wrapWholeMethod(text, "(?<![A-Za-z0-9_])onResume\\(", "admanager-onresume")
                    text = t4
                    if (c4) {
                        Logger.log("  [fix] org/libsdl/app/AdManager.onResume() wrapped in try/catch " +
                            "(guards against a null-Context crash in dead/legacy ad-SDK glue code - " +
                            "fix #106, confirmed via real-device logcat)")
                        changes.add("org/libsdl/app/AdManager.onResume() wrapped in try/catch")
                        anyChange = true
                    } else {
                        Logger.log("  [!] found $rel but the onResume() method wrap did NOT match - " +
                            "please send the log/apk back, this needs a closer look")
                        methodSnippetForLog(text, "(?<![A-Za-z0-9_])onResume\\(")?.let {
                            Logger.log("      -- raw smali for onResume() (first 14 lines) --\n$it")
                        }
                    }
                }

                // Whole-method try/catch wrap for EA's shared "eamobile" download/DLC-check SDK
                // (com/eamobile/download/DownloadActivity.smali - same package across many EA
                // mobile ports of this era, like com/bda/controller and org/libsdl/app above, so
                // scoped to this fixed path rather than matched by method name across the whole
                // tree, since "init(" alone is too generic to match unscoped). Confirmed via a
                // real crash log (Hihonor tablet, 2026-09-04): the game's own render loop calls
                // DownloadActivity.init() every frame on a background GLThread, which calls down
                // into DownloadActivityInternal.getAndroidUniqueId() -> TelephonyManager.getDeviceId()
                // - reading the device's IMEI, which modern Android (10+) flatly refuses to any
                // app without a system-signature permission no third-party app can hold:
                //   java.lang.SecurityException: : The uid ... does not meet the requirements
                //   to access device identifiers.
                // Not just this device or this run - that specific API call can never succeed on
                // a modern OS for an app like this, so this isn't a guess. Wrapping the whole
                // outer init() (rather than just getAndroidUniqueId() further down) guards this
                // entire "phone home to an EA download server that's been dead for a decade"
                // subsystem in one place, in case a retest turns up another failure deeper in the
                // same call chain (e.g. the network check that almost certainly follows it).
                if (rel.endsWith("com/eamobile/download/DownloadActivity.smali")) {
                    eaDownloadSmaliFound = true
                    val (t3b, c3b) = wrapWholeMethod(text, "init\\(", "eadownload")
                    text = t3b
                    if (c3b) {
                        Logger.log("  [fix] com/eamobile/download/DownloadActivity.init() wrapped in " +
                            "try/catch (guards the dead EA download-server / device-ID crash)")
                        changes.add("com/eamobile/download/DownloadActivity.init() wrapped in try/catch")
                        anyChange = true
                    } else {
                        Logger.log("  [!] found $rel but the init() method wrap did NOT match - " +
                            "please send the log/apk back, this needs a closer look")
                        methodSnippetForLog(text, "init\\(")?.let {
                            Logger.log("      -- raw smali for init() (first 14 lines) --\n$it")
                        }
                    }
                }

                // Whole-method try/catch wrap for EA's shared "easp.mtx.market" Google Play
                // billing wrapper (com/ea/easp/mtx/market/BillingService.smali - same "shared EA
                // SDK package used across many ports of this era" reasoning as the "eamobile"
                // download SDK above, so scoped to this fixed path). Confirmed via a real crash
                // log ("Round 28" - Hihonor tablet, 2026-09-08, the first crash log this project
                // has seen from this game with NO native SIGSEGV at all - see README.md for the
                // native-crash chase this finally closes out): bindToMarketBillingService() calls
                // Context.bindService() on the bare "com.android.vending.billing.MarketBillingService.BIND"
                // action with no package set - forbidden as an implicit service Intent since
                // Android 5.0, exactly the same class of bug as the Moga controller (fix #3) and
                // the EA Plasma/Samsung IAP initializer (fix #10) above, just for Google Play
                // Billing instead:
                //   java.lang.IllegalArgumentException: Service Intent must be explicit:
                //   Intent { act=com.android.vending.billing.MarketBillingService.BIND }
                //       at ... BillingService.bindToMarketBillingService(BillingService.java:16)
                //       at ... BillingService$BillingRequest.runRequest(BillingService.java:16)
                //       at ... BillingService.checkBillingSupported(BillingService.java:5)
                //       at ... MarketJNI$1.run(MarketJNI.java:10)
                // This runs on the main thread via a Handler callback (MarketJNI$1.run), so it's a
                // genuine app-killing FATAL EXCEPTION, not a silently-swallowed background-thread
                // failure - the game cannot proceed past this at all without a guard. In-app
                // purchases going unavailable is an acceptable, minor trade-off for a decade-old
                // game whose original Play Billing integration predates this OS restriction by
                // several years; actual gameplay does not depend on it.
                if (rel.endsWith("com/ea/easp/mtx/market/BillingService.smali")) {
                    marketBillingSmaliFound = true
                    val (t3c, c3c) = wrapWholeMethod(text, "bindToMarketBillingService\\(", "marketbilling")
                    text = t3c
                    if (c3c) {
                        Logger.log("  [fix] com/ea/easp/mtx/market/BillingService.bindToMarketBillingService() " +
                            "wrapped in try/catch (guards the Google Play Billing implicit-Intent " +
                            "crash - modern Android blocks implicit service Intents)")
                        changes.add("com/ea/easp/mtx/market/BillingService.bindToMarketBillingService() " +
                            "wrapped in try/catch (implicit-Intent bindService crash)")
                        anyChange = true
                    } else {
                        Logger.log("  [!] found $rel but the bindToMarketBillingService() method wrap " +
                            "did NOT match - please send the log/apk back, this needs a closer look")
                        methodSnippetForLog(text, "bindToMarketBillingService\\(")?.let {
                            Logger.log("      -- raw smali for bindToMarketBillingService() (first 14 lines) --\n$it")
                        }
                    }
                }

                // Whole-method try/catch wrap for a class exposing an "initializeIAP()" method -
                // an old EA "Plasma" SDK in-app-purchase initializer (seen in Mass Effect:
                // Infiltrator, PlasmaWrapper.<init> -> MassEffectActivity.initializeIAP) that
                // unconditionally calls bindService() on the bare Samsung Galaxy Apps IAP intent
                // action ("com.sec.android.iap.service.iapService") with no package set.
                // Forbidden as an implicit service Intent since Android 5.0, and guaranteed to
                // throw on any non-Samsung device since that service can't resolve at all there -
                // confirmed via a real crash log (Hihonor tablet, 2026-09-04):
                //   java.lang.IllegalArgumentException: Service Intent must be explicit:
                //   Intent { act=com.sec.android.iap.service.iapService }
                // Not scoped to one fixed file path like the Moga/AdManager fixes above, because
                // the containing class/package name is per-game (here:
                // com.ea.games.meinfiltrator_gamepad.MassEffectActivity) - the method name itself
                // is the stable, SDK-specific identifier, so (like patchFfmpegLoadLibrary() above)
                // every .smali file in the tree is checked for it rather than one fixed path, and
                // it's expected to match in at most one file per game, so a miss here is silent
                // (not logged as a warning) exactly like the ffmpeg pattern.
                val (t4, c4) = wrapWholeMethod(text, "initializeIAP\\(", "iap")
                text = t4
                if (c4) {
                    Logger.log("  [fix] $rel: initializeIAP() wrapped in try/catch (guards the EA " +
                        "Plasma SDK's Samsung IAP bindService() crash on non-Samsung devices)")
                    changes.add("$rel: initializeIAP() wrapped in try/catch (Samsung IAP bindService crash)")
                    anyChange = true
                }

                // EA "eamobile" download SDK: add the missing HTTP *read* timeout next to the
                // existing *connect* timeout - see patchEaHttpPostReadTimeout()'s doc comment.
                // Same unscoped, API-call-matched approach as the ffmpeg/initializeIAP patches
                // above (real bug in EA's shared SDK code, not tied to one file path per game).
                val (t5, c5) = patchEaHttpPostReadTimeout(text)
                text = t5
                if (c5) {
                    Logger.log("  [fix] $rel: added missing HttpConnectionParams.setSoTimeout() " +
                        "next to the existing setConnectionTimeout() (guards the EA download " +
                        "SDK's manifest-fetch HTTP POST against hanging forever on a dead/silent " +
                        "server - it can now fail fast with SocketTimeoutException instead)")
                    changes.add("$rel: added HTTP read-timeout to EA download SDK's sendHttpPost() " +
                        "(was connect-timeout only - could hang forever on a dead server)")
                    anyChange = true
                }

                // THE REAL FIX for the getAndroidUniqueId() SecurityException (fix #11 above
                // wrapped the wrong call site - see below). DownloadActivityInternal's own
                // getAndroidUniqueId() calls TelephonyManager.getDeviceId() with NO try/catch of
                // its own. Confirmed via a real debug-log capture (Hihonor tablet, 2026-09-04,
                // com.eamobile.download's own DEBUG_OUT logging enabled via a "debug.enable"
                // marker file) that this is exactly where the app goes silent every time: the
                // last line printed is "Brand: HONOR", and the very next thing
                // DownloadActivityInternal.init()'s device-info dump does is call
                // getAndroidUniqueId() - which is guaranteed to throw SecurityException on
                // Android 10+ for a non-system app (same permanent OS restriction fix #11's doc
                // comment already describes). The crash log confirmed it: "FileNotFoundException:
                // .../debug.txt: open failed: EPERM" right before it, at
                // Logging.DEBUG_INIT(Logging.java:85) <- DownloadActivityInternal.<init> <-
                // DownloadActivity.<init> <- MassEffectActivity.loadContent - i.e. this whole
                // call chain runs on a *background* thread that DownloadActivityInternal.init()
                // spawns, not the thread fix #11 wraps. Wrapping DownloadActivity.init() (fix
                // #11) only catches an exception thrown synchronously on the *calling* thread -
                // it cannot catch one thrown on a thread init() merely started, so it never
                // actually guarded this crash at all. What silently swallowed it instead was this
                // project's own fix #5 (the global background-thread SilentUncaughtHandler,
                // installed for exactly this class of dead-SDK crash) - which caught it, logged
                // nothing user-visible, and let the thread die. The result: no crash report, no
                // ANR (a dead background thread is invisible to the watchdog), just a permanently
                // stuck "please wait" state indistinguishable from a hang - which is exactly what
                // both this project's own testing and the user's tablet showed, over and over.
                // Fix #11 stays (harmless, and still a real gap on whatever calls DownloadActivity
                // directly on its own thread), but THIS wrap - directly around the method that
                // actually makes the doomed call, so it's caught no matter which thread runs it -
                // is what actually stops the hang. Unscoped/method-name-matched like the
                // initializeIAP() fix above, for the same reason (shared EA SDK code, not tied to
                // a fixed file path).
                val (t6, c6) = wrapWholeMethod(text, "getAndroidUniqueId\\(", "androidid")
                text = t6
                if (c6) {
                    Logger.log("  [fix] $rel: getAndroidUniqueId() wrapped in try/catch (guards " +
                        "the EA download SDK's unguarded TelephonyManager.getDeviceId() call - " +
                        "this is the actual crash site fix #11 could not reach, since it runs on " +
                        "a background thread of its own)")
                    changes.add("$rel: getAndroidUniqueId() wrapped in try/catch (the real fix for " +
                        "the device-ID SecurityException - fix #11 wrapped the wrong thread)")
                    anyChange = true
                }

                // Force shouldSkipUpdateCheck() to always return true - see forceReturnTrue()'s
                // doc comment for the full evidence chain (fix #14 let sendHttpPost() actually
                // run, which then revealed EA's server domain no longer resolves in DNS at all;
                // this is what lets a game whose local content is already complete load anyway,
                // instead of dead-ending on a server that's gone for good).
                val (t7, c7) = forceReturnTrue(text, "shouldSkipUpdateCheck\\(")
                text = t7
                if (c7) {
                    Logger.log("  [fix] $rel: shouldSkipUpdateCheck() forced to always return true " +
                        "(lets the EA download SDK use already-complete local content instead of " +
                        "dead-ending on a server whose domain no longer resolves in DNS at all)")
                    changes.add("$rel: shouldSkipUpdateCheck() forced to return true (skips a " +
                        "permanently-dead server check when local content is already complete)")
                    anyChange = true
                }

                // Fix #20 (2026-09-05): force assetsFoundLocally() to always return true, the
                // same forceReturnTrue() primitive already used on shouldSkipUpdateCheck() above.
                // Supersedes fixes #16/#18/#19's diagnostic-only approach for this method - those
                // got real on-device confirmation that it returns false with NO exception logged
                // and NEVER reaches checkFiles() (so it's not a missing-file problem), even with
                // every one of the 1180 files in Downloaded2.indicate independently confirmed
                // present via direct adb shell checks, and even surviving a full device reboot
                // (ruling out a stale scoped-storage/FUSE view). Whatever the exact remaining
                // cause turns out to be (fix #19's path-logging was aimed at pinning it down
                // further), it sits in a check this project cannot make the dead EA server answer
                // either way - so rather than keep chasing it indefinitely, this forces the same
                // "trust the local content" outcome fix #15 already forces one step later.
                //
                // Real trade-off (unlike fix #15, which only matters after this method already
                // said true): this bypasses checkFiles()'s actual per-file verification entirely,
                // so it assumes the local content folder genuinely is complete - true for this
                // user (independently verified) but not guaranteed for everyone. If the local
                // content is genuinely incomplete, the game will now try to load anyway instead of
                // clearly falling back to the download flow, likely surfacing as a different,
                // less obvious failure further in. Cannot affect IG2/Haegemonia either way (same
                // reasoning as every other fix in this EA "eamobile" SDK section - they don't use
                // it).
                val (t8, c8) = forceReturnTrue(text, "assetsFoundLocally\\(")
                text = t8
                if (c8) {
                    Logger.log("  [fix] $rel: assetsFoundLocally() forced to always return true " +
                        "(trusts already-placed local content instead of a check that keeps " +
                        "returning false for unresolved reasons, never reaching the per-file " +
                        "verification, on a server that can't be asked either way)")
                    changes.add("$rel: assetsFoundLocally() forced to return true (skips a local-" +
                        "content check that keeps failing for unresolved reasons)")
                    anyChange = true
                } else {
                    methodSnippetForLog(text, "assetsFoundLocally\\(", maxLines = 170)?.let {
                        Logger.log("  [!] $rel: found assetsFoundLocally() but forceReturnTrue() " +
                            "did NOT match (unexpected - it only requires the method decl line and " +
                            "\".end method\", not exact interior text) - please send this whole log " +
                            "back, the snippet below is what's actually there")
                        Logger.log("      -- raw smali for assetsFoundLocally() (up to 170 lines) --\n$it")
                    }
                }

                // Fix #21 (2026-09-05): force AssetManager.isAssetVersionCompatible(String) to
                // always return true, the same forceReturnTrue() primitive as fix #15/#20 above.
                //
                // Fix #20 unblocked the download screen (assetsFoundLocally() no longer sends the
                // game toward the dead EA server) but the real on-device log for the very next run
                // showed a NEW failure one step later, every single rendered frame (this whole
                // chain runs from DownloadActivityInternal.init(), which the game calls from its
                // own GLSurfaceView render callback - MassEffectActivity.onDrawFrame() ->
                // AndroidRenderer.onDrawFrame() -> GLThread.guardedRun() - not just once at
                // startup): DownloadActivityInternal.setState(STATE_SUCCESS) calls
                // AssetManager.getLocalAssetVersion(), which decompiles to: open
                // getFilePath("Downloaded.indicate") as a File, and if File.exists() is false,
                // return null outright (no exception, no log line - same silent shape as
                // assetsFoundLocally()'s own false result) - and it *did* return null here, per
                // the real log ("Local asset version: null"), despite this being the exact same
                // file fix #20's own evidence chain already confirmed present and byte-correct via
                // `adb shell cat`. So this is very likely the same unresolved path-resolution issue
                // as fix #20's, just hit from a second call site.
                //
                // That null then flows into isAssetVersionCompatible(minVersion), which calls
                // isVersionLower(null, "1.0.10") -> normalisedVersion(null) ->
                // Pattern.split() on a null CharSequence -> NullPointerException, caught inside
                // setState() but never resolved, so init() re-throws-and-catches it every single
                // frame forever - that's the newly-reported "freeze at the EA screen" (not a
                // one-time crash, an infinite per-frame retry loop with no forward progress).
                //
                // Rather than chase the same path-resolution mystery a second time (or hand-code
                // the expected version string, which would silently mismatch for any game/build
                // whose actual minimum differs), this applies the exact same "trust local content,
                // skip the check that can't resolve it" philosophy fix #20 already established -
                // this time one level higher, on the compatibility verdict itself rather than on
                // the version string it's built from. isAssetVersionCompatible()==true is exactly
                // what checkLocalAssetVersion() (its only caller) needs to take the same "OK, use
                // the local asset path" branch DownloadActivityInternal's pswitch_5 case already
                // takes on success - confirmed by reading that method's own decompiled body, not
                // guessed. It also fully sidesteps the NPE, since isVersionLower()/
                // normalisedVersion() are never called once this method's body is replaced outright
                // - getLocalAssetVersion() itself is left untouched (still returns null), but
                // nothing downstream of this method uses that return value in a way that can crash.
                //
                // Same real trade-off as fix #20: this assumes the local content actually is
                // compatible instead of verifying it, which is true for this user (independently
                // confirmed) but not guaranteed in general. Cannot affect IG2/Haegemonia - they
                // don't use the EA "eamobile" download SDK at all, same as every other fix in this
                // section.
                val (t9, c9) = forceReturnTrue(text, "isAssetVersionCompatible\\(")
                text = t9
                if (c9) {
                    Logger.log("  [fix] $rel: isAssetVersionCompatible() forced to always return " +
                        "true (skips a local-version check that crashes with a NullPointerException " +
                        "when the local version string can't be read, looping forever since this " +
                        "whole check re-runs every rendered frame)")
                    changes.add("$rel: isAssetVersionCompatible() forced to return true (skips a " +
                        "version check that was crashing every frame with a NullPointerException)")
                    anyChange = true
                } else {
                    methodSnippetForLog(text, "isAssetVersionCompatible\\(", maxLines = 90)?.let {
                        Logger.log("  [!] $rel: found isAssetVersionCompatible() but " +
                            "forceReturnTrue() did NOT match - please send this whole log back, " +
                            "the snippet below is what's actually there")
                        Logger.log("      -- raw smali for isAssetVersionCompatible() (up to 90 " +
                            "lines) --\n$it")
                    }
                }

                if (text != original) f.writeText(text)
            }

            if (!controllerSmaliFound) {
                Logger.log("  [i] no com/bda/controller/Controller.smali in $dexName " +
                    "(this game may not bundle the Moga SDK, or it's in a different dex)")
            }
            if (!adManagerSmaliFound) {
                Logger.log("  [i] no org/libsdl/app/AdManager.smali in $dexName " +
                    "(this game may not bundle that ad SDK, or it's in a different dex)")
            }
            if (!eaDownloadSmaliFound) {
                Logger.log("  [i] no com/eamobile/download/DownloadActivity.smali in $dexName " +
                    "(this game may not bundle that EA download SDK, or it's in a different dex)")
            }
            if (!marketBillingSmaliFound) {
                Logger.log("  [i] no com/ea/easp/mtx/market/BillingService.smali in $dexName " +
                    "(this game may not bundle Google Play Billing, or it's in a different dex)")
            }

            if (!anyChange) {
                Logger.log("dex: no known patterns matched in $dexName, keeping original bytes")
                outDexFiles[dexName] = dexBytes
                continue
            }

            if (handlerInstalled) {
                // The class the snippet above references has to actually exist somewhere in
                // the smali tree, or the reference resolves to nothing at runtime (a
                // NoClassDefFoundError right in <clinit> - i.e. an immediate crash on launch,
                // which would be *worse* than not installing the handler at all). Written as
                // one more plain .smali file, same as everything baksmali itself produced.
                val handlerFile = File(smaliDir, "apkcompatpatcher/SilentUncaughtHandler.smali")
                handlerFile.parentFile?.mkdirs()
                handlerFile.writeText(SILENT_HANDLER_CLASS_SMALI)
            }

            if (massEffectChanged) {
                for ((classPath, classSmali) in MassEffectRecipe.EXTRA_CLASSES) {
                    val classFile = File(smaliDir, classPath)
                    classFile.parentFile?.mkdirs()
                    classFile.writeText(classSmali)
                }
            }

            if (storageDiagInjected) {
                val diagFile = File(smaliDir, "org/soa/StorageDiag.smali")
                diagFile.parentFile?.mkdirs()
                diagFile.writeText(STORAGE_DIAG_CLASS_SMALI)
            }

            Logger.log("dex: reassembling $dexName from patched smali...")
            val outDex = File(workDir, "rebuilt_$dexName")
            val smaliOptions = SmaliOptions().apply {
                outputDexFile = outDex.absolutePath
                apiLevel = opcodes.api
            }
            val ok = Smali.assemble(smaliOptions, listOf(smaliDir.absolutePath))
            if (!ok) {
                throw RuntimeException("smali assemble failed for $dexName - see log above for details")
            }
            outDexFiles[dexName] = outDex.readBytes()
        }

        return Result(changes, outDexFiles)
    }

    private fun writeTemp(workDir: File, name: String, bytes: ByteArray): File {
        val f = File(workDir, "in_$name")
        f.writeBytes(bytes)
        return f
    }
}
