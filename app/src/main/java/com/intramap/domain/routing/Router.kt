package com.intramap.domain.routing

import java.util.PriorityQueue
import kotlin.math.hypot

enum class AccessibilityMode { STANDARD, STEP_FREE }

data class Route(
    val nodeIds: List<String>, val edgeIds: List<String>,
    /** Same-floor mapped walking distance only; transfer distance is unknown. */
    val totalDistance: Float, val nodesExpanded: Int,
    /** Includes nominal transfer penalties; not a distance or ETA. */
    val totalCost: Float = totalDistance
)
private data class Entry(val f: Float,val g: Float,val id: String)

fun findRoute(
    graph: IndoorGraph,startId: String,goalId: String,
    mode: AccessibilityMode = AccessibilityMode.STANDARD,
    blockedEdgeIds: Set<String> = emptySet(),useAStar: Boolean = true
): Route? {
    graph.nodes[startId] ?: return null
    val goal = graph.nodes[goalId] ?: return null
    if (graph.edges.values.any { !it.distance.isFinite() || it.distance < 0f }) return null
    // Independent floor frames make Euclidean cross-floor heuristics inadmissible.
    val safeHeuristic = useAStar && graph.edges.values.all { e ->
        val a = graph.nodes[e.sourceNodeId]; val b = graph.nodes[e.destinationNodeId]
        a != null && b != null && a.floorId == b.floorId && e.distance >= hypot(a.x-b.x,a.y-b.y)
    }
    fun h(id: String): Float {
        if (!safeHeuristic) return 0f
        val n = graph.nodes[id] ?: return 0f
        return if (n.floorId == goal.floorId) hypot(n.x-goal.x,n.y-goal.y) else 0f
    }
    val dist = HashMap<String,Float>()
    val prev = HashMap<String,Pair<String,String>>()
    val pq = PriorityQueue<Entry>(compareBy { it.f })
    dist[startId] = 0f; pq.add(Entry(h(startId),0f,startId))
    var expanded = 0
    while (pq.isNotEmpty()) {
        val cur = pq.poll() ?: break
        if (cur.g > (dist[cur.id] ?: Float.MAX_VALUE)) continue
        expanded++
        if (cur.id == goalId) {
            val nodes = arrayListOf(goalId); val edges = arrayListOf<String>(); var at = goalId
            while (at != startId) { val (p,eid) = prev.getValue(at); edges.add(eid); nodes.add(p); at = p }
            val orderedNodes = nodes.reversed(); val orderedEdges = edges.reversed()
            val walking = orderedEdges.indices.sumOf { i ->
                if (graph.nodes.getValue(orderedNodes[i]).floorId == graph.nodes.getValue(orderedNodes[i+1]).floorId)
                    graph.edges.getValue(orderedEdges[i]).distance.toDouble() else 0.0
            }.toFloat()
            return Route(orderedNodes,orderedEdges,walking,expanded,cur.g)
        }
        for ((nbr,e) in graph.neighbors(cur.id)) {
            if (!e.isAvailable || e.edgeId in blockedEdgeIds) continue
            val a = graph.nodes.getValue(cur.id); val b = graph.nodes.getValue(nbr)
            val stairs = e.accessibilityType == "STAIRS_ONLY" ||
                (a.floorId != b.floorId && (a.type == "STAIRS" || b.type == "STAIRS"))
            if (mode == AccessibilityMode.STEP_FREE && stairs) continue
            val ng = cur.g+e.distance
            if (ng.isFinite() && ng < (dist[nbr] ?: Float.MAX_VALUE)) {
                dist[nbr] = ng; prev[nbr] = cur.id to e.edgeId; pq.add(Entry(ng+h(nbr),ng,nbr))
            }
        }
    }
    return null
}
