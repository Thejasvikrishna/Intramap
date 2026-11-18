package com.intramap.domain.mapping

import com.intramap.data.repository.SavedWalkingDraft
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SubmitFrozenRouteTest {
    private fun frozen(): SavedWalkingDraft {
        val trace = WalkingTrace(0L); check(trace.step(1.0,0.0,1000L))
        val places = RoutePlaceEdits.restore(trace.snapshot()).add(1,"Shop",RoutePlaceCategory.SHOP).places
        return SavedWalkingDraft("account","building","floor",UUID.randomUUID().toString(),trace.snapshot(),1L,uploadAttempted = true,formatVersion = 2,places = places)
    }
    @Test fun draftUploadsPointsBeforeFinalization() = runBlocking {
        val events = mutableListOf<String>()
        submitFrozenRoute(frozen(),{ events += "status"; "draft" },{ events += "points" },{ events += "places" })
        assertEquals(listOf("status","points","places"),events)
    }
    @Test fun lostResponseRetryDoesNotRewriteSubmittedPoints() = runBlocking {
        listOf("submitted","approved").forEach { status ->
            val events = mutableListOf<String>()
            submitFrozenRoute(frozen(),{ status },{ events += "unexpected-points" },{ events += "verify-places" })
            assertEquals(listOf("verify-places"),events)
        }
    }
    @Test fun failedPointUploadCannotFinalize() = runBlocking {
        var finalized = false
        try { submitFrozenRoute(frozen(),{ "draft" },{ error("network interruption") },{ finalized = true }); fail("Upload should fail") }
        catch (_: IllegalStateException) { }
        assertFalse(finalized)
    }
    @Test fun mutableSnapshotIsRejectedBeforeRemoteCalls() = runBlocking {
        var contacted = false
        try { submitFrozenRoute(frozen().copy(uploadAttempted = false),{ contacted = true; "draft" },{},{}); fail("Mutable upload accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(contacted)
    }
}
