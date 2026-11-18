package com.intramap.data.supabase

import com.intramap.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable private data class BuildingRow(
    val id: String,@SerialName("building_key") val buildingKey: String,val name: String,val description: String = ""
)
@Serializable private data class VersionRow(
    val id: String,@SerialName("building_id") val buildingId: String,
    @SerialName("places_schema_version") val placesSchemaVersion: Int = 0
)
@Serializable private data class FloorRow(
    @SerialName("floor_key") val floorKey: String,@SerialName("floor_number") val floorNumber: Int,
    val name: String,@SerialName("width_m") val widthM: Float,@SerialName("height_m") val heightM: Float
)
@Serializable private data class NodeRow(
    @SerialName("node_key") val nodeKey: String,@SerialName("floor_key") val floorKey: String,
    val name: String,@SerialName("node_type") val nodeType: String,val x: Float,val y: Float,
    @SerialName("connector_group_id") val connectorGroupId: String? = null,
    val keywords: List<String> = emptyList(),val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null
)
@Serializable private data class EdgeRow(
    @SerialName("edge_key") val edgeKey: String,@SerialName("from_key") val fromKey: String,
    @SerialName("to_key") val toKey: String,@SerialName("distance_m") val distanceM: Float,
    @SerialName("accessibility_type") val accessibilityType: String = "NORMAL",
    @SerialName("is_available") val isAvailable: Boolean = true
)

/** Published versions only. Missing required place metadata fails refresh rather than erasing it. */
class SupabaseMapDataSource(private val client: SupabaseClient) {
    suspend fun fetchPublishedMaps(): List<PublishedMap> {
        val versions = client.from("map_versions").select { filter { eq("status","published") } }.decodeList<VersionRow>()
        if (versions.isEmpty()) return emptyList()
        val buildings = client.from("buildings").select().decodeList<BuildingRow>().associateBy { it.id }
        return versions.mapNotNull { version ->
            val owner = buildings[version.buildingId] ?: return@mapNotNull null
            require(version.placesSchemaVersion in 0..1) { "Unsupported published place schema" }
            val floors = client.from("floors").select { filter { eq("version_id",version.id) } }.decodeList<FloorRow>()
            val nodes = client.from("map_nodes").select { filter { eq("version_id",version.id) } }.decodeList<NodeRow>()
            val edges = client.from("map_edges").select { filter { eq("version_id",version.id) } }.decodeList<EdgeRow>()
            val places = if (version.placesSchemaVersion == 1) client.from("map_places").select {
                filter { eq("version_id",version.id) }
            }.decodeList<DraftMappedPlace>() else emptyList()
            val nodeKeys = nodes.map { it.nodeKey }.toSet()
            require(places.all { it.versionId == version.id && it.nodeKey in nodeKeys }) { "Published place references missing geometry" }
            require(places.map { it.placeId }.distinct().size == places.size) { "Duplicate published place IDs" }
            val byNode = places.groupBy { it.nodeKey }
            PublishedMap(Building(owner.buildingKey,owner.name,owner.description,
                floors.sortedBy { it.floorNumber }.map { Floor(it.floorKey,it.floorNumber,it.name,it.widthM,it.heightM) },
                nodes.map { n -> LocationNode(n.nodeKey,n.floorKey,n.name,n.nodeType,n.x,n.y,n.connectorGroupId,n.keywords,n.description,n.imageUrl,
                    byNode[n.nodeKey].orEmpty().sortedBy { it.placeId }.map { MappedPlace(it.placeId,it.name,it.category.name) }) },
                edges.map { PathEdge(it.edgeKey,it.fromKey,it.toKey,it.distanceM,it.accessibilityType,it.isAvailable) }),version.id)
        }
    }
}
