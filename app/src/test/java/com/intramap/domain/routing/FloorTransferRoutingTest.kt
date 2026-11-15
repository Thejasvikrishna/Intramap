package com.intramap.domain.routing

import com.intramap.data.model.*
import com.intramap.domain.navigation.generateInstructions
import org.junit.Assert.*
import org.junit.Test

class FloorTransferRoutingTest {
    private fun map() = Building("b","Building","",listOf(Floor("f",0,"Ground",20f,20f),Floor("g",1,"Upper",2000f,20f)),listOf(
        LocationNode("s","f","Start","ROOM",10f,1f),
        LocationNode("a","f","Stairs lower","STAIRS",9f,1f,"stairs"),
        LocationNode("b","g","Stairs upper","STAIRS",1000f,1f,"stairs"),
        LocationNode("c","f","Lift lower","ELEVATOR",11f,1f,"lift"),
        LocationNode("d","g","Lift upper","ELEVATOR",999f,1f,"lift")
    ),listOf(PathEdge("sa","s","a",1f),PathEdge("ab","a","b",20f,"STAIRS_ONLY"),
        PathEdge("sc","s","c",1f),PathEdge("cd","c","d",30f),PathEdge("db","d","b",1f)))
    @Test fun `independent floor frames do not bias shortest routing`() {
        val r = requireNotNull(findRoute(IndoorGraph(map()),"s","b"))
        assertEquals(listOf("s","a","b"),r.nodeIds)
        assertEquals(21f,r.totalCost,0f)
        assertEquals(1f,r.totalDistance,0f)
    }
    @Test fun `step-free rejects stairs even with incorrect legacy edge metadata`() {
        val source = map(); val graph = IndoorGraph(source.copy(edges = source.edges.map { if (it.edgeId == "ab") it.copy(accessibilityType = "NORMAL") else it }))
        val r = requireNotNull(findRoute(graph,"s","b",AccessibilityMode.STEP_FREE))
        assertEquals(listOf("s","c","d","b"),r.nodeIds)
        assertEquals(2f,r.totalDistance,0f)
        assertNull(findRoute(graph,"s","b",AccessibilityMode.STEP_FREE,setOf("cd")))
    }
    @Test fun `transfer penalties and coordinate offsets are not reported as metres`() {
        val graph = IndoorGraph(map()); val r = requireNotNull(findRoute(graph,"s","b"))
        val instructions = generateInstructions(graph,r,mapOf("f" to "Ground","g" to "Upper"))
        assertEquals(0f,instructions.first { it.text.startsWith("Take") }.distanceM,0f)
        assertEquals(1f,RouteProgress(graph.nodes,r.nodeIds).remainingDistanceM,0f)
    }
    @Test fun `blocked transfer reroutes to the other verified connector`() {
        val r = requireNotNull(findRoute(IndoorGraph(map()),"s","b",blockedEdgeIds = setOf("ab")))
        assertEquals(listOf("s","c","d","b"),r.nodeIds)
        assertEquals(32f,r.totalCost,0f)
    }
}
