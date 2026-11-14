package com.intramap.navigation

import com.intramap.data.model.Building

fun validateNavigationMap(b: Building): List<String> = buildList {
    if (b.floors.isEmpty()) add("No floors configured.")
    if (b.nodes.isEmpty()) add("No location nodes configured.")
    if (b.floors.map { it.floorId }.distinct().size != b.floors.size)
        add("Duplicate floor IDs.")
    if (b.nodes.map { it.nodeId }.distinct().size != b.nodes.size)
        add("Duplicate node IDs.")
    if (b.edges.map { it.edgeId }.distinct().size != b.edges.size)
        add("Duplicate edge IDs.")
    if (b.nodes.any { it.nodeId.startsWith("__nav_") } ||
        b.edges.any { it.edgeId.startsWith("__nav_") })
        add("IDs beginning __nav_ are reserved for live routing.")
    val floors = b.floors.associateBy { it.floorId }
    val nodes = b.nodes.associateBy { it.nodeId }
    b.floors.forEach { f ->
        if (!f.widthM.isFinite() || !f.heightM.isFinite() ||
            f.widthM <= 0f || f.heightM <= 0f)
            add("Invalid floor dimensions: ${f.floorId}.")
    }
    b.nodes.forEach { n ->
        val f = floors[n.floorId]
        if (f == null) add("Unknown floor for ${n.nodeId}.")
        else if (!n.x.isFinite() || !n.y.isFinite() ||
            n.x < 0f || n.y < 0f || n.x > f.widthM || n.y > f.heightM)
            add("Node outside floor bounds: ${n.nodeId}.")
    }
    b.edges.forEach { e ->
        val a = nodes[e.sourceNodeId]
        val c = nodes[e.destinationNodeId]
        if (a == null || c == null) add("Unknown endpoint: ${e.edgeId}.")
        if (!e.distance.isFinite() || e.distance < 0f)
            add("Invalid distance: ${e.edgeId}.")
        if (a != null && c != null && a.floorId != c.floorId &&
            (a.type !in setOf("STAIRS", "ELEVATOR") || a.type != c.type))
            add("Cross-floor edge must join matching connectors: ${e.edgeId}.")
    }
}
