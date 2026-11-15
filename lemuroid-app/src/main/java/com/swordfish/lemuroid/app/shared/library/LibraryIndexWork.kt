package com.swordfish.lemuroid.app.shared.library

import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.NotificationsManager
import com.swordfish.lemuroid.app.shared.covers.CoverDownloader
import com.swordfish.lemuroid.app.utils.android.createSyncForegroundInfo
import com.swordfish.lemuroid.lib.injection.AndroidWorkerInjection
import com.swordfish.lemuroid.lib.injection.WorkerKey
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.storage.local.RomFolderRepacker
import dagger.Binds
import dagger.android.AndroidInjector
import dagger.multibindings.IntoMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class LibraryIndexWork(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    @Inject
    lateinit var lemuroidLibrary: LemuroidLibrary

    @Inject
    lateinit var coverDownloader: CoverDownloader

    @Inject
    lateinit var romFolderRepacker: RomFolderRepacker

    override suspend fun doWork(): Result {
        AndroidWorkerInjection.inject(this)

        val notificationsManager = NotificationsManager(applicationContext)
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val foregroundInfo =
            createSyncForegroundInfo(
                NotificationsManager.LIBRARY_INDEXING_NOTIFICATION_ID,
                notificationsManager.libraryIndexingNotification(),
            )

        setForegroundAsync(foregroundInfo)

        val downloadCovers = inputData.getBoolean(LibraryIndexScheduler.KEY_DOWNLOAD_COVERS, false)
        val repackInFolder = inputData.getBoolean(LibraryIndexScheduler.KEY_REPACK_IN_FOLDER, false)

        if (repackInFolder && !isStopped) {
            withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    val repackResult =
                        romFolderRepacker.repackAll { done, total, currentName ->
                            notificationManager.notify(
                                NotificationsManager.LIBRARY_INDEXING_NOTIFICATION_ID,
                                notificationsManager.libraryIndexingNotification(
                                    title = applicationContext.getString(R.string.library_index_repack_title),
                                    message =
                                        applicationContext.getString(
                                            R.string.library_index_repack_progress,
                                            done,
                                            total,
                                            currentName,
                                        ),
                                    progress = done,
                                    max = total.coerceAtLeast(1),
                                    indeterminate = total <= 0,
                                ),
                            )
                        }
                    Timber.i("Folder repack completed: $repackResult")
                }.onFailure {
                    Timber.e(it, "Folder repack terminated with exception")
                }
            }
        }

        if (!isStopped) {
            notificationManager.notify(
                NotificationsManager.LIBRARY_INDEXING_NOTIFICATION_ID,
                notificationsManager.libraryIndexingNotification(),
            )
            val result =
                withContext(Dispatchers.IO) {
                    kotlin.runCatching {
                        lemuroidLibrary.indexLibrary()
                    }
                }

            result.exceptionOrNull()?.let {
                Timber.e("Library indexing work terminated with an exception:", it)
            }
        }

        if (downloadCovers && !isStopped) {
            withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    val coverResult =
                        coverDownloader.downloadMissingCovers { done, total ->
                            notificationManager.notify(
                                NotificationsManager.LIBRARY_INDEXING_NOTIFICATION_ID,
                                notificationsManager.libraryIndexingNotification(
                                    title = applicationContext.getString(R.string.library_index_covers_title),
                                    message =
                                        applicationContext.getString(
                                            R.string.library_index_covers_progress,
                                            done,
                                            total,
                                        ),
                                    progress = done,
                                    max = total.coerceAtLeast(1),
                                    indeterminate = total <= 0,
                                ),
                            )
                        }
                    Timber.i("Cover download completed: $coverResult")
                }.onFailure {
                    Timber.e(it, "Cover download terminated with exception")
                }
            }
        }

        LibraryIndexScheduler.scheduleCoreUpdate(applicationContext)

        return Result.success()
    }

    @dagger.Module(subcomponents = [Subcomponent::class])
    abstract class Module {
        @Binds
        @IntoMap
        @WorkerKey(LibraryIndexWork::class)
        abstract fun bindMyWorkerFactory(builder: Subcomponent.Builder): AndroidInjector.Factory<out ListenableWorker>
    }

    @dagger.Subcomponent
    interface Subcomponent : AndroidInjector<LibraryIndexWork> {
        @dagger.Subcomponent.Builder
        abstract class Builder : AndroidInjector.Builder<LibraryIndexWork>()
    }
}
