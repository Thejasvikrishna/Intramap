package com.intramap.ui.diagnostics

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import kotlin.math.sqrt
import com.intramap.navigation.NavigationSession
private data class SensorSample(
    val values: FloatArray = floatArrayOf(),
    val timestamp: Long = 0L
)

@Composable
fun NavigationDiagnosticsScreen(
    building: Building,
    initialFloorId: String,
    session: NavigationSession,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    var accelerometer by remember { mutableStateOf(SensorSample()) }
    var gyroscope by remember { mutableStateOf(SensorSample()) }
    var magnetometer by remember { mutableStateOf(SensorSample()) }
    var stepCount by remember { mutableStateOf(-1f) }

    val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    DisposableEffect(sensorManager, accelerometerSensor, gyroscopeSensor, magnetometerSensor, stepSensor) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> accelerometer = SensorSample(event.values.copyOf(), event.timestamp)
                    Sensor.TYPE_GYROSCOPE -> gyroscope = SensorSample(event.values.copyOf(), event.timestamp)
                    Sensor.TYPE_MAGNETIC_FIELD -> magnetometer = SensorSample(event.values.copyOf(), event.timestamp)
                    Sensor.TYPE_STEP_COUNTER -> stepCount = event.values.firstOrNull() ?: -1f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        accelerometerSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscopeSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometerSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        stepSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    val destination = session.destination
    val transitionTarget = session.transitionTarget
    val estimate = session.estimate

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onBack) { Text("Back") }

            Text("Navigation diagnostics", style = MaterialTheme.typography.headlineSmall)
            Text(building.buildingName)

            DiagnosticsCard("Navigation state") {
                DiagnosticRow("Ready", session.ready.toString())
                DiagnosticRow("Route active", (session.route != null).toString())
                DiagnosticRow("Arrived", session.arrived.toString())
                DiagnosticRow("Instruction", session.instruction)

                session.route?.let {
                    DiagnosticRow("Route distance", "%.1f m".format(it.totalDistance))
                    DiagnosticRow("Route nodes", it.nodeIds.size.toString())
                }

                DiagnosticRow("Displayed route nodes", session.routeNodes.size.toString())
                DiagnosticRow("Blocked edges", session.blocked.size.toString())

                destination?.let {
                    DiagnosticRow("Destination", it.name)
                    DiagnosticRow("Destination floor", it.floorId)
                }

                transitionTarget?.let {
                    DiagnosticRow("Pending floor transition", it.floorId)
                }
            }

            DiagnosticsCard("Estimated position") {
                if (estimate == null) {
                    DiagnosticRow("Status", "No position estimate")
                } else {
                    DiagnosticRow("Floor", estimate.floorId)
                    DiagnosticRow("X", "%.2f m".format(estimate.x))
                    DiagnosticRow("Y", "%.2f m".format(estimate.y))
                    DiagnosticRow("Spread", "%.2f m".format(estimate.spreadM))
                }

                if (estimate == null || estimate.floorId != initialFloorId) {
                    DiagnosticRow("Selected floor", initialFloorId)
                }
            }

            DiagnosticsCard("Map validation") {
                if (session.errors.isEmpty()) {
                    DiagnosticRow("Status", "No map-validation errors")
                } else {
                    DiagnosticRow("Error count", session.errors.size.toString())
                    session.errors.take(5).forEach { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            DiagnosticsCard("Motion sensors") {
                SensorStatus("Accelerometer", accelerometerSensor != null, accelerometer.values, accelerometer.timestamp)
                SensorStatus("Gyroscope", gyroscopeSensor != null, gyroscope.values, gyroscope.timestamp)
                SensorStatus("Magnetometer", magnetometerSensor != null, magnetometer.values, magnetometer.timestamp)

                if (stepSensor == null) {
                    DiagnosticRow("Step counter", "Not available")
                } else if (stepCount < 0f) {
                    DiagnosticRow("Step counter", "Available; waiting for sample")
                } else {
                    DiagnosticRow("Step counter", "%.0f cumulative steps".format(stepCount))
                }
            }

            DiagnosticsCard("How to validate") {
                Text(
                    "Walk slowly in a straight line, then turn. Check that heading and estimated position respond. " +
                        "Scan a mapped entrance to initialize navigation. If a floor connector is detected, confirm the transition.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SensorStatus(
    name: String,
    available: Boolean,
    values: FloatArray,
    timestamp: Long
) {
    if (!available) {
        DiagnosticRow(name, "Not available")
        return
    }

    if (values.isEmpty()) {
        DiagnosticRow(name, "Available; waiting for sample")
        return
    }

    val magnitude = sqrt(values.sumOf { (it * it).toDouble() })
    DiagnosticRow(name, "%.2f magnitude".format(magnitude))
    Text(
        values.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) },
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        "Last sample: $timestamp",
        style = MaterialTheme.typography.bodySmall
    )
}
