package com.intramap.data.supabase

import com.intramap.data.repository.SavedWalkingDraft
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** Caller must durably freeze and upload route points before invoking this RPC. */
class RecordingPlaceSubmissionSource(private val client: SupabaseClient) {
    suspend fun submitUploadedRecording(draft: SavedWalkingDraft) {
        client.postgrest.rpc("submit_walking_recording_with_places",placeSubmissionParameters(draft))
    }
}

internal fun placeSubmissionParameters(draft: SavedWalkingDraft): JsonObject {
    draft.validateFor(draft.account,draft.buildingId,draft.floorKey)
    require(draft.uploadAttempted) { "Checkpoint the frozen upload snapshot first" }
    require(draft.samples.size in 2..10000) { "A route needs movement before submission" }
    return buildJsonObject {
        put("p_recording",draft.recordingId)
        put("p_expected_points",draft.samples.size)
        put("p_places",Json.encodeToJsonElement(draft.places))
    }
}
