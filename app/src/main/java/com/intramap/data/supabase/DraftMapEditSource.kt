package com.intramap.data.supabase

import com.intramap.domain.mapping.DraftMapEdits
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DraftMapEditSource(private val client: SupabaseClient) {
    suspend fun save(versionId: String,fingerprint: String,edits: DraftMapEdits) {
        require(edits.dirty)
        val beforeNodes = edits.original.nodes.associateBy { it.nodeId }
        val beforeEdges = edits.original.edges.associateBy { it.edgeId }
        val changes = buildJsonObject {
            put("building_id",edits.original.buildingId)
            put("joins_verified",edits.current.edges.any { it.edgeId !in beforeEdges })
            put("nodes",JsonArray(edits.current.nodes.filter { beforeNodes[it.nodeId] != it }.map { n -> buildJsonObject {
                put("key",n.nodeId); put("name",n.name); put("type",n.type); put("x",n.x); put("y",n.y)
            } }))
            put("edges",JsonArray(edits.current.edges.filter { beforeEdges[it.edgeId] != it }.map { e -> buildJsonObject {
                put("key",e.edgeId); put("from",e.sourceNodeId); put("to",e.destinationNodeId); put("available",e.isAvailable)
            } }))
        }
        client.postgrest.rpc("save_draft_map_edits",buildJsonObject {
            put("v",versionId); put("expected_fingerprint",fingerprint); put("changes",changes)
        })
    }
}
