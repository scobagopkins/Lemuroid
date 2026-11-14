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
    private const val MAX_CHECKED_ENTRIES = 3
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
        val inputStream = context.contentResolver.openInputStream(baseStorageFile.uri)
        return ZipInputStream(inputStream).use {
            val gameEntry = findGameEntry(it, baseStorageFile.size)
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
        return runCatching {
            SevenZipUtils.openArchive(context, baseStorageFile.uri).use { opened ->
                val entries = SevenZipUtils.listEntries(opened.archive)
                val totalSize = entries.sumOf { it.size.coerceAtLeast(0L) }
                val gameEntry = find7zGameEntry(entries, totalSize)
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
    ): SevenZipEntryInfo? {
        if (entries.isEmpty()) return null
        if (entries.size == 1) return entries.first()
        if (totalSize <= 0L) return null
        return entries.firstOrNull { entry ->
            entry.size > 0L && (entry.size.toFloat() / totalSize.toFloat()) > SINGLE_ARCHIVE_THRESHOLD
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
    ): ZipEntry? {
        for (i in 0..MAX_CHECKED_ENTRIES) {
            val entry = openedInputStream.nextEntry ?: break
            if (!isGameEntry(entry, fileSize)) continue
            return entry
        }
        return null
    }

    private fun isGameEntry(
        entry: ZipEntry,
        fileSize: Long,
    ): Boolean {
        if (fileSize <= 0 || entry.compressedSize <= 0) return false
        return (entry.compressedSize.toFloat() / fileSize.toFloat()) > SINGLE_ARCHIVE_THRESHOLD
    }
}
