package com.intramap.domain.routing

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionRoutingTest {

    private fun building(): Building {
        val floor = Floor("f1", 1, "Ground", 30f, 10f)
        val nodes = listOf(
            LocationNode("a", "f1", "Entrance", "ENTRANCE", 2f, 5f),
            LocationNode("b", "f1", "Junction", "JUNCTION", 10f, 5f),
            LocationNode("c", "f1", "Library", "ROOM", 20f, 5f)
        )
        val edges = listOf(
            PathEdge("e1", "a", "b", 8f),
            PathEdge("e2", "b", "c", 10f)
        )
        return Building("building", "Test Building", "", listOf(floor), nodes, edges)
    }

    @Test
    fun `routes from approximate position near middle node`() {
        val building = building()
        val graph = IndoorGraph(building)

        val start = selectRoutingStart(
            building = building,
            graph = graph,
            estimatedFloorId = "f1",
            x = 9.0,
            y = 5.0,
            headingRadians = 0f,
            destinationId = "c"
        )

        assertEquals("b", start)
    }

    @Test
    fun `rejects candidate that cannot reach destination`() {
        val building = building()
        val graph = IndoorGraph(building)

        val start = selectRoutingStart(
            building = building,
            graph = graph,
            estimatedFloorId = "f1",
            x = 2.0,
            y = 5.0,
            headingRadians = 0f,
            destinationId = "missing"
        )

        assertNull(start)
    }

    @Test
    fun `route progress advances after reaching next node`() {
        val building = building()
        val progress = RouteProgress(
            building.nodes.associateBy { it.nodeId },
            listOf("a", "b", "c")
        )

        val advanced = progress.update("f1", 10.0, 5.0)

        assertEquals(true, advanced)
        assertEquals("c", progress.nextNodeId)
    }

    @Test
    fun `route progress advances when user moves beyond next node`() {
        val building = building()
        val progress = RouteProgress(
            building.nodes.associateBy { it.nodeId },
            listOf("a", "b", "c")
        )

        val advanced = progress.update("f1", 13.0, 5.0)

        assertEquals(true, advanced)
        assertEquals("c", progress.nextNodeId)
    }
}
