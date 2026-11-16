package com.intramap.data.repository

import com.intramap.domain.mapping.WalkingSample
import com.intramap.domain.mapping.WalkingTrace
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SavedWalkingDraft(
    val account: String, val buildingId: String, val floorKey: String, val recordingId: String,
    val samples: List<WalkingSample>, val revision: Long,
    val uploadAttempted: Boolean = false, val submitted: Boolean = false, val formatVersion: Int = 1
)

/** One bounded, process-scoped writer per account/building/floor; callers await durability receipts. */
class WalkingDraftStore internal constructor(
    private val directory: File, private val account: String, private val buildingId: String, private val floorKey: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val digest = MessageDigest.getInstance("SHA-256").digest("$account|$buildingId|$floorKey".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val target = File(directory,"$digest.json")
    private val pending = File(directory,"$digest.pending")
    private val queue = Channel<() -> Unit>(64)
    private var initialized = false
    private var cached: SavedWalkingDraft? = null
    init { io.launch { for (operation in queue) operation() } }

    private fun validate(draft: SavedWalkingDraft) {
        require(draft.formatVersion == 1 && draft.revision >= 0)
        require(draft.account == account && draft.buildingId == buildingId && draft.floorKey == floorKey)
        UUID.fromString(draft.recordingId)
        require(!draft.submitted || draft.uploadAttempted)
        WalkingTrace.restore(draft.samples)
    }
    private fun read(): SavedWalkingDraft? {
        if (!initialized) {
            if (target.exists()) {
                require(target.length() <= 8L * 1024 * 1024) { "Saved recording is too large" }
                cached = json.decodeFromString<SavedWalkingDraft>(target.readText()).also { validate(it) }
            }
            initialized = true
        }
        return cached
    }
    private fun <T> request(action: () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        if (queue.trySend {
            try { result.complete(action()) } catch (error: Exception) { result.completeExceptionally(error) }
        }.isFailure) result.completeExceptionally(IllegalStateException("Local recording writer is busy"))
        return result
    }
    fun load(): Deferred<SavedWalkingDraft?> = request { read() }
    fun save(draft: SavedWalkingDraft): Deferred<Unit> = request {
        validate(draft)
        val previous = read()
        check(previous == null || previous.recordingId == draft.recordingId) { "Another local recording already exists" }
        if (previous == null || draft.revision >= previous.revision) {
            check(previous?.uploadAttempted != true || (draft.uploadAttempted && draft.samples == previous.samples)) { "Uploaded geometry is frozen" }
            check(previous?.submitted != true || draft.submitted)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
            FileOutputStream(pending).use { stream ->
                stream.write(json.encodeToString(draft).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Fail visibly rather than silently falling back to non-atomic replacement.
            Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            cached = draft
        }
        Unit
    }
    fun deleteLocalCopy(): Deferred<Unit> = request {
        Files.deleteIfExists(target.toPath()); Files.deleteIfExists(pending.toPath())
        cached = null; initialized = true
        Unit
    }
    companion object {
        private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val instances = ConcurrentHashMap<String,WalkingDraftStore>()
        fun open(directory: File, account: String, buildingId: String, floorKey: String): WalkingDraftStore {
            require(account.isNotBlank() && buildingId.isNotBlank() && floorKey.isNotBlank())
            val key = "${directory.canonicalPath}|$account|$buildingId|$floorKey"
            return instances.getOrPut(key) { WalkingDraftStore(directory,account,buildingId,floorKey) }
        }
    }
}
