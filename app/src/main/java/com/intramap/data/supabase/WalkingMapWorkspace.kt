package com.intramap.data.supabase

import com.intramap.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WalkingMapWorkspace(private val client: SupabaseClient) {
    suspend fun editableBuildings(): List<MappingBuilding> = client.postgrest.rpc("editable_map_buildings").decodeList<MappingBuilding>()
    @Deprecated("Use editableBuildings; membership access is supported too")
    suspend fun ownedBuildings(): List<MappingBuilding> = editableBuildings()
    suspend fun draft(buildingId: String): MappingVersion = client.postgrest.rpc(
        "open_map_draft",buildJsonObject { put("b",buildingId) }
    ).decodeSingle<MappingVersion>().also { require(it.buildingId == buildingId && it.status == "draft") }
    suspend fun floors(versionId: String): List<MappingFloor> = client.from("floors").select {
        filter { eq("version_id",versionId) }
    }.decodeList<MappingFloor>()
    suspend fun nodes(versionId: String,floor: String): List<MappingNode> = DraftMapReviewSource(client).load(versionId).nodes.filter { it.floorKey == floor }
    suspend fun recordings(buildingId: String,floor: String): List<PathRecording> = client.postgrest.rpc(
        "pending_walking_recordings",buildJsonObject { put("b",buildingId); put("f",floor) }
    ).decodeList<PathRecording>()
    suspend fun importRecording(recordingId: String,versionId: String,floorName: String,floorNumber: Int,start: String?,facing: String?,end: String?) {
        PlaceWorkflowSource(client).requireInstalled()
        DraftPlaceSource(client).importRecording(recordingId,versionId,floorName,floorNumber,start,facing,end)
    }
    suspend fun updateNodeImage(nodeId: String,imageUrl: String?) {
        client.from("map_nodes").update({ set("image_url",imageUrl) }) { filter { eq("id",nodeId) } }
    }
}
