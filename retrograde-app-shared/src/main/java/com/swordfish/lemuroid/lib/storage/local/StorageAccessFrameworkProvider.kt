package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.leanback.preference.LeanbackPreferenceFragment
import com.swordfish.lemuroid.common.kotlin.extractEntryToFile
import com.swordfish.lemuroid.common.kotlin.is7z
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.common.kotlin.writeToFile
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.DataFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.RomFiles
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.StorageProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class StorageAccessFrameworkProvider(private val context: Context) : StorageProvider {
    override val id: String = "access_framework"

    override val name: String = context.getString(R.string.local_storage)

    override val uriSchemes = listOf("content")

    override val prefsFragmentClass: Class<LeanbackPreferenceFragment>? = null

    override val enabledByDefault = true

    override fun listBaseStorageFiles(): Flow<List<BaseStorageFile>> {
        return getExternalFolder()?.let { folder ->
            if (folder.startsWith("content://")) {
                traverseDirectoryEntries(Uri.parse(folder))
            } else {
                emptyFlow()
            }
        } ?: emptyFlow()
    }

    override fun getStorageFile(baseStorageFile: BaseStorageFile): StorageFile? {
        return DocumentFileParser.parseDocumentFile(context, baseStorageFile)
    }

    private fun getExternalFolder(): String? {
        val prefString = context.getString(R.string.pref_key_extenral_folder)
        val preferenceManager = SharedPreferencesHelper.getLegacySharedPreferences(context)
        return preferenceManager.getString(prefString, null)
    }

    private fun traverseDirectoryEntries(rootUri: Uri): Flow<List<BaseStorageFile>> =
        flow {
            val directoryDocumentIds = mutableListOf<String>()
            DocumentsContract.getTreeDocumentId(rootUri)?.let { directoryDocumentIds.add(it) }

            while (directoryDocumentIds.isNotEmpty()) {
                val currentDirectoryDocumentId = directoryDocumentIds.removeAt(0)

                val result =
                    runCatching {
                        listBaseStorageFiles(rootUri, currentDirectoryDocumentId)
                    }
                if (result.isFailure) {
                    Timber.e(result.exceptionOrNull(), "Error while listing files")
                }

                val (files, directories) =
                    result.getOrDefault(
                        listOf<BaseStorageFile>() to listOf<String>(),
                    )

                emit(files)
                directoryDocumentIds.addAll(directories)
            }
        }

    private fun listBaseStorageFiles(
        treeUri: Uri,
        rootDocumentId: String,
    ): Pair<List<BaseStorageFile>, List<String>> {
        val resultFiles = mutableListOf<BaseStorageFile>()
        val resultDirectories = mutableListOf<String>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocumentId)

        Timber.d("Querying files in directory: $childrenUri")

        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use {
            while (it.moveToNext()) {
                val documentId = it.getString(0)
                val documentName = it.getString(1)
                val documentSize = it.getLong(2)
                val mimeType = it.getString(3)

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    resultDirectories.add(documentId)
                } else {
                    val documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            documentId,
                        )
                    resultFiles.add(
                        BaseStorageFile(
                            name = documentName,
                            size = documentSize,
                            uri = documentUri,
                            path = documentUri.path,
                        ),
                    )
                }
            }
        }

        return resultFiles to resultDirectories
    }

    override fun getGameRomFiles(
        game: Game,
        dataFiles: List<DataFile>,
        allowVirtualFiles: Boolean,
    ): RomFiles {
        val originalDocumentUri = Uri.parse(game.fileUri)
        val originalDocument = DocumentFile.fromSingleUri(context, originalDocumentUri)!!

        val forceExtractZip = originalDocument.isZipped() && game.systemId == SystemID.COLECO.dbname
        val isZipped = originalDocument.isZipped() && originalDocument.name != game.fileName
        val is7z = originalDocument.is7z()

        return when {
            is7z -> getGameRomFiles7z(game, dataFiles, originalDocument)
            forceExtractZip && dataFiles.isEmpty() -> getColecoRomFilesZipped(game, originalDocument)
            isZipped && dataFiles.isEmpty() -> getGameRomFilesZipped(game, originalDocument)
            allowVirtualFiles -> getGameRomFilesVirtual(game, dataFiles)
            else -> getGameRomFilesStandard(game, dataFiles, originalDocument)
        }
    }

    private fun getGameRomFilesStandard(
        game: Game,
        dataFiles: List<DataFile>,
        originalDocument: DocumentFile,
    ): RomFiles {
        val gameEntry = getGameRomStandard(game, originalDocument)
        if (game.systemId == SystemID.MAME2003PLUS.dbname) {
            cacheArcadeCompanionZips(originalDocument.uri, gameEntry.parentFile)
        }
        val dataEntries = dataFiles.map { getDataFileStandard(game, it) }
        return RomFiles.Standard(listOf(gameEntry) + dataEntries)
    }

    private fun cacheArcadeCompanionZips(
        gameDocumentUri: Uri,
        cacheDir: File?,
    ) {
        if (cacheDir == null) return
        val treeUri = getExternalFolder()?.let { Uri.parse(it) } ?: return
        runCatching {
            val docId = DocumentsContract.getDocumentId(gameDocumentUri)
            val parentDocId =
                if ('/' in docId) {
                    docId.substringBeforeLast('/')
                } else {
                    DocumentsContract.getTreeDocumentId(treeUri)
                }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val siblingDocId = cursor.getString(0)
                    val displayName = cursor.getString(1) ?: continue
                    val lowerName = displayName.lowercase()
                    if (lowerName in ARCADE_BIOS_ZIPS) {
                        val destFile = File(cacheDir, lowerName)
                        if (!destFile.exists() || destFile.length() == 0L) {
                            val siblingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, siblingDocId)
                            context.contentResolver.openInputStream(siblingUri)?.use { input ->
                                input.writeToFile(destFile)
                            }
                        }
                    } else if (lowerName in ARCADE_BIOS_7Z) {
                        val zipName = "${lowerName.substringBeforeLast('.')}.zip"
                        val destFile = File(cacheDir, zipName)
                        if (!destFile.exists() || destFile.length() == 0L) {
                            val siblingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, siblingDocId)
                            SevenZipUtils.openArchive(context, siblingUri).use { opened ->
                                SevenZipUtils.repackAllToZip(opened.archive, destFile)
                            }
                        }
                    }
                }
            }
        }.onFailure {
            Timber.e(it, "Error caching arcade companion zips")
        }
    }

    private fun getColecoRomFilesZipped(
        game: Game,
        originalDocument: DocumentFile,
    ): RomFiles {
        val baseCacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        val targetName = "${game.fileName.substringBeforeLast('.')}.col"
        val cacheFile = File(baseCacheFile.parentFile, targetName)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return RomFiles.Standard(listOf(cacheFile))
        }

        ZipInputStream(context.contentResolver.openInputStream(originalDocument.uri)).use { zis ->
            var entry = zis.nextEntry
            var fallbackEntryName: String? = null
            while (entry != null) {
                if (!entry.isDirectory) {
                    val lower = entry.name.lowercase()
                    if (lower.endsWith(".col") || lower.endsWith(".rom") || lower.endsWith(".bin") || lower.endsWith(".cv")) {
                        zis.writeToFile(cacheFile)
                        return RomFiles.Standard(listOf(cacheFile))
                    }
                    if (fallbackEntryName == null) {
                        fallbackEntryName = entry.name
                    }
                }
                entry = zis.nextEntry
            }
            if (fallbackEntryName != null) {
                ZipInputStream(context.contentResolver.openInputStream(originalDocument.uri)).use { zis2 ->
                    zis2.extractEntryToFile(fallbackEntryName, cacheFile)
                    return RomFiles.Standard(listOf(cacheFile))
                }
            }
        }

        return getGameRomFilesStandard(game, emptyList(), originalDocument)
    }

    private fun getGameRomFiles7z(
        game: Game,
        dataFiles: List<DataFile>,
        originalDocument: DocumentFile,
    ): RomFiles {
        val baseCacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        val cacheDir = baseCacheFile.parentFile!!
        val dataEntries = dataFiles.map { getDataFileStandard(game, it) }

        if (game.systemId == SystemID.COLECO.dbname) {
            val targetName = "${game.fileName.substringBeforeLast('.')}.col"
            val cacheFile = File(cacheDir, targetName)
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                SevenZipUtils.openArchive(context, originalDocument.uri).use { opened ->
                    val entries = SevenZipUtils.listEntries(opened.archive)
                    val targetEntry =
                        entries.firstOrNull {
                            val lower = it.fileName.lowercase()
                            lower.endsWith(".col") || lower.endsWith(".rom") || lower.endsWith(".bin") || lower.endsWith(".cv")
                        } ?: entries.firstOrNull { it.fileName == game.fileName }
                            ?: entries.firstOrNull()
                    if (targetEntry != null) {
                        SevenZipUtils.extractEntryToFile(opened.archive, targetEntry.index, cacheFile)
                    }
                }
            }
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                return RomFiles.Standard(listOf(cacheFile) + dataEntries)
            }
        }

        if (game.systemId == SystemID.MAME2003PLUS.dbname || game.systemId == SystemID.FBNEO.dbname) {
            val rawName = originalDocument.name ?: game.fileName
            val zipName = "${rawName.substringBeforeLast('.')}.zip"
            val cacheZip = File(cacheDir, zipName)
            cacheArcadeCompanionZips(originalDocument.uri, cacheDir)
            if (!cacheZip.exists() || cacheZip.length() == 0L) {
                SevenZipUtils.openArchive(context, originalDocument.uri).use { opened ->
                    SevenZipUtils.repackAllToZip(opened.archive, cacheZip)
                }
            }
            return RomFiles.Standard(listOf(cacheZip) + dataEntries)
        }

        val docName = originalDocument.name ?: ""
        if (game.fileName.endsWith(".cue", ignoreCase = true) && !docName.equals(game.fileName, ignoreCase = true)) {
            val archiveBase = docName.substringBeforeLast('.').ifBlank { game.fileName.substringBeforeLast('.') }
            val cueDir = File(cacheDir, archiveBase)
            var cueFile = File(cueDir, game.fileName)
            if (!cueFile.exists() || cueFile.length() == 0L) {
                SevenZipUtils.openArchive(context, originalDocument.uri).use { opened ->
                    SevenZipUtils.extractAllToDirectory(opened.archive, cueDir)
                }
                cueFile = cueDir.walkTopDown().firstOrNull { it.isFile && it.name.equals(game.fileName, ignoreCase = true) }
                    ?: cueFile
            }
            return RomFiles.Standard(listOf(cueFile) + dataEntries)
        }

        val cacheFile = File(cacheDir, File(game.fileName).name)
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            SevenZipUtils.openArchive(context, originalDocument.uri).use { opened ->
                val entries = SevenZipUtils.listEntries(opened.archive)
                val targetEntry =
                    entries.firstOrNull { it.fileName == game.fileName || it.rawPath == game.fileName }
                        ?: entries.singleOrNull()
                        ?: throw IllegalStateException("Entry ${game.fileName} not found in $docName")
                SevenZipUtils.extractEntryToFile(opened.archive, targetEntry.index, cacheFile)
            }
        }
        return RomFiles.Standard(listOf(cacheFile) + dataEntries)
    }

    private fun getGameRomFilesZipped(
        game: Game,
        originalDocument: DocumentFile,
    ): RomFiles {
        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            return RomFiles.Standard(listOf(cacheFile))
        }

        val stream =
            ZipInputStream(
                context.contentResolver.openInputStream(originalDocument.uri),
            )
        stream.extractEntryToFile(game.fileName, cacheFile)
        return RomFiles.Standard(listOf(cacheFile))
    }

    private fun getGameRomFilesVirtual(
        game: Game,
        dataFiles: List<DataFile>,
    ): RomFiles {
        val gameEntry = getGameRomVirtual(game)
        val dataEntries = dataFiles.map { getDataFileVirtual(it) }
        return RomFiles.Virtual(listOf(gameEntry) + dataEntries)
    }

    private fun getDataFileVirtual(dataFile: DataFile): RomFiles.Virtual.Entry {
        return RomFiles.Virtual.Entry(
            "$VIRTUAL_FILE_PATH/${dataFile.fileName}",
            context.contentResolver.openFileDescriptor(Uri.parse(dataFile.fileUri), "r")!!,
        )
    }

    private fun getDataFileStandard(
        game: Game,
        dataFile: DataFile,
    ): File {
        val cacheFile =
            GameCacheUtils.getDataFileForGame(
                SAF_CACHE_SUBFOLDER,
                context,
                game,
                dataFile,
            )

        if (cacheFile.exists()) {
            return cacheFile
        }

        val stream = context.contentResolver.openInputStream(Uri.parse(dataFile.fileUri))!!
        stream.writeToFile(cacheFile)
        return cacheFile
    }

    private fun getGameRomVirtual(game: Game): RomFiles.Virtual.Entry {
        return RomFiles.Virtual.Entry(
            "$VIRTUAL_FILE_PATH/${game.fileName}",
            context.contentResolver.openFileDescriptor(Uri.parse(game.fileUri), "r")!!,
        )
    }

    private fun getGameRomStandard(
        game: Game,
        originalDocument: DocumentFile,
    ): File {
        val cacheFile = GameCacheUtils.getCacheFileForGame(SAF_CACHE_SUBFOLDER, context, game)

        if (cacheFile.exists()) {
            return cacheFile
        }

        val stream = context.contentResolver.openInputStream(originalDocument.uri)!!
        stream.writeToFile(cacheFile)
        return cacheFile
    }

    override fun getInputStream(uri: Uri): InputStream? {
        return context.contentResolver.openInputStream(uri)
    }

    companion object {
        const val SAF_CACHE_SUBFOLDER = "storage-framework-games"
        private val ARCADE_BIOS_ZIPS =
            setOf(
                "cpzn1.zip",
                "cpzn2.zip",
                "coh1000c.zip",
                "coh1002c.zip",
                "coh3002c.zip",
                "qsound.zip",
                "neogeo.zip",
                "pgm.zip",
            )
        private val ARCADE_BIOS_7Z =
            setOf(
                "cpzn1.7z",
                "cpzn2.7z",
                "coh1000c.7z",
                "coh1002c.7z",
                "coh3002c.7z",
                "qsound.7z",
                "neogeo.7z",
                "pgm.7z",
            )
        const val VIRTUAL_FILE_PATH = "/virtual/file/path"
    }
}
