package com.intramap.domain.mapping

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RoutePlaceEditsTest {
    private fun samples(): List<WalkingSample> {
        val trace = WalkingTrace(0L)
        check(trace.step(1.0,0.0,1000L))
        return trace.snapshot()
    }
    @Test fun twoPlacesAtSamePositionDoNotOverwrite() {
        val places = RoutePlaceEdits.restore(samples())
            .add(1,"Book shop",RoutePlaceCategory.SHOP)
            .add(1,"Room 101",RoutePlaceCategory.ROOM)
        assertEquals(2,places.places.size)
        assertEquals(2,places.places.map { it.placeId }.distinct().size)
        assertEquals(listOf(1,1),places.places.map { it.sampleIndex })
    }
    @Test fun editsPreserveIdentityAndUndoRedo() {
        val initial = RoutePlaceEdits.restore(samples()).add(1,"Office",RoutePlaceCategory.OFFICE)
        val id = initial.places.single().placeId
        val moved = initial.rename(id,"Main office",RoutePlaceCategory.OFFICE).moveToSample(id,0)
        assertEquals(id,moved.places.single().placeId)
        assertEquals(0,moved.places.single().sampleIndex)
        val deleted = moved.remove(id)
        assertTrue(deleted.places.isEmpty())
        assertEquals(moved.places,deleted.undo().places)
        assertTrue(deleted.undo().redo().places.isEmpty())
    }
    @Test fun placeRecordsRoundTripWithoutChangingGeometry() {
        val geometry = samples()
        val initial = RoutePlaceEdits.restore(geometry).add(0,"Entrance",RoutePlaceCategory.ENTRANCE)
        val encoded = Json.encodeToString(initial.places)
        val restored = RoutePlaceEdits.restore(geometry,Json.decodeFromString<List<RecordedRoutePlace>>(encoded))
        assertEquals(initial.places,restored.places)
        assertEquals(geometry,samples())
    }
    @Test fun unknownPositionsAndFrozenEditsAreRejected() {
        val initial = RoutePlaceEdits.restore(samples()).add(1,"Shop",RoutePlaceCategory.SHOP)
        try { initial.add(99,"Invalid",RoutePlaceCategory.ROOM); fail("Unknown position accepted") } catch (_: IllegalArgumentException) { }
        val frozen = initial.freeze()
        try { frozen.remove(frozen.places.single().placeId); fail("Frozen place deleted") } catch (_: IllegalStateException) { }
        assertFalse(frozen.canUndo)
    }
}
