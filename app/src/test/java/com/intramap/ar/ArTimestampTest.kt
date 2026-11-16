package com.intramap.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArTimestampTest {

    @Test
    fun `converts recent frame timestamp`() {
        val nowNanos = 10_000_000_000L
        val frameNanos = 9_500_000_000L

        assertEquals(
            19_500L,
            arFrameTimestampToElapsedMs(frameNanos, nowNanos, 20_000L)
        )
    }

    @Test
    fun `rejects stale frame timestamp`() {
        assertNull(
            arFrameTimestampToElapsedMs(
                1_000_000_000L,
                10_000_000_000L,
                20_000L
            )
        )
    }

    @Test
    fun `rejects future frame timestamp`() {
        assertNull(
            arFrameTimestampToElapsedMs(
                11_000_000_000L,
                10_000_000_000L,
                20_000L
            )
        )
    }

    @Test
    fun `rejects invalid frame timestamp`() {
        assertNull(
            arFrameTimestampToElapsedMs(
                0L,
                10_000_000_000L,
                20_000L
            )
        )
    }
}
