package com.intramap.data.repository

import com.intramap.data.model.Building
import kotlinx.serialization.json.Json
import java.io.File

/** Persists published maps in app-private storage for offline access. */
class OfflineMapCache(
    private val directory: File,
    private val json: Json
) {
    fun load(): List<Building> {
        if (!directory.exists()) return emptyList()

        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<Building>(file.readText())
                }.getOrNull()
            }
    }

    fun save(buildings: List<Building>) {
        directory.mkdirs()

        val validFileNames = buildings.map { "${it.buildingId}.json" }.toSet()

        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" && it.name !in validFileNames }
            .forEach { it.delete() }

        buildings.forEach { building ->
            runCatching {
                File(directory, "${building.buildingId}.json")
                    .writeText(json.encodeToString(Building.serializer(), building))
            }
        }
    }

    fun clear() {
        directory.listFiles()
            .orEmpty()
            .forEach { it.delete() }
    }
}
