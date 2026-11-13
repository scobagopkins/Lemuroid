package com.swordfish.lemuroid.app.shared.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
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

        startLibraryIndexWork()
        finish()
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

            startLibraryIndexWork()
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

    private fun startLibraryIndexWork() {
        LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
    }

    companion object {
        private const val REQUEST_CODE_PICK_FOLDER = 1
        private const val REQUEST_CODE_MANAGE_STORAGE = 2

        fun pickFolder(context: Context) {
            context.startActivity(Intent(context, StorageFrameworkPickerLauncher::class.java))
        }
    }
}
