package com.intramap.ui.map

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.ui.navigation.NavigationMotionControls
import com.intramap.ui.vision.LandmarkScannerScreen
import java.util.Locale
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    building: Building,
    initialFloorId: String,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
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
    var displayedFloor by rememberSaveable(building.buildingId) { mutableStateOf(initialFloorId) }
    var scanning by remember { mutableStateOf(false) }
    var confirmFloor by remember { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var accessibility by rememberSaveable { mutableStateOf(false) }

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

    val voiceKey = "${session.route?.nodeIds}:${session.arrived}"
    LaunchedEffect(voice, speechReady, voiceKey, scanning) {
        if (voice && speechReady && !scanning && session.route != null) {
            val result = speech?.setLanguage(Locale.getDefault())
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                speech?.speak(session.instruction, TextToSpeech.QUEUE_FLUSH, null, "intramap-guidance")
            }
        } else {
            speech?.stop()
        }
    }

    if (scanning) {
        BackHandler { scanning = false }
        val floor = session.estimate?.floorId
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

    if (confirmFloor) {
        val target = session.transitionTarget
        AlertDialog(
            onDismissRequest = { confirmFloor = false },
            title = { Text("Floor change") },
            text = { Text("Use the mapped connector to ${target?.floorId ?: "the next floor"}?") },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        session.pauseMotion()
                        session.confirmTransition()
                        confirmFloor = false
                    }
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFloor = false }) { Text("Cancel") }
            }
        )
    }

    val searchResults = session.landmarks
        .filter { node ->
            query.isBlank() ||
                node.name.contains(query, true) ||
                node.keywords.any { it.contains(query, true) }
        }
        .filter { it.type != "JUNCTION" }
        .take(5)

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            InteractiveFloorMap(
                building = building,
                floorId = displayedFloor,
                session = session
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 4.dp
            ) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search a place or ask for help") },
                        trailingIcon = {
                            IconButton(onClick = { voice = !voice }) {
                                Text(if (voice) "🔊" else "🎙️")
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (query.isNotBlank()) {
                        searchResults.forEach { node ->
                            ListItem(
                                headlineContent = { Text(node.name) },
                                supportingContent = { Text(node.floorId) },
                                modifier = Modifier.clickable {
                                    session.setDestination(node.nodeId)
                                    displayedFloor = node.floorId
                                    query = ""
                                }
                            )
                        }
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                building.floors.forEach { floor ->
                    AssistChip(
                        onClick = { displayedFloor = floor.floorId },
                        label = { Text(floor.floorName) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (displayedFloor == floor.floorId)
                                MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (session.errors.isNotEmpty()) {
                        Text(
                            "This map needs correction before navigation can start.",
                            style = MaterialTheme.typography.titleMedium
                        )
                    } else {
                        Text(session.instruction, style = MaterialTheme.typography.headlineSmall)

                        session.route?.let {
                            Text("${"%.0f".format(it.totalDistance)} m remaining")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = accessibility,
                                onClick = {
                                    accessibility = !accessibility
                                    session.updateAccessibilityPreference(accessibility)
                                },
                                label = { Text("Step-free") }
                            )

                            FilterChip(
                                selected = voice,
                                onClick = { voice = !voice },
                                label = { Text("Voice") }
                            )
                        }

                        if (session.transitionTarget != null) {
                            Button(
                                onClick = { confirmFloor = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Continue to ${session.transitionTarget?.floorId}")
                            }
                        }

                        if (session.ready && session.route != null && !session.arrived) {
                            key(session.motionEpoch) {
                                NavigationMotionControls(
                                    enabled = true,
                                    onStep = session::walk,
                                    onRunningChanged = { }
                                )
                            }
                        } else if (!session.ready) {
                            Button(
                                onClick = { scanning = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Scan entrance to start")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Diagnostics")
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveFloorMap(
    building: Building,
    floorId: String,
    session: NavigationSession
) {
    val floor = building.floors.firstOrNull { it.floorId == floorId } ?: return
    val nodes = remember(building) { building.nodes.associateBy { it.nodeId } }

    Canvas(
        Modifier
            .fillMaxSize()
            .padding(bottom = 220.dp)
    ) {
        val scale = min(
            (size.width - 48f) / floor.widthM,
            (size.height - 48f) / floor.heightM
        )

        val originX = (size.width - floor.widthM * scale) / 2f
        val originY = (size.height - floor.heightM * scale) / 2f

        fun point(x: Double, y: Double) = Offset(
            originX + x.toFloat() * scale,
            originY + y.toFloat() * scale
        )

        fun nodePoint(node: LocationNode) = point(node.x.toDouble(), node.y.toDouble())

        drawRoundRect(
            color = Color(0xFFF4F6F8),
            topLeft = point(0.0, 0.0),
            size = Size(floor.widthM * scale, floor.heightM * scale),
            cornerRadius = CornerRadius(24f, 24f)
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
                        Color(0xFFB8C4CC)
                    else
                        Color(0xFFE57373),
                    start = nodePoint(from),
                    end = nodePoint(to),
                    strokeWidth = 14f,
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
                style = Stroke(width = 16f, cap = StrokeCap.Round)
            )
        }

        building.nodes
            .filter { it.floorId == floorId && it.type != "JUNCTION" }
            .forEach { node ->
                drawCircle(
                    color = Color(0xFF202124),
                    radius = 8f,
                    center = nodePoint(node)
                )
            }

        session.estimate?.takeIf { it.floorId == floorId }?.let { estimate ->
            val center = point(estimate.x, estimate.y)
            drawCircle(Color(0xFF1A73E8), 14f, center)
            drawCircle(
                Color(0x331A73E8),
                (estimate.spreadM.toFloat() * scale).coerceIn(18f, size.minDimension / 3f),
                center,
                style = Stroke(width = 4f)
            )
        }

        session.destination?.takeIf { it.floorId == floorId }?.let { destination ->
            drawCircle(
                Color(0xFFEA4335),
                16f,
                nodePoint(destination),
                style = Stroke(width = 6f)
            )
        }
    }
}
