package com.intramap.data.repository

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PublishedMap
import com.intramap.data.model.PathEdge
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMapCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun publishedMap(id: String, name: String, versionId: String? = null): PublishedMap {
        val floor = Floor("$id-floor", 1, "Ground", 30f, 10f)
        val node = LocationNode(
            nodeId = "$id-entrance",
            floorId = floor.floorId,
            name = "Entrance",
            type = "ENTRANCE",
            x = 2f,
            y = 5f,
            keywords = listOf("entry")
        )
        val edge = PathEdge("$id-edge", node.nodeId, node.nodeId, 1f)

        return PublishedMap(
            building = Building(id, name, "", listOf(floor), listOf(node), listOf(edge)),
            versionId = versionId
        )
    }

    @Test
    fun `saves and loads published maps`() {
        val cache = OfflineMapCache(temporaryFolder.root, Json { ignoreUnknownKeys = true })
        val maps = listOf(
            publishedMap("b1", "Library", "version-1"),
            publishedMap("b2", "Science Block", "version-2")
        )

        cache.save(maps)
        val loaded = cache.load()

        assertEquals(setOf("b1", "b2"), loaded.map { it.building.buildingId }.toSet())
        assertEquals(setOf("version-1", "version-2"), loaded.map { it.versionId }.toSet())
    }

    @Test
    fun `removes cached maps no longer published`() {
        val cache = OfflineMapCache(temporaryFolder.root, Json { ignoreUnknownKeys = true })

        cache.save(
            listOf(
                publishedMap("b1", "Library", "version-1"),
                publishedMap("b2", "Science Block", "version-2")
            )
        )
        cache.save(listOf(publishedMap("b1", "Library", "version-1")))

        val loaded = cache.load()

        assertEquals(listOf("b1"), loaded.map { it.building.buildingId })
    }

    @Test
    fun `skips malformed cache files`() {
        val cache = OfflineMapCache(temporaryFolder.root, Json { ignoreUnknownKeys = true })
        cache.save(listOf(publishedMap("b1", "Library", "version-1")))
        temporaryFolder.root.resolve("broken.json").writeText("{ not valid json")

        val loaded = cache.load()

        assertEquals(listOf("b1"), loaded.map { it.building.buildingId })
        assertTrue(loaded.first().building.buildingName == "Library")
        assertEquals("version-1", loaded.first().versionId)
    }
}
