package com.intramap.domain.routing

import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge

class IndoorGraph(building: Building) {
    val nodes: Map<String, LocationNode> = building.nodes.associateBy { it.nodeId }
    val edges: Map<String, PathEdge> = building.edges.associateBy { it.edgeId }
    private val adj = HashMap<String, MutableList<Pair<String, PathEdge>>>()

    init {
        building.edges.forEach { e ->
            if (e.sourceNodeId in nodes && e.destinationNodeId in nodes) {
                adj.getOrPut(e.sourceNodeId) { mutableListOf() }.add(e.destinationNodeId to e)
                adj.getOrPut(e.destinationNodeId) { mutableListOf() }.add(e.sourceNodeId to e)
            }
        }
    }

    fun neighbors(id: String): List<Pair<String, PathEdge>> = adj[id].orEmpty()
}