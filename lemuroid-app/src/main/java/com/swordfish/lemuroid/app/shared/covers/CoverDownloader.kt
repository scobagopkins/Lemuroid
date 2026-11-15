package com.swordfish.lemuroid.app.shared.covers

import android.content.Context
import android.net.Uri
import coil.imageLoader
import com.swordfish.lemuroid.app.shared.library.CoverArtType
import com.swordfish.lemuroid.app.shared.library.CoverStorageLocation
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.covers.CustomCoverManager
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.local.RomFolderRepacker
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads game artwork from thumbnails.libretro.com according to the user's
 * selected [CoverArtType] and [CoverStorageLocation] (dedicated app storage or
 * hidden `<romRoot>/.covers/` folder with `.nomedia`), updating [Game.coverFrontUrl].
 */
class CoverDownloader(
    private val context: Context,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val customCoverManager: CustomCoverManager,
    private val libretroDBManager: LibretroDBManager,
    private val directoriesManager: DirectoriesManager,
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

    private val metaPrefs by lazy {
        context.getSharedPreferences(COVER_META_PREFS, Context.MODE_PRIVATE)
    }

    suspend fun downloadMissingCovers(
        artType: CoverArtType = CoverArtType.BOXART,
        storageLocation: CoverStorageLocation = CoverStorageLocation.APP_STORAGE,
        onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): DownloadSummary =
        withContext(Dispatchers.IO) {
            val games = retrogradeDatabase.gameDao().selectAll()
            if (games.isEmpty()) return@withContext DownloadSummary(0, 0, 0)

            val writableRomRoot =
                if (storageLocation == CoverStorageLocation.ROM_FOLDER) {
                    RomFolderRepacker.resolveWritableLocalRoot(context, directoriesManager)
                } else {
                    null
                }
            val effectiveLocation =
                if (writableRomRoot != null) {
                    CoverStorageLocation.ROM_FOLDER
                } else {
                    CoverStorageLocation.APP_STORAGE
                }
            val romCoversRoot =
                writableRomRoot?.let { root ->
                    File(root, ROM_COVERS_DIR_NAME).apply {
                        mkdirs()
                        runCatching {
                            val nomedia = File(this, ".nomedia")
                            if (!nomedia.exists()) nomedia.createNewFile()
                        }
                    }
                }

            val existingMetaSnapshot = metaPrefs.all
            val pendingMetaUpdates = ConcurrentHashMap<String, String>()
            val total = games.size
            val processed = AtomicInteger(0)
            val downloaded = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

            onProgress(0, total)

            try {
                coroutineScope {
                    games.map { game ->
                        async {
                            semaphore.withPermit {
                                coroutineContext.ensureActive()
                                val outcome =
                                    downloadCoverForGame(
                                        game = game,
                                        artType = artType,
                                        effectiveLocation = effectiveLocation,
                                        writableRomRoot = writableRomRoot,
                                        romCoversRoot = romCoversRoot,
                                        existingMeta = existingMetaSnapshot[game.fileUri] as? String,
                                        pendingMetaUpdates = pendingMetaUpdates,
                                    )
                                when (outcome) {
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
            } finally {
                if (pendingMetaUpdates.isNotEmpty()) {
                    val editor = metaPrefs.edit()
                    pendingMetaUpdates.forEach { (key, value) ->
                        editor.putString(key, value)
                    }
                    editor.apply()
                }
                if (downloaded.get() > 0) {
                    runCatching { context.imageLoader.memoryCache?.clear() }
                }
            }

            onProgress(total, total)
            DownloadSummary(downloaded.get(), skipped.get(), failed.get())
        }

    private suspend fun downloadCoverForGame(
        game: Game,
        artType: CoverArtType,
        effectiveLocation: CoverStorageLocation,
        writableRomRoot: File?,
        romCoversRoot: File?,
        existingMeta: String?,
        pendingMetaUpdates: ConcurrentHashMap<String, String>,
    ): Outcome {
        if (game.customCoverUri != null) {
            return Outcome.SKIPPED
        }

        val destFile = resolveDestFile(game, effectiveLocation, writableRomRoot, romCoversRoot)
        val expectedSignature = "${artType.name}|${destFile.absolutePath}"
        val localUri = Uri.fromFile(destFile).toString()

        if (destFile.isFile && destFile.length() > MIN_IMAGE_BYTES) {
            val matchesRecordedSignature = existingMeta == expectedSignature
            // Adopt pre-existing .covers/ artwork on disk after reinstall/reset if no conflicting type was recorded.
            val adoptExistingRomFolderArt =
                effectiveLocation == CoverStorageLocation.ROM_FOLDER && existingMeta == null
            if (matchesRecordedSignature || adoptExistingRomFolderArt) {
                if (game.coverFrontUrl != localUri) {
                    retrogradeDatabase.gameDao().update(game.copy(coverFrontUrl = localUri))
                }
                if (!matchesRecordedSignature) {
                    pendingMetaUpdates[game.fileUri] = expectedSignature
                }
                return Outcome.SKIPPED
            }
        }

        val candidates = buildCandidateUrls(game, artType)
        for (url in candidates) {
            val saved = fetchImageToFile(url, destFile)
            if (saved) {
                cleanupPreviousAppCoverIfReplaced(game.coverFrontUrl, destFile)
                pendingMetaUpdates[game.fileUri] = expectedSignature
                retrogradeDatabase.gameDao().update(game.copy(coverFrontUrl = localUri))
                return Outcome.DOWNLOADED
            }
        }
        return Outcome.FAILED
    }

    private fun resolveDestFile(
        game: Game,
        effectiveLocation: CoverStorageLocation,
        writableRomRoot: File?,
        romCoversRoot: File?,
    ): File {
        if (effectiveLocation != CoverStorageLocation.ROM_FOLDER || writableRomRoot == null || romCoversRoot == null) {
            return customCoverManager.downloadedCoverFileForGame(game.id)
        }

        val rawBase = game.fileName.substringBeforeLast('.', game.fileName)
        val safeBase = sanitizePathSegment(rawBase).ifBlank { "game_${game.id}" }
        val relativeParent = resolveRelativeParent(game, writableRomRoot)
        val targetDir =
            if (relativeParent.isNotEmpty()) {
                File(romCoversRoot, relativeParent)
            } else {
                File(romCoversRoot, sanitizePathSegment(game.systemId))
            }
        return File(targetDir, "$safeBase.png")
    }

    private fun resolveRelativeParent(
        game: Game,
        writableRomRoot: File,
    ): String {
        val uri = Uri.parse(game.fileUri)
        if (uri.scheme != "file") return ""
        val parentDir = uri.path?.let(::File)?.parentFile ?: return ""
        val rel =
            runCatching {
                parentDir.canonicalFile.toRelativeString(writableRomRoot.canonicalFile)
            }.getOrDefault("")
        if (rel.isEmpty() || rel == "." || rel == ".." || rel.startsWith("../") || rel.startsWith("..\\")) {
            return ""
        }
        return rel.replace('\\', '/')
            .split('/')
            .map(::sanitizePathSegment)
            .filter { it.isNotEmpty() }
            .joinToString(File.separator)
    }

    private fun cleanupPreviousAppCoverIfReplaced(
        previousUrl: String?,
        newDestFile: File,
    ) {
        if (previousUrl.isNullOrBlank() || !previousUrl.startsWith("file://")) return
        val oldPath = Uri.parse(previousUrl).path ?: return
        val oldFile = File(oldPath)
        val appCoversDir = customCoverManager.getDownloadedCoversDirectory()
        val isInAppCovers =
            runCatching {
                oldFile.parentFile?.canonicalFile == appCoversDir.canonicalFile &&
                    oldFile.canonicalFile != newDestFile.canonicalFile
            }.getOrDefault(false)
        if (isInAppCovers && oldFile.isFile) {
            oldFile.delete()
        }
    }

    private suspend fun buildCandidateUrls(
        game: Game,
        artType: CoverArtType,
    ): List<String> {
        val urls = LinkedHashSet<String>()

        // 1. Existing HTTP metadata URL rewritten strictly to requested artType folders
        val existingUrl = game.coverFrontUrl
        if (!existingUrl.isNullOrBlank() && (existingUrl.startsWith("http://") || existingUrl.startsWith("https://"))) {
            val httpsUrl =
                if (existingUrl.startsWith("http://")) {
                    "https://${existingUrl.removePrefix("http://")}"
                } else {
                    existingUrl
                }
            val matchedDir = NAMED_FOLDER_TOKENS.firstOrNull { httpsUrl.contains(it) }
            if (matchedDir != null) {
                for (dir in artType.libretroDirs) {
                    urls.add(httpsUrl.replace(matchedDir, "/$dir/"))
                }
            } else if (artType == CoverArtType.BOXART || artType == CoverArtType.AUTO) {
                urls.add(httpsUrl)
            }
        }

        // 2. Lookup against bundled LibretroDB + Libretro Thumbnails server for chosen artType
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

        for (imageType in artType.libretroDirs) {
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
        val parentDir = destFile.parentFile ?: return false
        parentDir.mkdirs()
        val partFile = File(parentDir, "${destFile.name}.part")
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

    private fun sanitizePathSegment(segment: String): String {
        val cleaned =
            buildString(segment.length) {
                for (ch in segment) {
                    append(if (ch in PATH_ILLEGAL_CHARS || ch.code < 32) '_' else ch)
                }
            }.trim()
        return if (cleaned == "." || cleaned == "..") "_" else cleaned
    }

    private enum class Outcome {
        DOWNLOADED,
        SKIPPED,
        FAILED,
    }

    companion object {
        private const val COVER_META_PREFS = "lemuroid_cover_download_meta"
        private const val ROM_COVERS_DIR_NAME = ".covers"
        private const val THUMBNAILS_HOST = "thumbnails.libretro.com"
        private const val MAX_CONCURRENT_DOWNLOADS = 6
        private const val MAX_CANDIDATE_URLS = 18
        private const val MIN_IMAGE_BYTES = 128L
        private const val MAX_IMAGE_BYTES = 15L * 1024L * 1024L
        private val NAMED_FOLDER_TOKENS = listOf("/Named_Boxarts/", "/Named_Titles/", "/Named_Snaps/")
        private val THUMB_ILLEGAL_CHARS = setOf('&', '*', '/', ':', '`', '<', '>', '?', '\\', '|', '"')
        private val PATH_ILLEGAL_CHARS = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    }
}
