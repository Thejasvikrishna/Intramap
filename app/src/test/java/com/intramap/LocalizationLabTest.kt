package com.intramap

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import com.intramap.localization.lab.LocalizationLabEngine
import org.junit.Assert.*
import org.junit.Test

class LocalizationLabTest {

    private val building = Building(
        buildingId = "test",
        buildingName = "Test Building",
        description = "",
        floors = listOf(
            Floor("G", 0, "Ground", 40f, 20f),
            Floor("F", 1, "First", 40f, 20f)
        ),
        nodes = listOf(
            LocationNode(
                "entry", "G", "Entrance", "ENTRANCE", 2f, 10f
            ),
            LocationNode(
                "junction", "G", "Junction", "JUNCTION", 20f, 10f
            ),
            LocationNode(
                "stairsG", "G", "Stairs", "STAIRS", 30f, 10f
            ),
            LocationNode(
                "stairsF", "F", "Stairs", "STAIRS", 30f, 10f
            ),
            LocationNode(
                "lab", "F", "Lab", "LAB", 20f, 10f
            )
        ),
        edges = listOf(
            PathEdge("e1", "entry", "junction", 18f),
            PathEdge("e2", "junction", "stairsG", 10f),
            PathEdge(
                "e3",
                "stairsG",
                "stairsF",
                8f,
                "STAIRS_ONLY"
            ),
            PathEdge("e4", "stairsF", "lab", 10f)
        )
    )

    private fun engine(start: String = "entry") =
        LocalizationLabEngine(
            building = building,
            seed = 42
        ).apply {
            reset(start)
        }

    @Test
    fun resetCreatesNormalizedParticles() {
        val engine = engine()
        val particles = engine.particles()

        assertEquals(300, particles.size)
        assertEquals(1.0, particles.sumOf { it.weight }, 1e-9)
        assertTrue(particles.all { it.floorId == "G" })
    }

    @Test
    fun rightwardMotionIncreasesWeightedMeanX() {
        val engine = engine()

        val before = engine.particles().sumOf {
            it.x * it.weight
        }

        repeat(5) {
            engine.predict(0.7, 90.0)
        }

        val after = engine.particles().sumOf {
            it.x * it.weight
        }

        assertTrue(after > before + 2.0)
    }

    @Test
    fun poorAdaptiveObservationIsRejected() {
        val engine = engine()
        val before = engine.particles()

        val update = engine.observeLandmark(
            nodeId = "entry",
            simulatedQuality = 0.1,
            adaptive = true
        )

        assertFalse(update.accepted)
        assertEquals(before, engine.particles())
    }

    @Test
    fun wrongFloorObservationIsRejected() {
        val engine = engine()

        val update = engine.observeLandmark(
            nodeId = "lab",
            simulatedQuality = 0.9
        )

        assertFalse(update.accepted)
        assertEquals("G", engine.estimate().floorId)
    }

    @Test
    fun nearbyLandmarkObservationIsAccepted() {
        val engine = engine()

        val update = engine.observeLandmark(
            nodeId = "entry",
            simulatedQuality = 0.9
        )

        assertTrue(update.accepted)

        assertEquals(
            1.0,
            engine.particles().sumOf { it.weight },
            1e-9
        )
    }

    @Test
    fun floorTransitionRejectedAwayFromConnector() {
        val engine = engine()

        val update = engine.confirmFloorTransition("stairsF")

        assertFalse(update.accepted)
        assertEquals("G", engine.estimate().floorId)
    }

    @Test
    fun confirmedMappedTransitionChangesFloor() {
        val engine = engine("stairsG")

        val update = engine.confirmFloorTransition("stairsF")

        assertTrue(update.accepted)
        assertEquals("F", engine.estimate().floorId)
    }

    @Test
    fun distantConflictingLandmarkIsRejected() {
        val engine = engine()

        val update = engine.observeLandmark(
            nodeId = "stairsG",
            simulatedQuality = 0.95
        )

        assertFalse(update.accepted)
    }

    @Test
    fun equalSeedsAndInputsProduceEqualParticles() {
        val first = engine()
        val second = engine()

        repeat(10) {
            first.predict(0.7, 90.0)
            second.predict(0.7, 90.0)
        }

        assertEquals(first.particles(), second.particles())
    }

    @Test
    fun relocalizeAcceptsNearbyNetworkPoint() {
        val engine = engine()

        val update = engine.relocalize(
            floorId = "G",
            x = 5.0,
            y = 10.0
        )

        assertTrue(update.accepted)
        assertEquals("entry", update.anchorNodeId)
        assertEquals("G", engine.estimate().floorId)
        assertEquals(300, engine.particles().size)
    }

    @Test
    fun relocalizeRejectsFarPoint() {
        val engine = engine()

        val update = engine.relocalize(
            floorId = "G",
            x = 5.0,
            y = 19.5
        )

        assertFalse(update.accepted)
    }

    @Test
    fun relocalizeRejectsUnknownFloor() {
        val engine = engine()

        val update = engine.relocalize(
            floorId = "missing",
            x = 5.0,
            y = 10.0
        )

        assertFalse(update.accepted)
    }
}
