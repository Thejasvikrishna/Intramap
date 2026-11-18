package com.intramap.ui.map

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import kotlin.math.min

internal data class IndoorMapTransform(val origin: Offset,val pan: Offset,val center: Offset,val scale: Float) {
    init { require(scale.isFinite() && scale > 0 && origin.x.isFinite() && origin.y.isFinite() && pan.x.isFinite() && pan.y.isFinite() && center.x.isFinite() && center.y.isFinite()) }
    fun project(point: Offset): Offset = origin + pan + (point-center)*scale
    fun unproject(point: Offset): Offset = center + (point-origin-pan)/scale
}

/** Draws supplied graph geometry only; attached places never create extra walking edges. */
@Composable
fun InteractiveIndoorMap(
    building: Building,floorId: String,route: List<LocationNode>,destinationId: String?,
    position: Offset?,spreadM: Double?,blocked: Set<String>,follow: Boolean,recenterToken: Int,
    topInsetPx: Float,bottomInsetPx: Float,onGesture: () -> Unit,onPlace: (LocationNode) -> Unit,
    modifier: Modifier = Modifier,showJunctions: Boolean = false,onMapPoint: ((Float,Float) -> Unit)? = null
) {
    val floor = building.floors.firstOrNull { it.floorId == floorId } ?: return
    val nodes = remember(building) { building.nodes.associateBy { it.nodeId } }
    val colors = MaterialTheme.colorScheme
    var zoom by remember(building.buildingId,floorId) { mutableFloatStateOf(1f) }
    var pan by remember(building.buildingId,floorId) { mutableStateOf(Offset.Zero) }
    var center by remember(building.buildingId,floorId) { mutableStateOf(Offset(floor.widthM/2,floor.heightM/2)) }
    val hits = remember(building.buildingId,floorId) { mutableListOf<Pair<LocationNode,Offset>>() }
    val transform = remember(building.buildingId,floorId) { arrayOfNulls<IndoorMapTransform>(1) }
    val gesture by rememberUpdatedState(onGesture)
    val select by rememberUpdatedState(onPlace)
    val mapPoint by rememberUpdatedState(onMapPoint)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val validPosition = position?.takeIf { it.x.isFinite() && it.y.isFinite() }
    LaunchedEffect(recenterToken,floorId) { pan = Offset.Zero; zoom = 1f; center = validPosition ?: Offset(floor.widthM/2,floor.heightM/2) }
    LaunchedEffect(follow,validPosition) { if (follow && validPosition != null) { center = validPosition; pan = Offset.Zero } }
    Canvas(modifier.semantics { contentDescription = "Map of ${floor.floorName}. Search provides a list of rooms and shops. Tap a point to choose places sharing its mapped access point." }
        .pointerInput(building.buildingId,floorId) {
            detectTransformGestures { centroid,delta,factor,_ ->
                val view = transform[0]
                if (view != null && factor.isFinite() && factor > 0 && delta.x.isFinite() && delta.y.isFinite()) {
                    gesture(); val next = (zoom*factor).coerceIn(0.5f,8f)
                    pan = centroid-view.origin-(centroid-view.origin-pan)*(next/zoom)+delta; zoom = next
                }
            }
        }.pointerInput(building.buildingId,floorId) {
            detectTapGestures { tap ->
                val callback = mapPoint
                if (callback != null) { transform[0]?.unproject(tap)?.let { if (it.x.isFinite() && it.y.isFinite()) callback(it.x,it.y) }; return@detectTapGestures }
                hits.minByOrNull { (_,point) -> (point-tap).getDistance() }?.takeIf { (_,point) -> (point-tap).getDistance() <= 28.dp.toPx() }?.let { select(it.first) }
            }
        }) {
        drawRect(colors.surfaceVariant); hits.clear(); transform[0] = null
        if (!floor.widthM.isFinite() || !floor.heightM.isFinite() || floor.widthM <= 0 || floor.heightM <= 0) return@Canvas
        val top = topInsetPx.coerceIn(0f,size.height)
        val bottom = (size.height-bottomInsetPx).coerceIn(top,size.height)
        val scale = min((size.width-48.dp.toPx()).coerceAtLeast(1f)/floor.widthM,(bottom-top-48.dp.toPx()).coerceAtLeast(1f)/floor.heightM).coerceIn(0.01f,1000000f)*zoom
        val view = IndoorMapTransform(Offset(size.width/2,(top+bottom)/2),pan,center.takeIf { it.x.isFinite() && it.y.isFinite() } ?: Offset(floor.widthM/2,floor.heightM/2),scale)
        transform[0] = view
        fun point(n: LocationNode) = view.project(Offset(n.x,n.y))
        fun valid(n: LocationNode) = n.x.isFinite() && n.y.isFinite()
        building.edges.forEach { e ->
            val a = nodes[e.sourceNodeId]; val b = nodes[e.destinationNodeId]
            if (a != null && b != null && a.floorId == floorId && b.floorId == floorId && valid(a) && valid(b)) {
                drawLine(colors.surface,point(a),point(b),18.dp.toPx(),StrokeCap.Round)
                drawLine(if (e.isAvailable && e.edgeId !in blocked) colors.outlineVariant else colors.error,point(a),point(b),11.dp.toPx(),StrokeCap.Round)
            }
        }
        route.zipWithNext().forEach { (a,b) -> if (a.floorId == floorId && b.floorId == floorId && valid(a) && valid(b)) drawLine(colors.primary,point(a),point(b),6.dp.toPx(),StrokeCap.Round) }
        paint.textSize = 12.sp.toPx(); paint.color = colors.onSurface.toArgb()
        building.nodes.filter { it.floorId == floorId && valid(it) && (showJunctions || it.type != "JUNCTION" || it.mappedPlaces.isNotEmpty() || it.nodeId == destinationId) }.forEach { n ->
            val p = point(n)
            if (p.x in -40f..(size.width+40) && p.y in -40f..(size.height+40)) {
                hits += n to p
                val named = n.type != "JUNCTION" || n.mappedPlaces.isNotEmpty() || n.nodeId == destinationId
                drawCircle(if (n.nodeId == destinationId) colors.error else colors.onSurfaceVariant,if (named) 5.dp.toPx() else 3.dp.toPx(),p)
                if (named) {
                    val label = n.mappedPlaces.firstOrNull()?.let { it.name + if (n.mappedPlaces.size > 1) " +${n.mappedPlaces.size-1}" else "" } ?: n.name
                    drawContext.canvas.nativeCanvas.drawText(label.take(48),p.x+8.dp.toPx(),p.y-8.dp.toPx(),paint)
                }
            }
        }
        validPosition?.let { p ->
            val screen = view.project(p)
            if (spreadM != null && spreadM.isFinite() && spreadM >= 0) drawCircle(colors.primary.copy(alpha = 0.14f),(spreadM.toFloat()*scale).coerceIn(1f,size.maxDimension.coerceAtLeast(1f)),screen)
            drawCircle(colors.surface,10.dp.toPx(),screen); drawCircle(colors.primary,7.dp.toPx(),screen)
        }
    }
}
