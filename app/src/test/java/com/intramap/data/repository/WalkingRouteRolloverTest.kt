package com.intramap.data.repository

import com.intramap.domain.mapping.WalkingSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class WalkingRouteRolloverTest {
    private fun draft(submitted: Boolean = false,attempted: Boolean = submitted) = SavedWalkingDraft(
        "account","building","floor",UUID.randomUUID().toString(),
        listOf(WalkingSample(0.0,0.0,0L,0.0,"START")),1L,attempted,submitted
    )
    @Test fun submittedRouteIsRetainedAndNextRouteCanBeSaved() = runBlocking {
        val directory = Files.createTempDirectory("route-rollover").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val first = draft(submitted = true)
        store.save(first).await()
        assertNull(store.load().await())
        assertEquals(listOf(first.recordingId),store.submittedRecordingIds().await())
        val second = draft()
        store.save(second).await()
        assertEquals(second,store.load().await())
        val reopened = WalkingDraftStore(directory,"account","building","floor")
        assertEquals(second,reopened.load().await())
        assertEquals(listOf(first.recordingId),reopened.submittedRecordingIds().await())
        assertTrue(directory.deleteRecursively())
    }
    @Test fun partialUploadIsNeverRolledOver() = runBlocking {
        val directory = Files.createTempDirectory("route-partial").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val pending = draft(attempted = true)
        store.save(pending).await()
        assertEquals(pending,store.load().await())
        assertTrue(store.submittedRecordingIds().await().isEmpty())
        val other = store.save(draft())
        try { other.await(); fail("Unfinished route was replaced") } catch (_: IllegalStateException) { }
        assertEquals(pending,store.load().await())
        assertTrue(directory.deleteRecursively())
    }
    @Test fun historyIsScopedAndArchivedIdsCannotBeReactivated() = runBlocking {
        val directory = Files.createTempDirectory("route-scope").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val completed = draft(submitted = true)
        store.save(completed).await(); store.load().await()
        val stale = store.save(completed.copy(revision = 2L))
        try { stale.await(); fail("Archived route reactivated") } catch (_: IllegalStateException) { }
        val outsider = WalkingDraftStore(directory,"other-account","building","floor")
        assertTrue(outsider.submittedRecordingIds().await().isEmpty())
        store.deleteLocalCopy().await()
        assertEquals(listOf(completed.recordingId),store.submittedRecordingIds().await())
        assertTrue(directory.deleteRecursively())
    }
}
