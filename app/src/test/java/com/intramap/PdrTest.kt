package com.intramap

import com.intramap.localization.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PdrTest {
    @Test fun stillPhoneGivesNoSteps() {
        val d = StepDetector()
        var n = 0
        for (i in 0 until 500) if (d.update(i * 20_000_000L, 0f, 0f, 9.81f) != null) n++
        assertEquals(0, n)
    }

    @Test fun twoHzWalkingGivesAboutTwentyStepsIn10s() {
        val d = StepDetector()
        var n = 0
        for (i in 0 until 500) {
            val t = i * 20_000_000L
            val a = 9.81f + 3f * sin(2.0 * PI * 2.0 * i * 0.02).toFloat()
            if (d.update(t, 0f, 0f, a) != null) n++
        }
        assertTrue("steps=$n", n in 18..21)
    }

    @Test fun headingRightIncreasesX() {
        val e = PdrEngine(10f, 10f, 0.7f)
        repeat(10) { e.onStep(90f) }
        assertEquals(17f, e.x, 0.01f)
        assertEquals(10f, e.y, 0.01f)
    }

    @Test fun headingUpDecreasesY() {
        val e = PdrEngine(10f, 10f, 0.7f)
        repeat(10) { e.onStep(0f) }
        assertEquals(3f, e.y, 0.01f)
    }

    @Test fun calibrationMapsRawToMapHeading() {
        val e = PdrEngine(0f, 0f, 1f)
        e.calibrate(200f, 90f)
        e.onStep(200f)
        assertEquals(1f, e.x, 0.01f)
    }
}