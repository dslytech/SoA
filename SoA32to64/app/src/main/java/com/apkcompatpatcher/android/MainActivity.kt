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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.apkcompatpatcher.android.databinding.ActivityMainBinding
import java.io.File

/**
 * SoA32to64 main screen: pick the game APK -> make the ZettaBridge APK -> save it to Downloads.
 * (The ZettaBridge APK is never installed as an Android app - it is imported in the launcher.)
 */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val ZETTABRIDGE_URL =
            "https://github.com/ZailoxTT/ZettaBridge/releases/download/v0.1.0/ZettaBridge-0.1.0.apk"
    }

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pickedApkUri: Uri? = null
    private var pickedApkName: String = ""
    private var lastPatchedFile: File? = null

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        pickedApkUri = uri
        pickedApkName = queryDisplayName(uri) ?: "selected.apk"
        binding.selectedFileLabel.text = getString(R.string.selected_file, pickedApkName)
        binding.patchButton.isEnabled = true
        binding.downloadApkButton.isEnabled = false
        lastPatchedFile = null
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

        binding.zettaBridgeButton.setOnClickListener {
            openZettaBridgeDownload()
        }

        binding.pickButton.setOnClickListener {
            pickApkLauncher.launch("*/*")
        }

        binding.patchButton.setOnClickListener {
            startPatch()
        }

        binding.downloadApkButton.setOnClickListener {
            downloadPatchedApk()
        }

        binding.downloadLogButton.setOnClickListener {
            downloadLog()
        }
    }

    /** Opens the official ZettaBridge 0.1.0 release APK (by its own author) in the browser/download manager. */
    private fun openZettaBridgeDownload() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ZETTABRIDGE_URL)))
        } catch (e: Exception) {
            Logger.logThrowable("openZettaBridgeDownload", e)
            Toast.makeText(this, getString(R.string.toast_open_link_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun startPatch() {
        val uri = pickedApkUri ?: return

        binding.patchButton.isEnabled = false
        binding.pickButton.isEnabled = false
        binding.downloadApkButton.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.logView.text = ""

        Thread {
            try {
                Logger.startNewSession(this)
                Logger.log("SoA32to64 starting")
                Logger.log("input: $pickedApkName")

                val workDir = File(cacheDir, "work").apply { deleteRecursively(); mkdirs() }
                val inputCopy = File(workDir, "input.apk")
                contentResolver.openInputStream(uri)?.use { input ->
                    inputCopy.outputStream().use { output -> input.copyTo(output) }
                } ?: throw ApkPatcher.PatchException("could not open the selected file")

                val result = ApkPatcher.patch(this, inputCopy, File(workDir, "patch"), pickedApkName)
                lastPatchedFile = result.outputFile

                mainHandler.post {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.pickButton.isEnabled = true
                    binding.patchButton.isEnabled = true
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

    private fun downloadLog() {
        val file = Logger.currentLogFile()
        if (file == null || !file.exists()) {
            Toast.makeText(this, getString(R.string.toast_no_log_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val savedName = "soa32to64-${file.name}"
        if (saveToDownloads(file, savedName, "text/plain")) {
            Toast.makeText(this, getString(R.string.toast_log_saved, savedName), Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, getString(R.string.toast_log_save_failed), Toast.LENGTH_LONG).show()
        }
    }

    /** Copies [source] into the public Downloads folder through MediaStore (Android 10+, no permission needed). */
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
