package com.intramap.data.supabase

import com.intramap.data.repository.SavedWalkingDraft
import com.intramap.domain.mapping.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class RecordingPlaceSubmissionTest {
    private fun snapshot(): SavedWalkingDraft {
        val trace = WalkingTrace(0L)
        check(trace.step(1.0,0.0,1000L))
        val places = RoutePlaceEdits.restore(trace.snapshot())
            .add(1,"Book shop",RoutePlaceCategory.SHOP)
            .add(1,"Room 101",RoutePlaceCategory.ROOM).places
        return SavedWalkingDraft("account","building","floor",UUID.randomUUID().toString(),trace.snapshot(),2L,
            uploadAttempted = true,formatVersion = 2,places = places)
    }
    @Test fun payloadContainsEveryPlaceWithoutFakeMovement() {
        val draft = snapshot()
        val payload = placeSubmissionParameters(draft)
        assertEquals(2,payload.getValue("p_expected_points").jsonPrimitive.int)
        val places = payload.getValue("p_places").jsonArray
        assertEquals(2,places.size)
        assertEquals(listOf(1,1),places.map { it.jsonObject.getValue("sampleIndex").jsonPrimitive.int })
        assertEquals(draft.recordingId,payload.getValue("p_recording").jsonPrimitive.content)
    }
    @Test fun retryPayloadIsStable() {
        val draft = snapshot()
        assertEquals(placeSubmissionParameters(draft),placeSubmissionParameters(draft.copy(revision = 3L)))
    }
    @Test fun mutableSnapshotIsRejectedBeforeNetworkCall() {
        try { placeSubmissionParameters(snapshot().copy(uploadAttempted = false)); fail("Mutable snapshot accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
