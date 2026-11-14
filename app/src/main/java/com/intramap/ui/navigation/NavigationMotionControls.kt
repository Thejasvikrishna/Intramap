package com.intramap.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intramap.sensors.ImuRecorder

@Composable
fun NavigationMotionControls(
    enabled: Boolean,
    onStep: (Double, Double) -> Unit,
    onRunningChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnRunning by rememberUpdatedState(onRunningChanged)
    val currentEnabled by rememberUpdatedState(enabled)
    var running by remember { mutableStateOf(false) }
    var calibrated by remember { mutableStateOf(false) }
    var upright by remember { mutableStateOf(true) }
    var stride by remember { mutableFloatStateOf(0.70f) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var headingAtNs by remember { mutableLongStateOf(-1L) }
    var acceptAfterNs by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var lastStepNs by remember { mutableLongStateOf(-1L) }
    var count by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf("Not started") }
    var status by remember { mutableStateOf("Start sensors, then calibrate your facing direction.") }
    val recorder = remember(context) {
        ImuRecorder(
            context.applicationContext,
            onAccel = { _, _, _, _ -> },
            onAzimuth = {
                azimuth = it
                headingAtNs = SystemClock.elapsedRealtimeNanos()
            },
            onStep = { timestamp ->
                val now = SystemClock.elapsedRealtimeNanos()
                if (currentEnabled && running && calibrated && timestamp >= acceptAfterNs &&
                    timestamp > lastStepNs && now - timestamp in 0L..2_000_000_000L &&
                    headingAtNs >= 0 && now - headingAtNs in 0L..2_000_000_000L) {
                    lastStepNs = timestamp
                    val heading = ((azimuth + offset) % 360f + 360f) % 360f
                    currentOnStep(stride.toDouble(), heading.toDouble())
                    count++
                }
            }
        )
    }

    fun stop(message: String) {
        running = false
        calibrated = false
        headingAtNs = -1L
        acceptAfterNs = Long.MAX_VALUE
        lastStepNs = -1L
        recorder.stop()
        source = "Not started"
        currentOnRunning(false)
        status = message
    }

    fun start() {
        if (!currentEnabled || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            status = "Confirm a position and destination while this screen is active."
            return
        }
        calibrated = false
        headingAtNs = -1L
        count = 0
        acceptAfterNs = Long.MAX_VALUE
        lastStepNs = -1L
        recorder.upright = upright
        running = recorder.start(null)
        source = recorder.stepSource
        currentOnRunning(running)
        status = if (running) "Wait for heading data, then choose your map-facing direction."
        else recorder.lastError ?: "Sensors unavailable."
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permission granted. Tap Start sensors."
        else "Permission denied. Tap Start sensors to try the accelerometer fallback."
    }
    var permissionAsked by remember { mutableStateOf(false) }

    DisposableEffect(recorder, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)
                stop("Paused. Restart and recalibrate when you return. Untracked movement is not recovered.")
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            recorder.stop()
            currentOnRunning(false)
        }
    }
    LaunchedEffect(enabled) { if (!enabled && running) stop("Tracking stopped.") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Walking controls", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(upright, { upright = it }, enabled = !running)
                Text("Phone upright")
            }
            Text("Step length: %.2f m".format(stride))
            Slider(stride, { stride = it }, enabled = !running, valueRange = 0.3f..1.2f)
            Button(
                enabled = enabled || running,
                onClick = {
                    if (running) stop("Tracking stopped.") else {
                        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            recorder.hasSystemStepDetector && context.checkSelfPermission(
                                Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission && !permissionAsked) {
                            permissionAsked = true
                            permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        } else start()
                    }
                }
            ) { Text(if (running) "Stop sensors" else "Start sensors") }
            Text("Face along a corridor; choose its direction on the map.")
            listOf(listOf("Up" to 0f, "Right" to 90f), listOf("Down" to 180f, "Left" to 270f)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, angle) ->
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = running && headingAtNs >= 0L,
                            onClick = {
                                offset = angle - azimuth
                                acceptAfterNs = SystemClock.elapsedRealtimeNanos()
                                lastStepNs = -1L
                                calibrated = true
                                status = "Calibrated. Begin walking; keep the phone orientation consistent."
                            }
                        ) { Text(label) }
                    }
                }
            }
            Text("Source: $source · Calibrated: $calibrated · Steps: $count")
            if (running && calibrated) Text("Map heading: %.0f°".format(((azimuth + offset) % 360f + 360f) % 360f))
            Text(status)
            Text("Scanning pauses sensors. After returning, restart and recalibrate. Keep still while scanning.")
        }
    }
}
