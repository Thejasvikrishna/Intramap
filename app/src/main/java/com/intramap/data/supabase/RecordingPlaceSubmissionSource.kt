package com.intramap.data.supabase

import com.intramap.data.model.PathRecording
import com.intramap.data.model.PathRecordingPoint
import com.intramap.data.repository.SavedWalkingDraft
import com.intramap.domain.mapping.submitFrozenRoute
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

class RecordingPlaceSubmissionSource(private val client: SupabaseClient) {
    suspend fun requireBackend() = PlaceWorkflowSource(client).requireInstalled()
    /** Never invokes camera APIs. Retries skip point writes after remote submission. */
    suspend fun uploadFrozenRecording(draft: SavedWalkingDraft) {
        requireBackend()
        submitFrozenRoute(draft,readRemoteStatus = {
            val remote = client.from("path_recordings").select { filter { eq("id",draft.recordingId) } }.decodeSingle<PathRecording>()
            check(remote.id == draft.recordingId && remote.buildingId == draft.buildingId && remote.floorKey == draft.floorKey) { "Recording scope changed" }
            check(remote.recorderId == client.auth.currentUserOrNull()?.id) { "Recording owner sign-in required" }
            remote.status
        },uploadPoints = {
            draft.samples.mapIndexed { index,p -> PathRecordingPoint(draft.recordingId,index,p.x.toFloat(),p.y.toFloat(),p.timeMs,p.headingDeg.toFloat(),p.marker) }
                .chunked(100).forEach { batch -> client.from("path_recording_points").upsert(batch) { onConflict = "recording_id,seq" } }
        },finalizePlaces = { submitUploadedRecording(draft) })
    }
    suspend fun submitUploadedRecording(draft: SavedWalkingDraft) {
        client.postgrest.rpc("submit_walking_recording_with_places",placeSubmissionParameters(draft))
    }
}
internal fun placeSubmissionParameters(draft: SavedWalkingDraft): JsonObject {
    draft.validateFor(draft.account,draft.buildingId,draft.floorKey)
    require(draft.uploadAttempted) { "Checkpoint the frozen upload snapshot first" }
    require(draft.samples.size in 2..10000) { "A route needs movement before submission" }
    return buildJsonObject {
        put("p_recording",draft.recordingId); put("p_expected_points",draft.samples.size)
        put("p_places",Json.encodeToJsonElement(draft.places))
    }
}
