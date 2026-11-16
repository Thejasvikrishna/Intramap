package com.intramap.ar

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

enum class ReferenceEnrollmentStatus {
    READY,
    MISSING_IMAGE,
    INVALID_IMAGE,
    INVALID_WIDTH,
    INVALID_POSITION,
    INVALID_HEADING,
    DUPLICATE_CONTENT
}

data class ReferenceImageCandidate(
    val referenceKey: String,
    val floorId: String,
    val imageBytes: ByteArray,
    val physicalWidthM: Float,
    val mapX: Double,
    val mapY: Double,
    val mapHeadingDeg: Double
)

object ReferenceImagePolicy {

    fun validate(
        candidate: ReferenceImageCandidate,
        existingContentHashes: Set<String>
    ): ReferenceEnrollmentStatus {
        if (candidate.referenceKey.isBlank() || candidate.floorId.isBlank()) {
            return ReferenceEnrollmentStatus.INVALID_IMAGE
        }

        if (candidate.imageBytes.isEmpty()) {
            return ReferenceEnrollmentStatus.MISSING_IMAGE
        }

        if (!candidate.physicalWidthM.isFinite() || candidate.physicalWidthM <= 0f) {
            return ReferenceEnrollmentStatus.INVALID_WIDTH
        }

        if (!candidate.mapX.isFinite() || !candidate.mapY.isFinite() ||
            candidate.mapX < 0.0 || candidate.mapY < 0.0
        ) {
            return ReferenceEnrollmentStatus.INVALID_POSITION
        }

        if (!candidate.mapHeadingDeg.isFinite() ||
            candidate.mapHeadingDeg < 0.0 || candidate.mapHeadingDeg >= 360.0
        ) {
            return ReferenceEnrollmentStatus.INVALID_HEADING
        }

        val hash = contentHash(candidate.imageBytes)
        if (hash in existingContentHashes) {
            return ReferenceEnrollmentStatus.DUPLICATE_CONTENT
        }

        return ReferenceEnrollmentStatus.READY
    }

    fun contentHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun pngBytes(image: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }
}
