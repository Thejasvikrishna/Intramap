package com.intramap.vision

import android.os.SystemClock
import com.google.ar.core.AugmentedImage
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.intramap.domain.localization.PlanarPose
import com.intramap.domain.localization.ReferenceImageTracking
import com.intramap.domain.localization.ReferencePoseObservation
import com.intramap.domain.localization.RegisteredReferencePose
import com.intramap.domain.localization.normalizedPlanarHeading
import kotlin.math.atan2
import kotlin.math.hypot

/** Pure geometry; world X/Z becomes planar X/Y. This heading is not compass north. */
internal fun referencePlanePose(translation: FloatArray,axis: FloatArray,negateAxis: Boolean = false): PlanarPose? {
    if (translation.size != 3 || axis.size != 3 || translation.any { !it.isFinite() } || axis.any { !it.isFinite() }) return null
    val horizontal = hypot(axis[0].toDouble(),axis[2].toDouble())
    val length = hypot(horizontal,axis[1].toDouble())
    // Conservative engineering policy: reject nearly vertical heading projections.
    if (length <= 1e-9 || horizontal/length < 0.5) return null
    val sign = if (negateAxis) -1.0 else 1.0
    val heading = normalizedPlanarHeading(Math.toDegrees(atan2(sign*axis[2],sign*axis[0])))
    return PlanarPose(translation[0].toDouble(),translation[2].toDouble(),heading)
}

/**
 * Stateless SDK-to-domain adapter. It neither starts a camera/session nor updates visitor position.
 * The owner must replace this adapter and its session token whenever the AR world epoch changes.
 */
class ArCoreReferenceAdapter(private val buildingId: String,private val versionId: String,private val sessionId: String) {
    init { require(buildingId.isNotBlank() && versionId.isNotBlank() && sessionId.isNotBlank()) }

    /**
     * Capture time must already be converted to Android elapsed milliseconds by the session owner.
     * Do not substitute callback time or blindly divide an SDK frame timestamp by a million.
     * Returns null rather than fabricating a pose when time, identity or tracking is unsuitable.
     */
    fun observation(
        frame: Frame,image: AugmentedImage,reference: RegisteredReferencePose,
        capturedAtElapsedMs: Long,nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): ReferencePoseObservation? {
        if (reference.buildingId != buildingId || reference.versionId != versionId || reference.referenceKey.isBlank()) return null
        if (image.name != reference.referenceKey || frame.timestamp <= 0L) return null
        if (capturedAtElapsedMs < 0L || nowElapsedMs < capturedAtElapsedMs || nowElapsedMs-capturedAtElapsedMs > 500L) return null
        if (frame.camera.trackingState != TrackingState.TRACKING || image.trackingState != TrackingState.TRACKING ||
            image.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) return null
        if (!frame.getUpdatedTrackables(AugmentedImage::class.java).contains(image)) return null
        val imagePose = image.centerPose
        val cameraPose = frame.camera.pose
        val imagePlane = referencePlanePose(imagePose.getTranslation(),imagePose.getXAxis()) ?: return null
        val cameraPlane = referencePlanePose(cameraPose.getTranslation(),cameraPose.getZAxis(),negateAxis = true) ?: return null
        return ReferencePoseObservation(buildingId,versionId,sessionId,reference.referenceKey,frame.timestamp,
            capturedAtElapsedMs,true,ReferenceImageTracking.FULL,imagePlane,cameraPlane)
    }
}
