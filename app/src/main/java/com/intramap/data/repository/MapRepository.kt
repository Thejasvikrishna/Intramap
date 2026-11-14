package com.intramap.data.repository

import android.content.Context
import com.intramap.data.model.Building
import kotlinx.serialization.json.Json

class MapRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: List<Building>? = null

    fun getBuildings(): List<Building> = cache ?: loadAll().also { cache = it }

    fun getBuilding(id: String): Building? = getBuildings().firstOrNull { it.buildingId == id }

    private fun loadAll(): List<Building> {
        val files = context.assets.list("maps").orEmpty().filter { it.endsWith(".json") }
        return files.mapNotNull { f ->
            runCatching {
                json.decodeFromString<Building>(
                    context.assets.open("maps/$f").bufferedReader().use { it.readText() })
            }.getOrNull()   // skip malformed map files instead of crashing
        }
    }
}