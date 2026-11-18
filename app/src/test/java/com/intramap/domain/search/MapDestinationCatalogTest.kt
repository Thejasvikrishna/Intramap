package com.intramap.domain.search

import com.intramap.data.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MapDestinationCatalogTest {
    private fun building() = Building("b","Building",floors = listOf(Floor("f",0,"Ground",20f,20f)),nodes = listOf(
        LocationNode("start","f","Entrance","ENTRANCE",1f,1f),
        LocationNode("junction","f","Path point","JUNCTION",1f,5f,mappedPlaces = listOf(
            MappedPlace("shop","Book shop","SHOP"),MappedPlace("office","Office 101","OFFICE")))
    ),edges = listOf(PathEdge("e","start","junction",4f)))
    @Test fun separatePlacesShareRealAnchorWithoutChangingGraph() {
        val map = building()
        val found = map.destinationCatalog().filter { it.independentPlaceId != null }
        assertEquals(2,found.size)
        assertEquals(2,found.map { it.selectionId }.distinct().size)
        assertEquals(setOf("junction"),found.map { it.anchorNodeId }.toSet())
        assertEquals(2,map.nodes.size)
        assertEquals(1,map.edges.size)
        assertEquals("Book shop",map.searchDestinations("  book  ").single().name)
    }
    @Test fun placesSurviveOfflineSerialization() {
        val original = building()
        val restored = Json.decodeFromString<Building>(Json.encodeToString(original))
        assertEquals(original,restored)
        assertEquals(original.destinationCatalog(),restored.destinationCatalog())
    }
    @Test fun previewRetainsAnchorIdentityAndPlaceName() {
        val map = building()
        val place = map.searchDestinations("Office").single()
        val preview = place.previewNode(map)!!
        assertEquals("junction",preview.nodeId)
        assertEquals("Office 101",preview.name)
        assertEquals(1f,preview.x)
        assertEquals(5f,preview.y)
    }
    @Test fun oldMapsNeedNoPlaceMetadata() {
        val old = LocationNode("old","f","Room","ROOM",0f,0f)
        assertTrue(old.mappedPlaces.isEmpty())
        val map = building().copy(nodes = listOf(old),edges = emptyList())
        assertEquals("node:old",map.destinationCatalog().single().selectionId)
    }
}
