package com.intramap.domain.routing

import com.intramap.data.model.LocationNode
import kotlin.math.hypot

/**
 * Tracks progress through mapped route nodes.
 *
 * A waypoint can be advanced when the position:
 * 1. Reaches its arrival radius, or
 * 2. Passes it along the outgoing same-floor segment while
 *    remaining sufficiently close to the waypoint and segment.
 *
 * Cross-floor waypoints are not automatically confirmed here.
 */
class RouteProgress(
    private val nodesById: Map<String, LocationNode>,
    initialRouteNodeIds: List<String>
) {
    var routeNodeIds: List<String> = initialRouteNodeIds.toList()
        private set

    private var nextIndex = 1

    val currentNodeId: String?
        get() = routeNodeIds.getOrNull(nextIndex - 1)

    val nextNodeId: String?
        get() = routeNodeIds.getOrNull(nextIndex)

    val remainingDistanceM: Float
        get() {
            var total = 0.0

            for (index in nextIndex until routeNodeIds.size) {
                val previous = nodesById[routeNodeIds[index - 1]]
                val current = nodesById[routeNodeIds[index]]

                if (previous != null && current != null) {
                    total += hypot(
                        (current.x - previous.x).toDouble(),
                        (current.y - previous.y).toDouble()
                    )
                }
            }

            return total.toFloat()
        }

    fun update(
        floorId: String,
        x: Double,
        y: Double,
        arrivalRadiusM: Double = 2.5,
        passAheadRadiusM: Double = 4.0
    ): Boolean {
        if (
            !x.isFinite() ||
            !y.isFinite() ||
            !arrivalRadiusM.isFinite() ||
            !passAheadRadiusM.isFinite() ||
            arrivalRadiusM < 0.0 ||
            passAheadRadiusM < 0.0
        ) {
            return false
        }

        var advanced = false

        while (nextIndex < routeNodeIds.size) {
            val next = nodesById[routeNodeIds[nextIndex]]
                ?: break

            if (next.floorId != floorId) {
                break
            }

            val previous = routeNodeIds
                .getOrNull(nextIndex - 1)
                ?.let { nodesById[it] }

            // Floor changes require explicit handling by the caller.
            if (
                previous != null &&
                previous.floorId != next.floorId
            ) {
                break
            }

            val distanceToNext = hypot(
                x - next.x.toDouble(),
                y - next.y.toDouble()
            )

            if (distanceToNext <= arrivalRadiusM) {
                nextIndex++
                advanced = true
                continue
            }

            val followingId = routeNodeIds
                .getOrNull(nextIndex + 1)
                ?: break

            val following = nodesById[followingId]
                ?: break

            if (following.floorId != floorId) {
                break
            }

            val dx = (
                    following.x.toDouble() -
                            next.x.toDouble()
                    )

            val dy = (
                    following.y.toDouble() -
                            next.y.toDouble()
                    )

            val segmentLengthSquared = dx * dx + dy * dy

            if (segmentLengthSquared <= 0.0) {
                break
            }

            val relativeX = x - next.x.toDouble()
            val relativeY = y - next.y.toDouble()

            val projection = (
                    relativeX * dx + relativeY * dy
                    ) / segmentLengthSquared

            val projectedX = next.x.toDouble() + projection * dx
            val projectedY = next.y.toDouble() + projection * dy

            val lateralDistance = hypot(
                x - projectedX,
                y - projectedY
            )

            val passedWaypoint =
                projection > 0.0 &&
                        projection <= 1.0 &&
                        distanceToNext <= passAheadRadiusM &&
                        lateralDistance <= arrivalRadiusM

            if (passedWaypoint) {
                nextIndex++
                advanced = true
                continue
            }

            break
        }

        return advanced
    }

    fun replaceRoute(newRouteNodeIds: List<String>) {
        routeNodeIds = newRouteNodeIds.toList()
        nextIndex = 1
    }
}