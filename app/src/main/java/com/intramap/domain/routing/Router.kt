package com.intramap.domain.routing

import java.util.PriorityQueue
import kotlin.math.hypot

enum class AccessibilityMode { STANDARD, STEP_FREE }

data class Route(
    val nodeIds: List<String>,
    val edgeIds: List<String>,
    val totalDistance: Float,
    val nodesExpanded: Int
)

private data class Entry(val f: Float, val g: Float, val id: String)

fun findRoute(
    graph: IndoorGraph,
    startId: String,
    goalId: String,
    mode: AccessibilityMode = AccessibilityMode.STANDARD,
    blockedEdgeIds: Set<String> = emptySet(),
    useAStar: Boolean = true
): Route? {
    graph.nodes[startId] ?: return null
    val goal = graph.nodes[goalId] ?: return null

    fun h(id: String): Float {
        if (!useAStar) return 0f
        val n = graph.nodes[id] ?: return 0f
        return hypot(n.x - goal.x, n.y - goal.y)
    }

    val dist = HashMap<String, Float>()
    val prev = HashMap<String, Pair<String, String>>()
    val pq = PriorityQueue<Entry>(compareBy { it.f })
    dist[startId] = 0f
    pq.add(Entry(h(startId), 0f, startId))
    var expanded = 0

    while (pq.isNotEmpty()) {
        val cur = pq.poll() ?: break
        if (cur.g > (dist[cur.id] ?: Float.MAX_VALUE)) continue
        expanded++
        if (cur.id == goalId) {
            val nodes = ArrayList<String>()
            val edges = ArrayList<String>()
            var at = goalId
            nodes.add(at)
            while (at != startId) {
                val (p, eid) = prev.getValue(at)
                edges.add(eid); nodes.add(p); at = p
            }
            return Route(nodes.reversed(), edges.reversed(), cur.g, expanded)
        }
        for ((nbr, e) in graph.neighbors(cur.id)) {
            if (!e.isAvailable || e.edgeId in blockedEdgeIds) continue
            if (mode == AccessibilityMode.STEP_FREE && e.accessibilityType == "STAIRS_ONLY") continue
            val ng = cur.g + e.distance
            if (ng < (dist[nbr] ?: Float.MAX_VALUE)) {
                dist[nbr] = ng
                prev[nbr] = cur.id to e.edgeId
                pq.add(Entry(ng + h(nbr), ng, nbr))
            }
        }
    }
    return null
}