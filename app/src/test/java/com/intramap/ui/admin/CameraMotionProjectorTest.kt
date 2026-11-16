package com.intramap.ui.admin

import org.junit.Assert.*
import org.junit.Test

class CameraMotionProjectorTest {
    @Test fun forwardMotionUsesMapUp() {
        val p = CameraMotionProjector()
        assertNull(p.observe(0.0,1.5,0.0,0.0,-1.0,100_000_000))
        val step = requireNotNull(p.observe(0.0,1.5,-0.5,0.0,-1.0,200_000_000))
        assertEquals(0.5,step.distanceM,1e-6)
        assertEquals(0.0,step.headingDeg,1e-6)
    }
    @Test fun rightMotionUsesEastHeading() {
        val p = CameraMotionProjector()
        p.observe(0.0,1.5,0.0,0.0,-1.0,100_000_000)
        assertEquals(90.0,requireNotNull(p.observe(0.5,1.5,0.0,0.0,-1.0,200_000_000)).headingDeg,1e-6)
    }
    @Test fun smallJitterDoesNotCreateSamples() {
        val p = CameraMotionProjector()
        p.observe(0.0,1.5,0.0,0.0,-1.0,100_000_000)
        assertNull(p.observe(0.02,1.5,0.01,0.0,-1.0,200_000_000))
    }
    @Test(expected = IllegalArgumentException::class) fun gapIsRejected() {
        val p = CameraMotionProjector()
        p.observe(0.0,1.5,0.0,0.0,-1.0,100_000_000)
        p.observe(0.0,1.5,-0.5,0.0,-1.0,1_100_000_000)
    }
    @Test(expected = IllegalArgumentException::class) fun poseJumpIsRejected() {
        val p = CameraMotionProjector()
        p.observe(0.0,1.5,0.0,0.0,-1.0,100_000_000)
        p.observe(5.0,1.5,0.0,0.0,-1.0,200_000_000)
    }
    @Test(expected = IllegalArgumentException::class) fun nonFinitePoseIsRejected() {
        CameraMotionProjector().observe(Double.NaN,1.5,0.0,0.0,-1.0,100_000_000)
    }
}
