package com.intramap.ui.pdr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.localization.PdrEngine
import com.intramap.sensors.ImuRecorder
import com.intramap.ui.map.FloorMapCanvas
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdrScreen(building: Building, floorId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val floor = building.floors.first { it.floorId == floorId }
    val candidates = remember(floorId) { building.nodes.filter { it.floorId == floorId && it.type != "JUNCTION" } }
    val initial = candidates.firstOrNull()

    val engine = remember { PdrEngine(initial?.x ?: floor.widthM / 2, initial?.y ?: floor.heightM / 2) }
    var sourceLabel by remember { mutableStateOf("Not started") }

    var acceptStepsAfterNs by remember {
        mutableLongStateOf(Long.MAX_VALUE)
    }

    var selectedStartId by remember {
        mutableStateOf(initial?.nodeId)
    }

    var pos by remember { mutableStateOf(Offset(engine.x, engine.y)) }
    var steps by remember { mutableIntStateOf(0) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var calibrated by remember { mutableStateOf(false) }
    var upright by remember { mutableStateOf(true) }
    var record by remember { mutableStateOf(true) }
    var stride by remember { mutableFloatStateOf(0.70f) }
    var status by remember { mutableStateOf("") }

    val recorder = remember {
        ImuRecorder(
            context = context.applicationContext,

            // Raw acceleration is logged by the recorder.
            // Do not update the position here.
            onAccel = { _, _, _, _ -> },

            onAzimuth = {
                azimuth = it
            },

            onStep = { timestampNs ->
                if (
                    running &&
                    calibrated &&
                    timestampNs >= acceptStepsAfterNs
                ) {
                    engine.onStep(azimuth)
                    pos = Offset(engine.x, engine.y)
                    steps = engine.steps
                }
            }
        )
    }
    fun startTracking() {
        calibrated = false
        acceptStepsAfterNs = Long.MAX_VALUE

        val stamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val directory = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "logs"
        )

        val file = if (record) {
            File(directory, "imu_$stamp.csv")
        } else {
            null
        }

        recorder.upright = upright

        running = recorder.start(file)
        sourceLabel = recorder.stepSource

        status = if (running) {
            if (file != null) {
                "Recording to ${file.name}"
            } else {
                "Running without recording"
            }
        } else {
            recorder.lastError ?: "Could not start sensors"
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        startTracking()

        if (!granted && running) {
            status = "Permission denied. Using software fallback."
        }
    }
    DisposableEffect(Unit) { onDispose { recorder.stop(); view.keepScreenOn = false } }
    SideEffect { view.keepScreenOn = running }

    Scaffold(topBar = { TopAppBar(title = { Text("PDR test · ${floor.floorName}") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            if (recorder.missing.isNotEmpty()) Text("Missing sensors: ${recorder.missing.joinToString()}")
            Text("1. Pick start node on the map:")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(candidates) { n ->
                    AssistChip(
                        enabled = !running,
                        onClick = {
                            selectedStartId = n.nodeId
                            engine.reset(n.x, n.y)

                            pos = Offset(n.x, n.y)
                            steps = 0

                            calibrated = false
                            acceptStepsAfterNs = Long.MAX_VALUE
                        },
                        label = { Text(n.name) }
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Switch(
                        checked = upright,
                        enabled = !running,
                        onCheckedChange = {
                            upright = it
                            recorder.upright = it
                            calibrated = false
                        }
                    )

                    Spacer(Modifier.width(8.dp))
                    Text("Phone upright")
                }

                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Switch(
                        checked = record,
                        enabled = !running,
                        onCheckedChange = { record = it }
                    )

                    Spacer(Modifier.width(8.dp))
                    Text("Save sensor log")
                }
            }
            Button(
                onClick = {
                    if (running) {
                        recorder.stop()
                        running = false
                        calibrated = false
                        acceptStepsAfterNs = Long.MAX_VALUE
                        sourceLabel = "Not started"
                        status = "Stopped"
                    } else {
                        val needsPermission =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                    recorder.hasSystemStepDetector &&
                                    context.checkSelfPermission(
                                        Manifest.permission.ACTIVITY_RECOGNITION
                                    ) != PackageManager.PERMISSION_GRANTED

                        if (needsPermission) {
                            permissionLauncher.launch(
                                Manifest.permission.ACTIVITY_RECOGNITION
                            )
                        } else {
                            startTracking()
                        }
                    }
                }
            ) {
                Text(if (running) "Stop" else "2. Start sensors")
            }

            Text("Step source: $sourceLabel")

            Text("3. Stand still, face along a corridor, and tell the app which way that is on the map:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Up" to 0f, "Right" to 90f, "Down" to 180f, "Left" to 270f).forEach { (label, deg) ->
                    OutlinedButton(
                        enabled = running,
                        onClick = {
                            if (recorder.hasHeading) {
                                engine.calibrate(azimuth, deg)
                                acceptStepsAfterNs = SystemClock.elapsedRealtimeNanos()
                                calibrated = true
                                status = "Heading calibrated. Begin walking."
                            } else {
                                status = "Waiting for heading data. Try again shortly."
                            }
                        }
                    ) {
                        Text(label)
                    }
                }
            }
            Text("Stride length: %.2f m".format(stride))
            Slider(
                value = stride,
                enabled = !running,
                onValueChange = {
                    stride = it
                    engine.stepLengthM = it
                },
                valueRange = 0.5f..0.9f
            )
            Text("Calibrated: $calibrated · Steps: $steps · Distance: %.1f m".format(engine.distanceM))
            Text("Position: (%.1f, %.1f) m · Map heading: %.0f°".format(pos.x, pos.y, engine.mapHeading(azimuth)))
            if (status.isNotEmpty()) Text(status)
            OutlinedButton(
                onClick = {
                    val selected = candidates.firstOrNull {
                        it.nodeId == selectedStartId
                    } ?: initial

                    engine.reset(
                        selected?.x ?: floor.widthM / 2,
                        selected?.y ?: floor.heightM / 2
                    )

                    pos = Offset(engine.x, engine.y)
                    steps = 0

                    calibrated = false
                    acceptStepsAfterNs = Long.MAX_VALUE

                    status = "Position reset. Calibrate heading before walking."
                }
            ) {
                Text("Reset position")
            }

            FloorMapCanvas(
                b = building,
                floor = floor,
                highlightId = null,
                userPos = pos
            )
        }
    }
}