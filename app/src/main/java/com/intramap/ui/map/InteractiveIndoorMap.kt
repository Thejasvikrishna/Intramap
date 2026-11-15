package com.intramap.ui.map

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import kotlin.math.min

/** Draws only published graph geometry supplied by the caller; never invents walls. */
@Composable
fun InteractiveIndoorMap(
    building: Building,
    floorId: String,
    route: List<LocationNode>,
    destinationId: String?,
    position: Offset?,
    spreadM: Double?,
    blocked: Set<String>,
    follow: Boolean,
    recenterToken: Int,
    topInsetPx: Float,
    bottomInsetPx: Float,
    onGesture: () -> Unit,
    onPlace: (LocationNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val floor = building.floors.firstOrNull { it.floorId == floorId } ?: return
    val nodes = remember(building) { building.nodes.associateBy { it.nodeId } }
    var zoom by remember(building.buildingId, floorId) { mutableFloatStateOf(1f) }
    var pan by remember(building.buildingId, floorId) { mutableStateOf(Offset.Zero) }
    var center by remember(building.buildingId, floorId) {
        mutableStateOf(Offset(floor.widthM / 2, floor.heightM / 2))
    }
    val hitTargets = remember(building.buildingId, floorId) { mutableListOf<Pair<LocationNode, Offset>>() }
    val gestureOrigin = remember { arrayOf(Offset.Zero) }
    val gesture by rememberUpdatedState(onGesture)
    val selectPlace by rememberUpdatedState(onPlace)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(40, 53, 70) } }
    LaunchedEffect(recenterToken, floorId) {
        pan = Offset.Zero; zoom = 1f
        center = position ?: Offset(floor.widthM / 2, floor.heightM / 2)
    }
    LaunchedEffect(follow, position) {
        if (follow && position != null) { center = position; pan = Offset.Zero }
    }
    Canvas(modifier.semantics {
        contentDescription = "Indoor map of ${floor.floorName}. Blue shows the route and estimated position. Search or tap a named place to select it."
    }.pointerInput(building.buildingId, floorId) {
        detectTransformGestures { centroid, delta, factor, _ ->
            gesture()
            val updated = (zoom * factor).coerceIn(0.5f, 8f)
            val ratio = updated / zoom
            pan = centroid - gestureOrigin[0] - (centroid - gestureOrigin[0] - pan) * ratio + delta
            zoom = updated
        }
    }.pointerInput(building.buildingId, floorId) {
        detectTapGestures { tap ->
            hitTargets.minByOrNull { (_, point) -> (point - tap).getDistance() }
                ?.takeIf { (_, point) -> (point - tap).getDistance() <= 28.dp.toPx() }
                ?.let { selectPlace(it.first) }
        }
    }) {
        drawRect(Color(0xFFF1F5F9))
        if (!floor.widthM.isFinite() || !floor.heightM.isFinite() || floor.widthM <= 0 || floor.heightM <= 0) return@Canvas
        val top = topInsetPx.coerceIn(0f, size.height)
        val bottom = (size.height - bottomInsetPx).coerceIn(top, size.height)
        val origin = Offset(size.width / 2, (top + bottom) / 2)
        gestureOrigin[0] = origin
        val availableHeight = (bottom - top - 48.dp.toPx()).coerceAtLeast(1f)
        val scale = min((size.width - 48.dp.toPx()).coerceAtLeast(1f) / floor.widthM,
            availableHeight / floor.heightM).coerceAtLeast(0.01f) * zoom
        fun point(x: Float, y: Float) = origin + pan + (Offset(x, y) - center) * scale
        building.edges.forEach { edge ->
            val a = nodes[edge.sourceNodeId]; val b = nodes[edge.destinationNodeId]
            if (a != null && b != null && a.floorId == floorId && b.floorId == floorId) {
                val color = if (edge.isAvailable && edge.edgeId !in blocked) Color(0xFFCBD5E1) else Color(0xFFDC2626)
                drawLine(Color.White, point(a.x,a.y), point(b.x,b.y), 18.dp.toPx(), StrokeCap.Round)
                drawLine(color, point(a.x,a.y), point(b.x,b.y), 11.dp.toPx(), StrokeCap.Round)
            }
        }
        route.zipWithNext().forEach { (a,b) ->
            if (a.floorId == floorId && b.floorId == floorId) {
                drawLine(Color(0xFF2563EB), point(a.x,a.y), point(b.x,b.y), 6.dp.toPx(), StrokeCap.Round)
            }
        }
        paint.textSize = 12.dp.toPx()
        val targets = mutableListOf<Pair<LocationNode,Offset>>()
        building.nodes.filter { it.floorId == floorId && it.type != "JUNCTION" }.forEach { node ->
            val p = point(node.x,node.y)
            if (p.x in -40f..(size.width+40) && p.y in -40f..(size.height+40)) {
                targets += node to p
                drawCircle(if (node.nodeId == destinationId) Color(0xFFDC2626) else Color(0xFF475569), 5.dp.toPx(), p)
                drawContext.canvas.nativeCanvas.drawText(node.name, p.x+8.dp.toPx(), p.y-8.dp.toPx(), paint)
            }
        }
        hitTargets.clear(); hitTargets.addAll(targets)
        position?.takeIf { it.x.isFinite() && it.y.isFinite() }?.let { p ->
            val screen = point(p.x,p.y)
            if (spreadM != null && spreadM.isFinite() && spreadM >= 0) {
                drawCircle(Color(0x222563EB), (spreadM.toFloat()*scale).coerceIn(1f,size.maxDimension.coerceAtLeast(1f)), screen)
            }
            drawCircle(Color.White, 10.dp.toPx(), screen)
            drawCircle(Color(0xFF2563EB), 7.dp.toPx(), screen)
        }
    }
}
