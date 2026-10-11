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

    data class PatchResult(val outputFile: File, val changes: List<String>)

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

        val zbGame = ZbPatcher.detectGame(zin.entries().toList().map { it.name })
        if (zbGame == null) {
            zin.close()
            throw PatchException(
                "SoA32to64 only supports Haegemonia: Legions of Iron and Imperium Galactica 2 " +
                    "(no libhaegemonia_base.so / libig2ps.so found in this APK)"
            )
        }
        Logger.log("game detected: ${zbGame.title} (${zbGame.pkg})")

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

            // --- 2d. native lib scan: Mass Effect: Infiltrator only - stub out the EA Origin/
            // DirtySDK online-service layer (dead servers, see ElfMassEffectOnlineStubPatcher's
            // doc comment for the full rationale and why this can never affect any other game).
            Logger.log("== native library scan (Mass Effect: Infiltrator EA Origin/network stub) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectOnlineStubPatcher.fixIfNeeded(name, bytesToCheck)
                for (siteNote in result.siteNotes) Logger.log("  [site] $name: $siteNote")
                if (result.patched) {
                    libReplacements[name] = bytesToCheck // fixIfNeeded() patches this array in place
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                }
            }

            // --- 2e. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the native
            // startup SIGSEGV chased since "Round 17". The full diagnostic-probe chain (fix
            // #23/#24/#25, ElfMassEffectHandleMessageDiagnosticPatcher/
            // ElfMassEffectSystemExitDiagnosticPatcher, both now unwired from this pipeline - their
            // job is done, see their doc comments for the full chase) traced the crash to
            // GameApplication::OnUpdate() deliberately calling Application::Exit()/System::Exit()
            // when a legacy "is primary external storage writable" watchdog
            // (GetWritableStorageDirectory(2, ...)) fails - which it always does under modern
            // Android's scoped storage. See ElfMassEffectPrimaryStorageExitPatcher's doc comment
            // for the full root-cause writeup; this neutralizes just that one watchdog's ability to
            // force-quit the game, nothing else.
            Logger.log("== native library scan (Mass Effect: Infiltrator storage-exit fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectPrimaryStorageExitPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2f. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the SECOND
            // native SIGSEGV, exposed only after fix #26 (2e, above) let execution proceed past
            // the storage-exit crash ("Round 25"). Root cause: FreeType's FT_Request_Size() reads
            // face->driver->clazz->request_size, and this bundled FreeType build's driver->clazz
            // is NULL (confirmed via real-device register dump: r3 00000000, fault addr 0x60) -
            // a pre-existing engine-side defect in the driver/module registration, unrelated to
            // Android version, apparently never exercised successfully until fix #26 let startup
            // reach this far. See ElfMassEffectFreeTypeRequestSizePatcher's doc comment for the
            // full root-cause writeup; this makes FT_Request_Size() always take FreeType's own
            // already-safe generic fallback sizing path instead of dereferencing the null pointer.
            Logger.log("== native library scan (Mass Effect: Infiltrator FreeType font-driver fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectFreeTypeRequestSizePatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2g. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the THIRD
            // native SIGSEGV in the same FreeType font-driver chain ("Round 27"). Fix #28
            // (ElfMassEffectFreeTypeSizeAllocPatcher, real-device tested "Round 27") forced a
            // generous, fixed FT_Size allocation size, on the hypothesis that face->size was
            // simply allocated too small - that hypothesis was DISPROVEN (the crash was byte-for-
            // byte identical with the forced allocation in place), and reading the real, static
            // `tt_driver_class` table straight out of the binary showed its *correct*
            // size_object_size (304 bytes) is nothing like the small guess that patch used and
            // wouldn't have mattered anyway - so that patch is unwired here (kept in the repo for
            // history) rather than shipped for no benefit and unnecessary shared-code risk. See
            // ElfMassEffectFreeTypeMetricsSkipPatcher's doc comment for the new approach and the
            // full evidence trail (including the tt_driver_class findings) that led to it.
            Logger.log("== native library scan (Mass Effect: Infiltrator FreeType metrics-skip fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectFreeTypeMetricsSkipPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2h. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the FOURTH
            // native SIGSEGV in the same FreeType font-driver chain, and the likely TRUE root
            // cause behind fixes #27 and #29 as well ("Round 28/29"). After fix #29 shipped, a
            // real-device test showed NO crash at all inside FT_Request_Metrics() for the first
            // time - fix #29 fully works - but a new crash appeared one call frame further up, in
            // FT_Request_Size(), this time with an outright invalid (odd/misaligned) `face`
            // pointer. Disassembling im::FreeTypeFont::FreeTypeFont() end to end showed why: the
            // constructor calls FT_New_Face() and then NEVER checks its return value before
            // reloading this->face and calling FT_Set_Char_Size() on it - so any time FT_New_Face()
            // fails for this specific debug-menu font, the constructor barrels ahead using whatever
            // (uninitialized, since operator new doesn't zero-init) garbage was already sitting in
            // this->face. Different leftover garbage on each run explains every different-looking
            // font crash symptom seen across fixes #27/#29 as the same underlying bug. See
            // ElfMassEffectFreeTypeNewFaceGuardPatcher's doc comment for the full instruction-by-
            // instruction writeup; this makes the constructor skip FT_Set_Char_Size() entirely
            // whenever FT_New_Face() itself already reported failure.
            Logger.log("== native library scan (Mass Effect: Infiltrator FreeType New_Face-result guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectFreeTypeNewFaceGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2i. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the FIFTH
            // native SIGSEGV, and the first one in this whole project that's outside the FreeType
            // font-driver chain ("Round 30"). After fix #31 closed the font saga, the game
            // proceeds all the way to actually starting a new game before crashing: Application::
            // LoadLevelWithCinematics() dereferences whatever LevelIndexData::GetLevel() returns
            // with zero NULL check, and GetLevel() returns NULL because the requested level isn't
            // in the level index - even though this project confirmed with the user (via adb) that
            // the underlying data file genuinely exists on their device at the expected path; why
            // the native VFS/file-open path still comes up empty is a separate, not yet answered
            // question. See ElfMassEffectLevelIndexNullGuardPatcher's doc comment for the full
            // writeup; this makes the crash site fall into the SAME function's own already-existing
            // "level found but has no cinematics" fallback path instead of dereferencing NULL,
            // regardless of the deeper mystery.
            Logger.log("== native library scan (Mass Effect: Infiltrator level-index null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectLevelIndexNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2j. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the SIXTH
            // native SIGSEGV ("Round 32"). After fix #32 let the game reach real level loading, a
            // background asset-loading thread now crashes in im::m3gext::ModelCache::
            // LoadNodeUncached() - it dereferences a "loaded objects" vector's first element with no
            // check for the vector being empty (a model/node file that couldn't be loaded), same
            // general shape of bug as fix #32, one level deeper. See
            // ElfMassEffectModelCacheNullGuardPatcher's doc comment for the full writeup and the
            // one documented trade-off (a narrower secondary null-check than the original had room
            // for, in the same fixed instruction budget); this makes the crash site fall into the
            // same function's own already-existing "no node produced" fallback instead.
            Logger.log("== native library scan (Mass Effect: Infiltrator model-cache null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectModelCacheNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2k. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the SEVENTH
            // native SIGSEGV ("Round 33"). After fix #33 let LoadNodeUncached() stop crashing, the
            // same background asset-loading thread now crashes one call frame deeper, in
            // im::m3gext::ModelCache::CacheCreateNode() - it unconditionally calls
            // m3g::Object3D::Duplicate() on a "source node to clone" pointer with no check for that
            // pointer being NULL (a third recurrence of the same "not found/empty result used
            // unchecked" pattern as fixes #32/#33, one level deeper still in the same model/node
            // cache subsystem). See ElfMassEffectObject3DDuplicateNullGuardPatcher's doc comment for
            // the full writeup; this makes the crash site fall into the same function's own
            // already-existing "duplicate came back NULL" fallback instead of crashing.
            Logger.log("== native library scan (Mass Effect: Infiltrator Object3D::Duplicate null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectObject3DDuplicateNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2l. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the EIGHTH
            // native SIGSEGV ("Round 33"), and the first one in a long while on the main GL thread
            // instead of a background asset-loading thread. After fix #34 the game reaches actual
            // HUD initialization during a level transition, where Hud::InitLayer() unconditionally
            // dereferences its "hud" widget in ~50 instructions across the function with no NULL
            // check anywhere - if WidgetFactory::CreateWidget("hud", ...) fails, the very first
            // dereference (writing an owner pointer into the widget) crashes. See
            // ElfMassEffectHudWidgetNullGuardPatcher's doc comment for the full writeup: this makes
            // every widget-dependent instruction across three small, independently-gated regions
            // conditional on the widget actually being non-NULL, applied atomically (all three or
            // none).
            Logger.log("== native library scan (Mass Effect: Infiltrator HUD widget null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudWidgetNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2m. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the NINTH
            // native SIGSEGV ("Round 34"). After fix #35 let Hud::InitLayer() get past the widget
            // null-guard, it crashes 228 bytes later - NOT a null pointer this time (code 2
            // SEGV_ACCERR, not code 1 SEGV_MAPERR): a stack slot shared between an earlier
            // boost::function temp and a never-actually-populated shared_ptr<Node> local gets read
            // as if it held a real Node, and SetVisible() is called on the leftover (always non-
            // NULL, always-the-same) boost::function manager-pointer value, writing into the
            // library's own read-only code/rodata segment. See
            // ElfMassEffectHudDebugNodeSkipPatcher's doc comment for the full writeup: since the
            // checked value was never a real Node on any reachable path, the fix is a single-byte
            // condition-code flip making the existing beq unconditional, always taking the already-
            // safe "skip it" path.
            Logger.log("== native library scan (Mass Effect: Infiltrator HUD debug-node skip fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudDebugNodeSkipPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2n. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the TENTH
            // native SIGSEGV ("Round 35"). After fix #36 skipped the buggy SetVisible() call, the
            // very next instruction crashes: the companion eastl::shared_ptr<Node>::~shared_ptr()
            // call on that same phantom stack-slot "Node", fault addr exactly 0x00000004 (SEGV_
            // MAPERR). Fix #36's doc comment had claimed this destructor call was "provably
            // harmless" - real-device testing disproved that: the compiled destructor never null-
            // checks its control-block field before dereferencing through it. See
            // ElfMassEffectHudDebugNodeDestructorGuardPatcher's doc comment for the full writeup and
            // correction: this repurposes fix #36's now-dead ldr/cmp pair to test the real control-
            // block field instead, and makes the destructor call conditional on it (bl -> blne).
            // Depends on fix #36 already being applied to the same bytes (checked explicitly).
            Logger.log("== native library scan (Mass Effect: Infiltrator HUD debug-node destructor guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudDebugNodeDestructorGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2o. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the ELEVENTH
            // native SIGSEGV ("Round 36"). After fix #37 cleared the whole Hud::InitLayer() crash
            // chain, the game reaches a completely different function: Widget::AppendChild()
            // dereferences a NULL child Node pointer (fault addr 0x28) - the same "unchecked empty
            // result" pattern as fixes #32-#34, but this time there's no in-place instruction
            // budget for a guard (Group::AppendChild(), which every path here eventually calls, is
            // itself not NULL-safe either, so the fix must skip the append entirely rather than
            // just guard one dereference). See ElfMassEffectAppendChildNullGuardPatcher's doc
            // comment for the full writeup: a same-size `bl` redirects the one crashing instruction
            // to a small out-of-line trampoline ("code cave") written into 16 bytes of confirmed-
            // unused zero padding elsewhere in the binary, which tests the child pointer and either
            // returns immediately (NULL) or does the original load and returns normally (non-NULL).
            Logger.log("== native library scan (Mass Effect: Infiltrator AppendChild null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectAppendChildNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2p. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the TWELFTH
            // native SIGSEGV ("Round 37"). After fix #38 cleared every main-thread/HUD-init crash
            // seen so far, the game hits its first background level-loading crash since fix #33:
            // GameWorld::InitLevel() dereferences a NULL LevelIndexData::GetLevel() result (same
            // "unchecked empty result" pattern, different call site). No small nearby fallback this
            // time either - the function is huge with exactly one shared exit point far away. See
            // ElfMassEffectGameWorldLevelNullGuardPatcher's doc comment: reuses fix #38's out-of-
            // line-trampoline ("code cave") technique, redirecting the crash into the function's
            // own existing shared epilogue (which already destructs a constructed PerfTimer local
            // and returns) instead of crashing.
            Logger.log("== native library scan (Mass Effect: Infiltrator GameWorld level null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectGameWorldLevelNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2q. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the THIRTEENTH
            // native SIGSEGV ("Round 38"). After fix #39 cleared the GameWorld::InitLevel() crash,
            // level loading gets into HUD setup for the freshly-loaded world before hitting this:
            // Hud::InitWorld() calls HudWidget::PostWorldSetup()/TriggerLevelTitle() on the SAME
            // m_hudWidget field fix #35 already guards inside Hud::InitLayer() (offset +568/0x238)
            // - but unconditionally, with no null check at all. Same underlying field, different
            // call site, same "unchecked empty/failed result" family of bug. See
            // ElfMassEffectHudWorldSetupNullGuardPatcher's doc comment: reuses fixes #38/#39's out-
            // of-line-trampoline ("code cave") technique in the same free .rodata block, skipping
            // both now-unreachable calls when the widget is NULL instead of crashing.
            Logger.log("== native library scan (Mass Effect: Infiltrator Hud::InitWorld null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudWorldSetupNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2r. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the FOURTEENTH
            // native SIGSEGV ("Round 39"). After fix #40 cleared both m_hudWidget call sites,
            // Hud::InitWorld() runs much further before hitting this: it reads level->[196] (a
            // level-tied title color) through the SAME LevelIndexData::GetLevel() result fixes
            // #5/#39 already had to guard at their own call sites - completely unconditionally.
            // See ElfMassEffectHudLevelTitleColorNullGuardPatcher's doc comment: reuses fixes
            // #38-#40's out-of-line-trampoline ("code cave") technique in the same free .rodata
            // block, skipping the whole color-compute-and-store sequence when the level is NULL
            // instead of crashing.
            Logger.log("== native library scan (Mass Effect: Infiltrator Hud level-title color null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudLevelTitleColorNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2s. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the FIFTEENTH
            // native SIGSEGV ("Round 40"). After fix #41 cleared the level-title-color read,
            // Hud::InitWorld() finishes and calls Hud::StateTransition(), which calls
            // InputSchemeBasic::SetFireEnabled(false) on the Hud's embedded input scheme -
            // which already null-checks ONE of its two dependent fields but not the sibling touch-
            // D-pad-forwarder field, unconditionally using it as `this` for another call. On this
            // device that field holds the literal sentinel value 1 (this build uses a physical
            // gamepad, so the touch D-pad forwarder it would normally point to is never built). See
            // ElfMassEffectTouchDPadForwarderNullGuardPatcher's doc comment: reuses fixes #38-#41's
            // out-of-line-trampoline ("code cave") technique, skipping the call when the field is
            // <=1 instead of crashing.
            Logger.log("== native library scan (Mass Effect: Infiltrator touch D-pad forwarder null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectTouchDPadForwarderNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2t. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the SIXTEENTH
            // native SIGSEGV ("Round 41"). Fix #42's crash frame (InputSchemeBasic::SetFireEnabled
            // (bool)+48) no longer appears at all - confirming that call is fixed - and the SAME
            // crash site is hit one call later: SetFireEnabled() calls SetAutoFireEnabled() a
            // SECOND time, via a tail-call, through a sibling forwarder field (this->[92]) that's
            // just as unguarded. This device's value there (0x1000) differs from fix #42's (1) -
            // consistent with genuinely uninitialized memory rather than one fixed sentinel. See
            // ElfMassEffectTouchDPadForwarderSecondNullGuardPatcher's doc comment: reuses fixes
            // #38-#42's out-of-line-trampoline ("code cave") technique, guarding on <=0x1000 (one
            // page - real heap pointers in this process are always far larger) and reproducing the
            // caller's own pop/return on the guard path, since this call site is a tail-call.
            Logger.log("== native library scan (Mass Effect: Infiltrator touch D-pad forwarder #2 null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectTouchDPadForwarderSecondNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2u. Mass Effect: Infiltrator only - PROACTIVE fix, found by static analysis
            // rather than a real-device crash log ("Round 42"). After fix #43 shipped, the user
            // asked how many more test/fix cycles were left; told honestly it can't be predicted,
            // they pushed for a broader scan instead of one-crash-per-test. Disassembling every
            // InputSchemeBasic method for the same bug pattern found that InputSchemeBasic::
            // OnEvent() reads the SAME two touch D-pad forwarder fields fixes #42/#43 already
            // guard (this->[80], this->[92]) - through a completely different call path (toggling
            // a fire-related setting on them) that this user's playthrough hasn't triggered yet.
            // See ElfMassEffectOnEventTouchDPadToggleNullGuardPatcher's doc comment: reuses the
            // same out-of-line-trampoline technique and <=0x1000 threshold as fixes #42/#43,
            // sharing a single cave between both call sites since the replaced instruction is
            // byte-identical at both.
            Logger.log("== native library scan (Mass Effect: Infiltrator OnEvent touch D-pad toggle null-guard fix - proactive) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectOnEventTouchDPadToggleNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2v. Mass Effect: Infiltrator only - REAL FIX (not diagnostic) for the EIGHTEENTH
            // native SIGSEGV ("Round 43"). 0.44 (fix #43 + proactive fix #44) real-device tested:
            // both confirmed fixed, and a genuinely new crash surfaces one call further in, right
            // where fix #41's own guard resumes execution - Hud::StateTransition() iterates a
            // vector of raw pointers and calls a virtual method through every element with no null
            // check; this device has a NULL entry in that vector. See
            // ElfMassEffectHudStateTransitionListenerNullGuardPatcher's doc comment: reuses the
            // out-of-line-trampoline technique in the same free .rodata block, skipping just that
            // one element's unused-return-value virtual call and resuming the loop normally for
            // the next element.
            Logger.log("== native library scan (Mass Effect: Infiltrator Hud::StateTransition listener null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudStateTransitionListenerNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2w. Mass Effect: Infiltrator only - REAL FIX for the TWENTY-FIRST native crash
            // ("Round 46"), a NEW site one call earlier than fix #45's own guard within the same
            // Hud::StateTransition() function: the listener/layer vector's own BEGIN pointer can
            // be NULL/never-constructed while the byte-length check that's supposed to catch an
            // empty container still passes (because `end` alone, not `end - begin`, ends up
            // looking like a plausible size when `begin` is exactly zero). Found via a controlled
            // A/B test the user ran (with vs. without the ~900MB downloaded level content present)
            // that also settled - in the negative - the long-open Round 30/31 mystery about
            // whether that content's presence matters here: it doesn't, the crash is identical
            // either way. See ElfMassEffectHudStateTransitionBeginNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator Hud::StateTransition begin-pointer null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudStateTransitionBeginNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2x. Mass Effect: Infiltrator only - REAL FIX for the TWENTY-SECOND native crash
            // ("Round 47"), a genuinely new site: Playable::AbortSniper(), reached now that fixes
            // #45/#46 let Hud::StateTransition()'s listener loop actually iterate real elements -
            // Playable::OnBioticSelectBegin() gets called through the loop's virtual dispatch and
            // calls into AbortSniper(), which reads an optional "current sniper weapon" field and
            // only guards it against exact NULL, missing the same small-sentinel (0x21) value this
            // project has hit repeatedly. Needs no code cave - the existing check is widened
            // in-place, exactly like fix #45's own 0.46->0.47 correction. See
            // ElfMassEffectAbortSniperNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator Playable::AbortSniper null-guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectAbortSniperNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2y. Mass Effect: Infiltrator only - REAL FIX for the TWENTY-FOURTH native crash
            // ("Round 49"), yet another new site in the same Hud::StateTransition() function - but
            // NOT the same call fixes #45/#46 guard (that's a vtable dispatch reached earlier in the
            // function); this is a separate, plain function call straight into
            // Playable::OnBioticSelectBegin() whose "this" pointer is read from a field that isn't
            // always populated with a real Playable* - on this device it held stray .rodata string
            // literal bytes ("blas...") instead. Neither of fix #45's existing thresholds (<=0x1000 /
            // >=0xF0000000) catches this new mid-range value, so this fix instead whitelists the
            // project's own observed real-pointer range (>= 0xC0000000) rather than adding a third
            // blacklist band. See ElfMassEffectHudBioticSelectCallGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator Hud::StateTransition biotic-select call guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectHudBioticSelectCallGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2z. Mass Effect: Infiltrator only - REAL FIX for the TWENTY-SEVENTH native crash
            // ("Round 52"), reached now that fix #45's Round 6 (the .data.rel.ro-range vtable
            // guard) let the listener loop's virtual dispatch through cleanly - Playable::
            // OnBioticSelectBegin() got further than ever before, into a THIRD call in the same
            // function (past AbortSniper(), which fix #47 already guards): Weapon::OnAbort(),
            // called with a "current weapon" field that isn't always populated (same never-
            // constructed 0x21 sentinel fixes #45/#47 have hit before). See
            // ElfMassEffectWeaponOnAbortCallGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator Weapon::OnAbort call guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectWeaponOnAbortCallGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2A. Mass Effect: Infiltrator only - REAL FIX for the TWENTY-EIGHTH native crash
            // ("Round 53"), reached now that fix #49 (Weapon::OnAbort() call guard) let Playable::
            // OnBioticSelectBegin() get past its third call cleanly - into a type-check block that
            // dispatches through "this" object's OWN vtable pointer (not a field on it, like every
            // prior fix in this function) and feeds the virtual call's return value into strcmp(),
            // so the earlier "just skip the call" technique (fix #48/#49) can't be reused: this
            // instead validates the vtable pointer itself against the library's actual
            // .data.rel.ro bounds (same structural technique as fix #45 Round 6) and, on failure,
            // skips the whole type-check block (both dispatches + both strcmp() calls) straight to
            // its own natural fallback continuation. See ElfMassEffectBioticSelectVtableGuardPatcher's
            // doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator biotic-select type-check vtable guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectBioticSelectVtableGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2B. Mass Effect: Infiltrator only - REAL FIX for the TWENTY-NINTH native crash
            // ("Round 54"), reached now that fix #50 let Playable::OnBioticSelectBegin()'s
            // type-check block through cleanly - Hud::StateTransition() progressed into a SIBLING
            // function, Playable::EndTapToAim(bool), which unconditionally calls reset() on a
            // shared_ptr<GameObject> MEMBER whose own control-block pointer isn't always populated
            // (same never-constructed-field pattern as every prior fix in this saga, this time
            // inside a shared_ptr's internals rather than a raw pointer). .rodata is essentially
            // out of single-region free space after fix #50, so this reuses the "chained
            // trampolines" technique across three separate small (8-byte) gaps instead of one
            // contiguous cave. See ElfMassEffectEndTapToAimResetGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator EndTapToAim reset guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectEndTapToAimResetGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2C. Mass Effect: Infiltrator only - PROACTIVE fix ("Round 55"), shipped
            // alongside fix #51 without waiting for its own crash report: Playable::EndTapToAim()
            // has its OWN independent call to Weapon::OnAbort() (the exact same call/field fix #49
            // already guards at a DIFFERENT call site inside OnBioticSelectBegin()) - found by
            // inspection while disassembling EndTapToAim() for fix #51, fixed with the identical
            // technique/threshold to cut down future real-device test rounds. See
            // ElfMassEffectEndTapToAimWeaponOnAbortGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator EndTapToAim Weapon::OnAbort guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectEndTapToAimWeaponOnAbortGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2D. Mass Effect: Infiltrator only - REAL FIX for the THIRTIETH native crash
            // ("Round 55"), a brand new call chain, not part of the Hud::StateTransition()/
            // Playable saga fixes #45-#52 cover: GameWorld::GetSubtitleWidget() (called from
            // LayerGameWorld::LoadLevel()) reads the UI system's root-widget field from the Hud
            // and passes it, unchecked, into UIWidget::GetWidget<Subtitles>() - which immediately
            // dereferences it to make a virtual call. That field isn't always populated yet at
            // this point in level loading (a plain NULL this time, not the usual small-sentinel
            // garbage). See ElfMassEffectSubtitleWidgetGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator subtitle widget guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectSubtitleWidgetGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2E. Mass Effect: Infiltrator only - REAL FIX for crash #54, a THIRD unguarded
            // pointer dereference inside Playable::EndTapToAim(bool), between the two call sites
            // fixes #51/#52 already guard: an optional touch-input-state field gets an
            // unconditional flag-byte write through it with no validity check. Found in the 0.57
            // test - a classic instance of this project's recurring "0xff......-prefixed wild
            // pointer" pattern (fault addr 0xff000082), which the plain "< 0xC0000000" whitelist
            // floor most fixes here use does NOT catch (0xff000000 is numerically above that
            // floor) - reuses the two-sided range fix #45 Round 4 already validated for this same
            // pattern instead. See ElfMassEffectEndTapToAimTouchFlagGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator EndTapToAim touch-flag guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectEndTapToAimTouchFlagGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2F. Mass Effect: Infiltrator only - REAL FIX for crash #55: the SAME
            // "two type-check virtual dispatches through one vtable pointer" idiom fix #50 already
            // fixed once (in Playable::OnBioticSelectBegin()), found a second time independently
            // inside Playable::EndTapToAim() itself - dispatching through this's own vtable with
            // no validity check. Reuses fix #50's exact .data.rel.ro bounds check. See
            // ElfMassEffectEndTapToAimVtableGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator EndTapToAim vtable guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectEndTapToAimVtableGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2G. Mass Effect: Infiltrator only - REAL FIX for crash #56: on the very first
            // level load, LayerGameWorld::LoadLevel() assigns its subtitle widget eastl::shared_ptr
            // through a control-block pointer that hasn't been fully constructed yet (the "ptr"
            // half looks like a real heap pointer, the "ctrl" half is leftover garbage). Guards the
            // reload of that ctrl pointer with this project's proven >=0xC0000000 heap-floor check,
            // skipping the whole increment/swap/destruct block exactly like the function's existing
            // self-assignment shortcut does. See
            // ElfMassEffectLoadLevelSubtitleWidgetGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator LoadLevel subtitle widget guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectLoadLevelSubtitleWidgetGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2H. Mass Effect: Infiltrator only - REAL FIX for crash #57: the SAME garbage
            // control-block pointer fix #56 already guards once (at the refcount-increment site)
            // gets used a second time in the same function, when the local temporary shared_ptr
            // GetSubtitleWidget() returned goes out of scope and its destructor runs - this call
            // happens unconditionally, on every path, so needs its own independent guard. See
            // ElfMassEffectLoadLevelSubtitleDtorGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator LoadLevel subtitle widget destructor guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectLoadLevelSubtitleDtorGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2I. Mass Effect: Infiltrator only - REAL FIX for crash #58: a brand new crash,
            // past level loading entirely (during rendering) - Environment::GetSunFlare() derefs
            // its Hud* parameter unconditionally with no NULL check, and some render-callback
            // caller passes NULL. Skips straight to the function's own single shared epilogue,
            // returning the input flare index unchanged (its own existing "nothing to do"
            // convention). See ElfMassEffectGetSunFlareNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator GetSunFlare null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectGetSunFlareNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2J. Mass Effect: Infiltrator only - REAL FIX for crash #60: a brand new crash,
            // deep in texture upload/rendering - OpenGLESRenderer::ValidateTexture() calls
            // Texture2D::GetImage() TWICE on the same object, properly null-checks the FIRST
            // result but feeds the SECOND, redundant result straight into Image2D::IsInvalid()
            // with no check at all. Gates the second result with the same "small garbage/sentinel
            // pointer" bounds check used throughout this project, bailing to the function's own
            // existing "texture invalid" path if it looks bad, or calling the real IsInvalid() and
            // resuming normally if it looks fine. See
            // ElfMassEffectValidateTextureImageNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator ValidateTexture Image null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectValidateTextureImageNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2K. Mass Effect: Infiltrator only - REAL FIX for crash #62: one level deeper in
            // the same texture-upload subsystem - eastl::shared_ptr<im::TextureImage>::~shared_ptr()
            // writes a decremented refcount back through a control-block pointer whose stored
            // refcount value isn't a plausible small integer (a partially-constructed TextureImage's
            // never-initialized embedded shared_ptr member). Validates the refcount VALUE itself
            // (not the pointer, which isn't reliably distinguishable by magnitude here) before
            // writing it back, bailing to the function's own existing "nothing more to do" path
            // otherwise. See ElfMassEffectUploadTexturesRefcountGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator UploadTextures refcount guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectUploadTexturesRefcountGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2L. Mass Effect: Infiltrator only - REAL FIX for crash #63: a brand new crash that
            // fires automatically during level LOADING, before the player does anything - a missing
            // null check in MusicManager::StartLevel(). LevelIndexData::GetLevel() can return null
            // when the requested level name isn't found in the level index, and StartLevel() then
            // unconditionally dereferences that result to read the level's music-track name (fault
            // addr 0xb4 - a null pointer plus a small struct-field offset). Bails to this function's
            // own existing ResumeLevelMusic() fallback instead, exactly as it already does when the
            // self-assignment guard a few instructions later trips. See
            // ElfMassEffectMusicStartLevelNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator MusicManager StartLevel null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectMusicStartLevelNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2M. Mass Effect: Infiltrator only - REAL FIX for crash #64: OpenGLESRenderer::
            // ValidateTexture()'s FIRST (not the fix #60-guarded second) Texture2D::GetImage() call
            // crashes inside GetImage() itself when the raw texture pointer is a dangling reference -
            // one that isn't distinguishable from a real pointer by magnitude alone, but is exactly
            // page-aligned (a signature this project hasn't needed before). Bails to the function's
            // own existing "texture invalid" path if the pointer is page-aligned OR outside the
            // established real-heap range, otherwise calls GetImage() exactly as before. See
            // ElfMassEffectValidateTextureFirstGetImageGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator ValidateTexture first GetImage guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectValidateTextureFirstGetImageGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2N. Mass Effect: Infiltrator only - REAL FIX for crash #66: a brand new crash found
            // only in landscape orientation (portrait never triggers it; confirmed via real-device
            // testing with and without Android's `am compat` orientation-override flags - identical
            // crash either way, so this is a genuine native engine bug, not a compat-flag artifact).
            // LayerGameWorld::OnDraw()'s camera/lens-flare aspect-ratio computation dereferences a
            // sub-object pointer that this device's landscape/resolution combination leaves NULL.
            // Bails to the function's own existing "no usable flare data this frame" fallback
            // (already used by a different condition a few instructions later) when NULL, otherwise
            // performs the original dereference exactly as before. See
            // ElfMassEffectLayerGameWorldOnDrawNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator LayerGameWorld OnDraw null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectLayerGameWorldOnDrawNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2O. Mass Effect: Infiltrator only - REAL FIX for crash #67: found in the 0.72
            // real-device test (every fix through #66 already in place) - a brand new crash, one
            // call earlier than any previous fix has reached. GameObject::GetNode() dereferences
            // its OWN 'this' pointer unconditionally, with no NULL check, and some untraceable
            // render/game-object-list caller passes NULL. Bails to a single-instruction unwind
            // that returns NULL (the same 'no node available' result this function's own other
            // internal fallback paths already produce). See
            // ElfMassEffectGameObjectGetNodeNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator GameObject GetNode null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectGameObjectGetNodeNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2P. Mass Effect: Infiltrator only - REAL FIX for crash #68: found in the 0.73
            // real-device test - one call further up the same chain fix #67 just made safe.
            // GameWorld::OnRender() calls GameObject::GetGroup() (which now correctly returns
            // NULL instead of crashing, per fix #67) and immediately dereferences the result with
            // no NULL check. Defaults the group-flag byte this call site reads to false on NULL -
            // which also makes the very next conditional read of the same pointer a no-op - instead
            // of crashing. See ElfMassEffectGameWorldOnRenderGroupNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator GameWorld OnRender Group null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectGameWorldOnRenderGroupNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Q. Mass Effect: Infiltrator only - REAL FIX for crash #69: found in the 0.74
            // real-device test - a different branch of the same per-frame HUD/render call chain
            // fixes #66-#68 already guard three crashes in. Playable::AllowIndicators() dereferences
            // its OWN 'this' pointer unconditionally, on its very first instruction, with no NULL
            // check, and Hud::DrawIndicators() calls it without checking first. Bails to a direct
            // 'return false' (the same outcome this function already produces for the ordinary
            // suppressed case) instead of crashing. See
            // ElfMassEffectAllowIndicatorsNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator AllowIndicators null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectAllowIndicatorsNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2R-2V. Mass Effect: Infiltrator only - PROACTIVE fixes #70-#74 (0.76 batch): not
            // triggered by a real crash log. After fixes #66-#69 turned out to be four crashes in a
            // row in the same per-frame render/HUD call chain, the user asked to cut down the number
            // of remaining one-crash-at-a-time real-device test rounds. This batch statically scans
            // the same "GameObject::Get*() const tiny leaf accessor dereferences its own 'this'
            // pointer completely unconditionally, no NULL check" bug shape that fixes #67 (GetNode())
            // and #69 (Playable::AllowIndicators()) already proved is real here, and guards every
            // other accessor sharing that exact shape - even without an individual crash log for
            // each one. Verified by objdump (every branch resolves correctly) and this project's
            // qemu-arm micro-harness (both an invalid and a valid 'this' register case), but NOT by
            // an observed crash - if any of these accessors' 'this' is in fact never null in
            // practice, its guard is a permanent no-op with zero behavioral effect. See each
            // patcher's own doc comment (ElfMassEffectGetCameraControllerNullGuardPatcher,
            // ElfMassEffectGetPlayerNullGuardPatcher, ElfMassEffectGetM3GWorldNullGuardPatcher,
            // ElfMassEffectGetDynamicsWorldNullGuardPatcher, ElfMassEffectGetGravityNullGuardPatcher).
            Logger.log("== native library scan (Mass Effect: Infiltrator proactive accessor null guards, fixes #70-#74) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()

                val r70 = ElfMassEffectGetCameraControllerNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (r70.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name (GetCameraControllerNullGuard): ${r70.note}")
                    changes.add("$name: ${r70.note}")
                } else {
                    Logger.log("  [skip] $name (GetCameraControllerNullGuard): ${r70.note}")
                }

                val r71 = ElfMassEffectGetPlayerNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (r71.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name (GetPlayerNullGuard): ${r71.note}")
                    changes.add("$name: ${r71.note}")
                } else {
                    Logger.log("  [skip] $name (GetPlayerNullGuard): ${r71.note}")
                }

                val r72 = ElfMassEffectGetM3GWorldNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (r72.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name (GetM3GWorldNullGuard): ${r72.note}")
                    changes.add("$name: ${r72.note}")
                } else {
                    Logger.log("  [skip] $name (GetM3GWorldNullGuard): ${r72.note}")
                }

                val r73 = ElfMassEffectGetDynamicsWorldNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (r73.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name (GetDynamicsWorldNullGuard): ${r73.note}")
                    changes.add("$name: ${r73.note}")
                } else {
                    Logger.log("  [skip] $name (GetDynamicsWorldNullGuard): ${r73.note}")
                }

                val r74 = ElfMassEffectGetGravityNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (r74.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name (GetGravityNullGuard): ${r74.note}")
                    changes.add("$name: ${r74.note}")
                } else {
                    Logger.log("  [skip] $name (GetGravityNullGuard): ${r74.note}")
                }
            }

            // --- 2W. Mass Effect: Infiltrator only - REAL FIX for crash #75: found in the 0.76
            // real-device test, the first crash ever seen in this project on the game's per-frame
            // UPDATE (logic) tick rather than the render/HUD chain fixes #66-#74 guard.
            // GameWorld::OnUpdate() dereferences its own player-GameObject field to read a one-shot
            // flag byte, completely unconditionally, before the player has spawned. See
            // ElfMassEffectGameWorldOnUpdatePlayerFlagNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator GameWorld OnUpdate player-flag null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectGameWorldOnUpdatePlayerFlagNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2X. Mass Effect: Infiltrator only - REAL FIX for crash #76: found in the 0.77
            // real-device test, one call frame further up the exact same GameWorld::OnUpdate()
            // caller chain fix #75 just made safe, in a sibling function - GameWorld::
            // UpdateSoundLevels() reads the SAME never-yet-spawned player-GameObject field (this
            // project's fourth fix, after #71/#75, to hit this one field) and immediately performs
            // a virtual-dispatch vtable read through it with no NULL check at all. Guards only the
            // one vtable-read instruction and, on the valid path, branches straight back into the
            // function's own UNTOUCHED "mov lr, pc" manual-dispatch instruction one address later -
            // so the original tail-call/virtual-dispatch sequence still computes its own return
            // address correctly, with no lr-relocation arithmetic needed anywhere in the cave. See
            // ElfMassEffectUpdateSoundLevelsPlayerVtableGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator UpdateSoundLevels player vtable guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectUpdateSoundLevelsPlayerVtableGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Y. Mass Effect: Infiltrator only - PROACTIVE fix, found by reading
            // v-atamanenko/masseffect-vita's loader source directly (the user pointed at it a
            // second time) rather than from a real-device crash log here. Its
            // loader/patch/deserialization_bug.c guards `im::serialization::Database`'s three
            // "unstructured object field" methods against a NULL ObjectHeaderDefinition* param -
            // their own README documents the user-visible symptom this prevents (a forced full
            // relaunch, disguised as a long loading screen, after dying or returning to the main
            // menu). This project's own established "skip and return a safe default" technique
            // handles the same NULL guard in-process, with no relaunch needed at all. See
            // ElfMassEffectUnstructuredFieldNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator unstructured-object-field null guard fix - proactive) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectUnstructuredFieldNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z2. Mass Effect: Infiltrator only - REAL FIX for crash #78: found in the 0.79
            // real-device test (fixes #75/#76 both confirmed working - GameWorld::OnUpdate() no
            // longer crashes on either of its own two previously-guarded sites). The game progressed
            // further into the SAME function, hitting a new crash 316 bytes later:
            // GameWorld::OnUpdate() unconditionally calls TeamGoals::OnUpdate(int) through its own
            // "teamGoals" field with no NULL check - that sub-object was simply never constructed for
            // this session. See ElfMassEffectTeamGoalsOnUpdateNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator TeamGoals OnUpdate null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectTeamGoalsOnUpdateNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z3. Mass Effect: Infiltrator only - PROACTIVE fix, found by reading
            // v-atamanenko/masseffect-vita's loader source directly (their loader/patch/info_menu.c,
            // comment: "Fix crash on INFO => ABOUT menu open") rather than from a real-device crash
            // log here. im::scene2d_new::Node::SetVisible(bool) dereferences its own 'this' pointer
            // unconditionally - guarded generically at the function's own entry point, covering
            // every caller throughout this engine's whole scene-graph/widget system at once, not just
            // the one menu screen that's documented to crash. See
            // ElfMassEffectNodeSetVisibleNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator Node::SetVisible null guard fix - proactive) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectNodeSetVisibleNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z4. Mass Effect: Infiltrator only - REAL FIX for crash #80, found in the 0.81
            // real-device test: the FIRST crash log this whole project has seen from actual
            // gameplay/loading-screen rendering (past every previous blocker - EA download SDK
            // skip, fix #30's Google Play Billing implicit-Intent guard). FreeType's own
            // FT_Done_Glyph(glyph) dereferences glyph->clazz->done without ever checking
            // glyph->clazz itself for NULL - fault addr 0xc matches exactly. See
            // ElfMassEffectDoneGlyphClazzNullGuardPatcher's doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator FT_Done_Glyph clazz null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectDoneGlyphClazzNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z5. Mass Effect: Infiltrator only - REAL FIX for crash #81, found in the 0.82
            // real-device test: the FIRST crash this project has seen on a BACKGROUND asset-loading
            // thread rather than the render (GLThread) thread. im::serialization_old::Deserializer's
            // copy constructor dereferences its own 'other' reference param unconditionally - fault
            // addr 0x0 matches a NULL reference exactly. Guarded at the function's own entry point
            // (shared by 16 call sites). See ElfMassEffectDeserializerCopyCtorNullGuardPatcher's doc
            // comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator Deserializer copy-ctor null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectDeserializerCopyCtorNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z6-2Z12. Mass Effect: Infiltrator only - PROACTIVE fix #82-#88, a batch of
            // GameObject accessor guards found by a whole-binary static scan (not a specific
            // crash log), the same "dereferences this with no NULL check" shape fixes
            // #67/#69/#70-74/#77-81 already proved is a real bug class in this class. See
            // ElfMassEffectGetRigidBodyPtrNullGuardPatcher's doc comment for the full scan
            // methodology.
            Logger.log("== native library scan (Mass Effect: Infiltrator GameObject accessor batch, fixes #82-#88) ==")
            // NOTE: each patcher object below declares its OWN nested `Result` data class (same
            // shape, but a distinct Kotlin type per object - not a shared base type), so these
            // can't be collected into one `listOf(...)` and iterated (that infers `List<Any>`,
            // which doesn't expose `.patched`/`.note`). Handling each call's result inline via
            // this tiny local helper keeps the loop compact without hitting that type error.
            fun applyGameObjectFix(name: String, patched: Boolean, note: String, anyPatchedSoFar: Boolean): Boolean {
                if (patched) {
                    Logger.log("  [fix] $name: $note")
                    changes.add("$name: $note")
                } else {
                    Logger.log("  [skip] $name: $note")
                }
                return anyPatchedSoFar || patched
            }
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                var anyPatched = false
                ElfMassEffectGetRigidBodyPtrNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                ElfMassEffectIsVisibleNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                ElfMassEffectIsTapTargetNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                ElfMassEffectIsCloakedNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                ElfMassEffectIsHiddenNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                ElfMassEffectSelectNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                ElfMassEffectGetPlayerObjNullGuardPatcher.fixIfNeeded(name, bytesToCheck).let {
                    anyPatched = applyGameObjectFix(name, it.patched, it.note, anyPatched)
                }
                if (anyPatched) libReplacements[name] = bytesToCheck
            }

            // --- 2Z13. Mass Effect: Infiltrator only - REAL FIX for crash #89, found in a real-
            // device 0.84 test: a direct, traceable consequence of fix #81 (2Z5) above. Fix #81
            // correctly stops Deserializer's copy constructor from crashing on an invalid source by
            // building a safe "empty" Deserializer instead - but ParticleSystem::CacheSystem() was
            // never written to expect an empty one, and unconditionally dereferences its field-12
            // pointer a few instructions later. Fix #81 didn't eliminate this crash, it shifted it
            // one level downstream. See ElfMassEffectCacheSystemEmptyDeserializerNullGuardPatcher's
            // doc comment for the full disassembly and root-cause trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator CacheSystem empty-Deserializer null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectCacheSystemEmptyDeserializerNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z14. Mass Effect: Infiltrator only - REAL FIX for crash #90, found in a real-
            // device 0.85 test: the SECOND downstream consequence of fix #81, one call deeper than
            // fix #89 (2Z13) above. Fix #89 correctly stopped CacheSystem() from crashing on the
            // empty Deserializer's field-12 pointer by bailing into CacheSystem's own existing
            // cleanup block - but that cleanup block's very next instruction destructs a shared_ptr
            // sub-object embedded in the same empty Deserializer, and THAT destructor was never
            // written to expect a null control-block pointer either. See
            // ElfMassEffectDeserializationEngineSharedPtrDtorNullGuardPatcher's doc comment for the
            // full disassembly and root-cause trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator DeserializationEngine shared_ptr destructor null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectDeserializationEngineSharedPtrDtorNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z15. Mass Effect: Infiltrator only - REAL FIX for a crash found in a real-
            // device 0.91 test, ONE INSTRUCTION past fix #80's (2Z9) own `clazz`-null guard in the
            // very same function: FT_Done_Glyph() also unconditionally reads through
            // `glyph->library` right after the (now-guarded) `glyph->clazz->done` read, without
            // ever checking `library` itself for NULL either. Fix #80's doc comment assumed that
            // read was "unaffected either way" - it wasn't; it's the exact same bug shape one field
            // over. See ElfMassEffectDoneGlyphLibraryNullGuardPatcher's doc comment for the full
            // disassembly and root-cause trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator FT_Done_Glyph library null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectDoneGlyphLibraryNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z16. Mass Effect: Infiltrator only - LIKELY ROOT-CAUSE FIX for the whole
            // FreeType null-guard saga (fixes #27-31, #80, #91): the game's own hardcoded CJK/
            // fallback font path, `/system/fonts/DroidSansFallback.ttf`, doesn't exist on modern
            // Android (dropped from stock/AOSP around 4.3/5.0, replaced by Noto). `FT_New_Face()`
            // fails silently (its return value is never checked - already root-caused in fixes
            // #27-31), leaving every subsequent FreeType call operating on a broken/uninitialized
            // face - consistent with every downstream crash this project has guarded in this
            // family. Confirmed via a real repack ("Fix RU Adreno") carrying this exact same
            // string substitution, and independently confirmed the replacement file
            // (`/system/fonts/DroidSans.ttf`) is actually present on the real Android 16 test
            // device. See ElfMassEffectFallbackFontPathPatcher's doc comment for the full trace.
            // Does NOT replace the existing null-guards (defense in depth) - see its doc comment.
            Logger.log("== native library scan (Mass Effect: Infiltrator fallback font path fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectFallbackFontPathPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z17. Mass Effect: Infiltrator only - fix #92, the FIRST crash found past the
            // entire FreeType saga on a real-device 0.92 test: GameWorld::UpdateViewCulling()
            // unconditionally dereferences this->m_culling (a Culling*/PortalCulling*/
            // QuadtreeCulling* at offset 0x41c) without checking it for NULL first. See
            // ElfMassEffectUpdateViewCullingNullGuardPatcher's doc comment for the full crash log,
            // disassembly, and root-cause trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator UpdateViewCulling null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectUpdateViewCullingNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z18. Mass Effect: Infiltrator only - fix #93, PROACTIVE companion to fix #92:
            // GameWorld::FinalizeCulling() reads the exact same this->m_culling field the exact same
            // unguarded way, one function over - not yet itself seen on a real-device crash, guarded
            // anyway per this project's established practice of covering every sibling instance of an
            // already-confirmed bug shape together. See
            // ElfMassEffectFinalizeCullingNullGuardPatcher's doc comment for the full trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator FinalizeCulling null guard fix - proactive) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectFinalizeCullingNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z19. Mass Effect: Infiltrator only - fix #94, the FIRST crash found past the
            // entire Hud::StateTransition() wild-pointer saga (fixes #45-#51), on a real-device 0.94
            // test. See ElfMassEffectUpdateObjectsPostCameraNullGuardPatcher's doc comment for the
            // full trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator UpdateObjectsPostCamera null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectUpdateObjectsPostCameraNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z20. Mass Effect: Infiltrator only - fix #95, found on a real-device 0.95 test.
            // See ElfMassEffectVFXUpdateFacingNullGuardPatcher's doc comment for the full trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator VFX::Update facing/up/side null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectVFXUpdateFacingNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z21. Mass Effect: Infiltrator only - fix #96, found via a proactive static scan
            // (not a real-device crash) of the whole .text section for the same field-452 bug shape
            // fixes #94/#95 already fixed elsewhere. See ElfMassEffectAllowHudElementNullGuardPatcher's
            // doc comment for the full trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator AllowHudElement null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectAllowHudElementNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

            // --- 2Z22. Mass Effect: Infiltrator only - fix #97, found on a real-device 0.97 test.
            // See ElfMassEffectOnUpdateSubsystemDispatchNullGuardPatcher's doc comment for the full trace.
            Logger.log("== native library scan (Mass Effect: Infiltrator OnUpdate subsystem dispatch null guard fix) ==")
            for (name in entryNames) {
                if (!name.endsWith("libMassEffect.so")) continue
                val bytesToCheck = libReplacements[name]
                    ?: zin.getInputStream(zin.getEntry(name)).readBytes()
                val result = ElfMassEffectOnUpdateSubsystemDispatchNullGuardPatcher.fixIfNeeded(name, bytesToCheck)
                if (result.patched) {
                    libReplacements[name] = bytesToCheck
                    Logger.log("  [fix] $name: ${result.note}")
                    changes.add("$name: ${result.note}")
                } else {
                    Logger.log("  [skip] $name: ${result.note}")
                }
            }

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

            // --- 2Z90. SoA32to64: ZettaBridge adaptation of the native libs (GLES1 -> GLES2 + video folder) ---
            Logger.log("== ZettaBridge native library fixes ==")
            ZbPatcher.patchLibs(context, zbGame, entryNames, zin, libReplacements, changes)

            // --- 3. dex patching ---
            Logger.log("== dex patching ==")
            val dexEntries = entryNames.filter { Regex("classes\\d*\\.dex").matches(it) }
            val dexBytes = dexEntries.associateWith { zin.getInputStream(zin.getEntry(it)).readBytes() }
            val dexResult = DexPatcher.patchDexFiles(context, dexBytes, File(workDir, "dexwork").apply { mkdirs() }, zbGame)
            changes.addAll(dexResult.changes)
            if (dexResult.changes.isEmpty()) {
                Logger.log("  no known crash patterns matched in this APK's dex - only the manifest/native-lib " +
                    "fixes above (if any) will apply.")
            }

            // --- 4. reassemble unsigned APK from the ORIGINAL, swapping only the touched entries ---
            Logger.log("== assembling patched APK ==")
            val unsigned = File(workDir, "unsigned.apk")
            writeRepackagedApk(zin, unsigned, newManifest, dexResult.dexFiles, libReplacements)

            // --- 5. output (SoA32to64: NOT signed on purpose - the ZettaBridge launcher imports the plain
            // APK and runs its code itself; this file is never installed as an Android app) ---
            val outputDir = File(context.cacheDir, "patched").apply { mkdirs() }
            val baseName = originalFileName.trim().let {
                if (it.isEmpty()) inputApk.nameWithoutExtension
                else it.removeSuffix(".apk").removeSuffix(".APK")
            }.removeSuffix("-rebuilded")
            val outName = "$baseName-zb.apk"
            val output = File(outputDir, outName)
            unsigned.copyTo(output, overwrite = true)

            Logger.log("done -> ${output.name}")
            return PatchResult(output, changes)
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
