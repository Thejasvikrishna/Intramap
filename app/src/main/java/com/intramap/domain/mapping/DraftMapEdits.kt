package com.intramap.domain.mapping

import com.intramap.data.model.Building
import com.intramap.data.model.PathEdge
import kotlin.math.hypot

/** Immutable local edit history. Saving establishes a new baseline; undo is for unsaved edits. */
data class DraftMapEdits private constructor(
    val original: Building, val current: Building,
    private val past: List<Building>, private val future: List<Building>
) {
    constructor(building: Building) : this(building,building,emptyList(),emptyList())
    val dirty: Boolean get() = current != original
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    private fun commit(next: Building): DraftMapEdits = if (next == current) this
        else copy(current = next,past = (past + current).takeLast(20),future = emptyList())
    fun undo(): DraftMapEdits = if (!canUndo) this else copy(current = past.last(),past = past.dropLast(1),future = (future + current).takeLast(20))
    fun redo(): DraftMapEdits = if (!canRedo) this else copy(current = future.last(),past = (past + current).takeLast(20),future = future.dropLast(1))

    fun renameNode(id: String,name: String,type: String): DraftMapEdits {
        val node = current.nodes.first { it.nodeId == id }
        val label = name.trim()
        require(label.length in 1..120) { "Enter a name of 1 to 120 characters." }
        require(type in setOf("ROOM","ENTRANCE","JUNCTION","STAIRS","ELEVATOR")) { "Unsupported place type." }
        val nodes = current.nodes.associateBy { it.nodeId }
        require(type == node.type || current.edges.none {
            (it.sourceNodeId == id || it.destinationNodeId == id) &&
                nodes[it.sourceNodeId]?.floorId != nodes[it.destinationNodeId]?.floorId
        }) { "A linked floor connector cannot change type in this editor." }
        return commit(current.copy(nodes = current.nodes.map { if (it.nodeId == id) it.copy(name = label,type = type) else it }))
    }
    fun moveNode(id: String,x: Float,y: Float): DraftMapEdits {
        val node = current.nodes.first { it.nodeId == id }
        val baseline = original.nodes.first { it.nodeId == id }
        val floor = current.floors.first { it.floorId == node.floorId }
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x <= floor.widthM && y <= floor.heightM) { "Choose a position inside the mapped floor." }
        require(hypot((x-baseline.x).toDouble(),(y-baseline.y).toDouble()) <= 3.0) { "Corrections are limited to 3 m; re-record larger errors." }
        val updated = current.nodes.map { if (it.nodeId == id) it.copy(x = x,y = y) else it }
        val nodes = updated.associateBy { it.nodeId }
        val edges = current.edges.map { edge ->
            if (edge.sourceNodeId != id && edge.destinationNodeId != id) edge else {
                val a = nodes.getValue(edge.sourceNodeId); val b = nodes.getValue(edge.destinationNodeId)
                if (a.floorId != b.floorId) edge else {
                    val distance = hypot((a.x-b.x).toDouble(),(a.y-b.y).toDouble()).toFloat()
                    require(distance > 0.05f) { "A correction cannot collapse an existing path." }
                    edge.copy(distance = distance)
                }
            }
        }
        return commit(current.copy(nodes = updated,edges = edges))
    }
    fun setAvailable(edgeId: String,available: Boolean): DraftMapEdits {
        require(current.edges.any { it.edgeId == edgeId }) { "Unknown path." }
        return commit(current.copy(edges = current.edges.map { if (it.edgeId == edgeId) it.copy(isAvailable = available) else it }))
    }
    fun joinEndpoints(aId: String,bId: String,edgeId: String,verifiedWalkable: Boolean): DraftMapEdits {
        require(verifiedWalkable) { "Explicitly verify the physical connection first." }
        require(aId != bId && edgeId.isNotBlank() && edgeId.length <= 120 && !edgeId.startsWith("__nav_") && current.edges.none { it.edgeId == edgeId }) { "Invalid connection identifier." }
        val a = current.nodes.first { it.nodeId == aId }; val b = current.nodes.first { it.nodeId == bId }
        require(a.floorId == b.floorId) { "This join is for nearby same-floor endpoints only." }
        val distance = hypot((a.x-b.x).toDouble(),(a.y-b.y).toDouble()).toFloat()
        require(distance > 0.05f && distance <= 3f) { "Only gaps of 0.05 to 3 m may be joined; record longer paths." }
        require(current.edges.none { (it.sourceNodeId == aId && it.destinationNodeId == bId) || (it.sourceNodeId == bId && it.destinationNodeId == aId) }) { "These endpoints are already connected." }
        return commit(current.copy(edges = current.edges + PathEdge(edgeId,aId,bId,distance)))
    }
}
