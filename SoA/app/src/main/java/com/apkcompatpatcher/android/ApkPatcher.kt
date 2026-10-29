package com.apkcompatpatcher.android

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-device port of the PC tool's main pipeline (`patch_apk.py:main()`).
 * Same overall shape: read the original APK's zip, patch the manifest in
 * place, patch the dex(es) via [DexPatcher], swap in a clean stub for any
 * ARM `libSDL_ffmpeg.so`, reassemble a new zip from the ORIGINAL with only
 * those specific entries replaced (everything else byte-identical to the
 * source APK), then sign it.
 */
object ApkPatcher {

    class PatchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class PatchResult(val outputFile: File, val changes: List<String>, val massEffect: Boolean = false)

    private const val DEFAULT_TARGET_SDK = 30

    /**
     * @param inputApk the actual bytes to patch - typically a private working copy (SAF content
     *   URIs aren't directly usable as a random-access zip source), so its own file name is
     *   usually NOT what the output should be named after - see [originalFileName].
     * @param originalFileName the real, user-facing name of the picked APK (with or without the
     *   ".apk" extension) - used to build the output file name, e.g. "MyGame.apk" ->
     *   "MyGame-rebuilded.apk". Falls back to inputApk's own name if blank.
     * @param targetSdk override for targetSdkVersion (advanced setting; default 30 - see the
     *   PC tool's README for why 30 and not higher).
     * @param customKeystore sign with this keystore instead of the app's own auto-generated
     *   AndroidKeyStore key (advanced setting).
     * @param forceResizable opt-in, off by default - bakes android:resizeableActivity="true"
     *   directly into the manifest (the APK-level equivalent of `adb shell am compat enable
     *   FORCE_RESIZE_APP` - see [ManifestPatcher.widenResizability]'s doc comment for the full
     *   explanation). NOTE: on some OEM launchers (confirmed on this project's own Honor/Magic UI
     *   test tablet) this alone can make the game open in a smaller floating/windowed view by
     *   default instead of fullscreen - a real trade-off of declaring an app resizable, not a bug
     *   in this patch. Kept as its own independent toggle (split from [forceUserOrientation] in
     *   0.88) specifically so it can be left off if that's not wanted while still getting free
     *   rotation.
     * @param forceUserOrientation opt-in, off by default - bakes android:screenOrientation="user"
     *   into the manifest's main/launcher activity (the APK-level equivalent of `adb shell am
     *   compat enable OVERRIDE_ANY_ORIENTATION_TO_USER` - see [ManifestPatcher.widenOrientation]'s
     *   doc comment). Unlike the real `am compat` override, this can still be overridden back at
     *   runtime by a game that calls `setRequestedOrientation()` itself, and only actually rotates
     *   if the device's own auto-rotate setting is on.
     */
    fun patch(
        context: Context,
        inputApk: File,
        workDir: File,
        originalFileName: String = "",
        targetSdk: Int = DEFAULT_TARGET_SDK,
        customKeystore: CustomKeystoreConfig? = null,
        forceResizable: Boolean = false,
        forceUserOrientation: Boolean = false
    ): PatchResult {
        if (workDir.exists()) workDir.deleteRecursively()
        workDir.mkdirs()

        val changes = mutableListOf<String>()
        val zin = ZipFile(inputApk)

        try {
            // --- 1. manifest ---
            Logger.log("== targetSdkVersion patch ==")
            val manifestEntry = zin.getEntry("AndroidManifest.xml")
                ?: throw PatchException("this doesn't look like a valid APK - no AndroidManifest.xml found")
            val originalManifest = zin.getInputStream(manifestEntry).readBytes()
            val afterSdkPatch = try {
                ManifestPatcher.patchTargetSdk(originalManifest, targetSdk)
            } catch (e: Exception) {
                throw PatchException("could not patch AndroidManifest.xml: ${e.message}", e)
            }
            changes.add("targetSdkVersion raised to $targetSdk")

            // Widen any android:configChanges an activity ALREADY declares to also cover
            // screenLayout/uiMode/smallestScreenSize/density/layoutDirection - see
            // ManifestPatcher.widenActivityConfigChanges for the real (non-crashing, so
            // log-evidenced rather than crash-log-confirmed) symptom this targets: an old
            // game's activity getting torn down and recreated by a modern config-change-heavy
            // ROM shortly after launch, because its own configChanges declaration predates
            // config aspects that didn't exist yet when it was built.
            val configResult = ManifestPatcher.widenActivityConfigChanges(afterSdkPatch)
            if (configResult.changes.isNotEmpty()) {
                Logger.log("== activity configChanges widen ==")
                for (c in configResult.changes) Logger.log("  $c")
                changes.addAll(configResult.changes)
            }

            // Raise any already-present android.max_aspect meta-data value - see
            // ManifestPatcher.widenMaxAspect for the two real-device logcat captures that
            // motivated this (the same activity's window flips between a full-size and a
            // boxed/centered layout while running, not just at cold start).
            val maxAspectResult = ManifestPatcher.widenMaxAspect(configResult.manifest)
            if (maxAspectResult.note != null) {
                Logger.log("== android.max_aspect widen ==")
                Logger.log("  ${maxAspectResult.note}")
                changes.add(maxAspectResult.note)
            }

            // Restore the pre-API-28 Apache HTTP client classes for old SDKs that still
            // use them (org.apache.http.*) - see ManifestPatcher.addUsesLibrary()'s doc
            // comment for the real crash log (Mass Effect: Infiltrator's EA download SDK,
            // NoClassDefFoundError on DefaultHttpClient) that this fixes; harmless/no-op
            // for any APK that doesn't reference these classes.
            Logger.log("== uses-library (org.apache.http.legacy) ==")
            val afterUsesLibrary = ManifestPatcher.addUsesLibrary(
                maxAspectResult.manifest, "org.apache.http.legacy", required = false
            )
            Logger.log("  added <uses-library android:name=\"org.apache.http.legacy\" android:required=\"false\"/>")
            changes.add(
                "added <uses-library android:name=\"org.apache.http.legacy\" android:required=\"false\"/> " +
                    "(restores the pre-API-28 Apache HTTP client classes for old SDKs that still call them - " +
                    "without this, targetSdkVersion 28+ makes them NoClassDefFoundError at runtime)"
            )

            // NOT an advanced setting - applied unconditionally like addUsesLibrary above, since
            // it doesn't change how the game looks/behaves, only its storage access. See
            // ManifestPatcher.addRequestLegacyExternalStorage's doc comment for the full "why"
            // and its important caveat (only has any effect if targetSdkVersion is exactly 29 -
            // a harmless no-op on Android 11+/targetSdk 30+, which is this app's own default).
            Logger.log("== requestLegacyExternalStorage ==")
            val afterLegacyStorage = ManifestPatcher.addRequestLegacyExternalStorage(afterUsesLibrary)
            for (c in afterLegacyStorage.changes) Logger.log("  $c")
            changes.addAll(afterLegacyStorage.changes)

            // NOT an advanced setting either, same reasoning as requestLegacyExternalStorage above
            // - see ManifestPatcher.addPerformanceCompatibilityFlags's doc comment for what each
            // of the 4 flags does and why none of them change how the game looks/plays.
            Logger.log("== largeHeap / extractNativeLibs / hardwareAccelerated / isGame ==")
            val afterPerfFlags = ManifestPatcher.addPerformanceCompatibilityFlags(afterLegacyStorage.manifest)
            for (c in afterPerfFlags.changes) Logger.log("  $c")
            changes.addAll(afterPerfFlags.changes)

            // ADVANCED SETTINGS, opt-in, off by default, independent of each other - see
            // widenResizability/widenOrientation's shared doc comment and the patch() kdoc above
            // for the full "why" (the APK-level equivalent of the am compat
            // OVERRIDE_ANY_ORIENTATION_TO_USER/FORCE_RESIZE_APP overrides, since an ordinary
            // installed app can't flip those for another package at runtime) and for why they're
            // two separate toggles rather than one combined one.
            var newManifest = afterPerfFlags.manifest
            if (forceResizable) {
                Logger.log("== resizable/multi-window (advanced setting) ==")
                val resizabilityResult = ManifestPatcher.widenResizability(newManifest)
                for (c in resizabilityResult.changes) Logger.log("  $c")
                changes.addAll(resizabilityResult.changes)
                newManifest = resizabilityResult.manifest
            }
            if (forceUserOrientation) {
                Logger.log("== free/user-following orientation (advanced setting) ==")
                val orientationResult = ManifestPatcher.widenOrientation(newManifest)
                for (c in orientationResult.changes) Logger.log("  $c")
                changes.addAll(orientationResult.changes)
                newManifest = orientationResult.manifest
            }

            // --- 2. native lib scan: try to make ARM libSDL_ffmpeg.so actually WORK; only
            // fall back to the old clean-stub replacement if that's not safely possible ---
            // See ElfSdlFfmpegRelinkPatcher's doc comment for the full story: this library is
            // what IG2/Haegemonia's :direct:-folder video feature decodes cutscenes through, so
            // unconditionally stubbing it (the original, safe-for-Mass-Effect-only assumption)
            // silently defeats that feature no matter how correctly it finds a video file on
            // disk - confirmed for real 2026-09-23 via the 1.02 debug-trace build, which found
            // the user's video files with fopen() every time and still played nothing, because
            // the stub is a total no-op behind that successful fopen(). ElfSdlFfmpegRelinkPatcher
            // only ever applies when its own strict safety gates all pass (same-segment problem
            // relocations, independently re-verified code-free via the file's own section header
            // table); anything else falls straight back to the clean stub exactly as before, so
            // this can never make things worse than they already were.
            Logger.log("== native library scan (libSDL_ffmpeg.so) ==")
            val libReplacements = mutableMapOf<String, ByteArray>()
            val entryNames = zin.entries().toList().map { it.name }
            for (name in entryNames) {
                if (name.startsWith("lib/") && name.endsWith("libSDL_ffmpeg.so")) {
                    if (name.startsWith("lib/armeabi")) {
                        val original = zin.getInputStream(zin.getEntry(name)).readBytes()
                        val relinkResult = ElfSdlFfmpegRelinkPatcher.fixIfNeeded(original)
                        if (relinkResult.patched && relinkResult.newBytes != null) {
                            libReplacements[name] = relinkResult.newBytes
                            Logger.log("  [fix] $name: ${relinkResult.note}")
                            changes.add("$name: relinked in place, real video decoding restored (${relinkResult.note})")
                        } else {
                            Logger.log("  $name: real-library relink not safely applicable (${relinkResult.note}) - falling back to clean stub")
                            val stub = context.assets.open("stub/libSDL_ffmpeg_armeabi-v7a.so").readBytes()
                            libReplacements[name] = stub
                            Logger.log("  $name: replacing with clean stub (avoids ELF text-relocation crash)")
                            changes.add("$name replaced with clean stub (fixes text-relocation crash)")
                        }
                    } else {
                        Logger.log("  $name: non-ARM ABI, no stub available for it - leaving as-is " +
                            "(only relevant if this build also ships a 64-bit/x86 variant)")
                    }
                }
            }

            // --- 2b. native lib scan: fix ELF text relocations in every OTHER .so ---
            // libSDL_ffmpeg.so gets a whole clean replacement (above) because it's a
            // non-essential helper; everything else - most importantly a game's own engine
            // libraries (e.g. Unity's libmain.so/libunity.so/libmono.so) - can't be stubbed
            // out, since it has to keep actually working. See ElfTextRelPatcher for the full
            // explanation of what "text relocations" are and why this fix is safe.
            Logger.log("== native library scan (ELF text relocations) ==")
            for (name in entryNames) {
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
                if (libReplacements.containsKey(name)) continue // already replaced above
                val original = zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfTextRelPatcher.fixIfNeeded(original)
                if (result.patched) {
                    libReplacements[name] = original // fixIfNeeded() patches this array in place
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ELF text relocations fixed (${result.note})")
                }
            }

            // --- 2c. native lib scan: fix broken DT_NEEDED paths (e.g. a dead absolute
            // build-machine path baked in as a "needed library" name instead of a plain
            // soname - see ElfNeededPathPatcher for the real crash this closes). Runs on
            // every .so not already replaced above, INCLUDING ones 2b already patched in
            // place (the two problems are unrelated and can both occur in the same file).
            Logger.log("== native library scan (broken DT_NEEDED paths) ==")
            for (name in entryNames) {
                if (!name.startsWith("lib/") || !name.endsWith(".so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfNeededPathPatcher.fixIfNeeded(bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck // fixIfNeeded() patches this array in place
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: broken DT_NEEDED path fixed (${result.note})")
                }
            }

            // --- 2d. Mass Effect: Infiltrator - NO game-specific native code patches (the old ones were removed).
            // The earlier long chain of ElfMassEffect* null-guards (fixes #9-#97) only treated
            // symptoms of ONE root cause: the game could not see its data files, because it looks
            // for them in Android/data/<pkg>, a folder that modern Android will not let anything
            // but the app itself create. The data folder is now Android/obb/<pkg> (see
            // the config.properties step below + DexPatcher's Mass Effect recipe), and the original,
            // unpatched game code runs fine. The only native fixes it needs are the two generic
            // ones above (text relocation + broken DT_NEEDED path).

            // --- 2Z23. Imperium Galactica 2 only - makes cutscene/intro video loading probe a
            // plain external "videos" folder FIRST (via a real fopen() existence check + the
            // game's own built-in ":direct:" file-access escape hatch), and only if a clip isn't
            // there does it fall back to the fully original, untouched "anim/mpeg4/..." package
            // lookup - so clips the player hasn't supplied behave exactly as before. See
            // ElfImperiumGalactica2DirectVideoFolderPatcher's doc comment for the full trace.
            Logger.log("== native library scan (Imperium Galactica 2 direct video folder) ==")
            for (name in entryNames) {
                if (!name.endsWith("libig2ps.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfImperiumGalactica2DirectVideoFolderPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z24. Haegemonia: Legions of Iron only - same idea as the IG2 fix above, but
            // implemented at the AssetHelper::OpenRawFile chokepoint itself (Haegemonia's own
            // video-path-building code is spread across several CString-based helper functions,
            // so a single literal-pool redirect like IG2's isn't safe there). The patch tries a
            // plain external "videos" folder via a real fopen() first, and if that fails (file
            // missing) falls straight through to the function's own completely untouched original
            // behavior - so it's a total no-op for every asset that isn't overridden. See
            // ElfHaegemoniaDirectVideoFolderPatcher's doc comment for the full trace.
            Logger.log("== native library scan (Haegemonia direct video folder) ==")
            for (name in entryNames) {
                if (!name.endsWith("libhaegemonia_base.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfHaegemoniaDirectVideoFolderPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z25. Haegemonia: Legions of Iron only - fix #105, found on a real-device 1.04
            // test: saving the game and then loading that very save crashed it every time (even
            // without fully exiting the app first), on at least one save slot - the structural
            // root cause means any slot hitting this code path is affected the same way. See
            // ElfHaegemoniaSaveGameNullGuardPatcher's doc comment for the full crash trace, the
            // cave design, and how it was verified (objdump + a real unicorn CPU emulation run
            // against the patched binary).
            Logger.log("== native library scan (Haegemonia save/load null guard) ==")
            for (name in entryNames) {
                if (!name.endsWith("libhaegemonia_base.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfHaegemoniaSaveGameNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z28. Haegemonia + IG2 (1.14): save-dir literal "/Android/data/" -> "/Android/obb//".
            // The game's own native mkdir of Android/data/<pkg> fails silently on Android 11+, so
            // saves never reach disk. Android/obb/<pkg> already exists (the obb file lives there);
            // the user creates files/save and files/campaignsave by hand with a file manager (the
            // same way the videos folder works - file creation in such folders is proven to work).
            Logger.log("== native library scan (save directory in obb) ==")
            // 1.15: switched OFF - folders created by a file manager are owned by another uid and the
            // game cannot write into them; the dex-side StorageDiag now creates the original
            // Android/data/<pkg>/files/save + campaignsave folders through the system API instead.
            val obbSaveDirEnabled = false
            for (name in entryNames) {
                if (!obbSaveDirEnabled) continue
                if (!name.endsWith("libhaegemonia_base.so") && !name.endsWith("libig2ps.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfHaegemoniaSaveDirPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z26/2Z27 REMOVED AGAIN (2026-10-05, v1.12) - the save/load debug-trace caves
            // (ElfHaegemoniaSaveDebugTracePatcher / ElfImperiumGalactica2SaveDebugTracePatcher) were
            // re-enabled in 1.10/1.11 and, on their FIRST real-device run, made Haegemonia freeze
            // instantly on every save and never wrote soa_save_debug.txt at all - i.e. the caves
            // themselves are broken on real hardware despite passing capstone + unicorn emulation.
            // Never re-enable them without finding that bug first. The .kt files stay in the tree,
            // unused, for reference only.

            // --- 2e. Mass Effect: Infiltrator - point the EA download SDK's data folder at
            // Android/obb/<pkg> instead of Android/data/<pkg>. See the 2d note above and
            // DexPatcher's Mass Effect recipe (which changes the matching Java-side constants).
            if (entryNames.any { it.endsWith("libMassEffect.so") }) {
                val cfgName = "assets/downloadcontent/config.properties"
                if (zin.getEntry(cfgName) != null) {
                    val cfgText = zin.getInputStream(zin.getEntry(cfgName)).readBytes().toString(Charsets.UTF_8)
                    val newCfg = cfgText.replace("DATA_FOLDER=/Android/data/", "DATA_FOLDER=/Android/obb/")
                    if (newCfg != cfgText) {
                        libReplacements[cfgName] = newCfg.toByteArray(Charsets.UTF_8)
                        Logger.log("  [fix] $cfgName: DATA_FOLDER moved from Android/data to Android/obb")
                        changes.add("$cfgName: DATA_FOLDER moved to Android/obb/<package> (game data goes into the OBB folder)")
                    }
                }
            }

            // --- 3. dex patching ---
            Logger.log("== dex patching ==")
            val dexEntries = entryNames.filter { Regex("classes\\d*\\.dex").matches(it) }
            val dexBytes = dexEntries.associateWith { zin.getInputStream(zin.getEntry(it)).readBytes() }
            val dexResult = DexPatcher.patchDexFiles(context, dexBytes, File(workDir, "dexwork").apply { mkdirs() })
            changes.addAll(dexResult.changes)
            if (dexResult.changes.isEmpty()) {
                Logger.log("  no known crash patterns matched in this APK's dex - only the manifest/native-lib " +
                    "fixes above (if any) will apply.")
            }

            // --- 4. reassemble unsigned APK from the ORIGINAL, swapping only the touched entries ---
            Logger.log("== assembling patched APK ==")
            val unsigned = File(workDir, "unsigned.apk")
            writeRepackagedApk(zin, unsigned, newManifest, dexResult.dexFiles, libReplacements)

            // --- 5. sign ---
            Logger.log("== signing ==")
            val outputDir = File(context.cacheDir, "patched").apply { mkdirs() }
            val baseName = originalFileName.trim().let {
                if (it.isEmpty()) inputApk.nameWithoutExtension
                else it.removeSuffix(".apk").removeSuffix(".APK")
            }
            val outName = "$baseName-rebuilded.apk"
            val output = File(outputDir, outName)
            ApkSigningUtil.signApk(unsigned, output, customKeystore)

            Logger.log("done -> ${output.name}")
            return PatchResult(output, changes, entryNames.any { it.endsWith("libMassEffect.so") })
        } finally {
            zin.close()
        }
    }

    private fun writeRepackagedApk(
        zin: ZipFile,
        outFile: File,
        newManifest: ByteArray,
        dexReplacements: Map<String, ByteArray>,
        libReplacements: Map<String, ByteArray>
    ) {
        BufferedOutputStream(FileOutputStream(outFile)).use { fos ->
            ZipOutputStream(fos).use { zout ->
                val entries = zin.entries().toList()
                val writtenNames = mutableSetOf<String>()

                for (entry in entries) {
                    if (entry.name.startsWith("META-INF/")) continue
                    if (entry.isDirectory) continue

                    val raw: ByteArray = when {
                        entry.name == "AndroidManifest.xml" -> newManifest
                        dexReplacements.containsKey(entry.name) -> dexReplacements.getValue(entry.name)
                        libReplacements.containsKey(entry.name) -> libReplacements.getValue(entry.name)
                        else -> zin.getInputStream(entry).readBytes()
                    }

                    writeEntry(zout, entry.name, raw, entry.method)
                    writtenNames.add(entry.name)
                }

                // classes*.dex that exist only in the rebuilt output (rare - multidex growth
                // from a patch that pushed a class over the 64k method limit).
                for ((name, bytes) in dexReplacements) {
                    if (name !in writtenNames) {
                        writeEntry(zout, name, bytes, ZipEntry.DEFLATED)
                    }
                }
            }
        }
    }

    /** Mirrors the original entry's compression method - STORED entries need their
     *  size/crc set up front (ZipOutputStream can't compute those for you like it
     *  does for DEFLATED entries). */
    private fun writeEntry(zout: ZipOutputStream, name: String, data: ByteArray, method: Int) {
        val entry = ZipEntry(name)
        if (method == ZipEntry.STORED) {
            entry.method = ZipEntry.STORED
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = CRC32()
            crc.update(data)
            entry.crc = crc.value
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zout.putNextEntry(entry)
        zout.write(data)
        zout.closeEntry()
    }
}
