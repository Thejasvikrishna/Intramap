package com.intramap.domain.search

import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode

private val NON_DESTINATION = setOf("JUNCTION")

fun searchDestinations(b: Building, query: String, floorId: String? = null): List<LocationNode> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return b.nodes
        .filter { it.type !in NON_DESTINATION && (floorId == null || it.floorId == floorId) }
        .mapNotNull { n ->
            val name = n.name.lowercase()
            val score = when {
                name == q -> 100
                name.startsWith(q) -> 80
                name.contains(q) -> 60
                n.type.lowercase().contains(q) -> 40
                n.keywords.any { it.lowercase().contains(q) } -> 30
                else -> 0
            }
            if (score > 0) n to score else null
        }
        .sortedByDescending { it.second }
        .map { it.first }
}