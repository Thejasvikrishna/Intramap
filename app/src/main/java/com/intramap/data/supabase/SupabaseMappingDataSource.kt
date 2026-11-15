package com.intramap.data.supabase

import com.intramap.data.model.MappingBuilding
import com.intramap.data.model.MappingEdge
import com.intramap.data.model.MappingFloor
import com.intramap.data.model.MappingNode
import com.intramap.data.model.MappingVersion
import com.intramap.data.model.PathRecording
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Admin-side Supabase operations.
 *
 * Ownership fields use the currently authenticated user's ID.
 * Database row-level security remains responsible for authorization.
 */
class SupabaseMappingDataSource(
    private val client: SupabaseClient
) {

    /**
     * Retrieves the authenticated user from Supabase.
     *
     * Fails if the current session cannot authenticate the user.
     */
    private suspend fun requireUserId(): String {
        return client.auth
            .retrieveUserForCurrentSession(updateSession = true)
            .id
    }

    suspend fun createBuilding(
        buildingKey: String,
        name: String,
        description: String
    ): MappingBuilding {
        val userId = requireUserId()

        return client.postgrest["buildings"].insert(
            buildJsonObject {
                put("building_key", buildingKey.trim())
                put("name", name.trim())
                put("description", description.trim())
                put("owner_id", userId)
            }
        ) {
            select()
        }.decodeSingle<MappingBuilding>()
    }

    suspend fun createDraftVersion(
        buildingId: String
    ): MappingVersion {
        val userId = requireUserId()

        val existing = client.from("map_versions").select {
            filter {
                eq("building_id", buildingId)
            }
        }.decodeList<MappingVersion>()

        val nextVersion =
            (existing.maxOfOrNull { it.versionNo } ?: 0) + 1

        return client.postgrest["map_versions"].insert(
            buildJsonObject {
                put("building_id", buildingId)
                put("version_no", nextVersion)
                put("status", "draft")
                put("created_by", userId)
            }
        ) {
            select()
        }.decodeSingle<MappingVersion>()
    }

    suspend fun addFloor(
        versionId: String,
        floorKey: String,
        floorNumber: Int,
        name: String,
        widthM: Float,
        heightM: Float
    ): MappingFloor {
        return client.postgrest["floors"].insert(
            buildJsonObject {
                put("version_id", versionId)
                put("floor_key", floorKey)
                put("floor_number", floorNumber)
                put("name", name)
                put("width_m", widthM)
                put("height_m", heightM)
            }
        ) {
            select()
        }.decodeSingle<MappingFloor>()
    }

    suspend fun addNode(
        versionId: String,
        nodeKey: String,
        floorKey: String,
        name: String,
        nodeType: String,
        x: Float,
        y: Float,
        keywords: List<String> = emptyList()
    ): MappingNode {
        return client.postgrest["map_nodes"].insert(
            buildJsonObject {
                put("version_id", versionId)
                put("node_key", nodeKey)
                put("floor_key", floorKey)
                put("name", name)
                put("node_type", nodeType)
                put("x", x)
                put("y", y)
                put(
                    "keywords",
                    JsonArray(
                        keywords.map { keyword ->
                            JsonPrimitive(keyword)
                        }
                    )
                )
            }
        ) {
            select()
        }.decodeSingle<MappingNode>()
    }

    suspend fun addEdge(
        versionId: String,
        edgeKey: String,
        fromKey: String,
        toKey: String,
        distanceM: Float,
        accessibilityType: String = "NORMAL"
    ): MappingEdge {
        return client.postgrest["map_edges"].insert(
            buildJsonObject {
                put("version_id", versionId)
                put("edge_key", edgeKey)
                put("from_key", fromKey)
                put("to_key", toKey)
                put("distance_m", distanceM)
                put("accessibility_type", accessibilityType)
            }
        ) {
            select()
        }.decodeSingle<MappingEdge>()
    }

    suspend fun startRecording(
        buildingId: String,
        floorKey: String,
        startAnchorKey: String?
    ): PathRecording {
        val userId = requireUserId()

        return client.postgrest["path_recordings"].insert(
            buildJsonObject {
                put("building_id", buildingId)
                put("floor_key", floorKey)
                put("start_anchor_key", startAnchorKey)
                put("status", "draft")
                put("recorder_id", userId)
            }
        ) {
            select()
        }.decodeSingle<PathRecording>()
    }

    suspend fun appendRecordingPoint(
        recordingId: String,
        seq: Int,
        x: Float,
        y: Float,
        timeMs: Long,
        headingDeg: Float?,
        marker: String?
    ) {
        client.postgrest["path_recording_points"].insert(
            buildJsonObject {
                put("recording_id", recordingId)
                put("seq", seq)
                put("x", x)
                put("y", y)
                put("t_ms", timeMs)
                put("heading_deg", headingDeg)
                put("marker", marker)
            }
        )
    }

    suspend fun submitRecording(
        recordingId: String
    ) {
        client.postgrest["path_recordings"].update(
            {
                set("status", "submitted")
            }
        ) {
            filter {
                eq("id", recordingId)
            }
        }
    }

    suspend fun publishVersion(
        versionId: String
    ) {
        client.postgrest.rpc(
            "publish_map_version",
            buildJsonObject {
                put("v", versionId)
            }
        )
    }
}