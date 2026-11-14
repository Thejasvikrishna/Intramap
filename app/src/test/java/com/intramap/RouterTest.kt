package com.intramap

import com.intramap.data.model.*
import com.intramap.domain.routing.*
import org.junit.Assert.*
import org.junit.Test

class RouterTest {
    private fun n(id: String, f: String, x: Float, y: Float) =
        LocationNode(id, f, id, "X", x, y)
    private fun e(id: String, a: String, b: String, d: Float, t: String = "NORMAL") =
        PathEdge(id, a, b, d, t)

    private val building = Building(
        "t", "Test", "",
        floors = listOf(Floor("A0", 0, "G", 40f, 20f), Floor("A1", 1, "F", 40f, 20f)),
        nodes = listOf(
            n("A0_entry", "A0", 2f, 10f), n("A0_j1", "A0", 20f, 10f),
            n("A0_stairs", "A0", 30f, 10f), n("A0_elev", "A0", 36f, 10f),
            n("A1_stairs", "A1", 30f, 10f), n("A1_elev", "A1", 36f, 10f),
            n("A1_j1", "A1", 20f, 10f), n("A1_lab", "A1", 20f, 16f)
        ),
        edges = listOf(
            e("e1", "A0_entry", "A0_j1", 18f), e("e4", "A0_j1", "A0_stairs", 10f),
            e("e5", "A0_stairs", "A0_elev", 6f),
            e("e6", "A0_stairs", "A1_stairs", 8f, "STAIRS_ONLY"),
            e("e7", "A0_elev", "A1_elev", 8f, "STEP_FREE"),
            e("e8", "A1_stairs", "A1_elev", 6f), e("e9", "A1_stairs", "A1_j1", 10f),
            e("e10", "A1_j1", "A1_lab", 6f)
        )
    )
    private val g = IndoorGraph(building)

    @Test fun astarMatchesDijkstra() {
        val a = findRoute(g, "A0_entry", "A1_lab", useAStar = true)!!
        val d = findRoute(g, "A0_entry", "A1_lab", useAStar = false)!!
        assertEquals(d.totalDistance, a.totalDistance, 0.001f)
        assertTrue(a.nodesExpanded <= d.nodesExpanded)
    }

    @Test fun standardUsesStairs() {
        assertEquals(52f, findRoute(g, "A0_entry", "A1_lab")!!.totalDistance, 0.001f)
    }

    @Test fun stepFreeUsesElevator() {
        val r = findRoute(g, "A0_entry", "A1_lab", AccessibilityMode.STEP_FREE)!!
        assertEquals(64f, r.totalDistance, 0.001f)
        assertTrue("e7" in r.edgeIds)
    }

    @Test fun blockedStairsReroutesViaElevator() {
        val r = findRoute(g, "A0_entry", "A1_lab", blockedEdgeIds = setOf("e6"))!!
        assertEquals(64f, r.totalDistance, 0.001f)
    }

    @Test fun noRouteWhenAllFloorLinksBlocked() {
        assertNull(findRoute(g, "A0_entry", "A1_lab", blockedEdgeIds = setOf("e6", "e7")))
    }
}