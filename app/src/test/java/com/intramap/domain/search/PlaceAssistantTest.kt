package com.intramap.domain.search

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceAssistantTest {

    private fun building(): Building {
        val floor = Floor("f1", 1, "Ground", 30f, 10f)
        val nodes = listOf(
            LocationNode("entrance", "f1", "Main Entrance", "ENTRANCE", 2f, 5f, keywords = listOf("entry", "door")),
            LocationNode("restroom", "f1", "Restroom", "ROOM", 10f, 5f, keywords = listOf("toilet", "washroom")),
            LocationNode("library", "f1", "Library", "ROOM", 20f, 5f, keywords = listOf("books"))
        )
        val edges = listOf(
            PathEdge("e1", "entrance", "restroom", 8f),
            PathEdge("e2", "restroom", "library", 10f)
        )
        return Building("building", "Test Building", "", listOf(floor), nodes, edges)
    }

    @Test
    fun `finds restroom request`() {
        val result = PlaceAssistant.interpret(building(), "f1", "where is the restroom")

        assertNotNull(result.destination)
        assertEquals("restroom", result.destination?.nodeId)
    }

    @Test
    fun `finds destination using take me to`() {
        val result = PlaceAssistant.interpret(building(), "f1", "take me to the library")

        assertNotNull(result.destination)
        assertEquals("library", result.destination?.nodeId)
    }

    @Test
    fun `does not invent unknown place`() {
        val result = PlaceAssistant.interpret(building(), "f1", "take me to mars")

        assertNull(result.destination)
    }

    @Test
    fun `nearby places prioritize current floor`() {
        val places = PlaceAssistant.nearbyPlaces(building(), "f1")

        assertEquals(3, places.size)
    }
}
