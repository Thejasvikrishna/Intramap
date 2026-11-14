package com.intramap.vision

import android.os.Bundle

data class CameraLandmarkObservation(
    val nodeId: String,
    val floorId: String,
    val observedText: String,
    val capturedAtElapsedMs: Long,
    val matchingScans: Int
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString("nodeId", nodeId)
        putString("floorId", floorId)
        putString("observedText", observedText)
        putLong("capturedAtElapsedMs", capturedAtElapsedMs)
        putInt("matchingScans", matchingScans)
    }

    companion object {
        const val RESULT_KEY = "camera_landmark_result"

        fun fromBundle(bundle: Bundle): CameraLandmarkObservation? {
            val nodeId = bundle.getString("nodeId") ?: return null
            val floorId = bundle.getString("floorId") ?: return null

            return CameraLandmarkObservation(
                nodeId = nodeId,
                floorId = floorId,
                observedText = bundle.getString("observedText").orEmpty(),
                capturedAtElapsedMs = bundle.getLong(
                    "capturedAtElapsedMs",
                    -1L
                ),
                matchingScans = bundle.getInt("matchingScans", 0)
            )
        }
    }
}