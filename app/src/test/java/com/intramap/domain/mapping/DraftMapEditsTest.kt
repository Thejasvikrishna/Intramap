package com.intramap.domain.mapping

import com.intramap.data.model.*
import org.junit.Assert.*
import org.junit.Test

class DraftMapEditsTest {
    private fun map() = Building("b","Building","",listOf(Floor("f",0,"Ground",20f,20f)),listOf(
        LocationNode("a","f","Entrance","ENTRANCE",1f,1f),
        LocationNode("b","f","Room","ROOM",2f,1f),
        LocationNode("c","f","Junction","JUNCTION",4f,1f)
    ),listOf(PathEdge("ab","a","b",1f)))
    @Test fun `moving a point updates incident geometry and undo restores it`() {
        val initial = DraftMapEdits(map())
        val moved = initial.moveNode("b",2f,2f)
        assertEquals(kotlin.math.sqrt(2.0).toFloat(),moved.current.edges.single().distance,1e-5f)
        assertEquals(initial.current,moved.undo().current)
        assertEquals(moved.current,moved.undo().redo().current)
        assertFalse(moved.undo().dirty)
    }
    @Test fun `new edits after undo discard the redo branch`() {
        val edits = DraftMapEdits(map()).renameNode("b","Library","ROOM").undo().setAvailable("ab",false)
        assertFalse(edits.canRedo)
        assertFalse(edits.current.edges.single().isAvailable)
        assertEquals("Room",edits.current.nodes.first { it.nodeId == "b" }.name)
    }
    @Test fun `nearby joins require explicit verification and preserve the original`() {
        val initial = DraftMapEdits(map())
        val joined = initial.joinEndpoints("a","c","ac",true)
        assertEquals(2,joined.current.edges.size)
        assertEquals(1,initial.current.edges.size)
        assertEquals(3f,joined.current.edges.last().distance,0f)
    }
    @Test(expected = IllegalArgumentException::class)
    fun `unverified joins are rejected`() { DraftMapEdits(map()).joinEndpoints("a","c","ac",false) }
    @Test(expected = IllegalArgumentException::class)
    fun `large point corrections require re-recording`() { DraftMapEdits(map()).moveNode("b",10f,10f) }
    @Test(expected = IllegalArgumentException::class)
    fun `collapsed paths are rejected`() { DraftMapEdits(map()).moveNode("b",1f,1f) }
    @Test(expected = IllegalArgumentException::class)
    fun `cross-floor joins are not inferred from nearby coordinates`() {
        val source = map()
        val other = source.copy(floors = source.floors + Floor("g",1,"Upper",20f,20f),
            nodes = source.nodes + LocationNode("d","g","Landing","STAIRS",2f,1f))
        DraftMapEdits(other).joinEndpoints("a","d","ad",true)
    }
    @Test(expected = IllegalArgumentException::class)
    fun `duplicate connections are rejected`() { DraftMapEdits(map()).joinEndpoints("b","a","ba",true) }
    @Test(expected = IllegalArgumentException::class)
    fun `invalid coordinates are rejected`() { DraftMapEdits(map()).moveNode("b",Float.NaN,2f) }
}
