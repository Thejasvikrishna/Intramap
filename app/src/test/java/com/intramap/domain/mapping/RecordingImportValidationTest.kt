package com.intramap.domain.mapping

import com.intramap.data.model.MappingNode
import com.intramap.data.model.MappingVersion
import com.intramap.data.model.PathRecording
import org.junit.Assert.fail
import org.junit.Test

class RecordingImportValidationTest {
    private val version = MappingVersion("version","building",1,"draft")
    private val recording = PathRecording("recording","building","floor","owner",startAnchorKey = "a",status = "submitted")
    private fun node(key: String,x: Float = 0f,y: Float = 0f) = MappingNode("db-$key","version",key,"floor",key,"JUNCTION",x,y)
    private val nodes = listOf(node("a"),node("b",y = 5f))
    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        fail("Invalid import selection was accepted")
    }
    @Test fun matchingBranchUsesRealStartAndDirection() {
        validateRecordingImport(recording,version,nodes,"a","b",null)
    }
    @Test fun branchCannotMoveToAnotherStart() {
        rejected { validateRecordingImport(recording,version,nodes,"b","a",null) }
    }
    @Test fun branchCannotLoseItsStartAfterRecovery() {
        rejected { validateRecordingImport(recording,version,nodes,null,"b",null) }
    }
    @Test fun coincidentAnchorsDoNotDefineFacing() {
        rejected { validateRecordingImport(recording,version,listOf(node("a"),node("b")),"a","b",null) }
    }
    @Test fun nonfiniteAnchorIsRejected() {
        rejected { validateRecordingImport(recording,version,listOf(node("a"),node("b",Float.NaN)),"a","b",null) }
    }
    @Test fun wrongBuildingFloorAndVersionAreRejected() {
        rejected { validateRecordingImport(recording.copy(buildingId = "other"),version,nodes,"a","b",null) }
        rejected { validateRecordingImport(recording,version,nodes.map { it.copy(floorKey = "other") },"a","b",null) }
        rejected { validateRecordingImport(recording,version,nodes.map { it.copy(versionId = "other") },"a","b",null) }
    }
    @Test fun unfinishedRecordingAndPublishedVersionAreRejected() {
        rejected { validateRecordingImport(recording.copy(status = "draft"),version,nodes,"a","b",null) }
        rejected { validateRecordingImport(recording,version.copy(status = "published"),nodes,"a","b",null) }
    }
    @Test fun firstUnanchoredRouteCanCreateFloor() {
        validateRecordingImport(recording.copy(startAnchorKey = null),version,emptyList(),null,null,null)
    }
    @Test fun firstRouteCannotClaimMissingAnchor() {
        rejected { validateRecordingImport(recording,version,emptyList(),"a","b",null) }
    }
    @Test fun unknownEndAndConflictingRecordedEndAreRejected() {
        rejected { validateRecordingImport(recording,version,nodes,"a","b","missing") }
        rejected { validateRecordingImport(recording.copy(endAnchorKey = "b"),version,nodes,"a","b",null) }
    }
}
