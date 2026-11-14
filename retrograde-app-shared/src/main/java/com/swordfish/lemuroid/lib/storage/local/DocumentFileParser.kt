package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import com.swordfish.lemuroid.common.kotlin.calculateCrc32
import com.swordfish.lemuroid.common.kotlin.toStringCRC32
import com.swordfish.lemuroid.lib.storage.BaseStorageFile
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.lib.storage.scanner.SerialScanner
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object DocumentFileParser {
    private const val MAX_CHECKED_ENTRIES = 16
    private const val SINGLE_ARCHIVE_THRESHOLD = 0.9
    private const val MAX_SIZE_CRC32 = 1_000_000_000
    private val SERIAL_SCAN_EXTENSIONS = setOf("pbp", "iso", "bin", "3ds")

    fun parseDocumentFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        return when (baseStorageFile.extension.lowercase()) {
            "zip" -> {
                Timber.d("Detected zip file. ${baseStorageFile.name}")
                parseZipFile(context, baseStorageFile)
            }

            "7z" -> {
                Timber.d("Detected 7z file. ${baseStorageFile.name}")
                parse7zFile(context, baseStorageFile)
            }

            else -> {
                Timber.d("Detected standard file. ${baseStorageFile.name}")
                parseStandardFile(context, baseStorageFile)
            }
        }
    }

    private fun parseZipFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        if (ArcadeCompanionUtils.isArcadeBiosFile(baseStorageFile.name)) {
            return StorageFile(
                baseStorageFile.name,
                baseStorageFile.size,
                null,
                null,
                baseStorageFile.uri,
                baseStorageFile.uri.path,
                null,
            )
        }
        val inputStream = context.contentResolver.openInputStream(baseStorageFile.uri)
        return ZipInputStream(inputStream).use {
            val gameEntry =
                findGameEntry(
                    it,
                    baseStorageFile.size,
                    baseStorageFile.name,
                    baseStorageFile.uri.path ?: "",
                )
            if (gameEntry != null) {
                Timber.d("Handing zip file as compressed game: ${baseStorageFile.name}")
                parseCompressedGame(baseStorageFile, gameEntry, it)
            } else {
                Timber.d("Handing zip file as standard: ${baseStorageFile.name}")
                parseStandardFile(context, baseStorageFile)
            }
        }
    }

    private fun parse7zFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        if (ArcadeCompanionUtils.isArcadeBiosFile(baseStorageFile.name)) {
            return StorageFile(
                baseStorageFile.name,
                baseStorageFile.size,
                null,
                null,
                baseStorageFile.uri,
                baseStorageFile.uri.path,
                null,
            )
        }
        return runCatching {
            SevenZipUtils.openArchive(context, baseStorageFile.uri).use { opened ->
                val entries = SevenZipUtils.listEntries(opened.archive)
                val totalSize = entries.sumOf { it.size.coerceAtLeast(0L) }
                val gameEntry =
                    find7zGameEntry(
                        entries,
                        totalSize,
                        baseStorageFile.name,
                        baseStorageFile.uri.path ?: "",
                    )
                if (gameEntry != null) {
                    Timber.d("Handling 7z file as compressed game: ${gameEntry.fileName}")
                    val diskInfo = extractSerialFrom7z(opened.archive, gameEntry)
                    StorageFile(
                        gameEntry.fileName,
                        gameEntry.size.coerceAtLeast(0L),
                        gameEntry.crcHex,
                        diskInfo.serial,
                        baseStorageFile.uri,
                        baseStorageFile.uri.path,
                        diskInfo.systemID,
                    )
                } else {
                    val cueEntry =
                        entries
                            .filter { it.fileName.endsWith(".cue", ignoreCase = true) }
                            .minByOrNull { it.rawPath.count { ch -> ch == '/' } }

                    if (cueEntry != null) {
                        val firstBin = entries.firstOrNull { it.fileName.endsWith(".bin", ignoreCase = true) }
                        val diskInfo =
                            if (firstBin != null) {
                                extractSerialFrom7z(opened.archive, firstBin)
                            } else {
                                SerialScanner.DiskInfo(null, null)
                            }
                        Timber.d("Handling 7z file as CUE archive: ${cueEntry.fileName}")
                        StorageFile(
                            cueEntry.fileName,
                            totalSize,
                            null,
                            diskInfo.serial,
                            baseStorageFile.uri,
                            baseStorageFile.uri.path,
                            diskInfo.systemID,
                        )
                    } else {
                        Timber.d("Handling 7z file as multi-entry/standard: ${baseStorageFile.name}")
                        StorageFile(
                            baseStorageFile.name,
                            baseStorageFile.size,
                            null,
                            null,
                            baseStorageFile.uri,
                            baseStorageFile.uri.path,
                            null,
                        )
                    }
                }
            }
        }.getOrElse { error ->
            Timber.w(error, "Failed parsing 7z archive ${baseStorageFile.name}, falling back to standard")
            StorageFile(
                baseStorageFile.name,
                baseStorageFile.size,
                null,
                null,
                baseStorageFile.uri,
                baseStorageFile.uri.path,
                null,
            )
        }
    }

    private fun extractSerialFrom7z(
        archive: net.sf.sevenzipjbinding.IInArchive,
        entry: SevenZipEntryInfo,
    ): SerialScanner.DiskInfo {
        val ext = entry.fileName.substringAfterLast('.', "").lowercase()
        if (ext !in SERIAL_SCAN_EXTENSIONS) {
            return SerialScanner.DiskInfo(null, null)
        }
        val maxBytes = if (ext == "pbp") 2 * 1024 * 1024 else 64 * 1024
        return runCatching {
            val prefix = SevenZipUtils.extractEntryPrefix(archive, entry.index, maxBytes)
            SerialScanner.extractInfo(entry.fileName, ByteArrayInputStream(prefix))
        }.getOrDefault(SerialScanner.DiskInfo(null, null))
    }

    private fun find7zGameEntry(
        entries: List<SevenZipEntryInfo>,
        totalSize: Long,
        archiveName: String = "",
        archivePath: String = "",
    ): SevenZipEntryInfo? {
        if (entries.isEmpty() || ArcadeCompanionUtils.isArcadeBiosFile(archiveName)) return null
        val nonDocEntries =
            entries.filterNot { ArcadeCompanionUtils.isIgnoredArchiveDocOrImage(it.fileName) }
                .ifEmpty { entries }
        if (nonDocEntries.size > 1 && nonDocEntries.any { it.fileName.endsWith(".cue", ignoreCase = true) }) {
            return null
        }
        val arcadePath = ArcadeCompanionUtils.isArcadePath(archivePath)
        if (nonDocEntries.size == 1) {
            val only = nonDocEntries.first()
            val ext = only.fileName.substringAfterLast('.', "").lowercase()
            if (arcadePath) {
                return if (ext in ArcadeCompanionUtils.UNMISTAKABLE_CONSOLE_EXTENSIONS) only else null
            }
            return if (
                ext in ArcadeCompanionUtils.UNMISTAKABLE_CONSOLE_EXTENSIONS ||
                ext in setOf("bin", "rom", "img", "mdf")
            ) {
                only
            } else {
                null
            }
        }
        val effectiveTotal = nonDocEntries.sumOf { it.size.coerceAtLeast(0L) }.takeIf { it > 0L } ?: totalSize
        if (effectiveTotal <= 0L) return null
        return nonDocEntries.firstOrNull { entry ->
            entry.size > 0L &&
                (entry.size.toFloat() / effectiveTotal.toFloat()) > SINGLE_ARCHIVE_THRESHOLD &&
                ArcadeCompanionUtils.isUnmistakableConsoleFile(entry.fileName)
        }
    }

    private fun parseCompressedGame(
        baseStorageFile: BaseStorageFile,
        entry: ZipEntry,
        zipInputStream: ZipInputStream,
    ): StorageFile {
        Timber.d("Processing zipped entry: ${entry.name}")

        val diskInfo = SerialScanner.extractInfo(entry.name, zipInputStream)

        return StorageFile(
            entry.name,
            entry.size,
            entry.crc.toStringCRC32(),
            diskInfo.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo.systemID,
        )
    }

    private fun parseStandardFile(
        context: Context,
        baseStorageFile: BaseStorageFile,
    ): StorageFile {
        val diskInfo =
            context.contentResolver.openInputStream(baseStorageFile.uri)
                ?.let { inputStream -> SerialScanner.extractInfo(baseStorageFile.name, inputStream) }

        val crc32 =
            if (baseStorageFile.size < MAX_SIZE_CRC32 && diskInfo?.serial == null) {
                context.contentResolver.openInputStream(baseStorageFile.uri)?.calculateCrc32()
            } else {
                null
            }

        Timber.d("Parsed standard file: $baseStorageFile")

        return StorageFile(
            baseStorageFile.name,
            baseStorageFile.size,
            crc32,
            diskInfo?.serial,
            baseStorageFile.uri,
            baseStorageFile.uri.path,
            diskInfo?.systemID,
        )
    }

    /* Finds a zip entry which we assume is a game. Lemuroid only supports single archive games,
       so we are looking for an entry which occupies a large percentage of the archive space.
       This is very fast heuristic to compute and avoids reading the whole stream in most
       scenarios.*/
    fun findGameEntry(
        openedInputStream: ZipInputStream,
        fileSize: Long = -1,
        archiveName: String = "",
        archivePath: String = "",
    ): ZipEntry? {
        if (ArcadeCompanionUtils.isArcadeBiosFile(archiveName)) return null
        val arcadePath = ArcadeCompanionUtils.isArcadePath(archivePath)
        for (i in 0..MAX_CHECKED_ENTRIES) {
            val entry = openedInputStream.nextEntry ?: break
            if (entry.isDirectory || ArcadeCompanionUtils.isIgnoredArchiveDocOrImage(entry.name)) continue
            if (!isGameEntry(entry, fileSize, arcadePath)) continue
            return entry
        }
        return null
    }

    private fun isGameEntry(
        entry: ZipEntry,
        fileSize: Long,
        arcadePath: Boolean = false,
    ): Boolean {
        if (fileSize <= 0 || entry.compressedSize <= 0) return false
        if ((entry.compressedSize.toFloat() / fileSize.toFloat()) <= SINGLE_ARCHIVE_THRESHOLD) return false
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        if (arcadePath) {
            return ext in ArcadeCompanionUtils.UNMISTAKABLE_CONSOLE_EXTENSIONS
        }
        return ext in ArcadeCompanionUtils.UNMISTAKABLE_CONSOLE_EXTENSIONS ||
            ext in setOf("bin", "rom", "img", "mdf")
    }
}
