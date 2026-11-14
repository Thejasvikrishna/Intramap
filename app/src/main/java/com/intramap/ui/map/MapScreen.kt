package com.intramap.ui.map

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.navigation.NavigationSession
import com.intramap.ui.navigation.NavigationMotionControls
import com.intramap.ui.vision.LandmarkScannerScreen
import java.util.Locale
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(building: Building, initialFloorId: String, onBack: () -> Unit) {
    val saver = remember(building) {
        Saver<NavigationSession, Bundle>(
            save = { it.snapshot() },
            restore = { saved -> NavigationSession(building).apply { restore(saved) } }
        )
    }
    val session = rememberSaveable(building.buildingId, saver = saver) {
        NavigationSession(building)
    }
    var query by remember { mutableStateOf("") }
    var selectingStart by remember { mutableStateOf(true) }
    var displayedFloor by rememberSaveable(building.buildingId) { mutableStateOf(initialFloorId) }
    var scanning by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var confirmFloor by remember { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    val currentFloor = session.estimate?.floorId
    LaunchedEffect(currentFloor) { currentFloor?.let { displayedFloor = it } }

    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var speech by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechReady by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val tts = TextToSpeech(context.applicationContext) { code ->
            speechReady = code == TextToSpeech.SUCCESS
        }
        speech = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }
    DisposableEffect(lifecycleOwner, speech) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) speech?.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val voiceKey = "${session.route?.nodeIds}:${session.nextIndex}:${session.arrived}"
    LaunchedEffect(voice, speechReady, voiceKey, scanning) {
        if (voice && speechReady && !scanning && session.route != null) {
            val tts = speech
            val result = tts?.setLanguage(Locale.getDefault())
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
                tts?.speak(session.instruction, TextToSpeech.QUEUE_FLUSH, null, "intramap-guidance")
        } else speech?.stop()
    }

    if (scanning) {
        BackHandler { scanning = false }
        val floor = session.estimate?.floorId
        // The scanner defaults to its first floor; reorder only the scanner's
        // floor selector without changing IDs, nodes, or edges.
        val scannerBuilding = remember(building, floor) {
            building.copy(floors = building.floors.sortedBy { if (it.floorId == floor) 0 else 1 })
        }
        LandmarkScannerScreen(
            building = scannerBuilding,
            onBack = { scanning = false },
            onUseObservation = { observation ->
                session.applyObservation(observation)
                scanning = false
            }
        )
        return
    }

    if (confirmStart) {
        val n = building.nodes.firstOrNull { it.nodeId == session.selectedStartId }
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Confirm actual location") },
            text = { Text("Are you physically at ${n?.name} on ${n?.floorId}? This reinitializes position; it is not a measured camera fix.") },
            confirmButton = {
                TextButton(onClick = {
                    session.confirmStart()
                    selectingStart = false
                    confirmStart = false
                }) { Text("I am here") }
            },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("Cancel") } }
        )
    }
    if (confirmFloor) {
        val target = session.transitionTarget
        AlertDialog(
            onDismissRequest = { confirmFloor = false },
            title = { Text("Confirm floor change") },
            text = { Text("Use the mapped connector. Confirm only after physically reaching ${target?.floorId}. Tracking is paused during the transition.") },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = { session.confirmTransition(); confirmFloor = false }
                ) { Text("I reached this floor") }
            },
            dismissButton = { TextButton(onClick = { confirmFloor = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${building.buildingName} · Navigation") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (session.errors.isNotEmpty()) {
                Text("Map needs correction", style = MaterialTheme.typography.titleLarge)
                session.errors.forEach { Text(it) }
                return@Column
            }
            Text("Choose your real start and destination, then start walking. The Lab remains a separate developer tool.")
            session.restoredNote?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectingStart, { selectingStart = true }, label = { Text("Choose start") })
                FilterChip(!selectingStart, { selectingStart = false }, label = { Text("Destination") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (selectingStart) "Search current location" else "Search destination") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            val results = session.landmarks.filter { n ->
                query.isBlank() || n.name.contains(query, true) || n.floorId.contains(query, true) ||
                    n.keywords.any { it.contains(query, true) }
            }.take(8)
            results.forEach { n ->
                ListItem(
                    headlineContent = { Text(n.name) },
                    supportingContent = { Text("${n.floorId} · ${n.type}") },
                    modifier = Modifier.clickable(enabled = !tracking) {
                        if (selectingStart) session.chooseStart(n.nodeId) else session.setDestination(n.nodeId)
                        displayedFloor = n.floorId
                        query = ""
                    }
                )
            }
            val selectedStart = building.nodes.firstOrNull { it.nodeId == session.selectedStartId }
            Text("Chosen start: ${selectedStart?.name ?: "not selected"} · ${selectedStart?.floorId.orEmpty()}")
            OutlinedButton(
                enabled = selectedStart != null && !tracking,
                onClick = { confirmStart = true }
            ) { Text(if (session.ready) "Explicitly relocalize at chosen start" else "Confirm starting location") }
            Text("Destination: ${session.destination?.name ?: "not selected"} · ${session.destination?.floorId.orEmpty()}")
            Row {
                FilterChip(
                    selected = session.stepFree,
                    onClick = {
                        session.updateAccessibilityPreference(!session.stepFree)
                    },
                    label = {
                        Text("Step-free")
                    }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(voice, { voice = !voice }, label = { Text("Voice guidance") })
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(session.instruction, style = MaterialTheme.typography.titleMedium)
                    session.route?.let { Text("Route length at last calculation: %.1f m".format(it.totalDistance)) }
                    session.estimate?.let { e ->
                        Text("${e.floorId} · (%.1f, %.1f m) · cloud spread %.2f m".format(e.x, e.y, e.spreadM))
                        Text("Spread is model dispersion, not guaranteed position accuracy.")
                    }
                    Text(session.status)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                building.floors.forEach { f ->
                    FilterChip(displayedFloor == f.floorId, { displayedFloor = f.floorId }, label = { Text(f.floorName) })
                }
            }
            NavigationFloorCanvas(building, displayedFloor, session)
            Button(
                enabled = session.ready,
                onClick = {
                    session.pauseMotion()
                    speech?.stop()
                    scanning = true
                }
            ) { Text("Scan mapped sign to correct position") }
            if (session.transitionTarget != null) {
                Button(onClick = { session.pauseMotion(); confirmFloor = true }) {
                    Text("Use connector and confirm ${session.transitionTarget?.floorId}")
                }
            }
            key(session.motionEpoch) {
                NavigationMotionControls(
                    enabled = session.ready && session.route != null && !session.arrived,
                    onStep = session::walk,
                    onRunningChanged = { tracking = it }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = session.route != null && !session.arrived,
                    onClick = session::confirmNextManually) { Text("Confirm waypoint") }
                OutlinedButton(enabled = session.route != null && !session.arrived,
                    onClick = session::reportBlocked) { Text("Report blocked") }
            }
            if (session.blocked.isNotEmpty()) TextButton(onClick = session::clearBlocked) { Text("Clear local blocked paths") }
            OutlinedButton(enabled = session.ready, onClick = { session.reroute() }) { Text("Recalculate route") }
            Text("Blocked reports affect routing on this device, not a building-wide service. The localization model is provisional; follow real signs and accessible paths.")
        }
    }
}

@Composable
private fun NavigationFloorCanvas(b: Building, floorId: String, session: NavigationSession) {
    val floor = b.floors.firstOrNull { it.floorId == floorId } ?: return
    val nodes = remember(b) { b.nodes.associateBy { it.nodeId } }
    Text("Gray: paths · blue: route · red: blocked · green: estimated position")
    Canvas(Modifier.fillMaxWidth().height(320.dp).padding(12.dp)) {
        val scale = min((size.width - 20f) / floor.widthM, (size.height - 20f) / floor.heightM)
        val ox = (size.width - floor.widthM * scale) / 2f
        val oy = (size.height - floor.heightM * scale) / 2f
        fun point(x: Double, y: Double) = Offset(ox + x.toFloat() * scale, oy + y.toFloat() * scale)
        fun pointNode(n: LocationNode) = point(n.x.toDouble(), n.y.toDouble())
        b.edges.forEach { edge ->
            val a = nodes[edge.sourceNodeId]
            val c = nodes[edge.destinationNodeId]
            if (a != null && c != null && a.floorId == floorId && c.floorId == floorId) {
                val color = if (!edge.isAvailable || edge.edgeId in session.blocked) Color.Red else Color.Gray
                drawLine(color, pointNode(a), pointNode(c), strokeWidth = 5f)
            }
        }
        session.routeNodes.zipWithNext().forEach { (a, c) ->
            if (a.floorId == floorId && c.floorId == floorId)
                drawLine(Color(0xFF1976D2), pointNode(a), pointNode(c), strokeWidth = 8f)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.DKGRAY
            textSize = 24f
        }
        b.nodes.filter { it.floorId == floorId }.forEach { n ->
            drawCircle(Color(0xFF455A64), 5f, pointNode(n))
            if (n.type != "JUNCTION") drawContext.canvas.nativeCanvas.drawText(
                n.name.take(22), pointNode(n).x + 8f, pointNode(n).y - 8f, paint)
        }
        session.particles.filter { it.floorId == floorId }.forEach {
            drawCircle(Color(0x307CB342), 3f, point(it.x, it.y))
        }
        session.estimate?.takeIf { it.floorId == floorId }?.let {
            val center = point(it.x, it.y)
            drawCircle(Color(0xFF388E3C), 9f, center)
            drawCircle(Color(0x66388E3C), (it.spreadM.toFloat() * scale).coerceIn(2f, size.minDimension / 2f), center, style = Stroke(2f))
        }
        session.destination?.takeIf { it.floorId == floorId }?.let {
            drawCircle(Color(0xFF7B1FA2), 11f, pointNode(it), style = Stroke(4f))
        }
    }
}
