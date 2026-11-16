package com.intramap.data.repository

import com.intramap.data.model.PublishedMap
import kotlinx.serialization.json.Json
import java.io.File

/** Persists published maps in app-private storage for offline access. */
class OfflineMapCache(
    private val directory: File,
    private val json: Json
) {
    fun load(): List<PublishedMap> {
        if (!directory.exists()) return emptyList()

        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<PublishedMap>(file.readText())
                }.getOrNull()
            }
    }

    fun save(maps: List<PublishedMap>) {
        directory.mkdirs()

        val validFileNames = maps.map { "${it.building.buildingId}.json" }.toSet()

        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" && it.name !in validFileNames }
            .forEach { it.delete() }

        maps.forEach { map ->
            runCatching {
                File(directory, "${map.building.buildingId}.json")
                    .writeText(json.encodeToString(PublishedMap.serializer(), map))
            }
        }
    }

    fun clear() {
        directory.listFiles()
            .orEmpty()
            .forEach { it.delete() }
    }
}
