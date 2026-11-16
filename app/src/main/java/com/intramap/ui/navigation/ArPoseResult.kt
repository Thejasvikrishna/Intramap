package com.intramap.ui.navigation

import android.os.Bundle

/** Carries an accepted AR reference pose from the camera screen back to visitor navigation. */
object ArPoseResult {
    const val KEY = "ar_reference_pose"

    data class Pose(
        val floorId: String,
        val x: Double,
        val y: Double,
        val headingDeg: Double
    )

    fun bundle(pose: Pose): Bundle = Bundle().apply {
        putString("floorId", pose.floorId)
        putDouble("x", pose.x)
        putDouble("y", pose.y)
        putDouble("headingDeg", pose.headingDeg)
    }

    fun fromBundle(bundle: Bundle?): Pose? {
        val floorId = bundle?.getString("floorId") ?: return null
        if (!bundle.containsKey("x") || !bundle.containsKey("y") || !bundle.containsKey("headingDeg")) {
            return null
        }

        return Pose(
            floorId = floorId,
            x = bundle.getDouble("x"),
            y = bundle.getDouble("y"),
            headingDeg = bundle.getDouble("headingDeg")
        )
    }
}
