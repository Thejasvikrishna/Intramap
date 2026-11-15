package com.intramap.domain.search

import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode

/**
 * Bounded nearby-place assistant.
 *
 * It interprets common requests and grounds every answer in actual mapped
 * places. It does not generate destinations, distances or routes.
 */
object PlaceAssistant {

    data class Result(
        val message: String,
        val destination: LocationNode?
    )

    fun interpret(
        building: Building,
        currentFloorId: String?,
        query: String
    ): Result {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) {
            return Result("Ask for a place, such as “nearest restroom” or “take me to the library”.", null)
        }

        val destination = findPlace(building, normalized)

        return if (destination == null) {
            Result("I could not find “${query.trim()}” in this building’s published map.", null)
        } else {
            val floorText = if (destination.floorId == currentFloorId) "on this floor" else "on ${destination.floorId}"
            Result("${destination.name} is $floorText. Starting navigation.", destination)
        }
    }

    fun nearbyPlaces(
        building: Building,
        currentFloorId: String?,
        limit: Int = 6
    ): List<LocationNode> {
        return building.nodes
            .filter { it.type != "JUNCTION" }
            .sortedBy { node ->
                if (currentFloorId == null) 1
                else if (node.floorId == currentFloorId) 0
                else 1
            }
            .take(limit)
    }

    private fun findPlace(building: Building, query: String): LocationNode? {
        val cleaned = query
            .trim()
            .lowercase()
            .removePrefix("take me to")
            .trim()
            .removePrefix("navigate to")
            .trim()
            .removePrefix("where is")
            .trim()
            .removePrefix("find")
            .trim()
            .removePrefix("nearest")
            .trim()
            .removePrefix("the ")
            .trim()

        if (cleaned.isBlank()) return null

        return building.nodes.firstOrNull { node ->
            node.type != "JUNCTION" && (
                node.name.lowercase() == cleaned ||
                    node.name.lowercase().contains(cleaned) ||
                    node.keywords.any { it.lowercase().contains(cleaned) }
                )
        }
    }
}
