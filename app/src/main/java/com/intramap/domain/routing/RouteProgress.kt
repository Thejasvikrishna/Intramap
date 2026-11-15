package com.intramap.domain.routing

import com.intramap.data.model.LocationNode
import kotlin.math.hypot

/** Same-floor progress only; floor transfers require explicit confirmation by the caller. */
class RouteProgress(private val nodesById: Map<String,LocationNode>,initialRouteNodeIds: List<String>) {
    var routeNodeIds: List<String> = initialRouteNodeIds.toList()
        private set
    private var nextIndex = 1
    val currentNodeId: String? get() = routeNodeIds.getOrNull(nextIndex-1)
    val nextNodeId: String? get() = routeNodeIds.getOrNull(nextIndex)
    /** Excludes transfers: coordinates on independent floors cannot be subtracted. */
    val remainingDistanceM: Float get() {
        var total = 0.0
        for (i in nextIndex until routeNodeIds.size) {
            val a = nodesById[routeNodeIds[i-1]]; val b = nodesById[routeNodeIds[i]]
            if (a != null && b != null && a.floorId == b.floorId) total += hypot((b.x-a.x).toDouble(),(b.y-a.y).toDouble())
        }
        return total.toFloat()
    }
    fun update(floorId: String,x: Double,y: Double,arrivalRadiusM: Double = 2.5,passAheadRadiusM: Double = 4.0): Boolean {
        if (!x.isFinite() || !y.isFinite() || !arrivalRadiusM.isFinite() || !passAheadRadiusM.isFinite() || arrivalRadiusM < 0 || passAheadRadiusM < 0) return false
        var advanced = false
        while (nextIndex < routeNodeIds.size) {
            val next = nodesById[routeNodeIds[nextIndex]] ?: break
            if (next.floorId != floorId) break
            val previous = routeNodeIds.getOrNull(nextIndex-1)?.let { nodesById[it] }
            if (previous != null && previous.floorId != next.floorId) break
            val distance = hypot(x-next.x.toDouble(),y-next.y.toDouble())
            if (distance <= arrivalRadiusM) { nextIndex++; advanced = true; continue }
            val following = routeNodeIds.getOrNull(nextIndex+1)?.let { nodesById[it] } ?: break
            if (following.floorId != floorId) break
            val dx = following.x.toDouble()-next.x.toDouble(); val dy = following.y.toDouble()-next.y.toDouble()
            val length2 = dx*dx+dy*dy
            if (length2 <= 0) break
            val projection = ((x-next.x.toDouble())*dx+(y-next.y.toDouble())*dy)/length2
            val lateral = hypot(x-(next.x.toDouble()+projection*dx),y-(next.y.toDouble()+projection*dy))
            if (projection > 0 && projection <= 1 && distance <= passAheadRadiusM && lateral <= arrivalRadiusM) { nextIndex++; advanced = true; continue }
            break
        }
        return advanced
    }
    fun replaceRoute(newRouteNodeIds: List<String>) { routeNodeIds = newRouteNodeIds.toList(); nextIndex = 1 }
}
