package com.intramap.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReferenceImagePolicyTest {

    private fun candidate(
        width: Float = 0.2f,
        x: Double = 1.0,
        y: Double = 1.0,
        heading: Double = 0.0,
        imageBytes: ByteArray = ByteArray(16)
    ) = ReferenceImageCandidate(
        referenceKey = "reference_a",
        floorId = "floor_a",
        imageBytes = imageBytes,
        physicalWidthM = width,
        mapX = x,
        mapY = y,
        mapHeadingDeg = heading
    )

    @Test
    fun `valid candidate is ready`() {
        assertEquals(
            ReferenceEnrollmentStatus.READY,
            ReferenceImagePolicy.validate(candidate(), emptySet())
        )
    }

    @Test
    fun `missing image is rejected`() {
        assertEquals(
            ReferenceEnrollmentStatus.MISSING_IMAGE,
            ReferenceImagePolicy.validate(candidate(imageBytes = ByteArray(0)), emptySet())
        )
    }

    @Test
    fun `invalid width is rejected`() {
        assertEquals(
            ReferenceEnrollmentStatus.INVALID_WIDTH,
            ReferenceImagePolicy.validate(candidate(width = 0f), emptySet())
        )
    }

    @Test
    fun `invalid heading is rejected`() {
        assertEquals(
            ReferenceEnrollmentStatus.INVALID_HEADING,
            ReferenceImagePolicy.validate(candidate(heading = 360.0), emptySet())
        )
    }

    @Test
    fun `invalid position is rejected`() {
        assertEquals(
            ReferenceEnrollmentStatus.INVALID_POSITION,
            ReferenceImagePolicy.validate(candidate(x = -1.0), emptySet())
        )
    }

    @Test
    fun `duplicate content is rejected`() {
        val bytes = ByteArray(16)
        val hash = ReferenceImagePolicy.contentHash(bytes)

        assertEquals(
            ReferenceEnrollmentStatus.DUPLICATE_CONTENT,
            ReferenceImagePolicy.validate(candidate(imageBytes = bytes), setOf(hash))
        )
    }

    @Test
    fun `different images produce different hashes`() {
        val first = ByteArray(16)
        val second = ByteArray(16)
        second[0] = 1

        assertNotEquals(
            ReferenceImagePolicy.contentHash(first),
            ReferenceImagePolicy.contentHash(second)
        )
    }
}
