package com.apkcompatpatcher.android

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Global safety net for THIS app (the patcher itself), not the games it
 * patches. If anything throws somewhere unexpected - a dexlib2 internal
 * assertion, an OOM on a huge dex, whatever - we still want a readable
 * crash file on disk rather than just "the app closed", since that file is
 * how a non-technical user can actually get useful information back to the
 * person maintaining this tool.
 *
 * English-only, deliberately: this app used to have an in-app Hungarian/
 * English switch (an `AppCompatDelegate.setApplicationLocales()` per-app
 * override, forced to English on first launch). In practice, on real
 * hardware, that override intermittently didn't apply - some launches
 * still came up in Hungarian despite the "force English" logic having run
 * (a known class of AppCompat per-app-locale timing gotcha, and possibly
 * compounded by `adb install -r` reinstalls during testing not clearing
 * app data - either way, not reliably fixable without a device to keep
 * iterating against). Rather than keep chasing that, the fix is
 * structural: there is now only ONE string resource set at all
 * (`res/values/strings.xml` - `res/values-hu/` was deleted), so there is no
 * "wrong" language for any resource-resolution timing issue to ever fall
 * into, regardless of device locale or install history.
 */
class PatcherApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val dir = File(cacheDir, "logs").apply { mkdirs() }
                val file = File(dir, "crash-${System.currentTimeMillis()}.txt")
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("apk-compat-patcher-android crashed")
                    pw.println("thread: ${thread.name}")
                    throwable.printStackTrace(pw)
                }
                file.writeText(sw.toString())
            } catch (_: Exception) {
                // If even crash-logging fails, fall through to the
                // platform's default handler below and give up quietly.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
