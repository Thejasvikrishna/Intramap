package com.intramap.domain.routing

import com.intramap.data.model.Building
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Selects a routing start from an approximate estimated position.
 *
 * A user does not need to stand exactly on a saved node. The function considers
 * same-floor candidates, distance, heading agreement, and whether the candidate
 * can reach the destination through the existing graph.
 */
fun selectRoutingStart(
    building: Building,
    graph: IndoorGraph,
    estimatedFloorId: String,
    x: Double,
    y: Double,
    headingRadians: Float?,
    destinationId: String,
    accessibilityMode: AccessibilityMode = AccessibilityMode.STANDARD,
    blockedEdgeIds: Set<String> = emptySet(),
    maxCandidateDistanceM: Double = 12.0
): String? {
    val destination = graph.nodes[destinationId] ?: return null

    val candidates = building.nodes
        .asSequence()
        .filter { it.floorId == estimatedFloorId }
        .filter { it.type != "JUNCTION" || graph.neighbors(it.nodeId).isNotEmpty() }
        .map { it to hypot(it.x - x, it.y - y) }
        .filter { (_, distance) -> distance <= maxCandidateDistanceM }
        .sortedBy { (_, distance) -> distance }
        .take(12)
        .toList()

    if (candidates.isEmpty()) return null

    val scored = candidates.mapNotNull { (node, distance) ->
        val route = findRoute(
            graph = graph,
            startId = node.nodeId,
            goalId = destinationId,
            mode = accessibilityMode,
            blockedEdgeIds = blockedEdgeIds
        ) ?: return@mapNotNull null

        var score = distance
        if (headingRadians != null) {
            val bearing = Math.atan2(node.y - y, node.x - x).toFloat()
            val difference = abs(normalizeAngle(bearing - headingRadians))
            if (difference > Math.PI * 0.75) score += 3.0
        }
        score + route.totalDistance * 0.05
        node.nodeId to score
    }

    return scored.minByOrNull { it.second }?.first
}

private fun normalizeAngle(angle: Float): Float {
    var value = angle
    while (value > Math.PI) value -= (2 * Math.PI).toFloat()
    while (value < -Math.PI) value += (2 * Math.PI).toFloat()
    return value
}
