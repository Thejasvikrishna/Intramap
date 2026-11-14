package com.intramap

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.vision.matchLandmarkText
import com.intramap.vision.normalizeSignText
import org.junit.Assert.*
import org.junit.Test

class LandmarkTextMatcherTest {

    private val building = Building(
        buildingId = "test",
        buildingName = "Test",
        description = "",
        floors = listOf(
            Floor("G", 0, "Ground", 40f, 20f),
            Floor("F", 1, "First", 40f, 20f)
        ),
        nodes = listOf(
            LocationNode(
                "library",
                "G",
                "Library",
                "LIBRARY",
                10f,
                10f
            ),
            LocationNode(
                "lab",
                "F",
                "AI Lab 101",
                "LAB",
                20f,
                10f
            ),
            LocationNode(
                "office",
                "F",
                "HOD Office",
                "OFFICE",
                30f,
                10f
            )
        ),
        edges = emptyList()
    )

    @Test
    fun normalizesWhitespaceAndPunctuation() {
        assertEquals(
            "AI LAB 101",
            normalizeSignText(" ai-lab\n101 ")
        )
    }

    @Test
    fun matchesSpecificRoomName() {
        val matches = matchLandmarkText(
            building,
            "DEPARTMENT\nAI LAB 101",
            "F"
        )

        assertEquals(1, matches.size)
        assertEquals("lab", matches.single().nodeId)
    }

    @Test
    fun rejectsPartialGenericName() {
        assertTrue(
            matchLandmarkText(
                building,
                "LAB",
                "F"
            ).isEmpty()
        )
    }

    @Test
    fun rejectsWrongFloor() {
        assertTrue(
            matchLandmarkText(
                building,
                "AI LAB 101",
                "G"
            ).isEmpty()
        )
    }

    @Test
    fun preservesAmbiguityForCallerToReject() {
        val matches = matchLandmarkText(
            building,
            "AI LAB 101\nHOD OFFICE",
            "F"
        )

        assertEquals(2, matches.size)
    }

    @Test
    fun rejectsSubstringInsideAnotherWord() {
        assertTrue(
            matchLandmarkText(
                building,
                "LIBRARYANNEX",
                "G"
            ).isEmpty()
        )
    }
}