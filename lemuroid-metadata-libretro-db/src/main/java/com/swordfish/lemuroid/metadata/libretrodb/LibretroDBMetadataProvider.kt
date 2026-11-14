package com.swordfish.lemuroid.metadata.libretrodb

import com.swordfish.lemuroid.common.kotlin.filterNullable
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.metadata.GameMetadata
import com.swordfish.lemuroid.lib.library.metadata.GameMetadataProvider
import com.swordfish.lemuroid.lib.storage.StorageFile
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDBManager
import com.swordfish.lemuroid.metadata.libretrodb.db.LibretroDatabase
import com.swordfish.lemuroid.metadata.libretrodb.db.entity.LibretroRom
import timber.log.Timber
import java.util.Locale

class LibretroDBMetadataProvider(private val ovgdbManager: LibretroDBManager) :
    GameMetadataProvider {
    companion object {
        private val THUMB_REPLACE = Regex("[&*/:`<>?\\|]")
        private val BIOS_AND_COMPANION_FILES =
            setOf(
                "coleco.rom",
                "colecovision.rom",
                "neogeo.zip",
                "neogeo.7z",
                "neocdz.zip",
                "neocdz.7z",
                "qsound.zip",
                "qsound.7z",
                "qsound_hle.zip",
                "qsound_hle.7z",
                "pgm.zip",
                "pgm.7z",
                "skns.zip",
                "skns.7z",
                "decocass.zip",
                "decocass.7z",
                "stvbios.zip",
                "stvbios.7z",
                "isgsm.zip",
                "isgsm.7z",
                "aleck64.zip",
                "aleck64.7z",
                "playch10.zip",
                "playch10.7z",
                "nss.zip",
                "nss.7z",
                "vssystem.zip",
                "vssystem.7z",
                "megaplay.zip",
                "megaplay.7z",
                "megatech.zip",
                "megatech.7z",
                "segac2.zip",
                "segac2.7z",
                "coh1000a.zip",
                "coh1000a.7z",
                "coh1000c.zip",
                "coh1000c.7z",
                "coh1000t.zip",
                "coh1000t.7z",
                "coh1000w.zip",
                "coh1000w.7z",
                "coh1001l.zip",
                "coh1001l.7z",
                "coh1002c.zip",
                "coh1002c.7z",
                "coh1002m.zip",
                "coh1002m.7z",
                "coh3002c.zip",
                "coh3002c.7z",
                "coh3002t.zip",
                "coh3002t.7z",
                "psarc95.zip",
                "psarc95.7z",
                "cpzn1.zip",
                "cpzn1.7z",
                "cpzn2.zip",
                "cpzn2.7z",
                "acpsx.zip",
                "acpsx.7z",
                "zn1.zip",
                "zn1.7z",
                "zn2.zip",
                "zn2.7z",
                "konamigv.zip",
                "konamigv.7z",
                "konamigx.zip",
                "konamigx.7z",
                "sys246.zip",
                "sys246.7z",
                "sys573.zip",
                "sys573.7z",
                "taitofx1.zip",
                "taitofx1.7z",
                "taitogn.zip",
                "taitogn.7z",
                "taitopjc.zip",
                "taitopjc.7z",
                "bubsys.zip",
                "bubsys.7z",
                "namco50.zip",
                "namco50.7z",
                "namco51.zip",
                "namco51.7z",
                "namco52.zip",
                "namco52.7z",
                "namco53.zip",
                "namco53.7z",
                "namco54.zip",
                "namco54.7z",
                "namcoc69.zip",
                "namcoc69.7z",
                "namcoc70.zip",
                "namcoc70.7z",
                "namcoc75.zip",
                "namcoc75.7z",
                "namcoc76.zip",
                "namcoc76.7z",
                "nmik0016.zip",
                "nmik0016.7z",
                "atarisy1.zip",
                "atarisy1.7z",
                "kinst.zip",
                "kinst.7z",
                "ar_bios.zip",
                "ar_bios.7z",
                "crysbios.zip",
                "crysbios.7z",
                "maxforce.zip",
                "maxforce.7z",
                "s32.zip",
                "s32.7z",
                "hikaru.zip",
                "hikaru.7z",
                "naomi.zip",
                "naomi.7z",
                "awbios.zip",
                "awbios.7z",
                "hng64.zip",
                "hng64.7z",
                "suprnova.zip",
                "suprnova.7z",
                "macsbios.zip",
                "macsbios.7z",
                "shtzone.zip",
                "shtzone.7z",
                "tourvis.zip",
                "tourvis.7z",
                "galgbios.zip",
                "galgbios.7z",
                "jvs13551.zip",
                "jvs13551.7z",
                "galpanic.zip",
                "galpanic.7z",
            )
    }

    private val sortedSystemIds: List<String> by lazy {
        SystemID.values()
            .map { it.dbname }
            .sortedByDescending { it.length }
    }

    override suspend fun retrieveMetadata(storageFile: StorageFile): GameMetadata? {
        if (storageFile.name.lowercase(Locale.getDefault()) in BIOS_AND_COMPANION_FILES) {
            return null
        }

        val db = ovgdbManager.dbInstance

        Timber.d("Looking metadata for file: $storageFile")

        val metadata =
            runCatching {
                findByCRC(storageFile, db)
                    ?: findBySerial(storageFile, db)
                    ?: findByFilename(db, storageFile)
                    ?: findByPathAndFilename(db, storageFile)
                    ?: findByUniqueExtension(storageFile)
                    ?: findByKnownSystem(storageFile)
                    ?: findByPathAndSupportedExtension(storageFile)
            }.getOrElse {
                Timber.e("Error in retrieving $storageFile metadata: $it... Skipping.")
                null
            }

        metadata?.let { Timber.d("Metadata retrieved for item: $it") }

        return metadata
    }

    private fun convertToGameMetadata(rom: LibretroRom): GameMetadata {
        val system = GameSystem.findById(rom.system!!)
        return GameMetadata(
            name = rom.name,
            romName = rom.romName,
            thumbnail = computeCoverUrl(system, rom.name),
            system = rom.system,
            developer = rom.developer,
        )
    }

    private suspend fun findRomByArchiveAwareFilename(
        db: LibretroDatabase,
        file: StorageFile,
    ): LibretroRom? {
        return db.gameDao().findByFileName(file.name)
            ?: if (file.extension.equals("7z", ignoreCase = true) || file.extension.equals("zip", ignoreCase = true)) {
                db.gameDao().findByFileName("${file.extensionlessName}.zip")
            } else {
                null
            }
    }

    private suspend fun findByFilename(
        db: LibretroDatabase,
        file: StorageFile,
    ): GameMetadata? {
        return findRomByArchiveAwareFilename(db, file)
            .filterNullable { extractGameSystem(it).scanOptions.scanByFilename }
            ?.let { convertToGameMetadata(it) }
    }

    private suspend fun findByPathAndFilename(
        db: LibretroDatabase,
        file: StorageFile,
    ): GameMetadata? {
        return findRomByArchiveAwareFilename(db, file)
            .filterNullable { extractGameSystem(it).scanOptions.scanByPathAndFilename }
            .filterNullable { parentContainsSystem(file.path, extractGameSystem(it).id.dbname) }
            ?.let { convertToGameMetadata(it) }
    }

    private fun findByPathAndSupportedExtension(file: StorageFile): GameMetadata? {
        val system =
            sortedSystemIds
                .filter { parentContainsSystem(file.path, it) }
                .map { GameSystem.findById(it) }
                .filter { it.scanOptions.scanByPathAndSupportedExtensions }
                .firstOrNull { it.supportedExtensions.contains(file.extension) }

        return system?.let {
            GameMetadata(
                name = file.extensionlessName,
                romName = file.name,
                thumbnail = null,
                system = it.id.dbname,
                developer = null,
            )
        }
    }

    private fun parentContainsSystem(
        parent: String?,
        dbname: String,
    ): Boolean {
        val p = parent?.toLowerCase(Locale.getDefault())?.replace('\\', '/') ?: return false
        if (p.contains(dbname)) return true
        if (dbname == "fbneo" && (p.contains("/fba/") || p.endsWith("/fba") || p.startsWith("fba/") || p.contains("neogeo") || p.contains("neo-geo") || p.contains("cps1") || p.contains("cps2") || p.contains("cps3") || p.contains("arcade"))) return true
        if (dbname == "mame2003plus" && (p.contains("mame") || p.contains("arcade") || p.contains("/zn") || p.endsWith("zn"))) return true
        if (dbname == "coleco" && (p.contains("coleco") || p.contains("vcol"))) return true
        return false
    }

    private suspend fun findByCRC(
        file: StorageFile,
        db: LibretroDatabase,
    ): GameMetadata? {
        if (file.crc == null || file.crc == "0") return null
        return file.crc?.let { crc32 -> db.gameDao().findByCRC(crc32) }
            ?.let { convertToGameMetadata(it) }
    }

    private suspend fun findBySerial(
        file: StorageFile,
        db: LibretroDatabase,
    ): GameMetadata? {
        if (file.serial == null) return null
        return db.gameDao().findBySerial(file.serial!!)
            ?.let { convertToGameMetadata(it) }
    }

    private fun findByKnownSystem(file: StorageFile): GameMetadata? {
        if (file.systemID == null) return null

        return GameMetadata(
            name = file.extensionlessName,
            romName = file.name,
            thumbnail = null,
            system = file.systemID!!.dbname,
            developer = null,
        )
    }

    private fun findByUniqueExtension(file: StorageFile): GameMetadata? {
        val system = GameSystem.findByUniqueFileExtension(file.extension)

        if (system?.scanOptions?.scanByUniqueExtension == false) {
            return null
        }

        val result =
            system?.let {
                GameMetadata(
                    name = file.extensionlessName,
                    romName = file.name,
                    thumbnail = null,
                    system = it.id.dbname,
                    developer = null,
                )
            }

        return result
    }

    private fun extractGameSystem(rom: LibretroRom): GameSystem {
        return GameSystem.findById(rom.system!!)
    }

    private fun computeCoverUrl(
        system: GameSystem,
        name: String?,
    ): String? {
        var systemName = system.libretroFullName

        // Specific mame version don't have any thumbnails in Libretro database
        if (system.id == SystemID.MAME2003PLUS) {
            systemName = "MAME"
        }

        if (name == null) {
            return null
        }

        val imageType = "Named_Boxarts"

        val thumbGameName = name.replace(THUMB_REPLACE, "_")

        return "http://thumbnails.libretro.com/$systemName/$imageType/$thumbGameName.png"
    }
}
