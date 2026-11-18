package com.intramap.domain.mapping

import com.intramap.data.model.MappingNode
import com.intramap.data.model.MappingVersion
import com.intramap.data.model.PathRecording
import kotlin.math.hypot

const val MIN_RECORDING_FACING_DISTANCE_M = 0.5

/** Client preflight only: the import RPC remains responsible for authorization and atomic validation. */
fun validateRecordingImport(recording: PathRecording,version: MappingVersion,floorNodes: List<MappingNode>,start: String?,facing: String?,end: String?) {
    require(version.status == "draft") { "Import requires an editable draft" }
    require(recording.buildingId == version.buildingId) { "Recording belongs to another building" }
    require(recording.status in setOf("submitted","approved")) { "Submit the recording before importing it" }
    require(floorNodes.all { it.versionId == version.id && it.floorKey == recording.floorKey }) { "Route anchors belong to another floor or version" }
    require(floorNodes.map { it.nodeKey }.distinct().size == floorNodes.size) { "Duplicate graph anchor keys" }
    recording.startAnchorKey?.let { require(start == it) { "Select the start anchor saved with this branch recording" } }
    recording.endAnchorKey?.let { require(end == it) { "Select the end anchor saved with this recording" } }
    if (floorNodes.isEmpty()) {
        require(start == null && facing == null && end == null) { "The first route cannot join nonexistent graph anchors" }
        return
    }
    val nodes = floorNodes.associateBy { it.nodeKey }
    val a = requireNotNull(nodes[start]) { "Select an existing start point on this floor" }
    val b = requireNotNull(nodes[facing]) { "Select an existing facing point on this floor" }
    require(a.nodeKey != b.nodeKey) { "Start and facing points must differ" }
    require(a.x.isFinite() && a.y.isFinite() && b.x.isFinite() && b.y.isFinite()) { "Anchor coordinates are invalid" }
    require(hypot(a.x.toDouble()-b.x.toDouble(),a.y.toDouble()-b.y.toDouble()) >= MIN_RECORDING_FACING_DISTANCE_M) { "Facing point must be at least 0.5 m from the start" }
    require(end == null || nodes.containsKey(end)) { "End join is not on this floor" }
}
