package com.intramap.domain.mapping

import org.junit.Assert.*
import org.junit.Test

class WalkingTraceTest {
    @Test fun `steps follow the localization heading convention`() {
        val trace = WalkingTrace(0)
        assertTrue(trace.step(1.0, 0.0, 1))
        assertTrue(trace.step(1.0, 90.0, 2))
        val end = trace.snapshot().last()
        assertEquals(1.0, end.x, 1e-9)
        assertEquals(-1.0, end.y, 1e-9)
        assertEquals(2.0, trace.distanceM, 1e-9)
    }

    @Test fun `invalid and repeated samples do not move the trace`() {
        val trace = WalkingTrace(10)
        assertFalse(trace.step(Double.NaN, 0.0, 11))
        assertFalse(trace.step(0.7, Double.POSITIVE_INFINITY, 11))
        assertFalse(trace.step(3.0, 0.0, 11))
        assertFalse(trace.step(0.7, 0.0, 10))
        assertFalse(trace.step(0.7, 0.0, 9))
        assertEquals(1, trace.snapshot().size)
        assertEquals(0.0, trace.distanceM, 0.0)
    }

    @Test fun `a place marker does not create artificial motion`() {
        val trace = WalkingTrace(0)
        trace.step(0.7, 90.0, 1)
        assertTrue(trace.mark("ROOM", "Library"))
        assertEquals(2, trace.snapshot().size)
        assertEquals("ROOM:Library", trace.snapshot().last().marker)
        assertEquals(0.7, trace.distanceM, 1e-9)
        assertFalse(trace.mark("UNKNOWN", "Place"))
        assertFalse(trace.mark("ROOM", " "))
    }

    @Test fun `snapshot is detached and headings wrap consistently`() {
        val trace = WalkingTrace(0)
        val initial = trace.snapshot()
        trace.step(1.0, -90.0, 1)
        assertEquals(1, initial.size)
        assertEquals(-1.0, trace.snapshot().last().x, 1e-9)
        assertEquals(270.0, trace.snapshot().last().headingDeg, 0.0)
    }
}
