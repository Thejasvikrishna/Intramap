package com.intramap.vision

import com.intramap.data.model.Building
import java.util.Locale

data class LandmarkTextMatch(
    val nodeId: String,
    val name: String,
    val floorId: String
)

fun normalizeSignText(text: String): String {
    return text
        .uppercase(Locale.ROOT)
        .replace(Regex("[^A-Z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

fun matchLandmarkText(
    building: Building,
    recognizedText: String,
    floorId: String
): List<LandmarkTextMatch> {
    val observed = normalizeSignText(recognizedText)

    if (observed.isBlank()) {
        return emptyList()
    }

    val paddedObservation = " $observed "

    return building.nodes
        .filter { node ->
            node.floorId == floorId &&
                    node.type != "JUNCTION"
        }
        .mapNotNull { node ->
            val expected = normalizeSignText(node.name)

            if (
                expected.length >= 3 &&
                paddedObservation.contains(" $expected ")
            ) {
                LandmarkTextMatch(
                    nodeId = node.nodeId,
                    name = node.name,
                    floorId = node.floorId
                )
            } else {
                null
            }
        }
}