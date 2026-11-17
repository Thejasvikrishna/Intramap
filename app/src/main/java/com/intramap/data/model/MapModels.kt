package com.intramap.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class Floor(
    val floorId: String, val floorNumber: Int, val floorName: String,
    val widthM: Float, val heightM: Float
)

@Serializable data class LocationNode(
    val nodeId: String, val floorId: String, val name: String, val type: String,
    val x: Float, val y: Float,
    val connectorGroupId: String? = null,
    val keywords: List<String> = emptyList(),
    val landmarkDescription: String? = null,
    val imageUrl: String? = null
)

@Serializable data class PathEdge(
    val edgeId: String,
    @SerialName("from") val sourceNodeId: String,
    @SerialName("to") val destinationNodeId: String,
    val distance: Float,
    val accessibilityType: String = "NORMAL",
    val isAvailable: Boolean = true
)

@Serializable data class Building(
    val buildingId: String, val buildingName: String, val description: String = "",
    val floors: List<Floor>, val nodes: List<LocationNode>, val edges: List<PathEdge>
)

/** A visitor-facing map plus the published version that produced it. */
@Serializable
data class PublishedMap(
    val building: Building,
    val versionId: String? = null
)
