package com.intramap.data.supabase

import com.intramap.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class DraftReviewSnapshot(
    val version: MappingVersion, val floors: List<MappingFloor>, val nodes: List<MappingNode>,
    val edges: List<MappingEdge>, val fingerprint: String
) {
    fun asBuilding(owner: MappingBuilding): Building {
        require(version.buildingId == owner.id && version.status == "draft")
        return Building(owner.id,owner.name,owner.description,
            floors.sortedBy { it.floorNumber }.map { Floor(it.floorKey,it.floorNumber,it.name,it.widthM,it.heightM) },
            nodes.map { LocationNode(it.nodeKey,it.floorKey,it.name,it.nodeType,it.x,it.y,it.connectorGroupId,it.keywords,it.description,it.imageUrl) },
            edges.map { PathEdge(it.edgeKey,it.fromKey,it.toKey,it.distanceM,it.accessibilityType,it.isAvailable) })
    }
}

class DraftMapReviewSource(private val client: SupabaseClient) {
    suspend fun load(versionId: String): DraftReviewSnapshot = client.postgrest.rpc(
        "review_draft_map",buildJsonObject { put("v",versionId) }
    ).decodeSingle<DraftReviewSnapshot>().also { require(it.version.id == versionId) }
    suspend fun publish(versionId: String, fingerprint: String) {
        client.postgrest.rpc("publish_reviewed_map",buildJsonObject { put("v",versionId); put("reviewed_fingerprint",fingerprint) })
    }
}
