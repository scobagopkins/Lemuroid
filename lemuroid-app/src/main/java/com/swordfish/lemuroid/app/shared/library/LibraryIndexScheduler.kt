package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

data class ScanOptions(
    val downloadCovers: Boolean = false,
    val repackInFolder: Boolean = false,
)

object LibraryIndexScheduler {
    val CORE_UPDATE_WORK_ID: String = CoreUpdateWork::class.java.simpleName
    val LIBRARY_INDEX_WORK_ID: String = LibraryIndexWork::class.java.simpleName

    const val KEY_DOWNLOAD_COVERS = "KEY_DOWNLOAD_COVERS"
    const val KEY_REPACK_IN_FOLDER = "KEY_REPACK_IN_FOLDER"

    fun scheduleLibrarySync(
        applicationContext: Context,
        options: ScanOptions = ScanOptions(),
    ) {
        val inputData =
            workDataOf(
                KEY_DOWNLOAD_COVERS to options.downloadCovers,
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
