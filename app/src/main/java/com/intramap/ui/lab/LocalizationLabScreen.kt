package com.intramap.ui.lab

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.ui.navigation.NavigationMotionControls
import com.intramap.vision.CameraLandmarkObservation
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalizationLabScreen(
    building: Building,
    onBack: () -> Unit,
    externalEngine: LocalizationLabEngine? = null,
    cameraObservation: Bundle? = null,
    onObservationConsumed: () -> Unit = {},
    onOpenScanner: (() -> Unit)? = null
) {
    val start = building.nodes.firstOrNull { it.type == "ENTRANCE" } ?: building.nodes.firstOrNull()
    if (start == null || building.floors.isEmpty()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Text("No localization map available.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    val engine = remember(building, externalEngine) {
        externalEngine ?: LocalizationLabEngine(building).apply { reset(start.nodeId) }
    }
    var revision by remember(engine) { mutableIntStateOf(0) }
    var motionEpoch by remember(engine) { mutableIntStateOf(0) }
    var tracking by remember { mutableStateOf(false) }
    var selected by remember(building) { mutableStateOf(start.nodeId) }
    var target by remember(building) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var length by remember { mutableFloatStateOf(0.7f) }
    var heading by remember { mutableFloatStateOf(0f) }
    var quality by remember { mutableFloatStateOf(0.8f) }
    var status by remember { mutableStateOf("Developer Lab: simulated or real motion; provisional observation model.") }

    LaunchedEffect(cameraObservation, engine) {
        val bundle = cameraObservation ?: return@LaunchedEffect
        try {
            val o = CameraLandmarkObservation.fromBundle(bundle)
            val n = building.nodes.firstOrNull { it.nodeId == o?.nodeId }
            val age = if (o == null) -1L else SystemClock.elapsedRealtime() - o.capturedAtElapsedMs
            if (o == null || n == null || n.floorId != o.floorId || o.observedText.isBlank() ||
                o.matchingScans < 2 || o.capturedAtElapsedMs < 0L || age !in 0L..30_000L) {
                status = "Camera observation rejected: invalid or expired result."
            } else {
                val update = engine.observeLandmark(o.nodeId, 0.5, adaptive = false)
                revision++
                status = "Camera: ${n.name}. ${update.message} Fixed observation uncertainty; confidence is not calibrated."
            }
        } finally { onObservationConsumed() }
    }
    val estimate = remember(engine, revision) { engine.estimate() }
    val particles = remember(engine, revision) { engine.particles() }
    val selectedNode = building.nodes.firstOrNull { it.nodeId == selected }
    val connectors = building.nodes.filter { it.type in setOf("STAIRS", "ELEVATOR") && it.floorId != estimate.floorId }
    val floor = building.floors.firstOrNull { it.floorId == estimate.floorId }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Localization Lab") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status, color = MaterialTheme.colorScheme.primary)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Current estimate", style = MaterialTheme.typography.titleMedium)
                    Text("Floor: ${estimate.floorId}")
                    Text("Position: %.1f, %.1f m".format(estimate.x, estimate.y))
                    Text("Cloud spread: %.2f m (not guaranteed accuracy)".format(estimate.spreadM))
                    Text("Effective sample size: %.0f".format(estimate.effectiveSampleSize))
                    Text("Motion drift accumulator: %.1f m".format(estimate.distanceSinceCorrectionM))
                }
            }
            Text("Selected: ${selectedNode?.name} — ${selectedNode?.floorId}")
            Box {
                OutlinedButton(enabled = !tracking, onClick = { expanded = true }) { Text("Choose starting landmark / observation") }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 300.dp)) {
                    building.nodes.filter { it.type != "JUNCTION" }.forEach { n ->
                        DropdownMenuItem(text = { Text("${n.name} — ${n.floorId}") }, onClick = { selected = n.nodeId; expanded = false })
                    }
                }
            }
            OutlinedButton(enabled = !tracking && selectedNode != null, onClick = {
                engine.reset(selected)
                target = null
                motionEpoch++
                revision++
                status = "Manual Lab reset; this is not a measured camera fix."
            }) { Text("Reset Lab at selected landmark") }
            key(motionEpoch) {
                NavigationMotionControls(enabled = true, onStep = { l, h ->
                    status = engine.predict(l, h).message
                    revision++
                }, onRunningChanged = { tracking = it })
            }
            Text("Simulated motion", style = MaterialTheme.typography.titleMedium)
            Text("Step length: %.2f m".format(length))
            Slider(length, { length = it }, enabled = !tracking, valueRange = 0.3f..1.2f)
            Text("Map heading: %.0f°".format(heading))
            Slider(heading, { heading = it }, enabled = !tracking, valueRange = -180f..180f)
            Button(enabled = !tracking, onClick = { status = engine.predict(length.toDouble(), heading.toDouble()).message; revision++ }) {
                Text("Apply simulated step")
            }
            Text("Simulated observation quality: %.2f".format(quality))
            Slider(quality, { quality = it }, enabled = !tracking, valueRange = 0f..1f)
            Button(enabled = !tracking, onClick = { status = engine.observeLandmark(selected, quality.toDouble(), true).message; revision++ }) {
                Text("Observe selected landmark (simulated)")
            }
            if (onOpenScanner != null) Button(onClick = { motionEpoch++; onOpenScanner() }) { Text("Scan a real landmark") }
            Text("Manual floor confirmation", style = MaterialTheme.typography.titleMedium)
            Text("Current: ${estimate.floorId}. Move near the mapped source connector first.")
            connectors.forEach { n ->
                FilterChip(selected = target == n.nodeId, onClick = { target = n.nodeId },
                    enabled = !tracking, label = { Text("${n.name} — ${n.floorId}") })
            }
            Button(enabled = !tracking && connectors.any { it.nodeId == target }, onClick = {
                target?.let {
                    val update = engine.confirmFloorTransition(it)
                    status = update.message
                    revision++
                    if (update.accepted) { target = null; motionEpoch++ }
                }
            }) { Text("Confirm floor transition") }
            if (floor != null && floor.widthM > 0 && floor.heightM > 0) {
                Text("Particle cloud", style = MaterialTheme.typography.titleMedium)
                Canvas(Modifier.fillMaxWidth().height(300.dp).padding(12.dp)) {
                    val scale = min(size.width / floor.widthM, size.height / floor.heightM)
                    fun p(x: Double, y: Double) = Offset(x.toFloat() * scale, y.toFloat() * scale)
                    val nodes = building.nodes.associateBy { it.nodeId }
                    building.edges.forEach { edge ->
                        val a = nodes[edge.sourceNodeId]
                        val b = nodes[edge.destinationNodeId]
                        if (a != null && b != null && a.floorId == floor.floorId && b.floorId == floor.floorId)
                            drawLine(if (edge.isAvailable) Color.Gray else Color.Red,
                                p(a.x.toDouble(), a.y.toDouble()), p(b.x.toDouble(), b.y.toDouble()), 4f)
                    }
                    particles.forEach { drawCircle(Color(0x40E53935), 4f, p(it.x, it.y)) }
                    drawCircle(Color(0xFF388E3C), 8f, p(estimate.x, estimate.y))
                }
            }
        }
    }
}
