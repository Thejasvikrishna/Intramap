package com.intramap.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MappingBuilding(
    val id: String,
    @SerialName("building_key") val buildingKey: String,
    val name: String,
    val description: String = ""
)

@Serializable
data class MappingVersion(
    val id: String,
    @SerialName("building_id") val buildingId: String,
    @SerialName("version_no") val versionNo: Int,
    val status: String
)

@Serializable
data class MappingFloor(
    val id: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("floor_key") val floorKey: String,
    @SerialName("floor_number") val floorNumber: Int,
    val name: String,
    @SerialName("width_m") val widthM: Float,
    @SerialName("height_m") val heightM: Float
)

@Serializable
data class MappingNode(
    val id: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("node_key") val nodeKey: String,
    @SerialName("floor_key") val floorKey: String,
    val name: String,
    @SerialName("node_type") val nodeType: String,
    val x: Float,
    val y: Float,
    @SerialName("connector_group_id") val connectorGroupId: String? = null,
    val keywords: List<String> = emptyList(),
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null
)

@Serializable
data class MappingEdge(
    val id: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("edge_key") val edgeKey: String,
    @SerialName("from_key") val fromKey: String,
    @SerialName("to_key") val toKey: String,
    @SerialName("distance_m") val distanceM: Float,
    @SerialName("accessibility_type") val accessibilityType: String = "NORMAL",
    @SerialName("is_available") val isAvailable: Boolean = true
)

@Serializable
data class PathRecording(
    val id: String,
    @SerialName("building_id") val buildingId: String,
    @SerialName("floor_key") val floorKey: String,
    @SerialName("recorder_id") val recorderId: String,
    @SerialName("start_anchor_key") val startAnchorKey: String? = null,
    @SerialName("end_anchor_key") val endAnchorKey: String? = null,
    val status: String = "draft"
)

@Serializable
data class PathRecordingPoint(
    @SerialName("recording_id") val recordingId: String,
    val seq: Int,
    val x: Float,
    val y: Float,
    @SerialName("t_ms") val timeMs: Long,
    @SerialName("heading_deg") val headingDeg: Float? = null,
    val marker: String? = null
)
