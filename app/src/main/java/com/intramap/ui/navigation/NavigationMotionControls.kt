package com.intramap.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
    var azimuth by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    var headingAtNs by remember { mutableLongStateOf(-1L) }
    var acceptAfterNs by remember { mutableLongStateOf(Long.MAX_VALUE) }
    var lastStepNs by remember { mutableLongStateOf(-1L) }
    var status by remember { mutableStateOf("Start tracking to begin.") }

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
                if (
                    currentEnabled && running && calibrated &&
                    timestamp >= acceptAfterNs && timestamp > lastStepNs &&
                    now - timestamp in 0L..2_000_000_000L &&
                    headingAtNs >= 0 && now - headingAtNs in 0L..2_000_000_000L
                ) {
                    lastStepNs = timestamp
                    val heading = ((azimuth + offset) % 360f + 360f) % 360f
                    currentOnStep(0.70, heading.toDouble())
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
        currentOnRunning(false)
        status = message
    }

    fun start() {
        if (!currentEnabled || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            status = "Start navigation while this screen is active."
            return
        }

        calibrated = false
        headingAtNs = -1L
        acceptAfterNs = Long.MAX_VALUE
        lastStepNs = -1L
        recorder.upright = upright
        running = recorder.start(null)
        currentOnRunning(running)
        status = if (running) "Hold the phone as you walk."
        else recorder.lastError ?: "Sensors unavailable."
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Permission granted. Tap Start."
        else "Permission denied. Tap Start to use the accelerometer fallback."
    }
    var permissionAsked by remember { mutableStateOf(false) }

    DisposableEffect(recorder, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                stop("Tracking paused.")
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            recorder.stop()
            currentOnRunning(false)
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled && running) stop("Tracking stopped.")
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (running) {
                        stop("Tracking stopped.")
                    } else {
                        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            recorder.hasSystemStepDetector && context.checkSelfPermission(
                                Manifest.permission.ACTIVITY_RECOGNITION
                            ) != PackageManager.PERMISSION_GRANTED

                        if (needsPermission && !permissionAsked) {
                            permissionAsked = true
                            permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        } else {
                            start()
                        }
                    }
                }
            ) {
                Text(if (running) "Stop" else "Start")
            }

            Text(
                if (running && calibrated) "Tracking active"
                else if (running) "Align direction"
                else status,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (running && !calibrated) {
            Text("Point the phone along the corridor you are facing.")
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                listOf("Forward" to 0f, "Right" to 90f, "Back" to 180f, "Left" to 270f).forEach { (label, angle) ->
                    AssistChip(
                        onClick = {
                            offset = angle - azimuth
                            acceptAfterNs = SystemClock.elapsedRealtimeNanos()
                            lastStepNs = -1L
                            calibrated = true
                            status = "Tracking active."
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}
