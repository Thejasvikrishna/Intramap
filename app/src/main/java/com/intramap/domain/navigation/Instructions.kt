package com.intramap.domain.navigation

import com.intramap.domain.routing.IndoorGraph
import com.intramap.domain.routing.Route
import kotlin.math.atan2

data class Instruction(val text: String,val nodeId: String,val floorId: String,val distanceM: Float,val routeIndex: Int)

fun generateInstructions(graph: IndoorGraph,route: Route,floorNames: Map<String,String>): List<Instruction> {
    val n = route.nodeIds.map { graph.nodes.getValue(it) }
    if (n.isEmpty()) return emptyList()
    if (n.size < 2) return listOf(Instruction("You are already at ${n[0].name}",n[0].nodeId,n[0].floorId,0f,0))
    fun label(i: Int) = if (n[i].type == "JUNCTION") "the junction" else n[i].name
    val raw = mutableListOf<Pair<String,Int>>()
    var prev: Double? = null
    for (i in 0 until n.size-1) {
        val a = n[i]; val b = n[i+1]
        if (a.floorId != b.floorId) {
            val how = if (a.type == "ELEVATOR") "elevator" else "stairs"
            raw.add("Take the $how to the ${floorNames[b.floorId] ?: b.floorId} floor" to i)
            prev = null; continue
        }
        val h = Math.toDegrees(atan2((b.y-a.y).toDouble(),(b.x-a.x).toDouble()))
        val p = prev
        if (i == 0) raw.add("Start at ${a.name} and head towards ${label(i+1)}" to i)
        else if (p == null) raw.add("Walk towards ${label(i+1)}" to i)
        else {
            val delta = ((h-p+540.0)%360.0)-180.0
            when {
                kotlin.math.abs(delta) < 30 -> {}
                kotlin.math.abs(delta) > 150 -> raw.add("Turn around at ${label(i)}" to i)
                delta > 0 -> raw.add("Turn right at ${label(i)}" to i)
                else -> raw.add("Turn left at ${label(i)}" to i)
            }
        }
        prev = h
    }
    raw.add("You have arrived at ${n.last().name}" to n.size-1)
    val prefix = DoubleArray(n.size)
    for (i in 1 until n.size) {
        val d = if (n[i-1].floorId == n[i].floorId) graph.edges[route.edgeIds[i-1]]?.distance ?: 0f else 0f
        prefix[i] = prefix[i-1]+d
    }
    return raw.mapIndexed { k,(text,idx) ->
        val next = raw.getOrNull(k+1)?.second ?: idx
        Instruction(text,n[idx].nodeId,n[idx].floorId,(prefix[next]-prefix[idx]).toFloat(),idx)
    }
}
