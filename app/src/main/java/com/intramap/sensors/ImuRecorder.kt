package com.intramap.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import com.intramap.localization.StepDetector
import java.io.BufferedWriter
import java.io.File

class ImuRecorder(
    context: Context,
    private val onAccel: (Long, Float, Float, Float) -> Unit,
    private val onAzimuth: (Float) -> Unit,
    private val onStep: (Long) -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext

    private val sm =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val rot =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val systemStep =
        sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val fallbackDetector = StepDetector()

    var upright = true

    val hasSystemStepDetector: Boolean
        get() = systemStep != null

    val missing: List<String>
        get() = buildList {
            if (accel == null) add("accelerometer")
            if (rot == null) add("rotation vector")
            if (gyro == null) add("gyroscope (optional)")
            if (systemStep == null) add("system step detector (fallback available)")
        }

    var stepSource = "Not started"
        private set

    var lastError: String? = null
        private set

    var hasHeading = false
        private set

    private var active = false
    private var useSystemSteps = false

    private val rotMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    private var writer: BufferedWriter? = null

    private fun hasActivityPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                appContext.checkSelfPermission(
                    Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED

    fun start(logFile: File?): Boolean {
        stop()

        lastError = null
        hasHeading = false
        fallbackDetector.reset()

        val orientationSensor = rot
        if (orientationSensor == null) {
            lastError = "No rotation-vector sensor is available."
            return false
        }

        try {
            writer = logFile?.let { file ->
                file.parentFile?.let { parent ->
                    check(parent.exists() || parent.mkdirs()) {
                        "Cannot create log directory."
                    }
                }

                file.bufferedWriter().also {
                    it.write("t_ns,type,v0,v1,v2,v3\n")
                }
            }

            check(
                sm.registerListener(
                    this,
                    orientationSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            ) {
                "Could not register the heading sensor."
            }

            val accelRegistered = accel?.let {
                sm.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_GAME
                )
            } ?: false

            useSystemSteps =
                if (systemStep != null && hasActivityPermission()) {
                    try {
                        sm.registerListener(
                            this,
                            systemStep,
                            SensorManager.SENSOR_DELAY_NORMAL
                        )
                    } catch (_: SecurityException) {
                        false
                    }
                } else {
                    false
                }

            check(useSystemSteps || accelRegistered) {
                "Neither system nor software step detection is available."
            }

            gyro?.let {
                sm.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }

            stepSource = if (useSystemSteps) {
                "Android system step detector"
            } else {
                "Software accelerometer fallback"
            }

            active = true
            return true
        } catch (e: Exception) {
            stop()
            lastError = e.message ?: "Could not start sensors."
            return false
        }
    }

    fun stop() {
        active = false
        sm.unregisterListener(this)

        runCatching { writer?.close() }
        writer = null

        useSystemSteps = false
        hasHeading = false
        stepSource = "Not started"
    }

    private fun log(t: Long, tag: String, values: FloatArray) {
        val output = writer ?: return

        try {
            val row = buildString {
                append(t)
                append(',')
                append(tag)

                repeat(4) { i ->
                    append(',')
                    if (i < values.size) append(values[i])
                }

                append('\n')
            }

            output.write(row)
        } catch (e: Exception) {
            lastError = "Logging stopped: ${e.message}"
            runCatching { output.close() }
            writer = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return

        val v = event.values
        val t = event.timestamp

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                log(t, "ACC", v)
                onAccel(t, v[0], v[1], v[2])

                if (!useSystemSteps) {
                    val step = fallbackDetector.update(t, v[0], v[1], v[2])

                    if (step != null) {
                        log(t, "STEP_SOFTWARE", floatArrayOf(1f))
                        onStep(t)
                    }
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                if (useSystemSteps) {
                    log(t, "STEP_SYSTEM", v)
                    onStep(t)
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                log(t, "GYRO", v)
            }

            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotMatrix, v)

                val matrix = if (upright) {
                    val ok = SensorManager.remapCoordinateSystem(
                        rotMatrix,
                        SensorManager.AXIS_X,
                        SensorManager.AXIS_Z,
                        remapped
                    )

                    if (!ok) return
                    remapped
                } else {
                    rotMatrix
                }

                SensorManager.getOrientation(matrix, orient)

                val azimuth = (
                        (Math.toDegrees(orient[0].toDouble()) + 360.0) % 360.0
                        ).toFloat()

                hasHeading = true

                log(t, "ROT", v)
                log(t, "AZ", floatArrayOf(azimuth))

                onAzimuth(azimuth)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}