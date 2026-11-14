package com.intramap.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.navigation.NavigationSession
import kotlin.math.min

@Composable
fun InteractiveFloorMap(
    building: Building,
    floorId: String,
    session: NavigationSession,
    modifier: Modifier = Modifier
) {
    val floor = building.floors.firstOrNull { it.floorId == floorId } ?: return
    val nodes = remember(building) { building.nodes.associateBy { it.nodeId } }

    var scale by remember(floorId) { mutableFloatStateOf(1f) }
    var offsetX by remember(floorId) { mutableFloatStateOf(0f) }
    var offsetY by remember(floorId) { mutableFloatStateOf(0f) }

    Canvas(
        modifier
            .fillMaxSize()
            .padding(bottom = 220.dp)
            .pointerInput(floorId) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.8f, 4f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        val baseScale = min(
            (size.width - 64f) / floor.widthM,
            (size.height - 64f) / floor.heightM
        )

        val effectiveScale = baseScale * scale
        val centerX = size.width / 2f + offsetX
        val centerY = size.height / 2f + offsetY

        fun point(x: Double, y: Double) = Offset(
            centerX + (x.toFloat() - floor.widthM / 2f) * effectiveScale,
            centerY + (y.toFloat() - floor.heightM / 2f) * effectiveScale
        )

        fun nodePoint(node: LocationNode) = point(node.x.toDouble(), node.y.toDouble())

        val floorTopLeft = point(0.0, 0.0)
        val floorWidth = floor.widthM * effectiveScale
        val floorHeight = floor.heightM * effectiveScale

        drawRoundRect(
            color = Color(0xFFF1F3F4),
            topLeft = floorTopLeft,
            size = Size(floorWidth, floorHeight),
            cornerRadius = CornerRadius(28f, 28f)
        )

        drawRoundRect(
            color = Color(0xFFDADCE0),
            topLeft = floorTopLeft,
            size = Size(floorWidth, floorHeight),
            cornerRadius = CornerRadius(28f, 28f),
            style = Stroke(width = 3f)
        )

        building.edges.forEach { edge ->
            val from = nodes[edge.sourceNodeId]
            val to = nodes[edge.destinationNodeId]

            if (
                from != null && to != null &&
                from.floorId == floorId && to.floorId == floorId
            ) {
                drawLine(
                    color = if (edge.isAvailable && edge.edgeId !in session.blocked)
                        Color(0xFFB0BEC5)
                    else
                        Color(0xFFE57373),
                    start = nodePoint(from),
                    end = nodePoint(to),
                    strokeWidth = 18f,
                    cap = StrokeCap.Round
                )
            }
        }

        if (session.routeNodes.size > 1) {
            val routePath = Path().apply {
                val first = nodePoint(session.routeNodes.first())
                moveTo(first.x, first.y)
                session.routeNodes.drop(1).forEach { node ->
                    val target = nodePoint(node)
                    lineTo(target.x, target.y)
                }
            }

            drawPath(
                routePath,
                color = Color(0xFF1A73E8),
                style = Stroke(width = 20f, cap = StrokeCap.Round)
            )
        }

        building.nodes
            .filter { it.floorId == floorId && it.type != "JUNCTION" }
            .forEach { node ->
                drawCircle(
                    color = Color(0xFF202124),
                    radius = 10f,
                    center = nodePoint(node)
                )
            }

        session.estimate?.takeIf { it.floorId == floorId }?.let { estimate ->
            val center = point(estimate.x, estimate.y)
            drawCircle(Color(0xFF1A73E8), 18f, center)
            drawCircle(
                Color(0x331A73E8),
                (estimate.spreadM.toFloat() * effectiveScale).coerceIn(24f, size.minDimension / 3f),
                center,
                style = Stroke(width = 5f)
            )
        }

        session.destination?.takeIf { it.floorId == floorId }?.let { destination ->
            drawCircle(
                Color(0xFFEA4335),
                20f,
                nodePoint(destination),
                style = Stroke(width = 8f)
            )
        }
    }
}
