package com.intramap.data.repository

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
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

    private fun building(id: String, name: String): Building {
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

        return Building(id, name, "", listOf(floor), listOf(node), listOf(edge))
    }

    @Test
    fun `saves and loads published buildings`() {
        val cache = OfflineMapCache(temporaryFolder.root, Json { ignoreUnknownKeys = true })
        val buildings = listOf(building("b1", "Library"), building("b2", "Science Block"))

        cache.save(buildings)
        val loaded = cache.load()

        assertEquals(setOf("b1", "b2"), loaded.map { it.buildingId }.toSet())
    }

    @Test
    fun `removes cached buildings no longer published`() {
        val cache = OfflineMapCache(temporaryFolder.root, Json { ignoreUnknownKeys = true })

        cache.save(listOf(building("b1", "Library"), building("b2", "Science Block")))
        cache.save(listOf(building("b1", "Library")))

        val loaded = cache.load()

        assertEquals(listOf("b1"), loaded.map { it.buildingId })
    }

    @Test
    fun `skips malformed cache files`() {
        val cache = OfflineMapCache(temporaryFolder.root, Json { ignoreUnknownKeys = true })
        cache.save(listOf(building("b1", "Library")))
        temporaryFolder.root.resolve("broken.json").writeText("{ not valid json")

        val loaded = cache.load()

        assertEquals(listOf("b1"), loaded.map { it.buildingId })
        assertTrue(loaded.first().buildingName == "Library")
    }
}
