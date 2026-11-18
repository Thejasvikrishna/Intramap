package com.intramap.domain.mapping

import com.intramap.data.model.MappingNode
import kotlin.math.hypot

/** Null means a usable real-node selection; no geometry is changed by this check. */
fun branchAnchorProblem(nodes: List<MappingNode>,versionId: String,floorKey: String,start: String?,facing: String?): String? {
    if (start == null) return "Tap an existing start point on this floor."
    if (facing == null) return "Choose Facing point, then tap a different saved point."
    val scoped = nodes.filter { it.versionId == versionId && it.floorKey == floorKey }
    if (scoped.map { it.nodeKey }.distinct().size != scoped.size) return "Duplicate saved point keys; reload the map."
    val a = scoped.firstOrNull { it.nodeKey == start } ?: return "The start point is not on this draft floor. Reload and select it again."
    val b = scoped.firstOrNull { it.nodeKey == facing } ?: return "The facing point is not on this draft floor. Reload and select it again."
    if (a.nodeKey == b.nodeKey) return "Start and facing points must be different."
    if (!a.x.isFinite() || !a.y.isFinite() || !b.x.isFinite() || !b.y.isFinite()) return "Saved point coordinates are invalid. Correct the draft before recording."
    if (hypot(a.x.toDouble()-b.x.toDouble(),a.y.toDouble()-b.y.toDouble()) < MIN_RECORDING_FACING_DISTANCE_M) return "Choose a facing point at least 0.5 m from the start."
    return null
}
