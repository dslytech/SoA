package com.apkcompatpatcher.android

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Two independent, both purely in-place, binary AndroidManifest.xml (AXML) patches. Neither
 * does a full apktool-style decode/re-encode (which is what inserting a brand-new attribute or
 * string would require, and which risks subtly corrupting the resource table) - both only
 * locate an attribute that's *already present* somewhere in the manifest and overwrite its
 * existing 4-byte integer value in place, byte for byte. Every other byte of the manifest chunk
 * is left untouched either way.
 *
 * [patchTargetSdk] is a direct Kotlin port of `patch_target_sdk()` from the PC tool's
 * `patch_apk.py`; see docs/case-study.md in the PC tool's repo for the full story of how that
 * one was derived. [widenActivityConfigChanges] is new, see its own doc comment.
 */
object ManifestPatcher {

    class ManifestPatchException(message: String) : Exception(message)

    // AXML config-change flag bits (ActivityInfo.CONFIG_*) not already covered by an old
    // (~2011-2013) game's own `android:configChanges` declaration - see
    // [widenActivityConfigChanges].
    private const val CONFIG_SCREEN_LAYOUT = 0x100
    private const val CONFIG_UI_MODE = 0x200
    private const val CONFIG_SMALLEST_SCREEN_SIZE = 0x800
    private const val CONFIG_DENSITY = 0x1000
    private const val CONFIG_LAYOUT_DIRECTION = 0x2000
    private const val MODERN_CONFIG_BITS =
        CONFIG_SCREEN_LAYOUT or CONFIG_UI_MODE or CONFIG_SMALLEST_SCREEN_SIZE or
            CONFIG_DENSITY or CONFIG_LAYOUT_DIRECTION

    /** Parses just enough of an AXML's string pool chunk to look up a string's index by value
     *  and read a string back out by index - shared by both patches below. Throws
     *  [ManifestPatchException] if [data] doesn't look like a binary AndroidManifest.xml at all;
     *  callers should let that propagate (there's nothing safe to patch in that case). */
    private class StringPool(private val data: ByteArray, private val buf: ByteBuffer) {
        private val stringCount: Int
        private val isUtf8: Boolean
        private val offsets: IntArray
        private val stringData: ByteArray
        val nodesStartOffset: Int

        init {
            val magic = buf.getShort(0).toInt() and 0xFFFF
            if (magic != 0x0003) throw ManifestPatchException("not a binary AndroidManifest.xml")

            val rootHeaderSize = buf.getShort(2).toInt() and 0xFFFF
            val spOff = rootHeaderSize

            val spCtype = buf.getShort(spOff).toInt() and 0xFFFF
            val spHeaderSize = buf.getShort(spOff + 2).toInt() and 0xFFFF
            val spSize = buf.getInt(spOff + 4)
            if (spCtype != 1) throw ManifestPatchException("expected string pool chunk")

            stringCount = buf.getInt(spOff + 8)
            val flags = buf.getInt(spOff + 16)
            val stringsStart = buf.getInt(spOff + 20)
            isUtf8 = (flags and 0x100) != 0

            val offArrayPos = spOff + spHeaderSize
            offsets = IntArray(stringCount) { i -> buf.getInt(offArrayPos + i * 4) }
            val stringsBase = spOff + stringsStart
            val stringDataLen = (spOff + spSize) - stringsBase
            stringData = data.copyOfRange(stringsBase, stringsBase + stringDataLen)

            val rmOff = spOff + spSize
            val rmType = buf.getShort(rmOff).toInt() and 0xFFFF
            val rmSize = buf.getInt(rmOff + 4)
            if (rmType != 0x0180) throw ManifestPatchException("expected resource map chunk")
            nodesStartOffset = rmOff + rmSize
        }

        private fun readLen(pos: Int): Pair<Int, Int> {
            val b0 = stringData[pos].toInt() and 0xFF
            return if (b0 and 0x80 != 0) {
                val b1 = stringData[pos + 1].toInt() and 0xFF
                (((b0 and 0x7f) shl 8) or b1) to (pos + 2)
            } else {
                b0 to (pos + 1)
            }
        }

        fun readString(idx: Int): String {
            return if (isUtf8) {
                var p = offsets[idx]
                val (_, p1) = readLen(p) // utf16 length (unused, but must be skipped)
                p = p1
                val (ulen, p2) = readLen(p)
                p = p2
                String(stringData, p, ulen, Charsets.UTF_8)
            } else {
                val off = offsets[idx]
                val lo = (stringData[off].toInt() and 0xFF) or ((stringData[off + 1].toInt() and 0xFF) shl 8)
                val length = lo and 0x7fff
                val p = off + 2
                String(stringData, p, length * 2, Charsets.UTF_16LE)
            }
        }

        fun indexOf(target: String): Int? = (0 until stringCount).firstOrNull { i ->
            try {
                readString(i) == target
            } catch (_: Exception) {
                false
            }
        }
    }

    fun patchTargetSdk(manifest: ByteArray, newSdk: Int): ByteArray {
        val data = manifest.copyOf()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val pool = StringPool(data, buf)

        val tsdkIdx = pool.indexOf("targetSdkVersion") ?: throw ManifestPatchException(
            "could not find 'targetSdkVersion' in the manifest string pool - " +
                "unexpected manifest shape, aborting rather than guessing"
        )

        var pos = pool.nodesStartOffset
        var patched = false
        while (pos < data.size) {
            val ctype2 = buf.getShort(pos).toInt() and 0xFFFF
            val csize2 = buf.getInt(pos + 4)
            if (ctype2 == 0x0102) { // start element
                val attributeSize = buf.getShort(pos + 16 + 8 + 2).toInt() and 0xFFFF
                val attributeCount = buf.getShort(pos + 16 + 8 + 4).toInt() and 0xFFFF
                val attrsBase = pos + 16 + 20
                for (a in 0 until attributeCount) {
                    val ap = attrsBase + a * attributeSize
                    val aName = buf.getInt(ap + 4)
                    if (aName == tsdkIdx) {
                        val oldSdk = buf.getInt(ap + 16)
                        buf.putInt(ap + 16, newSdk)
                        patched = true
                        Logger.log("  targetSdkVersion: $oldSdk -> $newSdk")
                    }
                }
            }
            if (csize2 <= 0) break // malformed / safety valve against an infinite loop
            pos += csize2
        }

        if (!patched) {
            throw ManifestPatchException(
                "found 'targetSdkVersion' string but no <uses-sdk> attribute using it - aborting"
            )
        }

        return data
    }

    data class ConfigChangesResult(val manifest: ByteArray, val changes: List<String>)

    /**
     * Widens any `android:configChanges` attribute that's ALREADY present on some element in
     * this manifest (in practice, always an `<activity>` - it's the only element the Android
     * schema allows it on) to also cover `screenLayout`, `uiMode`, `smallestScreenSize`,
     * `density`, and `layoutDirection`, on top of whatever bits it already had.
     *
     * ## Why: Dead Space on a real device
     *
     * Confirmed via a real `adb logcat`: an old (~2013) EA "Blast"-engine game
     * (`DeadSpaceActivity`) already declares `android:configChanges="0x000004A0"` - that's
     * `orientation | keyboardHidden | screenSize`, i.e. the handful of config changes that
     * mattered on a 2011-era phone. The very same log shows
     * `ActivityThread.handleRelaunchActivityInner` firing moments after the activity starts -
     * Android tearing the activity down and recreating it because of a configuration change
     * this declaration does NOT cover (most plausibly `screenLayout`/`density`/`uiMode`, which
     * this Hihonor tablet's multi-window-aware shell - "Magic Win" - actively juggles right at
     * launch, in a way a 2011 phone never did). The game's own internal renderer sizes its
     * surface once at startup; if that surface exists before this relaunch tears it down and
     * recreates it, the *game* doesn't know to resize what it draws to match, and the symptom
     * matches what was reported: a small, centered render instead of a full-screen one, and not
     * 100% reproducible (whether the relaunch actually happens, and exactly when, depends on how
     * fast the shell settles the window state on a given run).
     *
     * ## The fix, and its limits
     *
     * The attribute value is a plain 32-bit flag bitmask stored inline in the AXML - this just
     * ORs in the missing modern bits and overwrites that one `int` in place, the same technique
     * (and the same safety level) as [patchTargetSdk]'s `targetSdkVersion` overwrite. Existing
     * bits are only ever added to, never cleared.
     *
     * If `android:configChanges` isn't declared ANYWHERE in a given manifest at all (common for
     * many other old games - Dead Space happening to already declare a partial one is what makes
     * this an in-place fix rather than a much riskier AXML insertion), there's nothing here to
     * widen: [indexOf] returns null and this returns the manifest unchanged with an empty
     * [ConfigChangesResult.changes] list, rather than attempting to add a brand-new attribute
     * (which would mean growing the string pool and the attribute table - real AXML surgery,
     * not attempted here).
     *
     * This is a plausible, log-evidenced hypothesis, not a confirmed root cause the way the rest
     * of this app's fixes were confirmed (a before/after crash-log pair) - Dead Space doesn't
     * crash, so there's no crash log to compare, only the relaunch trace and the reported
     * symptom. Needs a real re-test to confirm it actually stops the relaunch/stops the
     * small-centered rendering.
     */
    fun widenActivityConfigChanges(manifest: ByteArray): ConfigChangesResult {
        val data = manifest.copyOf()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val pool = StringPool(data, buf)

        val configChangesIdx = pool.indexOf("configChanges")
            ?: return ConfigChangesResult(data, emptyList())
        val nameIdx = pool.indexOf("name")

        val changes = mutableListOf<String>()
        var pos = pool.nodesStartOffset
        while (pos < data.size) {
            val ctype2 = buf.getShort(pos).toInt() and 0xFFFF
            val csize2 = buf.getInt(pos + 4)
            if (ctype2 == 0x0102) { // start element
                val attributeSize = buf.getShort(pos + 16 + 8 + 2).toInt() and 0xFFFF
                val attributeCount = buf.getShort(pos + 16 + 8 + 4).toInt() and 0xFFFF
                val attrsBase = pos + 16 + 20

                var configAp = -1
                var label: String? = null
                for (a in 0 until attributeCount) {
                    val ap = attrsBase + a * attributeSize
                    val aName = buf.getInt(ap + 4)
                    if (aName == configChangesIdx) configAp = ap
                    if (nameIdx != null && aName == nameIdx) {
                        val rawValue = buf.getInt(ap + 8) // string-pool index, or -1 if not a string
                        if (rawValue >= 0) {
                            label = try { pool.readString(rawValue) } catch (_: Exception) { null }
                        }
                    }
                }

                if (configAp >= 0) {
                    val old = buf.getInt(configAp + 16)
                    val new = old or MODERN_CONFIG_BITS
                    if (new != old) {
                        buf.putInt(configAp + 16, new)
                        val who = label ?: "(unnamed element)"
                        changes.add(
                            "configChanges widened for $who: 0x%04X -> 0x%04X (added screenLayout|uiMode|smallestScreenSize|density|layoutDirection)"
                                .format(old, new)
                        )
                    }
                }
            }
            if (csize2 <= 0) break // malformed / safety valve against an infinite loop
            pos += csize2
        }

        return ConfigChangesResult(data, changes)
    }

    data class MaxAspectResult(val manifest: ByteArray, val note: String?)

    private const val TYPE_FLOAT = 0x04

    /**
     * Raises the value of a `<meta-data android:name="android.max_aspect" android:value="..."/>`
     * element, if present, to effectively "no limit" - in place, same technique as
     * [widenActivityConfigChanges]: only overwrite something that's already there.
     *
     * ## Why
     *
     * `android.max_aspect` is Android's own documented pre-8.0 mechanism for an app to declare
     * "letterbox/pillarbox me if the screen's aspect ratio exceeds this" (the modern replacement
     * is the `android:maxAspectRatio` activity attribute, added in API 26 - this meta-data is the
     * shim apps built before that used, and it's still honored). Confirmed via two real
     * `adb logcat` captures of Dead Space on the same real device, in the same run: the exact
     * same `DeadSpaceActivity` window flips between a full `1920x1200` "large/landscape" bounds
     * and a boxed, centered `711x1138` "normal/portrait" bounds - not just at cold start, but
     * dynamically while the app is running (confirmed by comparing `WindowManager`'s own
     * `mBounds`/`mWindowingMode`/screen-layout-bucket log lines from both captures side by side).
     * That's a real compatibility-box being applied and un-applied by the platform to the same
     * window, not a rendering bug in the game.
     *
     * This game already declares `android.max_aspect="2.400000"` - a real limit, from back when
     * 2.4:1 covered the tallest phone screens anyone shipped. Raising it removes that specific,
     * confirmed-present lever without guessing whether it's the *only* thing driving the
     * boxing (this Hihonor tablet's own "Magic Win" legacy-app compatibility layer very likely
     * also plays a role, based on the `landScapeFreeformFlag` field present in every logged
     * window-configuration line - something outside this app's control either way). The intent
     * behind the original developer's declared value was clearly narrower than what's safe on
     * a modern device, and every device this could matter for has a MUCH wider practical aspect
     * ratio ceiling than 2.4, so widening it (rather than deleting it, which isn't a safe in-place
     * edit - the attribute would still exist and need *some* value) is the closest available
     * fix that generalizes across whatever screen the person patching a different old game
     * happens to have, rather than hard-coding any specific device's resolution.
     *
     * ## The fix is a plain 4-byte overwrite, not a string edit
     *
     * `android:value="2.400000"` on a `<meta-data>` element LOOKS like a string in decoded XML,
     * but real byte inspection of this manifest showed it's actually stored as a raw
     * `TYPE_FLOAT` (`0x04`) attribute - the IEEE-754 bit pattern for `2.4f` sitting directly in
     * the attribute's 4-byte `data` slot, the exact same slot [patchTargetSdk] and
     * [widenActivityConfigChanges] already overwrite for their own attributes. So this needs no
     * string-pool surgery at all: just decode the existing float, and write back the bits of a
     * new one in the same slot. (An initial version of this fix assumed it was a string and
     * tried to rewrite it in the string pool - offline testing against the real captured
     * manifest caught that immediately, since no `<meta-data>` element's `value` attribute ever
     * resolved to a string index there; checking the raw `dataType` byte before trusting the
     * decoded-XML shape is what caught it.)
     *
     * Same honesty caveat as [widenActivityConfigChanges]: this is a plausible, evidence-backed
     * lever, checked offline for correctness, not a confirmed complete fix - there's a real
     * chance the OEM's own compatibility layer is doing some or all of this independent of
     * anything in the APK, in which case this helps not at all and the real fix is a
     * device/OS-level setting rather than anything patchable here.
     */
    fun widenMaxAspect(manifest: ByteArray, newAspect: Float = 99.0f): MaxAspectResult {
        val data = manifest.copyOf()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val pool = StringPool(data, buf)

        val nameAttrIdx = pool.indexOf("name") ?: return MaxAspectResult(data, null)
        val valueAttrIdx = pool.indexOf("value") ?: return MaxAspectResult(data, null)
        val maxAspectKeyIdx = pool.indexOf("android.max_aspect") ?: return MaxAspectResult(data, null)

        var pos = pool.nodesStartOffset
        while (pos < data.size) {
            val ctype2 = buf.getShort(pos).toInt() and 0xFFFF
            val csize2 = buf.getInt(pos + 4)
            if (ctype2 == 0x0102) { // start element
                val attributeSize = buf.getShort(pos + 16 + 8 + 2).toInt() and 0xFFFF
                val attributeCount = buf.getShort(pos + 16 + 8 + 4).toInt() and 0xFFFF
                val attrsBase = pos + 16 + 20

                var isMaxAspectMeta = false
                var valueAp = -1
                for (a in 0 until attributeCount) {
                    val ap = attrsBase + a * attributeSize
                    val aName = buf.getInt(ap + 4)
                    val rawValue = buf.getInt(ap + 8) // string-pool index, or -1 if not a string
                    if (aName == nameAttrIdx && rawValue == maxAspectKeyIdx) isMaxAspectMeta = true
                    if (aName == valueAttrIdx) valueAp = ap
                }

                if (isMaxAspectMeta && valueAp >= 0) {
                    val dataType = data[valueAp + 15].toInt() and 0xFF
                    if (dataType != TYPE_FLOAT) {
                        // Unexpected shape (not the raw-float encoding this was built and
                        // verified against) - don't guess at a different attribute layout.
                        return MaxAspectResult(manifest.copyOf(), null)
                    }
                    val oldBits = buf.getInt(valueAp + 16)
                    val oldValue = Float.fromBits(oldBits)
                    buf.putInt(valueAp + 16, newAspect.toRawBits())
                    return MaxAspectResult(data, "android.max_aspect widened: $oldValue -> $newAspect")
                }
            }
            if (csize2 <= 0) break // malformed / safety valve against an infinite loop
            pos += csize2
        }

        return MaxAspectResult(data, null) // no android.max_aspect meta-data element found on any element
    }

    // android:required's real, permanent Android platform attribute resource id.
    // Cross-checked (not guessed) two ways before being hardcoded here: it matches
    // the well-known, publicly documented value, AND it was independently resolved
    // at development time straight out of Android's own real android-30.apk
    // framework resource table via ARSCLib's TableBlock.resolveResourceId("android",
    // "attr", "required") - both agreed. Every android:* platform attribute id is
    // permanently frozen by Android (changing one would break every already-compiled
    // APK expecting it), so there's no version-skew risk in hardcoding it rather than
    // shipping ARSCLib's ~2.8MB of bundled per-API-level framework tables just to
    // re-derive this one constant on-device (see build.gradle.kts' packaging excludes).
    private const val ID_required = 0x0101028e

    /**
     * Adds a `<uses-library android:name="[libraryName]" android:required="[required]"/>`
     * element as a new child of `<application>` - a REAL AXML insertion (new element,
     * new attributes, new string-pool entries), unlike every other patch in this file,
     * which only ever overwrites an attribute value that's already present (see this
     * file's own top-of-file doc comment on why: hand-rolling that kind of insertion,
     * on top of this file's byte-level [StringPool] reader, would mean re-deriving
     * string-pool growth, resource-map growth, and every downstream chunk-size/offset
     * field by hand - real AXML surgery, and exactly the risk this file was written to
     * avoid). Instead this delegates the actual insertion to ARSCLib (io.github.reandroid),
     * a real, independent AXML-writing library - the same kind of "don't hand-roll it,
     * use the real tool" choice this project already made for dex editing (dexlib2/smali)
     * and signing (apksig).
     *
     * ## Why this exists: Mass Effect: Infiltrator, real-device crash log
     *
     * Confirmed via a real `adb logcat` capture, taken after fix #13 (getAndroidUniqueId()
     * try/catch) shipped and genuinely stopped the earlier "hangs at the EA screen" bug -
     * the user could now get past that screen, but reported the game immediately kicked
     * back out to the home screen instead. The log showed why: a real, uncaught
     * `FATAL EXCEPTION` on a background thread ("Thread-3"):
     * ```
     * java.lang.NoClassDefFoundError: Failed resolution of: Lorg/apache/http/impl/client/DefaultHttpClient;
     *     at com.eamobile.download.DownloadActivityInternal.sendHttpPost(DownloadActivityInternal.java:1)
     *     at com.eamobile.download.DownloadActivityInternal.updateDownloadFilesData(DownloadActivityInternal.java:843)
     *     at com.eamobile.download.DownloadActivityInternal.checkServerContent(DownloadActivityInternal.java:5)
     *     at com.eamobile.download.ContactingServerView$2.run(ContactingServerView.java:9)
     * Caused by: java.lang.ClassNotFoundException: org.apache.http.impl.client.DefaultHttpClient
     * ```
     * This EA download SDK is from 2012 and still uses the old Apache HTTP client
     * (`org.apache.http.*`) for its "contact the server, check for updates" step, which
     * this game's own config runs on every launch regardless of whether local assets are
     * already complete (`NUMBER_OF_HOURS_TO_UPDATE_CHECKING: 0 hours`, also visible in the
     * same log). Google removed the Apache HTTP client classes from the default Android
     * bootclasspath starting with API 28 - apps that still need them must explicitly opt
     * back in via this exact `<uses-library>` declaration (this is documented, standard
     * Android platform behavior, not something specific to this SDK or this game). This
     * app's own [patchTargetSdk] is what triggers this: raising an old game's
     * targetSdkVersion to 30 (needed for other reasons - see [ApkPatcher.DEFAULT_TARGET_SDK])
     * is exactly what makes these classes disappear for an app that was never rebuilt
     * against a modern SDK and so never had a reason to declare this itself. In other
     * words: this is a regression this project's OWN earlier, necessary fix introduces
     * for any old SDK still using Apache HTTP client - fix #12 (the missing HTTP
     * read-timeout on this exact `sendHttpPost()` call) was real but moot without this,
     * since the class can't even be loaded to reach that timeout code.
     *
     * Added unconditionally (not gated on detecting `Lorg/apache/http/` references in the
     * dex first) because it's a no-op for any APK that doesn't use these classes -
     * `required="false"` means it's simply never touched - and Apache HTTP client usage
     * was extremely common in pre-2014 Android SDKs generally, not just this one, so this
     * generalizes the same way [widenActivityConfigChanges]/[widenMaxAspect] do.
     *
     * ## Verification
     *
     * Checked offline against two different real, complete old-game manifests pulled from
     * this project's own earlier work (Imperium Galactica 2's and Haegemonia: Legions of
     * Iron's real `AndroidManifest.xml`, extracted from their actual APKs) - inserted this
     * exact element into each with a standalone JVM harness using ARSCLib directly (the
     * same API this function calls), then decoded the result with `apktool` (a completely
     * independent codebase from ARSCLib) to confirm: no parse errors, the new
     * `<uses-library>` element lands correctly inside `<application>`, both attributes
     * decode with the right name/type/value, and every other element/attribute in both
     * manifests survives the round-trip untouched. This is the same two-independent-tools
     * cross-check this project's dex fixes get from the kotlinc-harness + apktool
     * reassembly pipeline, just applied to the manifest side instead.
     *
     * `refreshFull()` (called before serializing) turned out to be required, not optional:
     * without it, ARSCLib produces a manifest that LOOKS right through this library's own
     * API but is actually malformed at the byte level (apktool failed to even parse it -
     * "hit unexpected end of file" - until refreshFull() was added). Worth calling out
     * explicitly since it's exactly the kind of silent-corruption risk this file's own
     * doc comment warns about for hand-rolled AXML edits - it turned out to apply just as
     * easily to a real library's API if you skip a required step, which is one more reason
     * the two-tool (ARSCLib + apktool) cross-check above matters, rather than trusting
     * ARSCLib's own success return value alone.
     */
    fun addUsesLibrary(manifest: ByteArray, libraryName: String, required: Boolean = false): ByteArray {
        val block = AndroidManifestBlock()
        block.readBytes(ByteArrayInputStream(manifest))

        val application = block.applicationElement
            ?: throw ManifestPatchException("no <application> element found - unexpected manifest shape, aborting rather than guessing")

        val usesLibrary = application.newElement("uses-library")
        usesLibrary.getOrCreateAndroidAttribute("name", AndroidManifestBlock.ID_name)
            .setValueAsString(libraryName)
        usesLibrary.getOrCreateAndroidAttribute("required", ID_required)
            .setValueAsBoolean(required)

        block.refreshFull()
        val out = ByteArrayOutputStream()
        block.writeBytes(out)
        return out.toByteArray()
    }

    // android:resizeableActivity's real, permanent Android platform attribute resource id
    // (introduced API 24). Not exposed as a named constant anywhere in ARSCLib's own
    // AndroidManifest interface (unlike ID_screenOrientation below), so cross-checked and
    // hardcoded here the same way ID_required already is: extracted directly from ARSCLib's
    // own bundled android-24.apk framework resource table (io.github.reandroid's APKEditor
    // jar ships one per API level, under frameworks/android/ - see build.gradle.kts' packaging
    // excludes for why none of them are shipped in THIS app's own APK) by decoding it and
    // reading its public.xml: `<public id="0x010104f6" type="attr" name="resizeableActivity" />`.
    // Same frozen-forever guarantee as every other android:* platform attribute id already
    // hardcoded in this file - no version-skew risk.
    private const val ID_resizeableActivity = 0x010104f6

    // ActivityInfo.SCREEN_ORIENTATION_USER - "the user's current preferred orientation, follows
    // the device's own rotation/auto-rotate setting instead of a fixed lock". Part of the public,
    // frozen Android platform API surface (android:screenOrientation's own documented enum
    // values), not something specific to this app.
    private const val SCREEN_ORIENTATION_USER = 2

    data class ResizabilityResult(val manifest: ByteArray, val changes: List<String>)

    /**
     * ADVANCED SETTINGS, opt-in (both off by default) - unlike every other manifest fix in this
     * file, these two change how a game actually LOOKS/behaves rather than just fixing a crash or
     * a platform-compatibility gap, so they're left for the person patching to decide per game
     * rather than applied unconditionally. Originally shipped as ONE combined toggle
     * (0.87-forceResizableOrientation); split into the two independent functions below
     * ([widenResizability]/[widenOrientation]) after real-device feedback that
     * `resizeableActivity=true` alone can make some OEM launchers (this project's own test
     * tablet's Magic UI included) open the app in a smaller floating/windowed view by default
     * instead of fullscreen - an unwanted side effect of the resizability flag specifically, not
     * the orientation one. Splitting them lets each be tried independently to isolate/avoid that.
     *
     * ## What this replicates, and why it has to be baked into the APK instead
     *
     * On a real tablet/foldable, Android's own per-app compatibility framework offers exactly
     * these overrides - `adb shell am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER <pkg>` (free
     * rotation, ignores the app's orientation lock and `setRequestedOrientation()` calls) and
     * `FORCE_RESIZE_APP` (ignores `android:resizeableActivity="false"`/unset, forces multi-window/
     * freeform capability) - which is exactly the pair of `adb` commands this fix was requested
     * to replicate. Those two overrides are real, but they're gated behind shell/ADB (or a device
     * manufacturer's own allowlist) at the platform level - `IPlatformCompat` refuses them from
     * an ordinary installed app's own process, by design, so there is no runtime API this app
     * could call to flip them for another package, on any device, rooted or not. The only way to
     * get the same practical effect from inside a normal APK is to declare the underlying
     * manifest attributes directly - which Android's own documentation for these very overrides
     * confirms produces the same behavior: `android:resizeableActivity="true"` on `<application>`
     * (inherited by every activity that doesn't explicitly override it) and
     * `android:screenOrientation="user"` on the main/launcher activity specifically (orientation
     * is not an `<application>`-level attribute).
     *
     * IMPORTANT LIMITATION vs the real `am compat` overrides: those are enforced by the OS at
     * runtime, so `OVERRIDE_ANY_ORIENTATION_TO_USER` genuinely ignores the app's own
     * `setRequestedOrientation()` calls. A baked-in manifest value can't do that - it's only the
     * activity's initial/default orientation, and a game that calls `setRequestedOrientation()`
     * itself during startup or in a menu can still override it back at runtime. Also,
     * `screenOrientation="user"` only follows the device's rotation sensor if the device's own
     * auto-rotate setting is turned on - it doesn't force rotation against a rotation lock.
     *
     * Also relevant on a genuinely modern OS: Android 16 (API 36) ignores orientation/resizability
     * restrictions on large screens (smallest width >= 600dp) automatically, but ONLY for apps
     * that target API 36 themselves - and explicitly NOT for anything declaring
     * `android:appCategory="game"`, which covers every game this project has ever touched. So
     * raising [ApkPatcher.DEFAULT_TARGET_SDK] further doesn't get old games this behavior for
     * free; the manifest attributes above are the only lever that actually applies to a game.
     *
     * ## Implementation
     *
     * Unlike [widenActivityConfigChanges]/[widenMaxAspect] (in-place overwrite of an attribute
     * that's already there), `resizeableActivity` essentially never already exists in a decade-old
     * game's manifest (the attribute didn't exist yet when these were built) - a real insertion is
     * needed, so this uses ARSCLib the same way [addUsesLibrary] does, rather than hand-rolling it.
     * `screenOrientation`, by contrast, is near-universally ALREADY declared by an old game's main
     * activity (orientation locking predates this project's entire target era) - `getOrCreate...`
     * handles both cases uniformly either way.
     */
    fun widenResizability(manifest: ByteArray): ResizabilityResult {
        val block = AndroidManifestBlock()
        block.readBytes(ByteArrayInputStream(manifest))
        val changes = mutableListOf<String>()

        val application = block.applicationElement
            ?: throw ManifestPatchException("no <application> element found - unexpected manifest shape, aborting rather than guessing")

        val existingResizeable = application.searchAttributeByResourceId(ID_resizeableActivity)
        val oldResizeableDesc = existingResizeable?.let {
            if (it.valueType == ValueType.BOOLEAN) (if (it.getData() != 0) "true" else "false") else "(non-boolean, unexpected)"
        } ?: "(not declared)"
        application.getOrCreateAndroidAttribute("resizeableActivity", ID_resizeableActivity)
            .setValueAsBoolean(true)
        changes.add("android:resizeableActivity on <application>: $oldResizeableDesc -> true")

        block.refreshFull()
        val out = ByteArrayOutputStream()
        block.writeBytes(out)
        return ResizabilityResult(out.toByteArray(), changes)
    }

    /**
     * See [widenResizability]'s doc comment (shared umbrella doc) for the full "why".
     *
     * Targets every `<activity>` that's EITHER the manifest's own main/launcher activity, OR that
     * already declares its own `android:screenOrientation` - not just the launcher alone. Found to
     * matter for real repacks of this project's own games: some third-party APK repacks (compared
     * directly against this project's own canonical manifest after a real-device report that free
     * rotation "wasn't doing anything") inject their own wrapper/splash launcher activity (e.g. a
     * `com.dataManager.MainActivity` in front of the real `.MassEffectActivity`) - each activity
     * with its OWN independent `screenOrientation`, since Android doesn't let a non-launcher
     * activity inherit one from anywhere. Touching only `block.mainActivity` in that shape patches
     * the wrapper/splash screen and silently leaves the real gameplay activity's own orientation
     * lock untouched - which is exactly indistinguishable from "the checkbox doesn't work" from
     * the outside. Simple single-activity games (this project's other 3: IG2/Haegemonia/CMR, and
     * a vanilla Mass Effect build) are unaffected by this change - their one launcher activity IS
     * the target either way, found via `mainActivity` same as before.
     */
    fun widenOrientation(manifest: ByteArray): ResizabilityResult {
        val block = AndroidManifestBlock()
        block.readBytes(ByteArrayInputStream(manifest))
        val changes = mutableListOf<String>()

        val application = block.applicationElement
            ?: throw ManifestPatchException("no <application> element found - unexpected manifest shape, aborting rather than guessing")

        val mainActivity = block.mainActivity
        val allActivities = application.getElements("activity")

        val targets = LinkedHashSet<com.reandroid.arsc.chunk.xml.ResXmlElement>()
        if (mainActivity != null) targets.add(mainActivity)
        while (allActivities.hasNext()) {
            val activity = allActivities.next() as com.reandroid.arsc.chunk.xml.ResXmlElement
            if (activity.searchAttributeByResourceId(ID_screenOrientation) != null) {
                targets.add(activity)
            }
        }

        if (targets.isEmpty()) {
            changes.add("android:screenOrientation: no main/launcher activity and no activity with an existing screenOrientation found - skipped, nothing applied")
        } else {
            for (activity in targets) {
                val activityName = activity.searchAttributeByResourceId(AndroidManifestBlock.ID_name)?.valueAsString
                    ?: "(unnamed activity)"
                val existingOrientation = activity.searchAttributeByResourceId(ID_screenOrientation)
                val oldOrientationDesc = existingOrientation?.let { "raw value ${it.getData()}" } ?: "(not declared)"
                val orientationAttr = activity.getOrCreateAndroidAttribute("screenOrientation", ID_screenOrientation)
                orientationAttr.setValueType(ValueType.DEC)
                orientationAttr.setData(SCREEN_ORIENTATION_USER)
                changes.add("android:screenOrientation on $activityName: $oldOrientationDesc -> user (2)")
            }
        }

        block.refreshFull()
        val out = ByteArrayOutputStream()
        block.writeBytes(out)
        return ResizabilityResult(out.toByteArray(), changes)
    }

    // AndroidManifestBlock.ID_screenOrientation (0x0101001e) is already exposed as a named
    // constant by ARSCLib itself (com.reandroid.app.AndroidManifest.ID_screenOrientation),
    // unlike ID_resizeableActivity above - reused here under a local alias only so both id
    // constants read consistently side by side.
    private val ID_screenOrientation = AndroidManifestBlock.ID_screenOrientation

    // android:requestLegacyExternalStorage's real, permanent platform attribute resource id
    // (introduced API 29/Android 10). Extracted the same way as ID_resizeableActivity above -
    // from ARSCLib's own bundled android-29.apk framework table (the API level that introduced
    // it): `<public id="0x01010603" type="attr" name="requestLegacyExternalStorage" />`.
    private const val ID_requestLegacyExternalStorage = 0x01010603

    /**
     * NOT an advanced setting - applied unconditionally, alongside [patchTargetSdk] and
     * [addUsesLibrary], since (unlike [widenResizability]/[widenOrientation]) it doesn't change
     * how a game looks or behaves at all, only how it's allowed to access storage. Requested
     * after comparing this project's canonical Mass Effect: Infiltrator manifest against a
     * third-party repack that already ships with this attribute set.
     *
     * ## What it does and why
     *
     * Android 10 (API 29) introduced Scoped Storage, which stops an app from freely
     * reading/writing arbitrary paths on shared/external storage - exactly the kind of direct
     * file access a decade-old game's own save/cache/asset-download code tends to use.
     * `android:requestLegacyExternalStorage="true"` opts the app back into the old, unrestricted
     * behavior.
     *
     * IMPORTANT CAVEAT, so nobody's surprised when this doesn't fix everything: per Android's own
     * documentation, this flag only has any effect for an app whose OWN targetSdkVersion is
     * exactly 29 (Android 10) - once targetSdkVersion is 30+ (Android 11 and up), the OS ignores
     * it entirely and enforces Scoped Storage regardless. Since this app's own
     * [ApkPatcher.DEFAULT_TARGET_SDK] is 30, a freshly-patched game running on a real Android 11+
     * device (including this project's own Android 16 test tablet) won't see any behavior change
     * from this flag - it's only useful for an Android 10 device, or if someone patches with a
     * lower `--target-sdk` override. Applied anyway, unconditionally and harmlessly, since it
     * costs nothing on devices where it's a no-op and helps on the ones where it isn't.
     *
     * ## Implementation
     *
     * Same as [widenResizability]: a decade-old game's manifest never already declares this
     * attribute (it didn't exist yet), so this is a real ARSCLib insertion rather than an
     * in-place overwrite.
     */
    fun addRequestLegacyExternalStorage(manifest: ByteArray): ResizabilityResult {
        val block = AndroidManifestBlock()
        block.readBytes(ByteArrayInputStream(manifest))
        val changes = mutableListOf<String>()

        val application = block.applicationElement
            ?: throw ManifestPatchException("no <application> element found - unexpected manifest shape, aborting rather than guessing")

        val existing = application.searchAttributeByResourceId(ID_requestLegacyExternalStorage)
        val oldDesc = existing?.let {
            if (it.valueType == ValueType.BOOLEAN) (if (it.getData() != 0) "true" else "false") else "(non-boolean, unexpected)"
        } ?: "(not declared)"
        application.getOrCreateAndroidAttribute("requestLegacyExternalStorage", ID_requestLegacyExternalStorage)
            .setValueAsBoolean(true)
        changes.add("android:requestLegacyExternalStorage on <application>: $oldDesc -> true (only takes effect if targetSdkVersion is exactly 29 - a no-op on Android 11+/targetSdk 30+, harmless either way)")

        block.refreshFull()
        val out = ByteArrayOutputStream()
        block.writeBytes(out)
        return ResizabilityResult(out.toByteArray(), changes)
    }

    // Resource ids for android:largeHeap / extractNativeLibs / hardwareAccelerated / isGame - all
    // <application>-level boolean flags found already set in the same real, working 3rd-party
    // repack that led to addRequestLegacyExternalStorage's flag and the widenOrientation() fix
    // above (see their doc comments for the full backstory). All 4 extracted the same
    // authoritative way as ID_resizeableActivity/ID_requestLegacyExternalStorage - pulled directly
    // from ARSCLib's own bundled android-23.apk framework table (the earliest one this project has
    // access to; an attribute already present in the API 23 table was introduced at or before API
    // 23, and platform attribute ids never change once assigned, so this table is just as
    // authoritative for these older attributes as the later android-24/29 tables were for the
    // newer ones).
    private const val ID_largeHeap = 0x0101035a
    private const val ID_extractNativeLibs = 0x010104ea
    private const val ID_hardwareAccelerated = 0x010102d3
    private const val ID_isGame = 0x010103f4

    /**
     * NOT an advanced setting - applied unconditionally, same category as
     * [addRequestLegacyExternalStorage]: none of these four change how a game looks or plays, only
     * how the OS treats the app process/package. All four were found already set in the same real,
     * working 3rd-party repack referenced in [addRequestLegacyExternalStorage]'s doc comment.
     *
     * - `android:largeHeap="true"`: requests a bigger Dalvik/ART heap for the app process. These
     *   are texture/asset-heavy decade-old 3D games - genuinely useful headroom against
     *   OutOfMemoryError, essentially free (the OS grants it per-process, not a fixed global
     *   reservation taken from anything else).
     * - `android:extractNativeLibs="true"`: forces the OS to extract native `.so` libraries to a
     *   writable location at install time rather than trying to mmap/run them directly out of the
     *   APK - removes one possible source of a native-library-load failure on an APK this app has
     *   itself repackaged/resigned (where original zip alignment/compression of the `.so` entries
     *   isn't something this pipeline controls).
     * - `android:hardwareAccelerated="true"`: likely a no-op in practice for a game patched
     *   through this app - it already defaults to true once targetSdkVersion >= 14, and this app's
     *   own [ApkPatcher.DEFAULT_TARGET_SDK] is 30 - but set explicitly anyway rather than relying
     *   on that default holding for every possible `--target-sdk` override value someone might
     *   pick.
     * - `android:isGame="true"`: mostly cosmetic/OS-integration (app-drawer categorization, some
     *   OEMs' own game-mode/performance-profile features) - harmless either way.
     */
    fun addPerformanceCompatibilityFlags(manifest: ByteArray): ResizabilityResult {
        val block = AndroidManifestBlock()
        block.readBytes(ByteArrayInputStream(manifest))
        val changes = mutableListOf<String>()

        val application = block.applicationElement
            ?: throw ManifestPatchException("no <application> element found - unexpected manifest shape, aborting rather than guessing")

        val flags = listOf(
            Triple("largeHeap", ID_largeHeap, "headroom against OutOfMemoryError on texture/asset-heavy old games"),
            Triple("extractNativeLibs", ID_extractNativeLibs, "forces native .so libraries to be extracted rather than mmap'd directly out of this app's own repackaged/resigned APK"),
            Triple("hardwareAccelerated", ID_hardwareAccelerated, "likely already the default at targetSdkVersion >= 14, set explicitly anyway"),
            Triple("isGame", ID_isGame, "cosmetic/OS-integration only (app-drawer categorization, some OEMs' game-mode features)")
        )

        for ((name, id, why) in flags) {
            val existing = application.searchAttributeByResourceId(id)
            val oldDesc = existing?.let {
                if (it.valueType == ValueType.BOOLEAN) (if (it.getData() != 0) "true" else "false") else "(non-boolean, unexpected)"
            } ?: "(not declared)"
            application.getOrCreateAndroidAttribute(name, id).setValueAsBoolean(true)
            changes.add("android:$name on <application>: $oldDesc -> true ($why)")
        }

        block.refreshFull()
        val out = ByteArrayOutputStream()
        block.writeBytes(out)
        return ResizabilityResult(out.toByteArray(), changes)
    }
}
