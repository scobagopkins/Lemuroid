package com.swordfish.lemuroid.app.shared.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.app.shared.library.ScanOptions
import com.swordfish.lemuroid.app.utils.android.displayErrorDialog
import com.swordfish.lemuroid.lib.android.RetrogradeActivity
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import java.io.File
import javax.inject.Inject

class StorageFrameworkPickerLauncher : RetrogradeActivity() {
    @Inject
    lateinit var directoriesManager: DirectoriesManager

    private var currentDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                requestManageAllFilesPermission()
            } else {
                showBuiltInFolderPicker(resolveInitialDirectory())
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            showBuiltInFolderPicker(resolveInitialDirectory())
        }
    }

    private fun requestManageAllFilesPermission() {
        try {
            val intent =
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
            } catch (e2: Exception) {
                launchSystemSafPicker()
            }
        }
    }

    private fun resolveInitialDirectory(): File {
        val root = Environment.getExternalStorageDirectory()
        val sharedPreferences = SharedPreferencesHelper.getLegacySharedPreferences(this)
        val legacyKey = getString(com.swordfish.lemuroid.lib.R.string.pref_key_legacy_external_folder)
        val savedPath = sharedPreferences.getString(legacyKey, null)
        if (!savedPath.isNullOrBlank()) {
            val savedDir = File(savedPath)
            if (savedDir.exists() && savedDir.isDirectory) {
                return savedDir
            }
        }
        val retroarchDir = File(root, "!D/!RETROARCH")
        if (retroarchDir.exists() && retroarchDir.isDirectory) {
            return retroarchDir
        }
        val dDir = File(root, "!D")
        if (dDir.exists() && dDir.isDirectory) {
            return dDir
        }
        return root
    }

    private fun showBuiltInFolderPicker(directory: File) {
        currentDialog?.dismiss()

        val root = Environment.getExternalStorageDirectory()
        val subDirs =
            directory.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()

        val entries = mutableListOf<Pair<String, File>>()
        val parent = directory.parentFile
        if (parent != null && directory.absolutePath != root.absolutePath && parent.canRead()) {
            entries.add("⬆️ .. (На уровень выше)" to parent)
        }
        subDirs.forEach { subDir ->
            entries.add("📁 ${subDir.name}" to subDir)
        }

        val labels = entries.map { it.first }.toTypedArray()

        currentDialog =
            AlertDialog.Builder(this)
                .setTitle(directory.absolutePath)
                .setItems(labels) { _, which ->
                    val target = entries[which].second
                    showBuiltInFolderPicker(target)
                }
                .setPositiveButton("Выбрать эту папку") { _, _ ->
                    saveLocalFolderSelection(directory)
                }
                .setNeutralButton("Системный выбор") { _, _ ->
                    launchSystemSafPicker()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .setOnCancelListener {
                    finish()
                }
                .show()
    }

    private fun saveLocalFolderSelection(directory: File) {
        val sharedPreferences = SharedPreferencesHelper.getLegacySharedPreferences(this)
        val legacyKey = getString(com.swordfish.lemuroid.lib.R.string.pref_key_legacy_external_folder)
        val safKey = getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder)

        sharedPreferences.edit().apply {
            putString(legacyKey, directory.absolutePath)
            putString(safKey, directory.absolutePath)
            apply()
        }

        showScanOptionsDialog(isLocalStorage = true)
    }

    private fun launchSystemSafPicker() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                this.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                this.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                this.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER)
        } catch (e: Exception) {
            showStorageAccessFrameworkNotSupportedDialog()
        }
    }

    private fun showStorageAccessFrameworkNotSupportedDialog() {
        val message = getString(R.string.dialog_saf_not_found, directoriesManager.getInternalRomsDirectory())
        val actionLabel = getString(R.string.ok)
        displayErrorDialog(message, actionLabel) { finish() }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                showBuiltInFolderPicker(resolveInitialDirectory())
            } else {
                launchSystemSafPicker()
            }
            return
        }

        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == Activity.RESULT_OK) {
            val sharedPreferences = SharedPreferencesHelper.getLegacySharedPreferences(this)
            val preferenceKey = getString(com.swordfish.lemuroid.lib.R.string.pref_key_extenral_folder)
            val legacyKey = getString(com.swordfish.lemuroid.lib.R.string.pref_key_legacy_external_folder)

            val currentValue: String? = sharedPreferences.getString(preferenceKey, null)
            val newValue = resultData?.data

            if (newValue != null && newValue.toString() != currentValue) {
                updatePersistableUris(newValue)

                sharedPreferences.edit().apply {
                    this.remove(legacyKey)
                    this.putString(preferenceKey, newValue.toString())
                    this.apply()
                }
            }

            showScanOptionsDialog(isLocalStorage = false)
            return
        }
        finish()
    }

    override fun onDestroy() {
        currentDialog?.dismiss()
        super.onDestroy()
    }

    private fun updatePersistableUris(uri: Uri) {
        contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .filter { it.uri != uri }
            .forEach {
                contentResolver.releasePersistableUriPermission(
                    it.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }

        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun showScanOptionsDialog(isLocalStorage: Boolean) {
        currentDialog?.dismiss()

        val prefs = SharedPreferencesHelper.getLegacySharedPreferences(this)
        val savedDownloadCovers = prefs.getBoolean(LibraryIndexScheduler.KEY_DOWNLOAD_COVERS, false)

        val padH = dpToPx(24)
        val padV = dpToPx(12)
        val gap = dpToPx(8)

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padH, padV, padH, padV)
            }

        val coversCheck =
            CheckBox(this).apply {
                text = getString(R.string.scan_options_download_covers_title)
                isChecked = savedDownloadCovers
            }
        container.addView(coversCheck)

        val coversDesc =
            TextView(this).apply {
                text = getString(R.string.scan_options_download_covers_desc)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(32), 0, 0, gap * 2)
                alpha = 0.75f
            }
        container.addView(coversDesc)

        val header =
            TextView(this).apply {
                text = getString(R.string.scan_options_repack_header)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, 0, gap)
            }
        container.addView(header)

        val radioCache =
            RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = getString(R.string.scan_options_repack_cache_title)
            }
        val radioCacheDesc =
            TextView(this).apply {
                text = getString(R.string.scan_options_repack_cache_desc)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(32), 0, 0, gap)
                alpha = 0.75f
            }

        val radioFolder =
            RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = getString(R.string.scan_options_repack_folder_title)
                isEnabled = isLocalStorage
            }
        val radioFolderDesc =
            TextView(this).apply {
                text =
                    if (isLocalStorage) {
                        getString(R.string.scan_options_repack_folder_desc)
                    } else {
                        getString(R.string.scan_options_repack_folder_saf_disabled)
                    }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dpToPx(32), 0, 0, gap)
                alpha = if (isLocalStorage) 0.75f else 0.5f
            }

        val radioGroup =
            RadioGroup(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(radioCache)
                addView(radioCacheDesc)
                addView(radioFolder)
                addView(radioFolderDesc)
                check(radioCache.id)
            }
        container.addView(radioGroup)

        val scrollView = ScrollView(this).apply { addView(container) }

        currentDialog =
            AlertDialog.Builder(this)
                .setTitle(R.string.scan_options_title)
                .setView(scrollView)
                .setPositiveButton(R.string.scan_options_action_scan) { _, _ ->
                    val download = coversCheck.isChecked
                    val repackFolder = isLocalStorage && radioGroup.checkedRadioButtonId == radioFolder.id
                    prefs.edit().putBoolean(LibraryIndexScheduler.KEY_DOWNLOAD_COVERS, download).apply()
                    LibraryIndexScheduler.scheduleLibrarySync(
                        applicationContext,
                        ScanOptions(
                            downloadCovers = download,
                            repackInFolder = repackFolder,
                        ),
                    )
                    finish()
                }
                .setNegativeButton(R.string.scan_options_action_skip) { _, _ ->
                    finish()
                }
                .setOnCancelListener {
                    finish()
                }
                .show()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics,
        ).toInt()

    companion object {
        private const val REQUEST_CODE_PICK_FOLDER = 1
        private const val REQUEST_CODE_MANAGE_STORAGE = 2

        fun pickFolder(context: Context) {
            context.startActivity(Intent(context, StorageFrameworkPickerLauncher::class.java))
        }
    }
}
