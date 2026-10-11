package com.apkcompatpatcher.android

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.apkcompatpatcher.android.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pickedApkUri: Uri? = null
    private var pickedApkName: String = ""
    private var lastPatchedFile: File? = null

    private var pickedKeystoreUri: Uri? = null
    private var pickedKeystoreName: String = ""

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedApkUri = uri
        pickedApkName = queryDisplayName(uri) ?: "selected.apk"
        binding.selectedFileLabel.text = getString(R.string.selected_file, pickedApkName)
        binding.patchButton.isEnabled = true
        binding.installButton.isEnabled = false
        binding.downloadApkButton.isEnabled = false
        lastPatchedFile = null
    }

    private val pickKeystoreLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedKeystoreUri = uri
        pickedKeystoreName = queryDisplayName(uri) ?: "keystore"
        binding.selectedKeystoreLabel.text = getString(R.string.selected_keystore, pickedKeystoreName)
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, version))
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutButton.setOnClickListener { showAbout() }

        Logger.listener = Logger.Listener { line ->
            mainHandler.post {
                binding.logView.append(line + "\n")
            }
        }

        binding.pickButton.setOnClickListener {
            pickApkLauncher.launch("*/*")
        }

        binding.patchButton.setOnClickListener {
            startPatch()
        }

        binding.obbToggle.setOnCheckedChangeListener { _, checked ->
            binding.obbPanel.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.advancedToggle.setOnCheckedChangeListener { _, checked ->
            binding.advancedPanel.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.useCustomKeystoreCheck.setOnCheckedChangeListener { _, checked ->
            binding.keystorePanel.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }

        binding.pickKeystoreButton.setOnClickListener {
            pickKeystoreLauncher.launch("*/*")
        }

        binding.installButton.setOnClickListener {
            installPatchedApk()
        }

        binding.downloadApkButton.setOnClickListener {
            downloadPatchedApk()
        }

        binding.downloadLogButton.setOnClickListener {
            downloadLog()
        }

        binding.grantAllFilesAccessButton.setOnClickListener {
            requestAllFilesAccess()
        }

        binding.ig2ShortcutButton.setOnClickListener {
            binding.obbPackageInput.setText("com.digitalreality.ig2")
        }

        binding.haegemoniaShortcutButton.setOnClickListener {
            binding.obbPackageInput.setText("com.microids.google.HaegemoniaFull")
        }

        binding.clearObbButton.setOnClickListener {
            clearObbFolder()
        }
    }

    /**
     * fix (2026-10-05): a full uninstall of a game does not reliably clean up its own
     * Android/obb/<package> folder on this device - a leftover/orphaned copy can survive
     * and then block the NEXT install from reading it, which surfaces as a black screen,
     * a "main expansion package file: IO Error" dialog, or the game simply never launching
     * - confirmed real-device on both IG2 and Haegemonia. The only thing that reliably fixes
     * it is deleting that whole folder before copying the game's own .obb file + videos back
     * in fresh (plain chmod does NOT work on this device/Android version - the FUSE storage
     * layer rejects it with "Operation not permitted" regardless of root). This used to mean
     * typing an "adb shell rm -rf ..." command by hand; these two helpers do the same thing
     * from inside the app, once the user has granted the one-time "All files access"
     * permission below (a plain app cannot touch another app's external obb folder without
     * it - that's what makes the leftover folder possible to begin with).
     */
    private fun hasAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(this, getString(R.string.toast_all_files_access_not_needed), Toast.LENGTH_SHORT).show()
            return
        }
        if (hasAllFilesAccess()) {
            Toast.makeText(this, getString(R.string.toast_all_files_access_already_granted), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                Logger.logThrowable("requestAllFilesAccess", e2)
                Toast.makeText(this, getString(R.string.toast_all_files_access_open_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearObbFolder() {
        val pkg = binding.obbPackageInput.text?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_obb_package_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, getString(R.string.toast_need_all_files_access), Toast.LENGTH_LONG).show()
            return
        }
        val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/$pkg")
        if (!obbDir.exists()) {
            Toast.makeText(this, getString(R.string.toast_obb_not_found, obbDir.path), Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_confirm_delete_title))
            .setMessage(getString(R.string.dialog_confirm_delete_message, obbDir.path))
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ ->
                val ok = try {
                    obbDir.deleteRecursively()
                } catch (e: Exception) {
                    Logger.logThrowable("clearObbFolder", e)
                    false
                }
                if (ok) {
                    Toast.makeText(this, getString(R.string.toast_obb_deleted, obbDir.path), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_obb_delete_failed), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun startPatch() {
        val uri = pickedApkUri ?: return

        // Advanced settings - validated on the UI thread so a mistake here
        // (e.g. "use custom keystore" checked but nothing picked) shows up
        // as an immediate, clear message instead of failing deep inside the
        // background thread.
        val targetSdkText = binding.targetSdkInput.text?.toString()?.trim()
        val targetSdk = if (targetSdkText.isNullOrEmpty()) 30 else targetSdkText.toIntOrNull()
        if (targetSdk == null) {
            Toast.makeText(this, getString(R.string.toast_invalid_target_sdk), Toast.LENGTH_SHORT).show()
            return
        }

        val forceUserOrientation = binding.forceUserOrientationCheck.isChecked
        val forceResizable = binding.forceResizableCheck.isChecked

        val useCustomKeystore = binding.useCustomKeystoreCheck.isChecked
        val keystoreUri = pickedKeystoreUri
        val keystoreAlias = binding.keystoreAliasInput.text?.toString()?.trim().orEmpty()
        val keystorePassword = binding.keystorePasswordInput.text?.toString().orEmpty()
        if (useCustomKeystore && (keystoreUri == null || keystoreAlias.isEmpty() || keystorePassword.isEmpty())) {
            Toast.makeText(this, getString(R.string.toast_keystore_required), Toast.LENGTH_LONG).show()
            return
        }

        binding.patchButton.isEnabled = false
        binding.pickButton.isEnabled = false
        binding.installButton.isEnabled = false
        binding.downloadApkButton.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.logView.text = ""

        Thread {
            try {
                Logger.startNewSession(this)
                Logger.log("apk-compat-patcher-android starting")
                Logger.log("input: $pickedApkName")
                Logger.log("targetSdkVersion override: $targetSdk")
                if (forceUserOrientation) {
                    Logger.log("advanced setting: free rotation (follow device orientation) = ON")
                }
                if (forceResizable) {
                    Logger.log("advanced setting: resizable / multi-window support = ON")
                }

                val workDir = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                val inputCopy = File(workDir, "input.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    inputCopy.outputStream().use { output -> input.copyTo(output) }
                } ?: throw ApkPatcher.PatchException("could not open the selected file")

                val customKeystore = if (useCustomKeystore && keystoreUri != null) {
                    Logger.log("using custom keystore: $pickedKeystoreName")
                    val keystoreCopy = File(workDir, "custom.keystore")
                    contentResolver.openInputStream(keystoreUri)?.use { input ->
                        keystoreCopy.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw ApkPatcher.PatchException("could not open the selected keystore file")
                    CustomKeystoreConfig(keystoreCopy, keystoreAlias, keystorePassword)
                } else {
                    null
                }

                val result = ApkPatcher.patch(
                    this, inputCopy, File(workDir, "patch"), pickedApkName, targetSdk, customKeystore,
                    forceResizable, forceUserOrientation
                )
                lastPatchedFile = result.outputFile

                mainHandler.post {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.pickButton.isEnabled = true
                    binding.patchButton.isEnabled = true
                    binding.installButton.isEnabled = true
                    binding.downloadApkButton.isEnabled = true
                    Toast.makeText(this, getString(R.string.toast_patch_done, result.outputFile.name), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Logger.logThrowable("patch pipeline", e)
                mainHandler.post {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.pickButton.isEnabled = true
                    binding.patchButton.isEnabled = true
                    Toast.makeText(this, getString(R.string.toast_patch_error), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installPatchedApk() {
        val file = lastPatchedFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Logger.logThrowable("install intent", e)
            Toast.makeText(this, getString(R.string.toast_install_launch_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Saves the just-patched APK straight into the device's public Downloads folder, instead of
     * (or in addition to) installing it directly - useful for keeping a copy, moving it to
     * another device, or sending it to someone else without going through the installer.
     */
    private fun downloadPatchedApk() {
        val file = lastPatchedFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.toast_no_apk_yet), Toast.LENGTH_SHORT).show()
            return
        }
        if (saveToDownloads(file, file.name, "application/vnd.android.package-archive")) {
            Toast.makeText(this, getString(R.string.toast_apk_saved, file.name), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_apk_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Saves the current log file straight into the device's public Downloads folder, instead
     * of opening a share sheet - a plain file the user can find in their file manager / Downloads
     * app afterwards, no picking an app to "share" it through.
     */
    private fun downloadLog() {
        val file = Logger.currentLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.toast_no_log_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val savedName = "apk-compat-patcher-${file.name}"
        if (saveToDownloads(file, savedName, "text/plain")) {
            Toast.makeText(this, getString(R.string.toast_log_saved, savedName), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_log_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Copies [source] into the device's public Downloads folder as [displayName], using the
     * scoped-storage MediaStore API - no storage permission needed, works on Android 10+.
     * This app's minSdk is 26, so Android 8-9 devices fall through to `false` here rather than
     * requesting the old broad WRITE_EXTERNAL_STORAGE permission for what's an increasingly
     * rare case on real hardware; the log can still be read from Android Studio's Device File
     * Explorer on those older versions if it ever comes up.
     */
    private fun saveToDownloads(source: File, displayName: String, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            val wrote = contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }
            wrote != null
        } catch (e: Exception) {
            Logger.logThrowable("saveToDownloads", e)
            false
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
