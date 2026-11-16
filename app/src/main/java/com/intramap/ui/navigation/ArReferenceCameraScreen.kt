package com.intramap.ui.navigation

import android.Manifest
import android.app.Activity
import android.opengl.GLSurfaceView
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
import com.intramap.ar.ArAvailability
import com.intramap.ar.ArAvailabilityChecker
import com.intramap.ar.ArCameraRenderer
import com.intramap.ar.ArReferenceSession
import com.intramap.ar.ArSessionState
import com.intramap.data.model.Building
import com.intramap.domain.localization.ReferenceAlignmentResult
import com.intramap.domain.localization.RegisteredReferencePose

@Composable
fun ArReferenceCameraScreen(
    building: Building,
    versionId: String,
    registrations: List<RegisteredReferencePose>,
    onAcceptedPose: (com.intramap.domain.localization.PlanarPose) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val activity = context as? Activity
    var permission by remember { mutableStateOf(ArAvailabilityChecker.hasCameraPermission(context)) }
    var availability by remember { mutableStateOf<ArAvailability?>(null) }
    var session by remember { mutableStateOf<ArReferenceSession?>(null) }
    var status by remember { mutableStateOf("AR reference positioning is unavailable until a supported AR session starts.") }
    val state = session?.state?.collectAsState()?.value
        ?: com.intramap.ar.ArSessionEvent(com.intramap.ar.ArSessionState.IDLE, "AR not started.")
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = granted
        status = if (granted) "Camera permission granted. Start AR positioning." else "Camera permission is required."
    }
    LaunchedEffect(activity, permission) {
        if (activity != null) availability = ArAvailabilityChecker.check(activity)
    }
    DisposableEffect(owner, session) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> session?.pause()
                Lifecycle.Event.ON_RESUME ->
                    if (session?.state?.value?.state == ArSessionState.PAUSED) session?.resume()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            session?.close()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Reference positioning", style = MaterialTheme.typography.titleLarge)
            Text(status)
            Text("State: ${state.state}")
            when (availability) {
                ArAvailability.Supported -> {
                    if (!permission) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("Grant camera permission")
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = session == null || state.state in listOf(
                                    ArSessionState.IDLE,
                                    ArSessionState.FAILED,
                                    ArSessionState.CLOSED
                                ),
                                onClick = {
                                    val created = ArReferenceSession(
                                        context,
                                        building.buildingId,
                                        versionId,
                                        registrations
                                    ) { result ->
                                        if (result is ReferenceAlignmentResult.Accepted) {
                                            onAcceptedPose(result.candidate.cameraMapPose)
                                        }
                                    }
                                    session = created
                                    status = if (created.start()) {
                                        "AR started. Scan a registered reference image."
                                    } else {
                                        created.state.value.message
                                    }
                                }
                            ) { Text("Start AR positioning") }
                            if (session != null) {
                                OutlinedButton(onClick = {
                                    session?.close()
                                    session = null
                                    status = "AR closed."
                                }) { Text("Stop") }
                            }
                        }
                    }
                }
                is ArAvailability.InstallNeeded, is ArAvailability.UpdateNeeded ->
                    Text("Install or update Google Play Services for AR, then reopen this screen.")
                ArAvailability.Unsupported -> Text("This device does not support ARCore.")
                is ArAvailability.Error -> Text(availability.message, color = MaterialTheme.colorScheme.error)
                null -> Text("Checking AR availability…")
            }
        }
        val activeSession = session
        if (activeSession != null && state.state == ArSessionState.RUNNING) {
            key(activeSession) {
                AndroidView(
                    factory = { preview ->
                        GLSurfaceView(preview).apply {
                            setEGLContextClientVersion(2)
                            preserveEGLContextOnPause = true
                            val renderer = ArCameraRenderer(
                                sessionProvider = { activeSession.activeSession() },
                                onFrame = { frame -> activeSession.onFrameReady(frame) }
                            )
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                            tag = renderer
                        }
                    },
                    onRelease = { view ->
                        (view.tag as? ArCameraRenderer)?.release()
                        activeSession.pause()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(Modifier.fillMaxSize())
        }
    }
}
