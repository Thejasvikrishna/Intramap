package com.intramap.data.supabase

import com.intramap.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class WalkingMapWorkspace(private val client: SupabaseClient) {
    suspend fun ownedBuildings(): List<MappingBuilding> {
        val user = client.auth.retrieveUserForCurrentSession(updateSession = true)
        return client.from("buildings").select { filter { eq("owner_id", user.id) } }.decodeList<MappingBuilding>()
    }
    suspend fun draft(buildingId: String): MappingVersion {
        val existing = client.from("map_versions").select {
            filter { eq("building_id", buildingId); eq("status", "draft") }
        }.decodeList<MappingVersion>()
        return existing.maxByOrNull { it.versionNo }
            ?: SupabaseMappingDataSource(client).createDraftVersion(buildingId)
    }
    suspend fun floors(versionId: String): List<MappingFloor> = client.from("floors").select {
        filter { eq("version_id", versionId) }
    }.decodeList<MappingFloor>()
    suspend fun nodes(versionId: String, floor: String): List<MappingNode> = client.from("map_nodes").select {
        filter { eq("version_id", versionId); eq("floor_key", floor) }
    }.decodeList<MappingNode>()
    suspend fun recordings(buildingId: String, floor: String): List<PathRecording> = client.from("path_recordings").select {
        filter { eq("building_id", buildingId); eq("floor_key", floor); eq("status", "submitted") }
    }.decodeList<PathRecording>()
    suspend fun importRecording(
        recordingId: String, versionId: String, floorName: String, floorNumber: Int,
        start: String?, facing: String?, end: String?
    ) {
        client.postgrest.rpc("import_walking_recording", buildJsonObject {
            put("recording", recordingId); put("draft", versionId)
            put("floor_name", floorName); put("floor_number", floorNumber)
            put("start_node", start); put("facing_node", facing); put("end_node", end)
        })
    }
}
