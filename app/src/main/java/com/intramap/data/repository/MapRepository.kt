package com.intramap.data.repository

import android.content.Context
import com.intramap.data.model.Building
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMapDataSource
import kotlinx.serialization.json.Json
import java.io.File

class MapRepository(
    private val context: Context,
    private val remoteSource: SupabaseMapDataSource? =
        SupabaseClientFactory.client?.let { SupabaseMapDataSource(it) }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val offlineCache = OfflineMapCache(
        directory = File(context.filesDir, "published_maps"),
        json = json
    )

    private var cache: List<Building>? = null

    fun getBuildings(): List<Building> = cache ?: loadLocal().also { cache = it }

    fun getBuilding(id: String): Building? = getBuildings().firstOrNull { it.buildingId == id }

    /**
     * Refreshes published maps from Supabase when configured and reachable.
     * Cached maps remain available when the network request fails.
     */
    suspend fun refreshPublishedMaps(): Result<Int> {
        val remote = remoteSource
            ?: return Result.failure(IllegalStateException("Supabase is not configured."))

        return runCatching {
            val remoteBuildings = remote.fetchPublishedBuildings()
            offlineCache.save(remoteBuildings)
            cache = loadLocal()
            remoteBuildings.size
        }
    }

    private fun loadLocal(): List<Building> {
        val bundled = loadBundledMaps()
        val cached = offlineCache.load()

        return (bundled + cached)
            .associateBy { it.buildingId }
            .values
            .sortedBy { it.buildingName.lowercase() }
    }

    private fun loadBundledMaps(): List<Building> {
        val files = context.assets.list("maps").orEmpty().filter { it.endsWith(".json") }

        return files.mapNotNull { fileName ->
            runCatching {
                json.decodeFromString<Building>(
                    context.assets.open("maps/$fileName").bufferedReader().use { it.readText() }
                )
            }.getOrNull()
        }
    }
}
