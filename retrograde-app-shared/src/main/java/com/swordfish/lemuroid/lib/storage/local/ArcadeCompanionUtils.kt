package com.swordfish.lemuroid.lib.storage.local

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

object ArcadeCompanionUtils {
    val ALL_ARCADE_BIOS_BASES: Set<String> =
        setOf(
            "neogeo",
            "neocdz",
            "qsound",
            "qsound_hle",
            "pgm",
            "skns",
            "decocass",
            "stvbios",
            "isgsm",
            "aleck64",
            "playch10",
            "nss",
            "vssystem",
            "megaplay",
            "megatech",
            "segac2",
            "coh1000a",
            "coh1000c",
            "coh1000t",
            "coh1000w",
            "coh1001l",
            "coh1002c",
            "coh1002m",
            "coh3002c",
            "coh3002t",
            "psarc95",
            "cpzn1",
            "cpzn2",
            "acpsx",
            "zn1",
            "zn2",
            "konamigv",
            "konamigx",
            "sys246",
            "sys573",
            "taitofx1",
            "taitogn",
            "taitopjc",
            "bubsys",
            "namco50",
            "namco51",
            "namco52",
            "namco53",
            "namco54",
            "namcoc69",
            "namcoc70",
            "namcoc75",
            "namcoc76",
            "nmik0016",
            "atarisy1",
            "kinst",
            "ar_bios",
            "crysbios",
            "maxforce",
            "s32",
            "hikaru",
            "naomi",
            "awbios",
            "hng64",
            "suprnova",
            "macsbios",
            "shtzone",
            "tourvis",
            "galgbios",
            "jvs13551",
            "galpanic",
        )

    val UNMISTAKABLE_CONSOLE_EXTENSIONS: Set<String> =
        setOf(
            "nes",
            "fds",
            "unf",
            "unif",
            "sfc",
            "smc",
            "fig",
            "swc",
            "gb",
            "gbc",
            "sgb",
            "gba",
            "agb",
            "z64",
            "n64",
            "v64",
            "ndd",
            "nds",
            "dsi",
            "3ds",
            "cia",
            "md",
            "smd",
            "gen",
            "32x",
            "sms",
            "gg",
            "sg",
            "pce",
            "sgx",
            "a26",
            "a52",
            "a78",
            "lnx",
            "lyx",
            "ngp",
            "ngc",
            "ws",
            "wsc",
            "col",
            "cv",
            "pbp",
            "iso",
            "cso",
            "chd",
            "cue",
        )

    val IGNORED_ARCHIVE_ENTRY_EXTENSIONS: Set<String> =
        setOf(
            "txt",
            "nfo",
            "md",
            "diz",
            "pdf",
            "xml",
            "dat",
            "url",
            "htm",
            "html",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "bmp",
            "webp",
        )

    private val BIOS_SUBDIRS =
        listOf(
            "",
            "bios",
            "BIOS",
            "system",
            "System",
            "system/fbneo",
            "system/mame2003-plus",
            "system/mame2003",
            "bios/fbneo",
            "bios/mame",
            "fbneo",
            "FBNeo",
            "fba",
            "mame",
            "MAME",
            "mame2003",
            "mame2003plus",
            "arcade",
            "Arcade",
            "neogeo",
            "NeoGeo",
            "zn",
            "RetroArch/system",
            "RetroArch/system/fbneo",
            "RetroArch/downloads",
        )

    private val SAF_BIOS_DIR_TOKENS =
        setOf(
            "bios",
            "system",
            "fbneo",
            "fba",
            "mame",
            "mame2003",
            "mame2003plus",
            "mame2003-plus",
            "arcade",
            "neogeo",
            "neo-geo",
            "zn",
            "retroarch",
            "roms",
        )

    fun isArcadeSystemId(systemId: String?): Boolean {
        return systemId == SystemID.FBNEO.dbname || systemId == SystemID.MAME2003PLUS.dbname
    }

    fun isArcadeBiosFile(fileName: String?): Boolean {
        val lower = fileName?.lowercase()?.trim() ?: return false
        if (!lower.endsWith(".zip") && !lower.endsWith(".7z")) return false
        return lower.substringBeforeLast('.') in ALL_ARCADE_BIOS_BASES
    }

    fun isArcadePath(path: String?): Boolean {
        val normalized = path?.lowercase()?.replace('\\', '/') ?: return false
        return normalized.contains("fbneo") ||
            normalized.contains("/fba/") ||
            normalized.endsWith("/fba") ||
            normalized.startsWith("fba/") ||
            normalized.contains("mame") ||
            normalized.contains("arcade") ||
            normalized.contains("neogeo") ||
            normalized.contains("neo-geo") ||
            normalized.contains("cps1") ||
            normalized.contains("cps2") ||
            normalized.contains("cps3") ||
            normalized.contains("/zn/") ||
            normalized.contains("zn1") ||
            normalized.contains("zn2")
    }

    fun isUnmistakableConsoleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in UNMISTAKABLE_CONSOLE_EXTENSIONS
    }

    fun isIgnoredArchiveDocOrImage(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IGNORED_ARCHIVE_ENTRY_EXTENSIONS
    }

    fun getSystemArcadeDirs(systemBaseDir: File): List<File> {
        return listOf(
            File(systemBaseDir, "fbneo"),
            File(systemBaseDir, "mame2003-plus"),
            File(systemBaseDir, "mame2003"),
            systemBaseDir,
        )
    }

    fun isValidZip(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 22L) return false
        return runCatching {
            ZipFile(file).use { zip -> zip.entries().hasMoreElements() }
        }.getOrDefault(false)
    }

    fun isValidFlatZip(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.length() <= 22L) return false
        return runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                if (!entries.hasMoreElements()) return@use false
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name.contains('/') || name.contains('\\')) return@use false
                }
                true
            }
        }.getOrDefault(false)
    }

    fun copyFileAtomic(
        src: File,
        dst: File,
        timestampMs: Long = src.lastModified(),
    ) {
        if (src.canonicalFile == dst.canonicalFile) return
        dst.parentFile?.mkdirs()
        val part = File(dst.parentFile, "${dst.name}.part")
        part.delete()
        try {
            src.inputStream().use { input ->
                FileOutputStream(part).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (dst.exists()) dst.delete()
            if (!part.renameTo(dst)) {
                part.copyTo(dst, overwrite = true)
                part.delete()
            }
            if (timestampMs > 0L) {
                dst.setLastModified(timestampMs)
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    fun stageLocalZipOrSevenZip(
        srcFile: File,
        targetZipFiles: List<File>,
    ) {
        if (!srcFile.exists() || !srcFile.isFile || srcFile.length() == 0L) return
        val isSevenZ = srcFile.extension.equals("7z", ignoreCase = true)
        val srcMod = srcFile.lastModified()
        val uniqueTargets = targetZipFiles.distinctBy { it.absolutePath }

        val outdated =
            uniqueTargets.filter { dst ->
                if (isSevenZ) {
                    !isValidFlatZip(dst) || (srcMod > 0L && dst.lastModified() < srcMod)
                } else {
                    !isValidZip(dst) ||
                        dst.length() != srcFile.length() ||
                        (srcMod > 0L && dst.lastModified() < srcMod)
                }
            }
        if (outdated.isEmpty()) return

        val firstTarget = outdated.first()
        runCatching {
            if (isSevenZ) {
                SevenZipUtils.repackFileToZip(srcFile, firstTarget)
                if (srcMod > 0L) firstTarget.setLastModified(srcMod)
            } else {
                if (!isValidZip(srcFile)) return
                copyFileAtomic(srcFile, firstTarget, srcMod)
            }
        }.onSuccess {
            outdated.drop(1).forEach { nextTarget ->
                runCatching { copyFileAtomic(firstTarget, nextTarget, srcMod) }
            }
        }
    }

    fun stageStreamBios(
        fileName: String,
        inputStream: InputStream,
        sourceTimestampMs: Long,
        systemBaseDir: File,
    ): Boolean {
        if (!isArcadeBiosFile(fileName)) return false
        val lower = fileName.lowercase()
        val base = lower.substringBeforeLast('.')
        val isSevenZ = lower.endsWith(".7z")
        val ext = if (isSevenZ) ".7z" else ".zip"

        val targets = getSystemArcadeDirs(systemBaseDir).map { File(it, "$base.zip") }
        val tempDir = File(systemBaseDir, "_tmp_bios").apply { mkdirs() }
        val tmpFile = File.createTempFile("bios_${base}_", ext, tempDir)
        return try {
            FileOutputStream(tmpFile).use { out ->
                inputStream.copyTo(out, bufferSize = 64 * 1024)
            }
            if (sourceTimestampMs > 0L) {
                tmpFile.setLastModified(sourceTimestampMs)
            }
            stageLocalZipOrSevenZip(tmpFile, targets)
            true
        } finally {
            tmpFile.delete()
        }
    }

    private fun collectLocalSearchDirs(
        gameDir: File,
        configuredRootDirs: List<File>,
    ): List<File> {
        val result = LinkedHashMap<String, File>()
        fun addDir(dir: File?) {
            if (dir != null && dir.exists() && dir.isDirectory) {
                val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                result.putIfAbsent(key, dir)
            }
        }

        addDir(gameDir)
        addDir(gameDir.parentFile)
        addDir(gameDir.parentFile?.parentFile)

        val extRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        val roots =
            (configuredRootDirs +
                listOfNotNull(
                    gameDir.parentFile,
                    gameDir.parentFile?.parentFile,
                    extRoot?.let { File(it, "!D/!RETROARCH") },
                    extRoot?.let { File(it, "RetroArch") },
                    extRoot?.let { File(it, "Lemuroid") },
                )).distinctBy { it.absolutePath }

        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            for (sub in BIOS_SUBDIRS) {
                addDir(if (sub.isEmpty()) root else File(root, sub))
            }
            runCatching { root.listFiles() }.getOrNull()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.take(64)
                ?.forEach { child ->
                    addDir(child)
                    val lower = child.name.lowercase()
                    if (lower in SAF_BIOS_DIR_TOKENS || lower.contains("bios") || lower.contains("system")) {
                        runCatching { child.listFiles() }.getOrNull()
                            ?.filter { it.isDirectory }
                            ?.take(24)
                            ?.forEach { addDir(it) }
                    }
                }
        }
        return result.values.toList()
    }

    fun stageLocalArcadeCompanions(
        game: Game,
        gameDir: File,
        cacheGameDir: File?,
        systemBaseDir: File,
        configuredRootDirs: List<File> = emptyList(),
    ): Boolean {
        if (!isArcadeSystemId(game.systemId)) return false

        val searchDirs = collectLocalSearchDirs(gameDir, configuredRootDirs)
        val systemTargets = getSystemArcadeDirs(systemBaseDir)
        val gameBase = File(game.fileName).nameWithoutExtension.lowercase()

        val foundBiosFiles = LinkedHashMap<String, File>()
        val foundSplitParents = LinkedHashMap<String, File>()

        for (dir in searchDirs) {
            val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue
            val isNearbyDir =
                dir.absolutePath == gameDir.absolutePath ||
                    dir.absolutePath == gameDir.parentFile?.absolutePath
            for (entry in entries) {
                if (!entry.isFile || entry.length() <= 22L) continue
                val lower = entry.name.lowercase()
                if (!lower.endsWith(".zip") && !lower.endsWith(".7z")) continue
                val stem = lower.substringBeforeLast('.')
                if (stem in ALL_ARCADE_BIOS_BASES) {
                    val existing = foundBiosFiles[stem]
                    if (existing == null || (existing.extension.equals("7z", true) && lower.endsWith(".zip"))) {
                        foundBiosFiles[stem] = entry
                    }
                } else if (
                    isNearbyDir &&
                    stem.length >= 2 &&
                    stem != gameBase &&
                    gameBase.startsWith(stem) &&
                    foundSplitParents.size < 6
                ) {
                    val existing = foundSplitParents[stem]
                    if (existing == null || (existing.extension.equals("7z", true) && lower.endsWith(".zip"))) {
                        foundSplitParents[stem] = entry
                    }
                }
            }
        }

        for ((stem, srcFile) in foundBiosFiles) {
            val targets = ArrayList<File>()
            cacheGameDir?.let { targets.add(File(it, "$stem.zip")) }
            systemTargets.forEach { targets.add(File(it, "$stem.zip")) }
            stageLocalZipOrSevenZip(srcFile, targets)
        }

        var needsCachePromotion = false
        for ((stem, srcFile) in foundSplitParents) {
            if (cacheGameDir != null) {
                val targets = ArrayList<File>()
                targets.add(File(cacheGameDir, "$stem.zip"))
                systemTargets.forEach { targets.add(File(it, "$stem.zip")) }
                stageLocalZipOrSevenZip(srcFile, targets)
            } else {
                val targets = systemTargets.map { File(it, "$stem.zip") }
                stageLocalZipOrSevenZip(srcFile, targets)
                if (srcFile.extension.equals("7z", true) || srcFile.parentFile?.absolutePath != gameDir.absolutePath) {
                    needsCachePromotion = true
                }
            }
        }

        return needsCachePromotion
    }

    private data class SafEntry(
        val docId: String,
        val name: String,
        val mimeType: String,
        val lastModified: Long,
        val size: Long,
    )

    private fun querySafChildren(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
    ): List<SafEntry> {
        val result = ArrayList<SafEntry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val lastMod = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                    val size = if (cursor.isNull(4)) -1L else cursor.getLong(4)
                    result.add(SafEntry(docId, name, mime, lastMod, size))
                }
            }
        }
        return result
    }

    fun stageSafArcadeCompanions(
        context: Context,
        game: Game,
        gameDocumentUri: Uri,
        treeUriHint: Uri?,
        cacheGameDir: File?,
        systemBaseDir: File,
    ) {
        if (!isArcadeSystemId(game.systemId)) return
        val gameBase = File(game.fileName).nameWithoutExtension.lowercase()

        val treeUri =
            treeUriHint ?: runCatching {
                val tid = DocumentsContract.getTreeDocumentId(gameDocumentUri)
                DocumentsContract.buildTreeDocumentUri(gameDocumentUri.authority, tid)
            }.getOrNull() ?: return

        val docId = runCatching { DocumentsContract.getDocumentId(gameDocumentUri) }.getOrNull() ?: return
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: docId
        val gameParentDocId = if ('/' in docId) docId.substringBeforeLast('/') else treeDocId
        val grandParentDocId = if ('/' in gameParentDocId && gameParentDocId != treeDocId) gameParentDocId.substringBeforeLast('/') else null

        val searchDocIds = LinkedHashSet<String>()
        searchDocIds.add(gameParentDocId)
        if (grandParentDocId != null) searchDocIds.add(grandParentDocId)
        searchDocIds.add(treeDocId)

        val treeChildren = querySafChildren(context, treeUri, treeDocId)
        treeChildren
            .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
            .take(48)
            .forEach { child ->
                searchDocIds.add(child.docId)
                val lower = child.name.lowercase()
                if (lower in SAF_BIOS_DIR_TOKENS || lower.contains("bios") || lower.contains("system")) {
                    querySafChildren(context, treeUri, child.docId)
                        .filter { it.mimeType == DocumentsContract.Document.MIME_TYPE_DIR }
                        .take(24)
                        .forEach { searchDocIds.add(it.docId) }
                }
            }

        val foundBiosEntries = LinkedHashMap<String, SafEntry>()
        val foundParentEntries = LinkedHashMap<String, SafEntry>()

        for (dirDocId in searchDocIds) {
            val isGameDir = dirDocId == gameParentDocId
            val children =
                if (dirDocId == treeDocId) {
                    treeChildren
                } else {
                    querySafChildren(context, treeUri, dirDocId)
                }
            for (entry in children) {
                if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val lower = entry.name.lowercase()
                if (!lower.endsWith(".zip") && !lower.endsWith(".7z")) continue
                val stem = lower.substringBeforeLast('.')
                if (stem in ALL_ARCADE_BIOS_BASES) {
                    val existing = foundBiosEntries[stem]
                    val existingIs7z = existing?.name?.lowercase()?.endsWith(".7z") == true
                    if (existing == null || (existingIs7z && lower.endsWith(".zip"))) {
                        foundBiosEntries[stem] = entry
                    }
                } else if (
                    isGameDir &&
                    stem.length >= 2 &&
                    stem != gameBase &&
                    gameBase.startsWith(stem) &&
                    foundParentEntries.size < 6
                ) {
                    val existing = foundParentEntries[stem]
                    val existingIs7z = existing?.name?.lowercase()?.endsWith(".7z") == true
                    if (existing == null || (existingIs7z && lower.endsWith(".zip"))) {
                        foundParentEntries[stem] = entry
                    }
                }
            }
        }

        val systemTargets = getSystemArcadeDirs(systemBaseDir)

        fun stageEntry(
            entry: SafEntry,
            targets: List<File>,
        ) {
            val isSevenZ = entry.name.lowercase().endsWith(".7z")
            val lastMod = entry.lastModified
            val uniqueTargets = targets.distinctBy { it.absolutePath }
            val outdated =
                uniqueTargets.filter { dst ->
                    if (isSevenZ) {
                        !isValidFlatZip(dst) || (lastMod > 0L && dst.lastModified() < lastMod)
                    } else {
                        !isValidZip(dst) ||
                            (entry.size > 0L && dst.length() != entry.size) ||
                            (lastMod > 0L && dst.lastModified() < lastMod)
                    }
                }
            if (outdated.isEmpty()) return

            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId)
            val firstTarget = outdated.first()
            runCatching {
                if (isSevenZ) {
                    SevenZipUtils.openArchive(context, docUri).use { opened ->
                        SevenZipUtils.repackAllToZip(opened.archive, firstTarget)
                    }
                    if (lastMod > 0L) firstTarget.setLastModified(lastMod)
                } else {
                    firstTarget.parentFile?.mkdirs()
                    val part = File(firstTarget.parentFile, "${firstTarget.name}.part")
                    part.delete()
                    try {
                        context.contentResolver.openInputStream(docUri)?.use { input ->
                            FileOutputStream(part).use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        } ?: return
                        if (!isValidZip(part)) {
                            part.delete()
                            return
                        }
                        if (firstTarget.exists()) firstTarget.delete()
                        if (!part.renameTo(firstTarget)) {
                            part.copyTo(firstTarget, overwrite = true)
                            part.delete()
                        }
                        if (lastMod > 0L) firstTarget.setLastModified(lastMod)
                    } catch (t: Throwable) {
                        part.delete()
                        throw t
                    }
                }
            }.onSuccess {
                outdated.drop(1).forEach { nextTarget ->
                    runCatching { copyFileAtomic(firstTarget, nextTarget, lastMod) }
                }
            }
        }

        for ((stem, entry) in foundBiosEntries) {
            val targets = ArrayList<File>()
            cacheGameDir?.let { targets.add(File(it, "$stem.zip")) }
            systemTargets.forEach { targets.add(File(it, "$stem.zip")) }
            stageEntry(entry, targets)
        }

        for ((stem, entry) in foundParentEntries) {
            val targets = ArrayList<File>()
            cacheGameDir?.let { targets.add(File(it, "$stem.zip")) }
            systemTargets.forEach { targets.add(File(it, "$stem.zip")) }
            stageEntry(entry, targets)
        }
    }
}
