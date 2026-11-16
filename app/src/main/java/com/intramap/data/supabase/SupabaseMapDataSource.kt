package com.intramap.data.supabase

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PublishedMap
import com.intramap.data.model.PathEdge
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable private data class BuildingRow(
    val id: String,
    @SerialName("building_key") val buildingKey: String,
    val name: String,
    val description: String = ""
)

@Serializable private data class VersionRow(
    val id: String,
    @SerialName("building_id") val buildingId: String
)

@Serializable private data class FloorRow(
    @SerialName("floor_key") val floorKey: String,
    @SerialName("floor_number") val floorNumber: Int,
    val name: String,
    @SerialName("width_m") val widthM: Float,
    @SerialName("height_m") val heightM: Float
)

@Serializable private data class NodeRow(
    @SerialName("node_key") val nodeKey: String,
    @SerialName("floor_key") val floorKey: String,
    val name: String,
    @SerialName("node_type") val nodeType: String,
    val x: Float,
    val y: Float,
    @SerialName("connector_group_id") val connectorGroupId: String? = null,
    val keywords: List<String> = emptyList(),
    val description: String? = null
)

@Serializable private data class EdgeRow(
    @SerialName("edge_key") val edgeKey: String,
    @SerialName("from_key") val fromKey: String,
    @SerialName("to_key") val toKey: String,
    @SerialName("distance_m") val distanceM: Float,
    @SerialName("accessibility_type") val accessibilityType: String = "NORMAL",
    @SerialName("is_available") val isAvailable: Boolean = true
)

/** Reads only published map versions (enforced server-side by RLS). */
class SupabaseMapDataSource(private val client: SupabaseClient) {

    suspend fun fetchPublishedMaps(): List<PublishedMap> {
        val versions = client.from("map_versions").select {
            filter { eq("status", "published") }
        }.decodeList<VersionRow>()
        if (versions.isEmpty()) return emptyList()

        val buildings = client.from("buildings").select().decodeList<BuildingRow>().associateBy { it.id }

        return versions.mapNotNull { version ->
            val buildingRow = buildings[version.buildingId] ?: return@mapNotNull null
            val floors = client.from("floors").select {
                filter { eq("version_id", version.id) }
            }.decodeList<FloorRow>()
            val nodes = client.from("map_nodes").select {
                filter { eq("version_id", version.id) }
            }.decodeList<NodeRow>()
            val edges = client.from("map_edges").select {
                filter { eq("version_id", version.id) }
            }.decodeList<EdgeRow>()

            PublishedMap(
                building = Building(
                    buildingId = buildingRow.buildingKey,
                    buildingName = buildingRow.name,
                    description = buildingRow.description,
                    floors = floors.sortedBy { it.floorNumber }.map {
                        Floor(it.floorKey, it.floorNumber, it.name, it.widthM, it.heightM)
                    },
                    nodes = nodes.map {
                        LocationNode(
                            nodeId = it.nodeKey, floorId = it.floorKey, name = it.name, type = it.nodeType,
                            x = it.x, y = it.y, connectorGroupId = it.connectorGroupId,
                            keywords = it.keywords, landmarkDescription = it.description
                        )
                    },
                    edges = edges.map {
                        PathEdge(it.edgeKey, it.fromKey, it.toKey, it.distanceM, it.accessibilityType, it.isAvailable)
                    }
                ),
                versionId = version.id
            )
        }
    }
}
