package com.intramap.data.repository

import com.intramap.domain.mapping.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class WalkingPlaceCheckpointTest {
    private fun draft(): SavedWalkingDraft {
        val trace = WalkingTrace(0L)
        check(trace.step(1.0,0.0,1000L))
        return SavedWalkingDraft("account","building","floor",UUID.randomUUID().toString(),trace.snapshot(),1L)
    }
    @Test fun legacyCheckpointDecodesWithoutPlaceField() {
        val id = UUID.randomUUID().toString()
        val old = """{"account":"account","buildingId":"building","floorKey":"floor","recordingId":"$id","samples":[{"x":0.0,"y":0.0,"timeMs":0,"headingDeg":0.0,"marker":"START"}],"revision":1}"""
        val decoded = Json.decodeFromString<SavedWalkingDraft>(old)
        decoded.validateFor("account","building","floor")
        assertEquals(1,decoded.formatVersion)
        assertTrue(decoded.places.isEmpty())
    }
    @Test fun multiplePlaceMarkersSurviveDiskRecovery() = runBlocking {
        val directory = Files.createTempDirectory("place-checkpoint").toFile()
        val original = draft()
        val places = RoutePlaceEdits.restore(original.samples)
            .add(1,"Book shop",RoutePlaceCategory.SHOP)
            .add(1,"Room 101",RoutePlaceCategory.ROOM).places
        val marked = original.withPlaces(places,2L)
        WalkingDraftStore(directory,"account","building","floor").save(marked).await()
        val restored = WalkingDraftStore(directory,"account","building","floor").load().await()!!
        assertEquals(marked,restored)
        assertEquals(2,restored.places.size)
        assertTrue(directory.deleteRecursively())
    }
    @Test fun frozenPlacesAndCheckpointDowngradeAreRejected() = runBlocking {
        val directory = Files.createTempDirectory("place-frozen").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val original = draft()
        val marked = original.withPlaces(RoutePlaceEdits.restore(original.samples).add(1,"Shop",RoutePlaceCategory.SHOP).places,2L)
        store.save(marked).await()
        try { store.save(original.copy(revision = 3L)).await(); fail("Downgrade accepted") } catch (_: IllegalStateException) { }
        val frozen = marked.copy(revision = 4L,uploadAttempted = true)
        store.save(frozen).await()
        try { store.save(frozen.copy(revision = 5L,places = emptyList())).await(); fail("Frozen markers changed") } catch (_: IllegalStateException) { }
        assertEquals(frozen,store.load().await())
        assertTrue(directory.deleteRecursively())
    }
}
