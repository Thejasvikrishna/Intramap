package com.intramap.navigation

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.vision.CameraLandmarkObservation
import org.junit.Assert.*
import org.junit.Test

class NavigationSessionTest {
    private fun map() = Building(
        buildingId = "test", buildingName = "Test",
        floors = listOf(Floor("G", 0, "Ground", 20f, 20f)),
        nodes = listOf(
            LocationNode("a", "G", "Entrance", "ENTRANCE", 2f, 5f),
            LocationNode("b", "G", "Library", "ROOM", 10f, 5f),
            LocationNode("c", "G", "C", "JUNCTION", 10f, 10f),
            LocationNode("d", "G", "D", "JUNCTION", 2f, 10f)
        ),
        edges = listOf(
            PathEdge("ab", "a", "b", 8f),
            PathEdge("bc", "b", "c", 5f),
            PathEdge("cd", "c", "d", 8f),
            PathEdge("da", "d", "a", 5f)
        )
    )

    @Test fun validMapHasNoErrors() { assertTrue(validateNavigationMap(map()).isEmpty()) }

    @Test fun invalidEndpointIsRejected() {
        val b = map()
        assertTrue(validateNavigationMap(b.copy(edges = b.edges + PathEdge("bad", "missing", "a", 1f))).isNotEmpty())
    }

    @Test fun duplicateNodesAreRejected() {
        val b = map()
        assertTrue(validateNavigationMap(b.copy(nodes = b.nodes + b.nodes.first())).isNotEmpty())
    }

    @Test fun projectionClampsToSegment() {
        val b = map()
        val p = projectPoint(50.0, 8.0, b.nodes[0], b.nodes[1])
        assertEquals(10.0, p.x, 1e-8)
        assertEquals(5.0, p.y, 1e-8)
        assertEquals(1.0, p.t, 1e-8)
    }

    @Test fun userMustConfirmStart() {
        val s = NavigationSession(map(), nowMs = { 10_000L })
        s.setDestination("b")
        assertFalse(s.ready)
        assertNull(s.route)
        s.chooseStart("a")
        assertFalse(s.ready)
        s.confirmStart()
        assertTrue(s.ready)
        assertEquals("b", s.route?.nodeIds?.last())
    }

    @Test fun blockedPathIsExcludedFromNewRoute() {
        val s = NavigationSession(map(), nowMs = { 10_000L })
        s.chooseStart("a")
        s.confirmStart()
        s.setDestination("b")
        assertTrue(s.route!!.edgeIds.contains("ab"))
        s.reportBlocked()
        assertTrue("ab" in s.blocked)
        assertFalse(s.route!!.edgeIds.contains("ab"))
        assertTrue(s.route!!.edgeIds.containsAll(listOf("da", "cd", "bc")))
    }

    @Test fun staleObservationCannotChangePosition() {
        val s = NavigationSession(map(), nowMs = { 100_000L })
        s.chooseStart("a")
        s.confirmStart()
        val before = s.estimate
        assertFalse(s.applyObservation(CameraLandmarkObservation("a", "G", "Entrance", 1L, 2)))
        assertEquals(before, s.estimate)
    }

    @Test fun wrongFloorObservationIsRejected() {
        val s = NavigationSession(map(), nowMs = { 10_000L })
        s.chooseStart("a")
        s.confirmStart()
        assertFalse(s.applyObservation(CameraLandmarkObservation("a", "other", "Entrance", 9_999L, 2)))
    }

    @Test fun repeatedMotionKeepsParticlesFiniteAndInsideFloor() {
        val e = LocalizationLabEngine(map())
        e.reset("a")
        repeat(60) { e.predict(0.7, 90.0) }
        assertTrue(e.particles().all { it.x.isFinite() && it.y.isFinite() && it.x in 0.0..20.0 && it.y in 0.0..20.0 })
        assertEquals(1.0, e.particles().sumOf { it.weight }, 1e-8)
    }
}
