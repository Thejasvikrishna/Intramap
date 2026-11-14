package com.intramap.ui.lab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intramap.sensors.ImuRecorder

@Composable
fun LabLiveMotionControls(
    onStep: (stepLengthM: Double, mapHeadingDeg: Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnStep by rememberUpdatedState(onStep)

    var running by remember { mutableStateOf(false) }
    var calibrated by remember { mutableStateOf(false) }
    var headingReady by remember { mutableStateOf(false) }
    var upright by remember { mutableStateOf(true) }

    var azimuthDeg by remember { mutableFloatStateOf(0f) }
    var headingOffsetDeg by remember { mutableFloatStateOf(0f) }
    var strideM by remember { mutableFloatStateOf(0.70f) }

    var acceptedAfterNs by remember {
        mutableLongStateOf(Long.MAX_VALUE)
    }

    var lastStepNs by remember {
        mutableLongStateOf(Long.MIN_VALUE)
    }

    var forwardedSteps by remember { mutableIntStateOf(0) }
    var sourceLabel by remember { mutableStateOf("Not started") }

    var status by remember {
        mutableStateOf(
            "Choose a Lab starting position, start sensors, " +
                    "then calibrate your facing direction."
        )
    }

    val recorder = remember(context) {
        ImuRecorder(
            context = context.applicationContext,
            onAccel = { _, _, _, _ -> },
            onAzimuth = { value ->
                azimuthDeg = value
                headingReady = true
            },
            onStep = { timestampNs ->
                if (
                    running &&
                    calibrated &&
                    timestampNs >= acceptedAfterNs &&
                    timestampNs > lastStepNs
                ) {
                    lastStepNs = timestampNs

                    val heading =
                        (
                                (azimuthDeg + headingOffsetDeg) % 360f +
                                        360f
                                ) % 360f

                    latestOnStep(
                        strideM.toDouble(),
                        heading.toDouble()
                    )

                    forwardedSteps++
                }
            }
        )
    }

    fun stopSensors(message: String) {
        running = false
        calibrated = false
        headingReady = false
        acceptedAfterNs = Long.MAX_VALUE
        lastStepNs = Long.MIN_VALUE
        recorder.stop()
        sourceLabel = "Not started"
        status = message
    }

    fun startSensors() {
        if (
            !lifecycleOwner.lifecycle.currentState.isAtLeast(
                Lifecycle.State.RESUMED
            )
        ) {
            status = "Return to the Lab before starting sensors."
            return
        }

        calibrated = false
        headingReady = false
        acceptedAfterNs = Long.MAX_VALUE
        lastStepNs = Long.MIN_VALUE
        forwardedSteps = 0

        recorder.upright = upright
        running = recorder.start(null)
        sourceLabel = recorder.stepSource

        status = if (running) {
            "Sensors started. Face a known map direction and calibrate."
        } else {
            recorder.lastError ?: "Could not start sensors."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        startSensors()

        if (!granted && running) {
            status =
                "Activity permission denied. " +
                        "Using the recorder's software step fallback. " +
                        "Calibrate before walking."
        }
    }

    DisposableEffect(recorder, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP
            ) {
                stopSensors(
                    "Tracking paused. Start sensors and recalibrate " +
                            "when you return."
                )
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            running = false
            calibrated = false
            recorder.stop()
        }
    }

    val mapHeadingDeg =
        ((azimuthDeg + headingOffsetDeg) % 360f + 360f) % 360f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Live walking",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            "Uses detected steps and phone heading. " +
                    "Each step updates the same Lab engine used by the scanner."
        )

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = upright,
                enabled = !running,
                onCheckedChange = {
                    upright = it
                    calibrated = false
                }
            )

            Spacer(Modifier.width(8.dp))
            Text("Phone held upright")
        }

        Text("Stride length: %.2f m".format(strideM))

        Slider(
            value = strideM,
            enabled = !running,
            onValueChange = { strideM = it },
            valueRange = 0.3f..1.2f
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (running) {
                    stopSensors("Tracking stopped.")
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
                        startSensors()
                    }
                }
            }
        ) {
            Text(if (running) "Stop walking updates" else "Start sensors")
        }

        Text("Step source: $sourceLabel")

        Text(
            "Face along a corridor, then choose your facing direction " +
                    "on the map:"
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                listOf("Up" to 0f, "Right" to 90f),
                listOf("Down" to 180f, "Left" to 270f)
            ).forEach { directions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    directions.forEach { (label, degrees) ->
                        OutlinedButton(
                            enabled = running && headingReady,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                headingOffsetDeg = degrees - azimuthDeg
                                acceptedAfterNs =
                                    SystemClock.elapsedRealtimeNanos()
                                lastStepNs = Long.MIN_VALUE
                                calibrated = true

                                status =
                                    "Heading calibrated to $label. " +
                                            "Begin walking."
                            }
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        Text("Calibrated: $calibrated")
        Text("Steps sent to engine: $forwardedSteps")

        if (running && calibrated) {
            Text(
                "Map heading: %.0f degrees".format(mapHeadingDeg)
            )
        }

        if (recorder.missing.isNotEmpty()) {
            Text(
                "Sensor availability: " +
                        recorder.missing.joinToString()
            )
        }

        Text(status)

        Text(
            "Opening the scanner or leaving the app pauses tracking. " +
                    "After returning, restart sensors and recalibrate. " +
                    "Movement while tracking is paused is not recorded."
        )

        HorizontalDivider()
    }
}