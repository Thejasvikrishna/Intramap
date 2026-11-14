package com.intramap.ui.lab

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intramap.data.model.Building
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.vision.CameraLandmarkObservation
import kotlin.math.max
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
    val start = remember(building) {
        building.nodes.firstOrNull {
            it.type == "ENTRANCE"
        } ?: building.nodes.firstOrNull()
    }

    if (start == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No map nodes are available.",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onBack) {
                Text("Back")
            }
        }

        return
    }

    val engine = remember(building, externalEngine) {
        externalEngine ?: LocalizationLabEngine(building).apply {
            reset(start.nodeId)
        }
    }

    var revision by remember(building) {
        mutableIntStateOf(0)
    }

    var selectedLandmarkId by remember(building) {
        mutableStateOf(start.nodeId)
    }

    var stepLengthM by remember {
        mutableStateOf(0.7f)
    }

    var mapHeadingDeg by remember {
        mutableStateOf(0f)
    }

    var landmarkQuality by remember {
        mutableStateOf(0.8f)
    }

    var status by remember {
        mutableStateOf(
            "Lab initialized. Motion is simulated; camera observations are optional."
        )
    }

    LaunchedEffect(cameraObservation) {
        val bundle = cameraObservation ?: return@LaunchedEffect

        try {
            val observation =
                CameraLandmarkObservation.fromBundle(bundle)

            if (observation == null) {
                status = "Camera observation rejected: invalid result."
            } else {
                val ageMs =
                    SystemClock.elapsedRealtime() -
                            observation.capturedAtElapsedMs

                val mappedNode = building.nodes.firstOrNull {
                    it.nodeId == observation.nodeId
                }

                status = when {
                    observation.capturedAtElapsedMs < 0L ||
                            ageMs !in 0L..30_000L -> {
                        "Camera observation rejected: expired timestamp."
                    }

                    observation.matchingScans < 2 -> {
                        "Camera observation rejected: insufficient consistency."
                    }

                    mappedNode == null -> {
                        "Camera observation rejected: unknown landmark."
                    }

                    mappedNode.floorId != observation.floorId -> {
                        "Camera observation rejected: map/floor mismatch."
                    }

                    else -> {
                        val update = engine.observeLandmark(
                            nodeId = observation.nodeId,
                            simulatedQuality = 0.5,
                            adaptive = false
                        )

                        revision++

                        val outcome = if (update.accepted) {
                            "accepted"
                        } else {
                            "rejected"
                        }

                        "Camera-derived observation $outcome: " +
                                "${mappedNode.name}. ${update.message} " +
                                "Fixed observation uncertainty; " +
                                "confidence is not calibrated."
                    }
                }
            }
        } finally {
            onObservationConsumed()
        }
    }

    val estimate = remember(engine, revision) {
        engine.estimate()
    }

    val particles = remember(engine, revision) {
        engine.particles()
    }

    val landmarks = remember(building) {
        building.nodes.filter {
            it.type in setOf("DOOR", "ROOM", "STAIRS", "ELEVATOR")
        }
    }

    val currentFloor = remember(estimate, building) {
        building.floors.firstOrNull {
            it.floorId == estimate.floorId
        } ?: building.floors.firstOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = "Localization Lab",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Current estimate",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Floor: ${estimate.floorId}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Position: " +
                            "%.1f, %.1f m".format(
                                estimate.x,
                                estimate.y
                            ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Spread: %.2f m".format(estimate.spreadM),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Effective sample size: %.0f".format(
                        estimate.effectiveSampleSize
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Distance since correction: %.1f m".format(
                        estimate.distanceSinceCorrectionM
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Simulated pedestrian motion",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Step length: %.2f m".format(stepLengthM),
                    style = MaterialTheme.typography.bodyMedium
                )

                Slider(
                    value = stepLengthM,
                    onValueChange = { stepLengthM = it },
                    valueRange = 0.3f..1.2f
                )

                Text(
                    text = "Map heading: %.0f degrees".format(mapHeadingDeg),
                    style = MaterialTheme.typography.bodyMedium
                )

                Slider(
                    value = mapHeadingDeg,
                    onValueChange = { mapHeadingDeg = it },
                    valueRange = -180f..180f
                )

                Button(
                    onClick = {
                        val update = engine.predict(
                            stepLengthM = stepLengthM.toDouble(),
                            mapHeadingDeg = mapHeadingDeg.toDouble()
                        )

                        revision++
                        status = update.message
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply one step")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Landmark observation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Selected landmark: " +
                            (building.nodes.firstOrNull {
                                it.nodeId == selectedLandmarkId
                            }?.name ?: "None"),
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = "Simulated visual quality: %.2f".format(
                        landmarkQuality
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )

                Slider(
                    value = landmarkQuality,
                    onValueChange = { landmarkQuality = it },
                    valueRange = 0f..1f
                )

                Button(
                    onClick = {
                        val update = engine.observeLandmark(
                            nodeId = selectedLandmarkId,
                            simulatedQuality = landmarkQuality.toDouble(),
                            adaptive = true
                        )

                        revision++
                        status = update.message
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Observe selected landmark")
                }

                if (onOpenScanner != null) {
                    Button(
                        onClick = { onOpenScanner.invoke() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Scan a real landmark")
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Manual floor confirmation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Select a target connector, then confirm the transition.",
                    style = MaterialTheme.typography.bodySmall
                )

                landmarks
                    .filter {
                        it.type in setOf("STAIRS", "ELEVATOR")
                    }
                    .forEach { connector ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${connector.name} — ${connector.floorId}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            OutlinedButton(
                                onClick = {
                                    selectedLandmarkId = connector.nodeId
                                }
                            ) {
                                Text("Select")
                            }
                        }
                    }

                Button(
                    onClick = {
                        val update = engine.confirmFloorTransition(
                            targetNodeId = selectedLandmarkId
                        )

                        revision++
                        status = update.message
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Confirm floor transition")
                }
            }
        }

        if (currentFloor != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Particle cloud",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    val floorWidth = currentFloor.widthM.toFloat()
                    val floorHeight = currentFloor.heightM.toFloat()

                    val canvasSize = 320.dp

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(canvasSize)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            val usableWidth = size.width
                            val usableHeight = size.height

                            val scale = min(
                                usableWidth / max(floorWidth, 0.01f),
                                usableHeight / max(floorHeight, 0.01f)
                            )

                            fun toCanvasX(x: Double): Float =
                                (x.toFloat() * scale)

                            fun toCanvasY(y: Double): Float =
                                usableHeight - (y.toFloat() * scale)

                            building.edges.forEach { edge ->
                                val a = building.nodes.firstOrNull {
                                    it.nodeId == edge.sourceNodeId
                                }

                                val b = building.nodes.firstOrNull {
                                    it.nodeId == edge.destinationNodeId
                                }

                                if (a != null && b != null &&
                                    a.floorId == currentFloor.floorId &&
                                    b.floorId == currentFloor.floorId
                                ) {
                                    drawLine(
                                        color = Color.Gray,
                                        start = Offset(
                                            toCanvasX(a.x.toDouble()),
                                            toCanvasY(a.y.toDouble())
                                        ),
                                        end = Offset(
                                            toCanvasX(b.x.toDouble()),
                                            toCanvasY(b.y.toDouble())
                                        ),
                                        strokeWidth = 3f
                                    )
                                }
                            }

                            building.nodes
                                .filter {
                                    it.floorId == currentFloor.floorId
                                }
                                .forEach { node ->
                                    drawCircle(
                                        color = Color(0xFF4A90E2),
                                        radius = 7f,
                                        center = Offset(
                                            toCanvasX(node.x.toDouble()),
                                            toCanvasY(node.y.toDouble())
                                        )
                                    )
                                }

                            particles
                                .filter {
                                    it.floorId == currentFloor.floorId
                                }
                                .forEach { particle ->
                                    drawCircle(
                                        color = Color(0x33E53935),
                                        radius = 6f,
                                        center = Offset(
                                            toCanvasX(particle.x),
                                            toCanvasY(particle.y)
                                        )
                                    )
                                }

                            drawCircle(
                                color = Color(0xFFE53935),
                                radius = 12f,
                                center = Offset(
                                    toCanvasX(estimate.x),
                                    toCanvasY(estimate.y)
                                ),
                                style = Stroke(width = 5f)
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}