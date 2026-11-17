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

/** Numeric geometry only. Save receipts complete only after atomic, durable replacement. */
class WalkingDraftStore internal constructor(
    private val directory: File, private val account: String, private val buildingId: String, private val floorKey: String
) {
    private sealed interface Operation
    private class Save(val draft: SavedWalkingDraft,val result: CompletableDeferred<Unit>) : Operation
    private class Load(val result: CompletableDeferred<SavedWalkingDraft?>) : Operation
    private class Delete(val result: CompletableDeferred<Unit>) : Operation
    private val json = Json { ignoreUnknownKeys = true }
    private val digest = MessageDigest.getInstance("SHA-256").digest("$account|$buildingId|$floorKey".toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val target = File(directory,"$digest.json")
    private val pending = File(directory,"$digest.pending")
    private val queue = Channel<Operation>(64)
    private var initialized = false
    private var cached: SavedWalkingDraft? = null
    init {
        io.launch {
            var barrier: Operation? = null
            while (true) {
                val operation = barrier ?: queue.receive()
                barrier = null
                when (operation) {
                    is Save -> {
                        delay(750)
                        val batch = mutableListOf(operation)
                        while (batch.size < 64) {
                            val next = queue.tryReceive().getOrNull() ?: break
                            if (next is Save) batch += next else { barrier = next; break }
                        }
                        saveBatch(batch)
                    }
                    is Load -> try { operation.result.complete(read()) } catch (e: Exception) { operation.result.completeExceptionally(e) }
                    is Delete -> try {
                        Files.deleteIfExists(target.toPath()); Files.deleteIfExists(pending.toPath())
                        cached = null; initialized = true; operation.result.complete(Unit)
                    } catch (e: Exception) { operation.result.completeExceptionally(e) }
                }
            }
        }
    }
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
    private fun saveBatch(batch: List<Save>) {
        val accepted = mutableListOf<Save>()
        try {
            var latest = read()
            var changed = false
            for (save in batch) {
                try {
                    val draft = save.draft
                    validate(draft)
                    val previous = latest
                    check(previous == null || previous.recordingId == draft.recordingId) { "Another local recording already exists" }
                    if (previous == null || draft.revision >= previous.revision) {
                        check(previous?.uploadAttempted != true || (draft.uploadAttempted && draft.samples == previous.samples)) { "Uploaded geometry is frozen" }
                        check(previous?.submitted != true || draft.submitted)
                        latest = draft; changed = true
                    }
                    accepted += save
                } catch (e: Exception) { save.result.completeExceptionally(e) }
            }
            if (changed) {
                val draft = requireNotNull(latest)
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording directory" }
                FileOutputStream(pending).use { stream ->
                    stream.write(json.encodeToString(draft).toByteArray(Charsets.UTF_8)); stream.fd.sync()
                }
                Files.move(pending.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                cached = draft
            }
            accepted.forEach { it.result.complete(Unit) }
        } catch (e: Exception) {
            batch.forEach { if (!it.result.isCompleted) it.result.completeExceptionally(e) }
        }
    }
    fun load(): Deferred<SavedWalkingDraft?> {
        val result = CompletableDeferred<SavedWalkingDraft?>()
        if (queue.trySend(Load(result)).isFailure) result.completeExceptionally(IllegalStateException("Local recording writer is busy"))
        return result
    }
    fun save(draft: SavedWalkingDraft): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        if (queue.trySend(Save(draft.copy(samples = draft.samples.toList()),result)).isFailure) result.completeExceptionally(IllegalStateException("Local recording writer is busy"))
        return result
    }
    fun deleteLocalCopy(): Deferred<Unit> {
        val result = CompletableDeferred<Unit>()
        if (queue.trySend(Delete(result)).isFailure) result.completeExceptionally(IllegalStateException("Local recording writer is busy"))
        return result
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
