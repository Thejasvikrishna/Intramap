package com.intramap.navigation

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationSessionTest {

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
    fun `choosing destination creates route`() {
        val session = NavigationSession(building())
        session.chooseStart("a")
        session.setDestination("c")

        assertNotNull(session.route)
        assertEquals(listOf("a", "b", "c"), session.route?.nodeIds)
    }

    @Test
    fun `invalid destination does not create route`() {
        val session = NavigationSession(building())
        session.chooseStart("a")
        session.setDestination("missing")

        assertNull(session.route)
    }

    @Test
    fun `accessibility preference recalculates route`() {
        val session = NavigationSession(building())
        session.chooseStart("a")
        session.setDestination("c")
        session.updateAccessibilityPreference(true)

        assertNotNull(session.route)
        assertEquals(true, session.stepFree)
    }
}
