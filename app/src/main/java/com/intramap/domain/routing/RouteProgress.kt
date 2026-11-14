package com.intramap.domain.routing

import com.intramap.data.model.LocationNode
import kotlin.math.hypot

/**
 * Tracks progress along a route using distance-to-next-node and passed-node
 * detection. This avoids requiring the visitor to stand precisely on each node.
 */
class RouteProgress(
    private val nodesById: Map<String, LocationNode>,
    initialRouteNodeIds: List<String>
) {
    var routeNodeIds: List<String> = initialRouteNodeIds
        private set

    private var nextIndex = 1

    val currentNodeId: String?
        get() = routeNodeIds.getOrNull(nextIndex - 1)

    val nextNodeId: String?
        get() = routeNodeIds.getOrNull(nextIndex)

    val remainingDistanceM: Float
        get() {
            var total = 0f
            for (index in nextIndex until routeNodeIds.size) {
                val previous = nodesById[routeNodeIds[index - 1]]
                val current = nodesById[routeNodeIds[index]]
                if (previous != null && current != null) {
                    total += hypot(current.x - previous.x, current.y - previous.y).toFloat()
                }
            }
            return total
        }

    /**
     * Returns true when progress advanced. A node is considered passed when the
     * estimated position is close to it, or when the user has moved beyond it
     * toward the following node.
     */
    fun update(
        floorId: String,
        x: Double,
        y: Double,
        arrivalRadiusM: Double = 2.5,
        passAheadRadiusM: Double = 4.0
    ): Boolean {
        var advanced = false

        while (nextIndex < routeNodeIds.size) {
            val next = nodesById[routeNodeIds[nextIndex]] ?: break
            if (next.floorId != floorId) break

            val distanceToNext = hypot(next.x - x, next.y - y)
            if (distanceToNext <= arrivalRadiusM) {
                nextIndex++
                advanced = true
                continue
            }

            val following = nodesById[routeNodeIds.getOrNull(nextIndex + 1) ?: break] ?: break
            if (following.floorId == floorId) {
                val distanceToFollowing = hypot(following.x - x, following.y - y)
                val segmentLength = hypot(following.x - next.x, following.y - next.y)

                if (segmentLength > 0 && distanceToFollowing < distanceToNext && distanceToNext <= passAheadRadiusM) {
                    nextIndex++
                    advanced = true
                    continue
                }
            }
            break
        }

        return advanced
    }

    fun replaceRoute(newRouteNodeIds: List<String>) {
        routeNodeIds = newRouteNodeIds
        nextIndex = 1
    }
}
