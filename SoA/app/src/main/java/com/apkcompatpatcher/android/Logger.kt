package com.apkcompatpatcher.android

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Very small append-only logger used for two things at once:
 *
 *  1. Driving the on-screen log view during a patch run (via [listener]),
 *     exactly like the `[patch] ...` console lines the PC version prints.
 *  2. Writing every line to a plain text file, so that if something goes
 *     wrong - anywhere, including deep inside dexlib2/apksig - there is a
 *     complete, shareable record the user can send back for debugging, the
 *     same way `adb logcat` was used while building the original PC tool.
 *     This matters a lot here specifically because nothing in this
 *     on-device pipeline has been tested against a real device by whoever
 *     wrote it - see README.md.
 *
 *     The file lives in the app's EXTERNAL app-specific directory
 *     (getExternalFilesDir), not the internal cache dir - that path
 *     (/storage/emulated/0/Android/data/<pkg>/files/logs/) is reachable
 *     with a plain `adb pull`, no root and no `run-as` needed, because it's
 *     on the shared/external storage volume the "shell" adb user already
 *     has access to. The internal cache dir needs either root or `run-as`
 *     to read from outside the app, and both of those are unreliable on at
 *     least some Android emulators (e.g. BlueStacks: `run-as` can fail with
 *     "setgid(AID_PACKAGE_INFO) failed", and `adb root` can silently stay
 *     as "shell" with no error) even though they work fine via USB on a
 *     real device - this sidesteps that entirely, everywhere. Falls back
 *     to the internal cache dir only if external storage is ever
 *     unavailable (getExternalFilesDir returning null - very rare, e.g. a
 *     removed SD card on some devices).
 */
object Logger {

    fun interface Listener {
        fun onLine(line: String)
    }

    @Volatile
    var listener: Listener? = null

    private var logFile: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Call once per patch run, before anything else, to start a fresh log file. */
    fun startNewSession(context: Context): File {
        val baseDir = context.getExternalFilesDir("logs") ?: File(context.cacheDir, "logs")
        val dir = baseDir.apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "patch-log-$stamp.txt")
        logFile = file
        file.writeText("apk-compat-patcher-android log - $stamp\n\n")
        return file
    }

    fun currentLogFile(): File? = logFile

    fun log(msg: String) {
        val line = "[${timeFmt.format(Date())}] $msg"
        android.util.Log.i("ApkCompatPatcher", msg)
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) {
            // Logging must never be the thing that crashes a run.
        }
        listener?.onLine(line)
    }

    fun logThrowable(context: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        log("ERROR in $context: ${t::class.java.name}: ${t.message}")
        log(sw.toString())
    }
}
