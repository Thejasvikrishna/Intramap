package com.intramap.domain.mapping

import com.intramap.data.model.MappingNode
import com.intramap.data.model.MappingVersion
import com.intramap.data.model.PathRecording
import org.junit.Test

class RecordingFacingDistanceTest {
    private val version = MappingVersion("v","building",1,"draft")
    private val recording = PathRecording("r","building","floor","owner",startAnchorKey = "a",status = "submitted")
    private fun validate(distance: Float) {
        val nodes = listOf(
            MappingNode("db-a","v","a","floor","Start","JUNCTION",0f,0f),
            MappingNode("db-b","v","b","floor","Facing","JUNCTION",distance,0f)
        )
        validateRecordingImport(recording,version,nodes,"a","b",null)
    }
    @Test fun exactHalfMetreMatchesServerBoundary() { validate(0.5f) }
    @Test fun moreThanHalfMetreIsAccepted() { validate(1f) }
    @Test(expected = IllegalArgumentException::class) fun justUnderHalfMetreIsRejected() { validate(0.499f) }
    @Test(expected = IllegalArgumentException::class) fun oldOneCentimetreThresholdIsNotEnough() { validate(0.02f) }
    @Test(expected = IllegalArgumentException::class) fun infiniteDirectionIsRejected() { validate(Float.POSITIVE_INFINITY) }
}
