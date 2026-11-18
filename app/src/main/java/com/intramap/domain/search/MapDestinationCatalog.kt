package com.intramap.domain.search

import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode

/** UI identity is separate from the routing anchor: no fake nodes or zero-length edges. */
data class MapDestination(
    val selectionId: String,val anchorNodeId: String,val floorId: String,
    val name: String,val category: String,val independentPlaceId: String? = null,
    val keywords: List<String> = emptyList()
) {
    fun previewNode(building: Building): LocationNode? = building.nodes.firstOrNull { it.nodeId == anchorNodeId && it.floorId == floorId }
        ?.copy(name = name,type = category)
}

fun Building.destinationCatalog(): List<MapDestination> {
    val placeIds = nodes.flatMap { it.mappedPlaces }.map { it.placeId }
    require(placeIds.all { it.isNotBlank() } && placeIds.distinct().size == placeIds.size) { "Invalid mapped place identity" }
    return nodes.flatMap { node ->
        val legacy = if (node.type == "JUNCTION") emptyList() else listOf(MapDestination(
            "node:${node.nodeId}",node.nodeId,node.floorId,node.name,node.type,keywords = node.keywords))
        legacy + node.mappedPlaces.map { place ->
            MapDestination("place:${place.placeId}",node.nodeId,node.floorId,place.name,place.category,place.placeId,node.keywords)
        }
    }.sortedWith(compareBy<MapDestination> { it.name.lowercase() }.thenBy { it.selectionId })
}

fun Building.searchDestinations(query: String,limit: Int = 30): List<MapDestination> {
    require(limit in 1..1000)
    val term = query.trim()
    return destinationCatalog().filter { term.isEmpty() || it.name.contains(term,true) || it.keywords.any { word -> word.contains(term,true) } }.take(limit)
}
