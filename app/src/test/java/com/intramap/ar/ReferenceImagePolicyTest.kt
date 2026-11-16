package com.intramap.ar

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReferenceImagePolicyTest {

    private fun candidate(
        width: Float = 0.2f,
        x: Double = 1.0,
        y: Double = 1.0,
        heading: Double = 0.0,
        image: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    ) = ReferenceImageCandidate(
        referenceKey = "reference_a",
        floorId = "floor_a",
        image = image,
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
        val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val hash = ReferenceImagePolicy.contentHash(image)

        assertEquals(
            ReferenceEnrollmentStatus.DUPLICATE_CONTENT,
            ReferenceImagePolicy.validate(candidate(image = image), setOf(hash))
        )
    }

    @Test
    fun `different images produce different hashes`() {
        val first = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)

        assertNotEquals(
            ReferenceImagePolicy.contentHash(first),
            ReferenceImagePolicy.contentHash(second)
        )
    }
}
