package com.intramap.domain.localization

import com.intramap.data.model.Building
import com.intramap.data.model.Floor
import org.junit.Assert.*
import org.junit.Test

class ReferencePoseAlignmentTest {
    private fun building() = Building("building","Test","",listOf(Floor("f",0,"Ground",50f,50f),Floor("g",1,"Upper",50f,50f)),emptyList(),emptyList())
    private fun references() = listOf(
        RegisteredReferencePose("building","version","sign","f",PlanarPose(12.0,20.0,180.0)),
        RegisteredReferencePose("building","version","upper","g",PlanarPose(30.0,30.0,180.0)))
    private fun aligner() = ReferencePoseAligner(building(),"version","world",references())
    private fun observation() = ReferencePoseObservation("building","version","world","sign",1L,1000L,true,
        ReferenceImageTracking.FULL,PlanarPose(2.0,3.0,90.0),PlanarPose(3.0,3.0,0.0))
    private fun reason(result: ReferenceAlignmentResult) = (result as ReferenceAlignmentResult.Rejected).reason
    private fun assertPose(expected: PlanarPose,actual: PlanarPose) {
        assertEquals(expected.x,actual.x,1e-8); assertEquals(expected.y,actual.y,1e-8)
        assertEquals(normalizedPlanarHeading(expected.headingDeg),normalizedPlanarHeading(actual.headingDeg),1e-8)
    }
    @Test fun `rotation and translation preserve camera offset from the marker`() {
        val transform = PlanarReferenceTransform.between(PlanarPose(2.0,3.0,90.0),PlanarPose(12.0,20.0,180.0))
        assertPose(PlanarPose(12.0,21.0,90.0),transform.project(PlanarPose(3.0,3.0,0.0)))
    }
    @Test fun `inverse transformation restores the original pose`() {
        val source = PlanarPose(-3.0,7.0,350.0)
        val transform = PlanarReferenceTransform.between(source,PlanarPose(20.0,10.0,10.0))
        val pose = PlanarPose(4.0,-2.0,170.0)
        assertPose(pose,transform.inverse().project(transform.project(pose)))
    }
    @Test fun `known camera registration also transforms the image pose`() {
        val transform = PlanarReferenceTransform.between(PlanarPose(3.0,3.0,0.0),PlanarPose(12.0,21.0,90.0))
        assertPose(PlanarPose(12.0,20.0,180.0),transform.project(PlanarPose(2.0,3.0,90.0)))
    }
    @Test fun `valid reference yields a candidate scoped to the exact world and map version`() {
        val candidate = (aligner().observe(observation(),1100L) as ReferenceAlignmentResult.Accepted).candidate
        assertPose(PlanarPose(12.0,21.0,90.0),candidate.cameraMapPose)
        assertEquals("world",candidate.sessionId); assertEquals("version",candidate.versionId); assertEquals("f",candidate.floorId)
    }
    @Test fun `cached image poses and paused cameras cannot initialize a candidate`() {
        assertEquals(ReferencePoseRejection.NOT_LIVE_TRACKING,reason(aligner().observe(observation().copy(imageTracking = ReferenceImageTracking.LAST_KNOWN),1100L)))
        assertEquals(ReferencePoseRejection.NOT_LIVE_TRACKING,reason(aligner().observe(observation().copy(cameraTracking = false),1100L)))
    }
    @Test fun `another session or map version is rejected`() {
        assertEquals(ReferencePoseRejection.WRONG_CONTEXT,reason(aligner().observe(observation().copy(sessionId = "old-world"),1100L)))
        assertEquals(ReferencePoseRejection.WRONG_CONTEXT,reason(aligner().observe(observation().copy(versionId = "old-version"),1100L)))
    }
    @Test fun `stale and future elapsed times are rejected`() {
        assertEquals(ReferencePoseRejection.STALE_SAMPLE,reason(aligner().observe(observation(),1501L)))
        assertEquals(ReferencePoseRejection.STALE_SAMPLE,reason(aligner().observe(observation(),999L)))
    }
    @Test fun `an accepted frame cannot be replayed`() {
        val engine = aligner(); engine.observe(observation(),1100L)
        assertEquals(ReferencePoseRejection.NON_INCREASING_FRAME,reason(engine.observe(observation(),1101L)))
        assertTrue(engine.observe(observation().copy(frameId = 2L),1101L) is ReferenceAlignmentResult.Accepted)
    }
    @Test fun `floor changes require explicit confirmation`() {
        val engine = aligner(); engine.observe(observation(),1100L)
        val upper = observation().copy(referenceKey = "upper",frameId = 2L)
        assertEquals(ReferencePoseRejection.FLOOR_CONFIRMATION_REQUIRED,reason(engine.observe(upper,1100L)))
        engine.confirmFloorChange("g")
        assertEquals("g",(engine.observe(upper,1101L) as ReferenceAlignmentResult.Accepted).candidate.floorId)
    }
    @Test fun `out of bounds poses are not clamped or consumed as accepted frames`() {
        val engine = aligner()
        assertEquals(ReferencePoseRejection.OUTSIDE_FLOOR_BOUNDS,reason(engine.observe(observation().copy(cameraWorldPose = PlanarPose(1000.0,3.0,0.0)),1100L)))
        assertTrue(engine.observe(observation(),1101L) is ReferenceAlignmentResult.Accepted)
    }
    @Test(expected = IllegalArgumentException::class)
    fun `duplicate registered keys cannot silently replace a reference`() { ReferencePoseAligner(building(),"version","world",references()+references().first()) }
    @Test(expected = IllegalArgumentException::class)
    fun `nonfinite pose input is rejected`() { PlanarPose(Double.NaN,0.0,0.0) }
}
