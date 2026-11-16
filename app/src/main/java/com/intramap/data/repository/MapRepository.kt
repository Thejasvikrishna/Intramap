package com.intramap.data.repository

import android.content.Context
import com.intramap.data.model.Building
import com.intramap.data.model.PublishedMap
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

    private var cache: List<PublishedMap>? = null

    fun getBuildings(): List<Building> = getPublishedMaps().map { it.building }

    fun getBuilding(id: String): Building? =
        getPublishedMaps().firstOrNull { it.building.buildingId == id }?.building

    fun getPublishedMap(id: String): PublishedMap? =
        getPublishedMaps().firstOrNull { it.building.buildingId == id }

    /**
     * Refreshes published maps from Supabase when configured and reachable.
     * Cached maps remain available when the network request fails.
     */
    suspend fun refreshPublishedMaps(): Result<Int> {
        val remote = remoteSource
            ?: return Result.failure(IllegalStateException("Supabase is not configured."))

        return runCatching {
            val remoteMaps = remote.fetchPublishedMaps()
            offlineCache.save(remoteMaps)
            cache = loadLocal()
            remoteMaps.size
        }
    }

    private fun getPublishedMaps(): List<PublishedMap> =
        cache ?: loadLocal().also { cache = it }

    private fun loadLocal(): List<PublishedMap> {
        val bundled = loadBundledMaps()
        val cached = offlineCache.load()

        return (bundled + cached)
            .associateBy { it.building.buildingId }
            .values
            .sortedBy { it.building.buildingName.lowercase() }
    }

    private fun loadBundledMaps(): List<PublishedMap> {
        val files = context.assets.list("maps").orEmpty().filter { it.endsWith(".json") }

        return files.mapNotNull { fileName ->
            runCatching {
                val building = json.decodeFromString<Building>(
                    context.assets.open("maps/$fileName").bufferedReader().use { it.readText() }
                )
                PublishedMap(building)
            }.getOrNull()
        }
    }
}
