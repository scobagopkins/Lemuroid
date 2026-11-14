package com.swordfish.lemuroid.lib.game

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.swordfish.lemuroid.lib.bios.BiosManager
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.LemuroidLibrary
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.migration.DesmumeMigrationHandler
import com.swordfish.lemuroid.lib.saves.SaveState
import com.swordfish.lemuroid.lib.saves.SavesCoherencyEngine
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import com.swordfish.lemuroid.lib.storage.RomFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class GameLoader(
    private val lemuroidLibrary: LemuroidLibrary,
    private val statesManager: StatesManager,
    private val savesManager: SavesManager,
    private val coreVariablesManager: CoreVariablesManager,
    private val retrogradeDatabase: RetrogradeDatabase,
    private val savesCoherencyEngine: SavesCoherencyEngine,
    private val directoriesManager: DirectoriesManager,
    private val biosManager: BiosManager,
    private val desmumeMigrationHandler: DesmumeMigrationHandler,
) {
    sealed class LoadingState {
        object LoadingCore : LoadingState()

        object LoadingGame : LoadingState()

        class Ready(val gameData: GameData) : LoadingState()
    }

    fun load(
        appContext: Context,
        game: Game,
        loadSave: Boolean,
        systemCoreConfig: SystemCoreConfig,
        directLoad: Boolean,
    ): Flow<LoadingState> =
        flow {
            try {
                emit(LoadingState.LoadingCore)

                val system = GameSystem.findById(game.systemId)

                if (!isArchitectureSupported(systemCoreConfig)) {
                    throw GameLoaderException(GameLoaderError.UnsupportedArchitecture)
                }

                val coreLibrary =
                    runCatching {
                        findLibrary(appContext, systemCoreConfig.coreID)!!.absolutePath
                    }.getOrElse { throw GameLoaderException(GameLoaderError.LoadCore) }

                emit(LoadingState.LoadingGame)

                if (system.id == SystemID.COLECO) {
                    ensureColecoBiosAvailable(game)
                }

                val missingBiosFiles = biosManager.getMissingBiosFiles(systemCoreConfig, game)
                if (missingBiosFiles.isNotEmpty()) {
                    throw GameLoaderException(GameLoaderError.MissingBiosFiles(missingBiosFiles))
                }

                if (system.id == SystemID.MAME2003PLUS) {
                    runCatching {
                        val companions =
                            retrogradeDatabase.gameDao().selectBySystemAndFileNames(
                                system.id.dbname,
                                ZN_COMPANION_ROMS,
                            )
                        companions
                            .filter { it.id != game.id }
                            .forEach { companion ->
                                lemuroidLibrary.getGameFiles(companion, emptyList(), false)
                            }
                    }.onFailure { Timber.w(it, "Failed to pre-cache MAME companion ROMs") }
                }

                val rawGameFiles =
                    runCatching {
                        val useVFS = systemCoreConfig.supportsLibretroVFS && directLoad
                        val dataFiles = retrogradeDatabase.dataFileDao().selectDataFilesForGame(game.id)
                        lemuroidLibrary.getGameFiles(game, dataFiles, useVFS)
                    }.getOrElse { throw it }

                val gameFiles =
                    if (systemCoreConfig.coreID == CoreID.MAME2003PLUS) {
                        runCatching {
                            prepareMame2003PlusRom(appContext, rawGameFiles)
                        }.getOrDefault(rawGameFiles)
                    } else {
                        rawGameFiles
                    }

                val saveRAM =
                    runCatching {
                        val data = savesManager.getSaveRAM(game, systemCoreConfig)
                        desmumeMigrationHandler.resolveSaveData(game, systemCoreConfig.coreID, data)
                    }.getOrElse { throw GameLoaderException(GameLoaderError.Saves) }
                val saveRAMData = saveRAM.data

                val quickSaveData =
                    runCatching {
                        val shouldDiscardSave =
                            !savesCoherencyEngine.shouldDiscardAutoSaveState(
                                game,
                                systemCoreConfig.coreID,
                                saveRAM.timestampOverride,
                            )

                        if (systemCoreConfig.statesSupported && loadSave && shouldDiscardSave) {
                            statesManager.getAutoSave(game, systemCoreConfig.coreID)
                        } else {
                            null
                        }
                    }.getOrElse { throw GameLoaderException(GameLoaderError.Saves) }

                val coreVariables =
                    coreVariablesManager.getOptionsForCore(system.id, systemCoreConfig)
                        .toTypedArray()

                val systemDirectory = directoriesManager.getSystemDirectory()
                val savesDirectory = directoriesManager.getSavesDirectory()

                emit(
                    LoadingState.Ready(
                        GameData(
                            game,
                            coreLibrary,
                            gameFiles,
                            quickSaveData,
                            saveRAMData,
                            coreVariables,
                            systemDirectory,
                            savesDirectory,
                        ),
                    ),
                )
            } catch (e: GameLoaderException) {
                Timber.e(e, "Error while preparing game")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error while preparing game")
                throw GameLoaderException(GameLoaderError.Generic)
            }
        }

    private fun ensureColecoBiosAvailable(game: Game) {
        runCatching {
            val sysDir = directoriesManager.getSystemDirectory()
            val targetBios = File(sysDir, "colecovision.rom")
            if (targetBios.exists() && targetBios.length() > 0L) return

            val candidateDirs = mutableListOf<File>()
            Uri.parse(game.fileUri).path?.let { File(it).parentFile }?.let { candidateDirs.add(it) }
            val extRoot = Environment.getExternalStorageDirectory()
            candidateDirs.add(File(extRoot, "!D/!RETROARCH/vcol"))
            candidateDirs.add(File(extRoot, "!D/!RETROARCH"))

            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                for (name in listOf("colecovision.rom", "coleco.rom", "COLECO.ROM", "COLECOVISION.ROM")) {
                    val f = File(dir, name)
                    if (f.exists() && f.isFile && f.length() > 0L) {
                        f.copyTo(targetBios, overwrite = true)
                        return
                    }
                }
            }
        }
    }

    private fun prepareMame2003PlusRom(
        appContext: Context,
        gameFiles: RomFiles,
    ): RomFiles {
        if (gameFiles !is RomFiles.Standard || gameFiles.files.isEmpty()) {
            return gameFiles
        }
        val romFile = gameFiles.files.first()
        if (!romFile.exists() || !romFile.name.endsWith(".zip", ignoreCase = true)) {
            return gameFiles
        }

        val entryCrcs = mutableSetOf<Long>()
        ZipFile(romFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!e.isDirectory && e.crc >= 0) {
                    entryCrcs.add(e.crc)
                }
            }
        }

        val lowerName = romFile.name.lowercase()
        val targetDriverName: String? =
            when {
                lowerName == "sfex.zip" && 0x08247bd4L in entryCrcs -> "sfexa"
                lowerName == "sfex.zip" && 0xde02bd29L in entryCrcs -> "sfexu"
                lowerName == "sfex.zip" && 0xea100607L in entryCrcs -> "sfexj"
                lowerName == "sfex2p.zip" && 0x2938118cL in entryCrcs -> "sfex2pu"
                lowerName == "sfex2p.zip" && 0xc437d602L in entryCrcs -> "sfex2pa"
                lowerName == "sfex2p.zip" && 0xc6d0aea3L in entryCrcs -> "sfex2pj"
                else -> null
            }

        val srcDir = romFile.parentFile
        val znSearchDirs =
            listOfNotNull(srcDir) +
                com.swordfish.lemuroid.lib.storage.local.ArcadeCompanionUtils.getSystemArcadeDirs(
                    directoriesManager.getSystemDirectory(),
                )
        val hasZnBiosSibling =
            znSearchDirs.any { dir ->
                dir.exists() &&
                    listOf("cpzn1.zip", "cpzn2.zip", "coh1000c.zip", "coh1002c.zip", "coh3002c.zip")
                        .any { File(dir, it).exists() }
            }

        if (targetDriverName == null && !hasZnBiosSibling && 0x50033af6L !in entryCrcs && 0xe860ea8bL !in entryCrcs) {
            return gameFiles
        }

        val stageDir = File(appContext.cacheDir, "mame2003plus-zn")
        stageDir.mkdirs()

        // Copy the original ROM zip into stageDir so parent/clone lookup finds all mask ROMs
        val stagedOriginal = File(stageDir, lowerName)
        if (!stagedOriginal.exists() || stagedOriginal.length() != romFile.length()) {
            romFile.copyTo(stagedOriginal, overwrite = true)
        }

        // Copy any sibling/system ZN/QSound zips into stageDir
        for (searchDir in znSearchDirs) {
            if (!searchDir.exists()) continue
            for (compName in ZN_COMPANION_ROMS) {
                val srcComp = File(searchDir, compName)
                if (srcComp.exists() && srcComp.isFile) {
                    val dstComp = File(stageDir, compName.lowercase())
                    if (!dstComp.exists() || dstComp.length() != srcComp.length()) {
                        srcComp.copyTo(dstComp, overwrite = true)
                    }
                }
            }
        }

        // Extract ZN-1 (0x50033af6) and ZN-2 (0xe860ea8b) BIOS bytes if available
        val defaultZnDir = File(Environment.getExternalStorageDirectory(), "!D/!RETROARCH/zn")
        val zn1BiosBytes =
            findEntryByCrc(
                listOfNotNull(
                    romFile,
                    srcDir?.let { File(it, "cpzn1.zip") },
                    srcDir?.let { File(it, "coh1000c.zip") },
                    srcDir?.let { File(it, "coh1002c.zip") },
                    File(stageDir, "cpzn1.zip"),
                    File(defaultZnDir, "cpzn1.zip"),
                ),
                0x50033af6L,
            )
        val zn2BiosBytes =
            findEntryByCrc(
                listOfNotNull(
                    romFile,
                    srcDir?.let { File(it, "cpzn2.zip") },
                    srcDir?.let { File(it, "coh3002c.zip") },
                    File(stageDir, "cpzn2.zip"),
                    File(defaultZnDir, "cpzn2.zip"),
                ),
                0xe860ea8bL,
            )

        // Build coh1000c.zip, coh1002c.zip, coh3002c.zip with BIOS + CAT702 keys
        if (zn1BiosBytes != null) {
            writeZnBiosZip(File(stageDir, "coh1000c.zip"), "coh-1000c.353", zn1BiosBytes)
            writeZnBiosZip(File(stageDir, "coh1002c.zip"), "coh-1002c.353", zn1BiosBytes)
        }
        if (zn2BiosBytes != null) {
            writeZnBiosZip(File(stageDir, "coh3002c.zip"), "m534002c-59.ic353", zn2BiosBytes)
        }

        if (targetDriverName != null) {
            val cloneZip = File(stageDir, "$targetDriverName.zip")
            writeCloneStageZip(cloneZip, zn1BiosBytes, zn2BiosBytes)
            return RomFiles.Standard(listOf(cloneZip) + gameFiles.files.drop(1))
        }

        return RomFiles.Standard(listOf(stagedOriginal) + gameFiles.files.drop(1))
    }

    private fun findEntryByCrc(
        candidates: List<File>,
        targetCrc: Long,
    ): ByteArray? {
        for (file in candidates) {
            if (!file.exists() || !file.isFile) continue
            runCatching {
                ZipFile(file).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && e.crc == targetCrc) {
                            return zf.getInputStream(e).use { it.readBytes() }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun writeZnBiosZip(
        targetZip: File,
        biosEntryName: String,
        biosBytes: ByteArray,
    ) {
        if (targetZip.exists() && targetZip.length() > 500_000L) return
        ZipOutputStream(FileOutputStream(targetZip)).use { zos ->
            putStoredEntry(zos, biosEntryName, biosBytes)
            for ((name, bytes) in CAT702_KEYS) {
                putStoredEntry(zos, name, bytes)
                putStoredEntry(zos, "$name.ic652", bytes)
            }
        }
    }

    private fun writeCloneStageZip(
        targetZip: File,
        zn1BiosBytes: ByteArray?,
        zn2BiosBytes: ByteArray?,
    ) {
        ZipOutputStream(FileOutputStream(targetZip)).use { zos ->
            if (zn1BiosBytes != null) {
                putStoredEntry(zos, "coh-1000c.353", zn1BiosBytes)
                putStoredEntry(zos, "coh-1002c.353", zn1BiosBytes)
            }
            if (zn2BiosBytes != null) {
                putStoredEntry(zos, "m534002c-59.ic353", zn2BiosBytes)
                putStoredEntry(zos, "coh-3002c.353", zn2BiosBytes)
            }
            for ((name, bytes) in CAT702_KEYS) {
                putStoredEntry(zos, name, bytes)
                putStoredEntry(zos, "$name.ic652", bytes)
            }
        }
    }

    private fun putStoredEntry(
        zos: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val crc = CRC32().apply { update(bytes) }.value
        val entry =
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc
            }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun isArchitectureSupported(systemCoreConfig: SystemCoreConfig): Boolean {
        val supportedOnlyArchitectures = systemCoreConfig.supportedOnlyArchitectures ?: return true
        return Build.SUPPORTED_ABIS.toSet().intersect(supportedOnlyArchitectures).isNotEmpty()
    }

    private fun findLibrary(
        context: Context,
        coreID: CoreID,
    ): File? {
        val files =
            sequenceOf(
                File(context.applicationInfo.nativeLibraryDir),
                context.filesDir,
            )

        return files
            .flatMap { it.walkBottomUp() }
            .firstOrNull { it.name == coreID.libretroFileName }
    }

    companion object {
        private val ZN_COMPANION_ROMS =
            listOf(
                "coh1000c.zip",
                "coh1002c.zip",
                "coh3002c.zip",
                "cpzn1.zip",
                "cpzn2.zip",
                "qsound.zip",
                "sfex.zip",
                "sfexp.zip",
                "sfex2.zip",
                "sfex2p.zip",
                "rvschool.zip",
                "starglad.zip",
                "ts2.zip",
                "plsmaswd.zip",
                "glpracr.zip",
            )

        private val CAT702_KEYS: Map<String, ByteArray> =
            mapOf(
                "cp01" to byteArrayOf(0xf0.toByte(), 0x81.toByte(), 0xc1.toByte(), 0x20.toByte(), 0xe2.toByte(), 0xfe.toByte(), 0x04.toByte(), 0xf8.toByte()),
                "cp02" to byteArrayOf(0xfc.toByte(), 0xf1.toByte(), 0x08.toByte(), 0x18.toByte(), 0xe2.toByte(), 0xc2.toByte(), 0x40.toByte(), 0x80.toByte()),
                "cp03" to byteArrayOf(0xc0.toByte(), 0x10.toByte(), 0x60.toByte(), 0x7c.toByte(), 0x04.toByte(), 0xfa.toByte(), 0x03.toByte(), 0x01.toByte()),
                "cp04" to byteArrayOf(0xf8.toByte(), 0xe2.toByte(), 0xe1.toByte(), 0x81.toByte(), 0x7c.toByte(), 0x0c.toByte(), 0x30.toByte(), 0xc0.toByte()),
                "cp05" to byteArrayOf(0x80.toByte(), 0x08.toByte(), 0x30.toByte(), 0xc2.toByte(), 0xfe.toByte(), 0xfd.toByte(), 0xe1.toByte(), 0xe0.toByte()),
                "cp06" to byteArrayOf(0xf0.toByte(), 0x20.toByte(), 0x3c.toByte(), 0xfd.toByte(), 0x81.toByte(), 0x78.toByte(), 0xfa.toByte(), 0x02.toByte()),
                "cp07" to byteArrayOf(0xf8.toByte(), 0x60.toByte(), 0x20.toByte(), 0x3c.toByte(), 0xfd.toByte(), 0x03.toByte(), 0xf2.toByte(), 0xf0.toByte()),
                "cp08" to byteArrayOf(0xe0.toByte(), 0xf2.toByte(), 0x70.toByte(), 0x81.toByte(), 0xc1.toByte(), 0x3c.toByte(), 0x04.toByte(), 0xf8.toByte()),
                "cp09" to byteArrayOf(0xfc.toByte(), 0x20.toByte(), 0x38.toByte(), 0x08.toByte(), 0xf1.toByte(), 0x03.toByte(), 0x82.toByte(), 0x80.toByte()),
                "cp10" to byteArrayOf(0xe0.toByte(), 0x40.toByte(), 0x38.toByte(), 0x08.toByte(), 0xf1.toByte(), 0x03.toByte(), 0xfe.toByte(), 0xfc.toByte()),
                "cp11" to byteArrayOf(0xf0.toByte(), 0x20.toByte(), 0xe1.toByte(), 0x81.toByte(), 0x7c.toByte(), 0x04.toByte(), 0xfa.toByte(), 0x02.toByte()),
                "cp12" to byteArrayOf(0xfc.toByte(), 0x82.toByte(), 0x60.toByte(), 0xe1.toByte(), 0xf9.toByte(), 0x38.toByte(), 0x30.toByte(), 0xf0.toByte()),
                "cp13" to byteArrayOf(0x02.toByte(), 0x70.toByte(), 0x08.toByte(), 0x04.toByte(), 0x3c.toByte(), 0x20.toByte(), 0xe1.toByte(), 0x01.toByte()),
            )
    }

    @Suppress("ArrayInDataClass")
    data class GameData(
        val game: Game,
        val coreLibrary: String,
        val gameFiles: RomFiles,
        val quickSaveData: SaveState?,
        val saveRAMData: ByteArray?,
        val coreVariables: Array<CoreVariable>,
        val systemDirectory: File,
        val savesDirectory: File,
    )
}
