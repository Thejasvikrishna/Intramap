package com.intramap.data.repository

import com.intramap.domain.mapping.WalkingSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class WalkingCheckpointBatchTest {
    private fun draft(id: String,revision: Long,uploaded: Boolean = false) = SavedWalkingDraft(
        "account","building","floor",id,listOf(WalkingSample(0.0,0.0,0L,0.0,"START")),revision,uploaded
    )
    @Test fun rapidSavesRetainLatestRevisionAndReloadFromDisk() = runBlocking {
        val directory = Files.createTempDirectory("walking-batch").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val id = UUID.randomUUID().toString()
        val receipts = (1L..20L).map { store.save(draft(id,it)) }
        receipts.forEach { it.await() }
        assertEquals(20L,store.load().await()!!.revision)
        val reopened = WalkingDraftStore(directory,"account","building","floor")
        assertEquals(20L,reopened.load().await()!!.revision)
        assertEquals(1,directory.listFiles()!!.count { it.extension == "json" })
        directory.deleteRecursively()
    }
    @Test fun queuedDeleteIsABarrierBeforeAnotherRecording() = runBlocking {
        val directory = Files.createTempDirectory("walking-barrier").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val first = store.save(draft(UUID.randomUUID().toString(),1L))
        val removal = store.deleteLocalCopy()
        val secondId = UUID.randomUUID().toString()
        val second = store.save(draft(secondId,1L))
        first.await(); removal.await(); second.await()
        assertEquals(secondId,store.load().await()!!.recordingId)
        directory.deleteRecursively()
    }
    @Test fun frozenGeometryCannotChangeWithinOneBatch() = runBlocking {
        val directory = Files.createTempDirectory("walking-frozen").toFile()
        val store = WalkingDraftStore(directory,"account","building","floor")
        val id = UUID.randomUUID().toString()
        val frozen = draft(id,1L,true)
        val first = store.save(frozen)
        val changed = store.save(frozen.copy(revision = 2L,samples = listOf(WalkingSample(0.0,0.0,0L,0.0,"ROOM:Changed"))))
        first.await()
        try { changed.await(); fail("Frozen geometry was accepted") } catch (_: IllegalStateException) { }
        assertEquals(frozen.samples,store.load().await()!!.samples)
        directory.deleteRecursively()
    }
}
