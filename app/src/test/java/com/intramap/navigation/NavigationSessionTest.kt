package com.intramap.navigation

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionTest {

    private fun building(): Building {
        val floor = Floor(
            "f1",
            1,
            "Ground",
            30f,
            10f
        )

        val nodes = listOf(
            LocationNode(
                "a",
                "f1",
                "Entrance",
                "ENTRANCE",
                2f,
                5f
            ),
            LocationNode(
                "b",
                "f1",
                "Junction",
                "JUNCTION",
                10f,
                5f
            ),
            LocationNode(
                "c",
                "f1",
                "Library",
                "ROOM",
                20f,
                5f
            )
        )

        val edges = listOf(
            PathEdge("e1", "a", "b", 8f),
            PathEdge("e2", "b", "c", 10f)
        )

        return Building(
            "building",
            "Test Building",
            "",
            listOf(floor),
            nodes,
            edges
        )
    }

    private fun newSession(): NavigationSession {
        return NavigationSession(
            building = building(),
            nowMs = { 10_000L }
        )
    }

    private fun confirmedSession(): NavigationSession {
        val session = newSession()

        assertTrue(
            "Fixture map is invalid: ${session.errors}",
            session.errors.isEmpty()
        )

        session.chooseStart("a")
        session.confirmStart()

        assertTrue(
            "Starting location was not initialized: ${session.status}",
            session.ready
        )

        return session
    }

    @Test
    fun `choosing destination creates route`() {
        val session = confirmedSession()

        session.setDestination("c")

        assertNotNull(
            "Expected a route after start confirmation: ${session.status}",
            session.route
        )

        assertEquals(
            listOf("a", "b", "c"),
            session.route?.nodeIds
        )
    }

    @Test
    fun `invalid destination does not create route`() {
        val session = confirmedSession()

        session.setDestination("missing")

        assertNull(session.route)
        assertNull(session.destination)
    }

    @Test
    fun `accessibility preference recalculates route`() {
        val session = confirmedSession()

        session.setDestination("c")

        assertNotNull(
            "Initial route was not created: ${session.status}",
            session.route
        )

        session.updateAccessibilityPreference(true)

        assertTrue(session.stepFree)

        assertNotNull(
            "Expected an accessible route: ${session.status}",
            session.route
        )

        assertEquals(
            listOf("a", "b", "c"),
            session.route?.nodeIds
        )
    }

    @Test
    fun `choosing a start without confirming does not initialize navigation`() {
        val session = newSession()

        session.chooseStart("a")
        session.setDestination("c")

        assertEquals(false, session.ready)
        assertNull(session.estimate)
        assertNull(session.route)
    }

    @Test
    fun `reference pose relocalizes and creates route`() {
        val session = newSession()

        session.setDestination("c")

        val accepted = session.applyReferencePose(
            floorId = "f1",
            x = 5.0,
            y = 5.0,
            headingDeg = 0.0
        )

        assertTrue(
            "Expected reference pose to be accepted: ${session.status}",
            accepted
        )

        assertNotNull(session.estimate)
        assertNotNull(
            "Expected a route after AR relocalization: ${session.status}",
            session.route
        )

        assertEquals(
            "c",
            session.route?.nodeIds?.last()
        )
    }

    @Test
    fun `reference pose far from network is rejected`() {
        val session = newSession()

        session.setDestination("c")

        val accepted = session.applyReferencePose(
            floorId = "f1",
            x = 5.0,
            y = 19.5,
            headingDeg = 0.0
        )

        assertEquals(false, accepted)
        assertNull(session.route)
    }
}
