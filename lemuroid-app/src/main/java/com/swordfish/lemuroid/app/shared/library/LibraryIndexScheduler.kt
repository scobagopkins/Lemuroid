package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

enum class CoverArtType(val libretroDirs: List<String>) {
    BOXART(listOf("Named_Boxarts")),
    SNAP(listOf("Named_Snaps")),
    TITLE(listOf("Named_Titles")),
    AUTO(listOf("Named_Boxarts", "Named_Titles", "Named_Snaps")),
    ;

    companion object {
        fun fromName(name: String?): CoverArtType =
            entries.firstOrNull { it.name == name } ?: BOXART
    }
}

enum class CoverStorageLocation {
    APP_STORAGE,
    ROM_FOLDER,
    ;

    companion object {
        fun fromName(name: String?): CoverStorageLocation =
            entries.firstOrNull { it.name == name } ?: APP_STORAGE
    }
}

data class ScanOptions(
    val downloadCovers: Boolean = false,
    val coverArtType: CoverArtType = CoverArtType.BOXART,
    val coverStorageLocation: CoverStorageLocation = CoverStorageLocation.APP_STORAGE,
    val repackInFolder: Boolean = false,
)

object LibraryIndexScheduler {
    val CORE_UPDATE_WORK_ID: String = CoreUpdateWork::class.java.simpleName
    val LIBRARY_INDEX_WORK_ID: String = LibraryIndexWork::class.java.simpleName

    const val KEY_DOWNLOAD_COVERS = "KEY_DOWNLOAD_COVERS"
    const val KEY_COVER_ART_TYPE = "KEY_COVER_ART_TYPE"
    const val KEY_COVER_STORAGE_LOCATION = "KEY_COVER_STORAGE_LOCATION"
    const val KEY_REPACK_IN_FOLDER = "KEY_REPACK_IN_FOLDER"

    fun scheduleLibrarySync(
        applicationContext: Context,
        options: ScanOptions = ScanOptions(),
    ) {
        val inputData =
            workDataOf(
                KEY_DOWNLOAD_COVERS to options.downloadCovers,
                KEY_COVER_ART_TYPE to options.coverArtType.name,
                KEY_COVER_STORAGE_LOCATION to options.coverStorageLocation.name,
                KEY_REPACK_IN_FOLDER to options.repackInFolder,
            )

        val request =
            OneTimeWorkRequestBuilder<LibraryIndexWork>()
                .setInputData(inputData)
                .build()

        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                LIBRARY_INDEX_WORK_ID,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            .enqueue()
    }

    fun scheduleCoreUpdate(applicationContext: Context) {
        WorkManager.getInstance(applicationContext)
            .beginUniqueWork(
                CORE_UPDATE_WORK_ID,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CoreUpdateWork>().build(),
            )
            .enqueue()
    }

    fun cancelLibrarySync(applicationContext: Context) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(LIBRARY_INDEX_WORK_ID)
    }

    fun cancelCoreUpdate(applicationContext: Context) {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(CORE_UPDATE_WORK_ID)
    }
}
