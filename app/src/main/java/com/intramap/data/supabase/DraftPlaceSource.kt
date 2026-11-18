package com.intramap.data.supabase

import com.intramap.domain.mapping.RoutePlaceCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class DraftMappedPlace(
    @SerialName("version_id") val versionId: String,
    @SerialName("place_id") val placeId: String,
    @SerialName("node_key") val nodeKey: String,
    val name: String,
    val category: RoutePlaceCategory
)

/** Place edits attach destinations to existing graph points; they never invent walking edges. */
class DraftPlaceSource(private val client: SupabaseClient) {
    suspend fun load(versionId: String): List<DraftMappedPlace> = client.from("map_places").select {
        filter { eq("version_id",versionId) }
    }.decodeList<DraftMappedPlace>()

    suspend fun save(place: DraftMappedPlace,reviewedFingerprint: String) {
        require(place.name.isNotBlank() && place.name.length <= 120)
        client.postgrest.rpc("save_draft_place",buildJsonObject {
            put("v",place.versionId); put("expected_fingerprint",reviewedFingerprint)
            put("p_place_id",place.placeId); put("p_node_key",place.nodeKey)
            put("p_name",place.name.trim()); put("p_category",place.category.name); put("p_delete",false)
        })
    }
    suspend fun delete(place: DraftMappedPlace,reviewedFingerprint: String) {
        client.postgrest.rpc("save_draft_place",buildJsonObject {
            put("v",place.versionId); put("expected_fingerprint",reviewedFingerprint)
            put("p_place_id",place.placeId); put("p_node_key",place.nodeKey)
            put("p_name",place.name); put("p_category",place.category.name); put("p_delete",true)
        })
    }
    suspend fun importRecording(recordingId: String,versionId: String,floorName: String,floorNumber: Int,start: String?,facing: String?,end: String?) {
        client.postgrest.rpc("import_walking_recording_with_places",buildJsonObject {
            put("recording",recordingId); put("draft",versionId)
            put("floor_name",floorName); put("floor_number",floorNumber)
            put("start_node",start); put("facing_node",facing); put("end_node",end)
        })
    }
}
