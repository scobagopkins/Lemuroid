package com.swordfish.lemuroid.app.shared.covers

import android.net.Uri
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.covers.CustomCoverManager
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDBManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads game cover art into [CustomCoverManager.getDownloadedCoversDirectory]
 * and persists the resulting file:// URI onto [Game.coverFrontUrl] so covers render
 * cleanly offline and do not clutter user ROM directories or expire with cache cleanup.
 */
class CoverDownloader(
    private val retrogradeDatabase: RetrogradeDatabase,
    private val customCoverManager: CustomCoverManager,
    private val libretroDBManager: LibretroDBManager,
    baseOkHttpClient: OkHttpClient,
) {
    data class DownloadSummary(
        val downloaded: Int,
        val skipped: Int,
        val failed: Int,
    )

    private val client: OkHttpClient =
        baseOkHttpClient.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    suspend fun downloadMissingCovers(
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): DownloadSummary =
        withContext(Dispatchers.IO) {
            val games = retrogradeDatabase.gameDao().selectAll()
            if (games.isEmpty()) return@withContext DownloadSummary(0, 0, 0)

            val total = games.size
            val processed = AtomicInteger(0)
            val downloaded = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

            onProgress(0, total)

            coroutineScope {
                games.map { game ->
                    async {
                        semaphore.withPermit {
                            coroutineContext.ensureActive()
                            when (downloadCoverForGame(game)) {
                                Outcome.DOWNLOADED -> downloaded.incrementAndGet()
                                Outcome.SKIPPED -> skipped.incrementAndGet()
                                Outcome.FAILED -> failed.incrementAndGet()
                            }
                            val done = processed.incrementAndGet()
                            if (done == total || done % 3 == 0) {
                                onProgress(done, total)
                            }
                        }
                    }
                }.awaitAll()
            }

            onProgress(total, total)
            DownloadSummary(downloaded.get(), skipped.get(), failed.get())
        }

    private suspend fun downloadCoverForGame(game: Game): Outcome {
        if (game.customCoverUri != null) {
            return Outcome.SKIPPED
        }

        val currentUrl = game.coverFrontUrl
        if (currentUrl != null && currentUrl.startsWith("file://")) {
            val existing = Uri.parse(currentUrl).path?.let(::File)
            if (existing != null && existing.isFile && existing.length() > MIN_IMAGE_BYTES) {
                return Outcome.SKIPPED
            }
        }

        val destFile = customCoverManager.downloadedCoverFileForGame(game.id)
        if (destFile.isFile && destFile.length() > MIN_IMAGE_BYTES) {
            val localUri = Uri.fromFile(destFile).toString()
            if (game.coverFrontUrl != localUri) {
                retrogradeDatabase.gameDao().update(game.copy(coverFrontUrl = localUri))
            }
            return Outcome.SKIPPED
        }

        val candidates = buildCandidateUrls(game)
        for (url in candidates) {
            val saved = fetchImageToFile(url, destFile)
            if (saved) {
                val localUri = Uri.fromFile(destFile).toString()
                retrogradeDatabase.gameDao().update(game.copy(coverFrontUrl = localUri))
                return Outcome.DOWNLOADED
            }
        }
        return Outcome.FAILED
    }

    private suspend fun buildCandidateUrls(game: Game): List<String> {
        val urls = LinkedHashSet<String>()

        // 1. Existing metadata URL upgraded to HTTPS + Titles/Snaps fallbacks
        val existingUrl = game.coverFrontUrl
        if (!existingUrl.isNullOrBlank() && (existingUrl.startsWith("http://") || existingUrl.startsWith("https://"))) {
            val httpsUrl = if (existingUrl.startsWith("http://")) "https://${existingUrl.removePrefix("http://")}" else existingUrl
            urls.add(httpsUrl)
            if (httpsUrl.contains("/Named_Boxarts/")) {
                urls.add(httpsUrl.replace("/Named_Boxarts/", "/Named_Titles/"))
                urls.add(httpsUrl.replace("/Named_Boxarts/", "/Named_Snaps/"))
            }
        }

        // 2. Fallback lookup against bundled LibretroDB + Libretro Thumbnails server
        val system = runCatching { GameSystem.findById(game.systemId) }.getOrNull() ?: return urls.toList()
        val systemFolders =
            buildList {
                add(if (system.id == SystemID.MAME2003PLUS) "MAME" else system.libretroFullName)
                if (system.id == SystemID.FBNEO) add("MAME")
                if (system.id == SystemID.MAME2003PLUS) add("FBNeo - Arcade Games")
            }.distinct()

        val romBase = game.fileName.substringBeforeLast('.')
        val dbRom =
            runCatching {
                val dao = libretroDBManager.dbInstance.gameDao()
                dao.findByFileName(game.fileName) ?: dao.findByFileName("$romBase.zip")
            }.getOrNull()

        val candidateTitles =
            listOfNotNull(dbRom?.name, game.title, romBase)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        for (imageType in IMAGE_TYPES) {
            for (systemFolder in systemFolders) {
                for (title in candidateTitles) {
                    if (urls.size >= MAX_CANDIDATE_URLS) break
                    val sanitizedTitle = sanitizeThumbnailTitle(title)
                    val encodedUrl =
                        HttpUrl.Builder()
                            .scheme("https")
                            .host(THUMBNAILS_HOST)
                            .addPathSegment(systemFolder)
                            .addPathSegment(imageType)
                            .addPathSegment("$sanitizedTitle.png")
                            .build()
                            .toString()
                    urls.add(encodedUrl)
                }
            }
        }

        return urls.toList()
    }

    private fun fetchImageToFile(
        url: String,
        destFile: File,
    ): Boolean {
        val parsedUrl = url.toHttpUrlOrNull() ?: return false
        val partFile = File(destFile.parentFile, "${destFile.name}.part")
        partFile.delete()

        val request =
            Request.Builder()
                .url(parsedUrl)
                .header("User-Agent", "Lemuroid")
                .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.isNotEmpty() && !contentType.startsWith("image/")) {
                    return false
                }

                val contentLength = body.contentLength()
                if (contentLength > MAX_IMAGE_BYTES) return false

                destFile.parentFile?.mkdirs()
                FileOutputStream(partFile).use { out ->
                    body.byteStream().copyTo(out)
                }

                if (!isSupportedImageFile(partFile)) {
                    partFile.delete()
                    return false
                }

                if (destFile.exists()) destFile.delete()
                if (!partFile.renameTo(destFile)) {
                    partFile.copyTo(destFile, overwrite = true)
                    partFile.delete()
                }
                true
            }
        } catch (t: Throwable) {
            Timber.d(t, "Cover download attempt failed for $url")
            partFile.delete()
            false
        }
    }

    private fun isSupportedImageFile(file: File): Boolean {
        if (!file.isFile || file.length() !in MIN_IMAGE_BYTES..MAX_IMAGE_BYTES) return false
        val header = ByteArray(12)
        val read =
            runCatching {
                file.inputStream().use { it.read(header) }
            }.getOrDefault(0)
        if (read < 8) return false

        val isPng =
            header[0] == 0x89.toByte() &&
                header[1] == 0x50.toByte() &&
                header[2] == 0x4E.toByte() &&
                header[3] == 0x47.toByte()
        val isJpeg =
            header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte()
        val isWebp =
            read >= 12 &&
                header[0] == 'R'.code.toByte() &&
                header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        return isPng || isJpeg || isWebp
    }

    private fun sanitizeThumbnailTitle(title: String): String {
        val sb = StringBuilder(title.length)
        for (ch in title) {
            sb.append(if (ch in THUMB_ILLEGAL_CHARS) '_' else ch)
        }
        return sb.toString()
    }

    private enum class Outcome {
        DOWNLOADED,
        SKIPPED,
        FAILED,
    }

    companion object {
        private const val THUMBNAILS_HOST = "thumbnails.libretro.com"
        private const val MAX_CONCURRENT_DOWNLOADS = 6
        private const val MAX_CANDIDATE_URLS = 8
        private const val MIN_IMAGE_BYTES = 128L
        private const val MAX_IMAGE_BYTES = 15L * 1024L * 1024L
        private val IMAGE_TYPES = listOf("Named_Boxarts", "Named_Titles", "Named_Snaps")
        private val THUMB_ILLEGAL_CHARS = setOf('&', '*', '/', ':', '`', '<', '>', '?', '\\', '|', '"')
    }
}
