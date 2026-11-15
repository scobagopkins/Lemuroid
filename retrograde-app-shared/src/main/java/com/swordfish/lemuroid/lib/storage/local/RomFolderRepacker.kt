package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.common.kotlin.is7z
import com.swordfish.lemuroid.common.kotlin.isZipped
import com.swordfish.lemuroid.lib.R
import com.swordfish.lemuroid.lib.preferences.SharedPreferencesHelper
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Safely converts archives inside the user's configured Local Storage ROM folder so
 * emulators can launch games directly with zero runtime cache writes:
 *   - Arcade / BIOS .7z -> flat .zip
 *   - Coleco .7z / .zip -> extracted <base>.col
 *   - Single-ROM console .7z (and unmistakable single-ROM console .zip) -> extracted ROM
 *   - Multi-file .cue/.bin .7z -> extracted <base>/ subfolder
 *
 * Every output is written to a temporary .part path and verified before replacing/deleting
 * the source archive; colliding target filenames are skipped without deleting the source.
 */
class RomFolderRepacker(
    private val context: Context,
    private val directoriesManager: DirectoriesManager,
) {
    data class RepackSummary(
        val converted: Int,
        val skipped: Int,
        val failed: Int,
        val safUnsupported: Boolean = false,
    )

    suspend fun repackAll(
        onProgress: suspend (completed: Int, total: Int, currentFile: String) -> Unit = { _, _, _ -> },
    ): RepackSummary =
        withContext(Dispatchers.IO) {
            val rootDir = resolveWritableLocalRoot(context, directoriesManager)
            if (rootDir == null) {
                return@withContext RepackSummary(0, 0, 0, safUnsupported = true)
            }

            val candidates =
                rootDir.walkTopDown()
                    .onEnter { dir -> !dir.name.startsWith(".") }
                    .filter { file ->
                        file.isFile &&
                            !file.name.startsWith(".") &&
                            (file.is7z() || file.isZipped())
                    }
                    .toList()

            if (candidates.isEmpty()) {
                return@withContext RepackSummary(0, 0, 0)
            }

            val total = candidates.size
            var converted = 0
            var skipped = 0
            var failed = 0

            for ((index, archiveFile) in candidates.withIndex()) {
                coroutineContext.ensureActive()
                onProgress(index, total, archiveFile.name)

                val outcome = runCatching { repackSingleArchive(archiveFile) }.getOrElse { err ->
                    Timber.w(err, "Failed repacking ${archiveFile.absolutePath}")
                    FileOutcome.FAILED
                }
                when (outcome) {
                    FileOutcome.CONVERTED -> converted++
                    FileOutcome.SKIPPED -> skipped++
                    FileOutcome.FAILED -> failed++
                }
            }

            onProgress(total, total, "")
            RepackSummary(converted, skipped, failed)
        }

    // Root resolution shared via companion object below.

    private fun repackSingleArchive(file: File): FileOutcome {
        return when {
            file.is7z() -> repackSevenZip(file)
            file.isZipped() -> repackZipIfConsoleOnly(file)
            else -> FileOutcome.SKIPPED
        }
    }

    private fun repackSevenZip(file: File): FileOutcome {
        val parentDir = file.parentFile ?: return FileOutcome.SKIPPED
        val baseName = file.nameWithoutExtension
        val lowerPath = file.absolutePath.lowercase().replace('\\', '/')
        val isColecoDir = lowerPath.contains("coleco") || lowerPath.contains("vcol")
        val isArcadeDir = ArcadeCompanionUtils.isArcadePath(lowerPath)

        return SevenZipUtils.openArchive(file).use { opened ->
            val entries = SevenZipUtils.listEntries(opened.archive)
            if (entries.isEmpty()) return FileOutcome.SKIPPED

            if (ArcadeCompanionUtils.isArcadeBiosFile(file.name)) {
                return convert7zToFlatZip(opened.archive, file, File(parentDir, "$baseName.zip"))
            }

            val nonDocEntries = filterNonDocEntries(entries)

            val cueEntry = nonDocEntries.firstOrNull { it.fileName.endsWith(".cue", ignoreCase = true) }
            if (cueEntry != null && nonDocEntries.size > 1) {
                val targetDir = File(parentDir, baseName)
                if (targetDir.exists()) return FileOutcome.SKIPPED
                val partDir = File(parentDir, "$baseName.part")
                partDir.deleteRecursively()
                return try {
                    SevenZipUtils.extractAllToDirectory(opened.archive, partDir)
                    val extractedCue =
                        partDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".cue", ignoreCase = true) }
                    if (extractedCue == null || !partDir.renameTo(targetDir)) {
                        partDir.deleteRecursively()
                        FileOutcome.FAILED
                    } else {
                        deleteSourceSafely(file)
                        FileOutcome.CONVERTED
                    }
                } catch (t: Throwable) {
                    partDir.deleteRecursively()
                    throw t
                }
            }

            val singleEntry = if (nonDocEntries.size == 1) nonDocEntries.first() else null
            if (singleEntry != null) {
                val ext = singleEntry.fileName.substringAfterLast('.', "").lowercase()
                val isColecoRom = isColecoDir || ext in COLECO_EXTENSIONS
                if (isColecoRom) {
                    val targetCol = File(parentDir, "$baseName.col")
                    return extractVerified7zEntry(opened.archive, singleEntry, file, targetCol)
                }

                val isConsoleSingle =
                    (ext in ArcadeCompanionUtils.UNMISTAKABLE_CONSOLE_EXTENSIONS) ||
                        (!isArcadeDir && ext in SINGLE_DISC_EXTENSIONS)
                if (isConsoleSingle && ext.isNotEmpty()) {
                    val targetRom = File(parentDir, "$baseName.$ext")
                    return extractVerified7zEntry(opened.archive, singleEntry, file, targetRom)
                }
            }

            // Arcade ROMset or multi-chip archive -> flat .zip
            return convert7zToFlatZip(opened.archive, file, File(parentDir, "$baseName.zip"))
        }
    }

    private fun extractVerified7zEntry(
        archive: net.sf.sevenzipjbinding.IInArchive,
        entry: SevenZipEntryInfo,
        source7z: File,
        targetFile: File,
    ): FileOutcome {
        if (targetFile.exists()) return FileOutcome.SKIPPED
        SevenZipUtils.extractEntryToFile(archive, entry.index, targetFile)
        val valid =
            targetFile.isFile &&
                targetFile.length() > 0L &&
                (entry.size <= 0L || targetFile.length() == entry.size)
        if (!valid) {
            targetFile.delete()
            return FileOutcome.FAILED
        }
        deleteSourceSafely(source7z)
        return FileOutcome.CONVERTED
    }

    private fun convert7zToFlatZip(
        archive: net.sf.sevenzipjbinding.IInArchive,
        source7z: File,
        targetZip: File,
    ): FileOutcome {
        if (targetZip.exists()) return FileOutcome.SKIPPED
        SevenZipUtils.repackAllToZip(archive, targetZip)
        if (!ArcadeCompanionUtils.isValidFlatZip(targetZip)) {
            targetZip.delete()
            return FileOutcome.FAILED
        }
        deleteSourceSafely(source7z)
        return FileOutcome.CONVERTED
    }

    private fun repackZipIfConsoleOnly(file: File): FileOutcome {
        if (ArcadeCompanionUtils.isArcadeBiosFile(file.name)) return FileOutcome.SKIPPED
        val parentDir = file.parentFile ?: return FileOutcome.SKIPPED
        val baseName = file.nameWithoutExtension
        val lowerPath = file.absolutePath.lowercase().replace('\\', '/')
        if (ArcadeCompanionUtils.isArcadePath(lowerPath)) return FileOutcome.SKIPPED

        val isColecoDir = lowerPath.contains("coleco") || lowerPath.contains("vcol")
        val entries = inspectZipEntries(file) ?: return FileOutcome.SKIPPED
        val nonDoc = filterNonDocZipEntries(entries)
        if (nonDoc.size != 1) return FileOutcome.SKIPPED

        val only = nonDoc.first()
        val ext = only.name.substringAfterLast('/', only.name).substringAfterLast('.', "").lowercase()
        val targetExt =
            when {
                isColecoDir || ext in COLECO_EXTENSIONS -> "col"
                ext in ArcadeCompanionUtils.UNMISTAKABLE_CONSOLE_EXTENSIONS -> ext
                else -> return FileOutcome.SKIPPED
            }

        val targetFile = File(parentDir, "$baseName.$targetExt")
        if (targetFile.exists()) return FileOutcome.SKIPPED

        val partFile = File(parentDir, "${targetFile.name}.part")
        partFile.delete()
        return try {
            var copiedBytes = 0L
            ZipInputStream(file.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == only.name) {
                        BufferedOutputStream(FileOutputStream(partFile), 64 * 1024).use { bos ->
                            copiedBytes = zis.copyTo(bos)
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            val expected = only.size
            if (!partFile.isFile || copiedBytes <= 0L || (expected > 0L && copiedBytes != expected)) {
                partFile.delete()
                return FileOutcome.FAILED
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            deleteSourceSafely(file)
            FileOutcome.CONVERTED
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
    }

    private fun inspectZipEntries(file: File): List<ZipEntrySummary>? {
        val list = mutableListOf<ZipEntrySummary>()
        var tooMany = false
        return runCatching {
            ZipInputStream(file.inputStream()).use { zis ->
                var e: ZipEntry? = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        list.add(ZipEntrySummary(e.name, e.size))
                        if (list.size > 24) {
                            tooMany = true
                            break
                        }
                    }
                    e = zis.nextEntry
                }
            }
            if (tooMany) null else list
        }.getOrNull()
    }

    private fun filterNonDocEntries(entries: List<SevenZipEntryInfo>): List<SevenZipEntryInfo> {
        return entries.filterNot { isDocOrImage(it.fileName, it.size) }.ifEmpty { entries }
    }

    private fun filterNonDocZipEntries(entries: List<ZipEntrySummary>): List<ZipEntrySummary> {
        return entries.filterNot { isDocOrImage(it.name, it.size) }.ifEmpty { entries }
    }

    private fun isDocOrImage(
        fileName: String,
        size: Long,
    ): Boolean {
        val base = fileName.substringAfterLast('/')
        if (base.endsWith(".md", ignoreCase = true) && size >= 64L * 1024L) {
            return false
        }
        return ArcadeCompanionUtils.isIgnoredArchiveDocOrImage(base)
    }

    private fun deleteSourceSafely(file: File) {
        if (!file.delete()) {
            Timber.w("Converted archive cleanly, but could not delete original: ${file.absolutePath}")
        }
    }

    private data class ZipEntrySummary(
        val name: String,
        val size: Long,
    )

    private enum class FileOutcome {
        CONVERTED,
        SKIPPED,
        FAILED,
    }

    companion object {
        fun resolveWritableLocalRoot(
            context: Context,
            directoriesManager: DirectoriesManager,
        ): File? {
            val prefs = SharedPreferencesHelper.getLegacySharedPreferences(context)
            val legacyPath = prefs.getString(context.getString(R.string.pref_key_legacy_external_folder), null)
            if (!legacyPath.isNullOrBlank()) {
                val dir = File(legacyPath)
                return if (dir.isDirectory && dir.canWrite()) dir else null
            }

            val safValue = prefs.getString(context.getString(R.string.pref_key_extenral_folder), null)
            if (!safValue.isNullOrBlank() && safValue.startsWith("content://")) {
                return null
            }

            val internal = directoriesManager.getInternalRomsDirectory()
            return if (internal.isDirectory && internal.canWrite()) internal else null
        }

        private val COLECO_EXTENSIONS = setOf("col", "rom", "cv")
        private val SINGLE_DISC_EXTENSIONS = setOf("iso", "chd", "mdf")
    }
}
