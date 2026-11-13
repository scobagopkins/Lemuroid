package com.swordfish.lemuroid.lib.storage

import android.content.Context
import java.io.File

class DirectoriesManager(private val appContext: Context) {
    @Deprecated("Use the external states directory")
    fun getInternalStatesDirectory(): File =
        File(appContext.filesDir, "states").apply {
            mkdirs()
        }

    fun getCoresDirectory(): File =
        File(appContext.filesDir, "cores").apply {
            mkdirs()
        }

    fun getSystemDirectory(): File =
        File(appContext.filesDir, "system").apply {
            mkdirs()
            try {
                listOf("colecovision.rom", "coleco.rom").forEach { name ->
                    val dest = File(this, name)
                    if (!dest.exists() || dest.length() != 8192L) {
                        appContext.assets.open(name).use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (ignored: Exception) {
            }
        }

    fun getStatesDirectory(): File =
        File(appContext.getExternalFilesDir(null), "states").apply {
            mkdirs()
        }

    fun getStatesPreviewDirectory(): File =
        File(appContext.getExternalFilesDir(null), "state-previews").apply {
            mkdirs()
        }

    fun getSavesDirectory(): File =
        File(appContext.getExternalFilesDir(null), "saves").apply {
            mkdirs()
        }

    fun getInternalRomsDirectory(): File =
        File(appContext.getExternalFilesDir(null), "roms").apply {
            mkdirs()
        }
}
