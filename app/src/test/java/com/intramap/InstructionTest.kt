package com.intramap

import com.intramap.data.model.*
import com.intramap.domain.navigation.generateInstructions
import com.intramap.domain.routing.*
import org.junit.Assert.*
import org.junit.Test

class InstructionTest {
    private fun n(id: String, f: String, x: Float, y: Float) = LocationNode(id, f, id, "X", x, y)
    private fun e(id: String, a: String, b: String, d: Float, t: String = "NORMAL") = PathEdge(id, a, b, d, t)

    private val building = Building("t", "T", "",
        listOf(Floor("A0", 0, "Ground", 40f, 20f), Floor("A1", 1, "First", 40f, 20f)),
        listOf(n("A0_entry", "A0", 2f, 10f), n("A0_j1", "A0", 20f, 10f), n("A0_stairs", "A0", 30f, 10f),
            n("A1_stairs", "A1", 30f, 10f), n("A1_j1", "A1", 20f, 10f), n("A1_lab", "A1", 20f, 16f)),
        listOf(e("e1", "A0_entry", "A0_j1", 18f), e("e4", "A0_j1", "A0_stairs", 10f),
            e("e6", "A0_stairs", "A1_stairs", 8f, "STAIRS_ONLY"),
            e("e9", "A1_stairs", "A1_j1", 10f), e("e10", "A1_j1", "A1_lab", 6f)))

    @Test fun stairsThenLeftTurn() {
        val g = IndoorGraph(building)
        val r = findRoute(g, "A0_entry", "A1_lab")!!
        val s = generateInstructions(g, r, mapOf("A1" to "First"))
        assertEquals(5, s.size)
        assertTrue(s[0].text.startsWith("Start at"))
        assertTrue(s[1].text.contains("stairs to the First floor"))
        assertTrue(s[2].text.startsWith("Walk towards"))
        assertTrue(s[3].text.startsWith("Turn left"))
        assertTrue(s[4].text.startsWith("You have arrived"))
        assertEquals(28f, s[0].distanceM, 0.001f)
    }
}