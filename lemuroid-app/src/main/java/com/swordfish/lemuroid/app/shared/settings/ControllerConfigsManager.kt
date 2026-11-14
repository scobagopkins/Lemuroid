package com.swordfish.lemuroid.app.shared.settings

import android.content.SharedPreferences
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ControllerConfigsManager(private val sharedPreferences: Lazy<SharedPreferences>) {
    suspend fun getControllerConfigs(
        systemId: SystemID,
        systemCoreConfig: SystemCoreConfig,
    ): Map<Int, ControllerConfig> = getControllerConfigs(systemId, systemCoreConfig, null)

    suspend fun getControllerConfigs(
        systemId: SystemID,
        systemCoreConfig: SystemCoreConfig,
        game: Game?,
    ): Map<Int, ControllerConfig> =
        withContext(Dispatchers.IO) {
            val isArcade = isArcadeSystem(systemId.dbname, systemCoreConfig.coreID)
            systemCoreConfig.controllerConfigs.entries.associate { (port, controllers) ->
                val storedName =
                    if (isArcade && game != null) {
                        sharedPreferences.get().getString(
                            getGameSharedPreferencesId(game.id, systemCoreConfig.coreID, port),
                            null,
                        )
                    } else if (!isArcade) {
                        sharedPreferences.get().getString(
                            getSharedPreferencesId(systemId.dbname, systemCoreConfig.coreID, port),
                            null,
                        )
                    } else {
                        null
                    }
                val currentController =
                    storedName?.let { name -> controllers.firstOrNull { it.name == name } }
                        ?: getDefaultControllerConfig(game, controllers)
                port to currentController
            }
        }

    companion object {
        private val STREET_FIGHTER_ROM_REGEX =
            Regex("sf|sf[jpua]|sf[12].*|sf3|sfiii.*|sfa|sfa[23udrjb].*|sfz|sfz[23udrjb].*|sfex.*|ssf2.*|hsf2.*|sftm.*")

        fun getSharedPreferencesId(
            systemId: String,
            coreID: CoreID,
            port: Int,
        ) = "pref_key_controller_type_${systemId}_${coreID.coreName}_$port"

        fun getGameSharedPreferencesId(
            gameId: Int,
            coreID: CoreID,
            port: Int,
        ) = "pref_key_controller_type_game_${gameId}_${coreID.coreName}_$port"

        fun isArcadeSystem(
            systemId: String?,
            coreID: CoreID? = null,
        ): Boolean {
            return systemId == SystemID.FBNEO.dbname ||
                systemId == SystemID.MAME2003PLUS.dbname ||
                coreID == CoreID.FBNEO ||
                coreID == CoreID.MAME2003PLUS ||
                coreID == CoreID.MAME2003
        }

        fun getPreferenceKey(
            game: Game,
            coreID: CoreID,
            port: Int,
        ): String {
            return if (isArcadeSystem(game.systemId, coreID)) {
                getGameSharedPreferencesId(game.id, coreID, port)
            } else {
                getSharedPreferencesId(game.systemId, coreID, port)
            }
        }

        fun isStreetFighterGame(game: Game?): Boolean {
            if (game == null) return false
            val titleLower = game.title.lowercase()
            val customLower = game.customName?.lowercase().orEmpty()
            val fileLower = game.fileName.lowercase()
            if (
                titleLower.contains("street fighter") ||
                titleLower.contains("streetfighter") ||
                customLower.contains("street fighter") ||
                customLower.contains("streetfighter") ||
                fileLower.contains("street fighter") ||
                fileLower.contains("streetfighter")
            ) {
                return true
            }
            val stem = fileLower.substringBeforeLast('.')
            return STREET_FIGHTER_ROM_REGEX.matches(stem)
        }

        fun getDefaultControllerConfig(
            game: Game?,
            controllers: List<ControllerConfig>,
        ): ControllerConfig {
            if (isStreetFighterGame(game)) {
                controllers.firstOrNull { it.name == "psone" }?.let { return it }
            }
            return controllers.first()
        }

        fun getDefaultControllerName(
            game: Game?,
            controllers: List<ControllerConfig>,
        ): String = getDefaultControllerConfig(game, controllers).name
    }
}
