package com.intramap.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.intramap.data.model.Building
import com.intramap.data.model.Floor

@Composable
fun FloorMapCanvas(
    b: Building, floor: Floor, highlightId: String?,
    routeNodeIds: List<String> = emptyList(), currentNodeId: String? = null,userPos: Offset? = null, modifier: Modifier = Modifier
) {
    val nodes = b.nodes.filter { it.floorId == floor.floorId }
    val byId = b.nodes.associateBy { it.nodeId }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = Color.DarkGray)

    Canvas(modifier.fillMaxWidth().aspectRatio(floor.widthM / floor.heightM).background(Color(0xFFF5F5F5))) {
        val sx = size.width / floor.widthM
        val sy = size.height / floor.heightM
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)

        b.edges.forEach { e ->
            val a = byId[e.sourceNodeId]; val c = byId[e.destinationNodeId]
            if (a != null && c != null && a.floorId == floor.floorId && c.floorId == floor.floorId)
                drawLine(Color.Gray, p(a.x, a.y), p(c.x, c.y), strokeWidth = 6f)
        }
        routeNodeIds.zipWithNext().forEach { (u, v) ->
            val a = byId[u]; val c = byId[v]
            if (a != null && c != null && a.floorId == floor.floorId && c.floorId == floor.floorId)
                drawLine(Color(0xFFFF6F00), p(a.x, a.y), p(c.x, c.y), strokeWidth = 12f)
        }

        nodes.forEach { n ->
            val hi = n.nodeId == highlightId
            val pos = p(n.x, n.y)
            drawCircle(if (hi) Color.Red else Color(0xFF1565C0), if (hi) 18f else 10f, pos)
            if (hi) drawCircle(Color.Red, 30f, pos, style = Stroke(4f))
            if (n.nodeId == currentNodeId) drawCircle(Color(0xFF2E7D32), 16f, pos)

            if (n.type != "JUNCTION") {
                val layout = measurer.measure(n.name, labelStyle)
                val tx = (pos.x + 12f)
                    .coerceAtMost(size.width - layout.size.width)
                    .coerceAtLeast(0f)
                drawText(layout, topLeft = Offset(tx, pos.y - 34f))
            }
        }
        if (userPos != null) drawCircle(Color(0xFF6A1B9A), 14f, p(userPos.x, userPos.y))
    }
}