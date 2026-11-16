package com.intramap.ui.admin

import android.Manifest
import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.intramap.ar.ArAvailabilityChecker
import com.intramap.ar.ArCameraRenderer
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*

internal data class CameraMotion(val distanceM: Double, val headingDeg: Double)

/** Projects ARCore X/Z translation into a single, explicitly aligned local floor frame. */
internal class CameraMotionProjector {
    private var origin: DoubleArray? = null
    private var forwardX = 0.0
    private var forwardZ = -1.0
    private var acceptedX = 0.0
    private var acceptedY = 0.0
    private var lastNs = -1L
    fun observe(x: Double, height: Double, z: Double, fx: Double, fz: Double, timeNs: Long): CameraMotion? {
        require(listOf(x,height,z,fx,fz).all { it.isFinite() }) { "Invalid camera pose" }
        require(timeNs > lastNs && timeNs > 0) { "Non-monotonic camera timestamp" }
        if (lastNs > 0) require(timeNs-lastNs <= 750_000_000L) { "Camera frame gap" }
        lastNs = timeNs
        val base = origin
        if (base == null) {
            val length = hypot(fx,fz)
            require(length >= 0.3) { "Hold the camera upright facing the route" }
            forwardX = fx/length; forwardZ = fz/length
            origin = doubleArrayOf(x,height,z)
            return null
        }
        require(abs(height-base[1]) <= 1.5) { "Height change: record each floor separately" }
        val dx = x-base[0]; val dz = z-base[2]
        val mapX = dx*(-forwardZ)+dz*forwardX
        val mapY = -(dx*forwardX+dz*forwardZ)
        require(abs(mapX) <= 10000 && abs(mapY) <= 10000) { "Camera frame out of range" }
        val stepX = mapX-acceptedX; val stepY = mapY-acceptedY
        val distance = hypot(stepX,stepY)
        require(distance <= 1.5) { "Camera pose jump" }
        if (distance < 0.15) return null
        acceptedX = mapX; acceptedY = mapY
        return CameraMotion(distance,(Math.toDegrees(atan2(stepX,-stepY))+360.0)%360.0)
    }
}

/** Camera frames stay in the renderer; only numeric pose deltas leave this component. */
@Composable
fun CameraMappingControls(
    enabled: Boolean,
    onStep: (Double,Double) -> Unit,
    onInterrupted: (String) -> Unit,
    onRunningChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val owner = LocalLifecycleOwner.current
    val holder = remember { AtomicReference<Session?>(null) }
    val alive = remember { AtomicBoolean(true) }
    val queued = remember { AtomicBoolean(false) }
    var preview by remember { mutableStateOf<GLSurfaceView?>(null) }
    var opened by remember { mutableStateOf(false) }
    var permission by remember { mutableStateOf(ArAvailabilityChecker.hasCameraPermission(context)) }
    var installRequested by remember { mutableStateOf(false) }
    var tracking by remember { mutableStateOf(false) }
    var sampling by remember { mutableStateOf(false) }
    var lastFrameMs by remember { mutableLongStateOf(0L) }
    var projector by remember { mutableStateOf(CameraMotionProjector()) }
    var status by remember { mutableStateOf("Camera route mapping · local relative geometry") }
    val allowed by rememberUpdatedState(enabled)
    val emit by rememberUpdatedState(onStep)
    val interrupted by rememberUpdatedState(onInterrupted)
    val running by rememberUpdatedState(onRunningChanged)
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
    fun stop(message: String) {
        val wasSampling = sampling
        sampling = false; tracking = false; running(false); status = message
        if (wasSampling) interrupted(message)
    }
    val stopNow by rememberUpdatedState<(String) -> Unit> { stop(it) }
    val acceptFrame by rememberUpdatedState<(FloatArray,FloatArray,Long,Boolean) -> Unit> { xyz,forward,ns,tracked ->
        lastFrameMs = SystemClock.elapsedRealtime()
        tracking = tracked
        if (sampling && !tracked) stop("Camera tracking lost. Segment stopped; no gap was joined.")
        else if (sampling && allowed) {
            try {
                projector.observe(xyz[0].toDouble(),xyz[1].toDouble(),xyz[2].toDouble(),-forward[0].toDouble(),-forward[2].toDouble(),ns)?.let { emit(it.distanceM,it.headingDeg) }
            } catch (e: IllegalArgumentException) { stop("${e.message}. Review this segment and start a new recording.") }
        }
    }
    LaunchedEffect(enabled) { if (!enabled && sampling) stop("Camera capture paused. Review the saved segment.") }
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            if (sampling && SystemClock.elapsedRealtime()-lastFrameMs > 1000) stop("Camera frames stopped. Segment paused without connecting a gap.")
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                stopNow("Camera session interrupted. Review this segment; do not resume an unrelated AR frame.")
                preview?.onPause(); holder.get()?.pause()
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                try { holder.get()?.resume(); preview?.onResume() }
                catch (_: Exception) { stopNow("Camera unavailable after resuming.") }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            preview?.onPause()
            holder.getAndSet(null)?.let { it.pause(); it.close() }
            running(false)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("No camera images, video or audio are saved/uploaded by this capture flow. The route still needs map alignment and review.", style = MaterialTheme.typography.labelSmall)
        if (!permission) Button(enabled = enabled, onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        if (permission && !opened) Button(enabled = enabled && activity != null, onClick = {
            var created: Session? = null
            try {
                val install = ArCoreApk.getInstance().requestInstall(requireNotNull(activity),!installRequested)
                if (install == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { installRequested = true; status = "Complete ARCore installation, then open the camera again." }
                else {
                    val value = Session(context); created = value
                    value.configure(Config(value).apply {
                        cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                        geospatialMode = Config.GeospatialMode.DISABLED
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    })
                    value.resume(); holder.set(value); created = null; opened = true
                    status = "Move the phone slowly until tracking is ready, then align Forward."
                }
            } catch (_: Exception) { created?.close(); status = "ARCore camera unavailable. Stop here and choose sensor mode in a new recording if needed." }
        }) { Text("Open live camera") }
        if (opened) {
            AndroidView(factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2); preserveEGLContextOnPause = true
                    var lastDelivered = 0L
                    val renderer = ArCameraRenderer({ holder.get() },{ activity?.windowManager?.defaultDisplay?.rotation ?: 0 }) { frame ->
                        if (frame.timestamp > 0 && frame.timestamp-lastDelivered >= 100_000_000L && queued.compareAndSet(false,true)) {
                            lastDelivered = frame.timestamp
                            val pose = frame.camera.pose
                            val xyz = pose.translation; val forward = pose.zAxis
                            val timestamp = frame.timestamp
                            val tracked = frame.camera.trackingState == TrackingState.TRACKING
                            activity?.runOnUiThread {
                                try { if (alive.get()) acceptFrame(xyz,forward,timestamp,tracked) }
                                finally { queued.set(false) }
                            } ?: queued.set(false)
                        }
                    }
                    setRenderer(renderer); renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    tag = renderer; preview = this; onResume()
                }
            }, onRelease = { gl -> gl.queueEvent { (gl.tag as? ArCameraRenderer)?.release() }; gl.onPause() },
                modifier = Modifier.fillMaxWidth().height(220.dp))
            Text(if (tracking) "Camera tracking ready" else "Waiting for camera tracking",style = MaterialTheme.typography.labelMedium)
            if (!sampling) Button(enabled = enabled && tracking, onClick = {
                projector = CameraMotionProjector(); lastFrameMs = SystemClock.elapsedRealtime(); sampling = true; running(true)
                status = "Mapping from camera poses. Stay on this floor."
            }) { Text("Align Forward and start capture") }
            else OutlinedButton(onClick = { stop("Camera segment stopped. Review before submitting.") }) { Text("Stop camera segment") }
        }
    }
}
