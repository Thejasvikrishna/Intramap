package com.intramap.domain.mapping

import com.intramap.data.repository.SavedWalkingDraft
import com.intramap.data.repository.WalkingDraftStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class WalkingRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun draft(account: String = "account-a"): SavedWalkingDraft {
        val trace = WalkingTrace(1)
        trace.step(0.7,90.0,2); trace.mark("ROOM","Library")
        return SavedWalkingDraft(account,"building","floor",UUID.randomUUID().toString(),trace.snapshot(),1)
    }
    @Test fun `restoration preserves geometry markers and travelled distance`() {
        val original = draft()
        val restored = WalkingTrace.restore(original.samples)
        assertEquals(original.samples,restored.snapshot())
        assertEquals(0.7,restored.distanceM,1e-9)
        assertTrue(restored.step(0.7,180.0,3))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `restoration rejects discontinuous geometry`() {
        WalkingTrace.restore(listOf(WalkingSample(0.0,0.0,1,0.0),WalkingSample(20.0,0.0,2,0.0)))
    }
    @Test fun `snapshots survive store writes and another account cannot read them`() = runBlocking {
        val dir = temporary.newFolder()
        val first = WalkingDraftStore.open(dir,"account-a","building","floor")
        val saved = draft()
        first.save(saved).await()
        assertEquals(saved,WalkingDraftStore(dir,"account-a","building","floor").load().await())
        val other = WalkingDraftStore.open(dir,"account-b","building","floor")
        assertNull(other.load().await())
        first.deleteLocalCopy().await()
        assertNull(first.load().await())
    }
    @Test fun `older saves cannot overwrite newer acknowledged revisions`() = runBlocking {
        val store = WalkingDraftStore.open(temporary.newFolder(),"account-a","building","floor")
        val original = draft()
        store.save(original.copy(revision = 5)).await()
        store.save(original.copy(revision = 2)).await()
        assertEquals(5L,store.load().await()?.revision)
    }
    @Test fun `geometry is frozen after an upload attempt`() = runBlocking {
        val store = WalkingDraftStore.open(temporary.newFolder(),"account-a","building","floor")
        val frozen = draft().copy(uploadAttempted = true)
        store.save(frozen).await()
        val changed = WalkingTrace.restore(frozen.samples)
        changed.step(0.7,90.0,3)
        var rejected = false
        try { store.save(frozen.copy(samples = changed.snapshot(),revision = 2)).await() }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(frozen,store.load().await())
    }
}
