package com.intramap.domain.localization

import com.intramap.data.model.Building
import kotlin.math.cos
import kotlin.math.sin

/** Metres in one horizontal XY plane; heading is from +X toward +Y, NOT compass north. */
data class PlanarPose(val x: Double,val y: Double,val headingDeg: Double) {
    init { require(x.isFinite() && y.isFinite() && headingDeg.isFinite()) }
}
fun normalizedPlanarHeading(value: Double): Double {
    require(value.isFinite())
    return ((value % 360.0)+360.0)%360.0
}

/** Rigid alignment only: this never invents a scale calibration. */
data class PlanarReferenceTransform private constructor(val tx: Double,val ty: Double,val rotationDeg: Double) {
    init { require(tx.isFinite() && ty.isFinite() && rotationDeg.isFinite() && rotationDeg >= 0 && rotationDeg < 360) }
    fun project(pose: PlanarPose): PlanarPose {
        val angle = Math.toRadians(rotationDeg); val c = cos(angle); val s = sin(angle)
        return PlanarPose(c*pose.x-s*pose.y+tx,s*pose.x+c*pose.y+ty,
            normalizedPlanarHeading(normalizedPlanarHeading(pose.headingDeg)+rotationDeg))
    }
    fun inverse(): PlanarReferenceTransform {
        val angle = Math.toRadians(rotationDeg); val c = cos(angle); val s = sin(angle)
        return PlanarReferenceTransform(-c*tx-s*ty,s*tx-c*ty,normalizedPlanarHeading(-rotationDeg))
    }
    companion object {
        fun between(source: PlanarPose,target: PlanarPose): PlanarReferenceTransform {
            val rotation = normalizedPlanarHeading(normalizedPlanarHeading(target.headingDeg)-normalizedPlanarHeading(source.headingDeg))
            val angle = Math.toRadians(rotation); val c = cos(angle); val s = sin(angle)
            return PlanarReferenceTransform(target.x-c*source.x+s*source.y,target.y-s*source.x-c*source.y,rotation)
        }
    }
}

data class RegisteredReferencePose(
    val buildingId: String,val versionId: String,val referenceKey: String,val floorId: String,
    /** Registered image centre and its horizontal image +X direction in the map frame. */
    val imageMapPose: PlanarPose
)
enum class ReferenceImageTracking { FULL, LAST_KNOWN, PAUSED, STOPPED }

/** Both poses must originate from the SAME current camera frame and world-session epoch. */
data class ReferencePoseObservation(
    val buildingId: String,val versionId: String,val sessionId: String,val referenceKey: String,
    /** Opaque monotonically increasing frame ID within this session, not an elapsed clock. */
    val frameId: Long,
    /** Adapter-normalized Android elapsed time; never substitute an arbitrary SDK timestamp. */
    val capturedAtElapsedMs: Long,
    val cameraTracking: Boolean,val imageTracking: ReferenceImageTracking,
    val imageWorldPose: PlanarPose,val cameraWorldPose: PlanarPose
)
data class ReferencePoseCandidate(
    val buildingId: String,val versionId: String,val sessionId: String,val referenceKey: String,
    val floorId: String,val frameId: Long,val capturedAtElapsedMs: Long,
    val cameraMapPose: PlanarPose,val worldToMap: PlanarReferenceTransform
)
enum class ReferencePoseRejection {
    WRONG_CONTEXT, UNKNOWN_REFERENCE, NOT_LIVE_TRACKING, STALE_SAMPLE,
    NON_INCREASING_FRAME, FLOOR_CONFIRMATION_REQUIRED, OUTSIDE_FLOOR_BOUNDS, INVALID_GEOMETRY
}
sealed class ReferenceAlignmentResult {
    data class Accepted(val candidate: ReferencePoseCandidate): ReferenceAlignmentResult()
    data class Rejected(val reason: ReferencePoseRejection): ReferenceAlignmentResult()
}

/** Produces geometric candidates only, not calibrated confidence or proof of sensor accuracy. */
class ReferencePoseAligner(
    private val building: Building,private val versionId: String,private val sessionId: String,
    registrations: List<RegisteredReferencePose>,private val maxAgeMs: Long = 500
) {
    private val floors = building.floors.associateBy { it.floorId }
    private val references = registrations.associateBy { it.referenceKey }
    private var lastAcceptedFrame = 0L
    private var allowedFloor: String? = null
    init {
        require(building.buildingId.isNotBlank() && versionId.isNotBlank() && sessionId.isNotBlank())
        require(maxAgeMs in 1L..5000L)
        require(floors.size == building.floors.size && references.size == registrations.size)
        require(building.floors.all { it.widthM.isFinite() && it.heightM.isFinite() && it.widthM > 0 && it.heightM > 0 })
        require(registrations.all { r ->
            val f = floors[r.floorId]
            r.buildingId == building.buildingId && r.versionId == versionId && r.referenceKey.isNotBlank() &&
                f != null && r.imageMapPose.x >= 0 && r.imageMapPose.y >= 0 && r.imageMapPose.x <= f.widthM && r.imageMapPose.y <= f.heightM
        })
    }
    /** Call only after explicit physical floor-arrival confirmation, not a floor-tab tap. */
    @Synchronized fun confirmFloorChange(floorId: String) { require(floorId in floors); allowedFloor = floorId }

    @Synchronized fun observe(observation: ReferencePoseObservation,nowElapsedMs: Long): ReferenceAlignmentResult {
        fun reject(reason: ReferencePoseRejection) = ReferenceAlignmentResult.Rejected(reason)
        if (observation.buildingId != building.buildingId || observation.versionId != versionId || observation.sessionId != sessionId)
            return reject(ReferencePoseRejection.WRONG_CONTEXT)
        val reference = references[observation.referenceKey] ?: return reject(ReferencePoseRejection.UNKNOWN_REFERENCE)
        if (!observation.cameraTracking || observation.imageTracking != ReferenceImageTracking.FULL)
            return reject(ReferencePoseRejection.NOT_LIVE_TRACKING)
        if (observation.capturedAtElapsedMs < 0 || nowElapsedMs < observation.capturedAtElapsedMs ||
            nowElapsedMs-observation.capturedAtElapsedMs > maxAgeMs) return reject(ReferencePoseRejection.STALE_SAMPLE)
        if (observation.frameId <= lastAcceptedFrame) return reject(ReferencePoseRejection.NON_INCREASING_FRAME)
        if (allowedFloor != null && allowedFloor != reference.floorId) return reject(ReferencePoseRejection.FLOOR_CONFIRMATION_REQUIRED)
        val transform: PlanarReferenceTransform
        val pose: PlanarPose
        try {
            transform = PlanarReferenceTransform.between(observation.imageWorldPose,reference.imageMapPose)
            pose = transform.project(observation.cameraWorldPose)
        } catch (_: IllegalArgumentException) { return reject(ReferencePoseRejection.INVALID_GEOMETRY) }
        val floor = floors.getValue(reference.floorId)
        if (pose.x < 0 || pose.y < 0 || pose.x > floor.widthM || pose.y > floor.heightM)
            return reject(ReferencePoseRejection.OUTSIDE_FLOOR_BOUNDS)
        lastAcceptedFrame = observation.frameId; allowedFloor = reference.floorId
        return ReferenceAlignmentResult.Accepted(ReferencePoseCandidate(building.buildingId,versionId,sessionId,reference.referenceKey,
            reference.floorId,observation.frameId,observation.capturedAtElapsedMs,pose,transform))
    }
}
