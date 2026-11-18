package com.intramap.domain.mapping

import com.intramap.data.model.MappingNode
import org.junit.Assert.*
import org.junit.Test

class BranchAnchorSelectionTest {
    private fun node(key: String,x: Float = 0f) = MappingNode("db-$key","v",key,"floor",key,"JUNCTION",x,0f)
    private val nodes = listOf(node("a"),node("b",0.5f))
    private fun problem(points: List<MappingNode> = nodes,start: String? = "a",facing: String? = "b") = branchAnchorProblem(points,"v","floor",start,facing)
    @Test fun halfMetreSelectionIsUsable() { assertNull(problem()) }
    @Test fun closerSelectionIsRejectedBeforeRecording() { assertNotNull(problem(listOf(node("a"),node("b",0.499f)))) }
    @Test fun bothRolesAreRequired() { assertNotNull(problem(start = null)); assertNotNull(problem(facing = null)) }
    @Test fun samePointCannotDefineDirection() { assertNotNull(problem(facing = "a")) }
    @Test fun anotherFloorOrVersionCannotSupplyAnchors() {
        assertNotNull(problem(nodes.map { it.copy(floorKey = "other") }))
        assertNotNull(problem(nodes.map { it.copy(versionId = "other") }))
    }
    @Test fun nonfiniteCoordinatesAreRejected() {
        assertNotNull(problem(listOf(node("a"),node("b",Float.NaN))))
        assertNotNull(problem(listOf(node("a"),node("b",Float.POSITIVE_INFINITY))))
    }
    @Test fun duplicateKeysAreRejected() { assertNotNull(problem(nodes + node("b",1f))) }
}
