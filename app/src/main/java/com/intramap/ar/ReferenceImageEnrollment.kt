package com.intramap.ar

import android.graphics.Bitmap
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
    val image: Bitmap,
    val physicalWidthM: Float,
    val mapX: Double,
    val mapY: Double,
    val mapHeadingDeg: Double
)

/**
 * Client-side enrollment validation only. It cannot establish ARCore recognition
 * quality or prove that a physical marker was placed correctly.
 */
object ReferenceImagePolicy {

    fun validate(
        candidate: ReferenceImageCandidate,
        existingContentHashes: Set<String>
    ): ReferenceEnrollmentStatus {
        if (candidate.referenceKey.isBlank() || candidate.floorId.isBlank()) {
            return ReferenceEnrollmentStatus.INVALID_IMAGE
        }

        if (candidate.image.width <= 0 || candidate.image.height <= 0) {
            return ReferenceEnrollmentStatus.INVALID_IMAGE
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

        val hash = contentHash(candidate.image)
        if (hash in existingContentHashes) {
            return ReferenceEnrollmentStatus.DUPLICATE_CONTENT
        }

        return ReferenceEnrollmentStatus.READY
    }

    fun contentHash(image: Bitmap): String {
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(width.toString().toByteArray())
        digest.update(height.toString().toByteArray())

        pixels.forEach { pixel ->
            digest.update(byteArrayOf(
                (pixel ushr 24).toByte(),
                (pixel ushr 16).toByte(),
                (pixel ushr 8).toByte(),
                pixel.toByte()
            ))
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
