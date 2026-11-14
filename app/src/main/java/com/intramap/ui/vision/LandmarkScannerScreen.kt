package com.intramap.ui.vision

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.intramap.data.model.Building
import com.intramap.vision.CameraLandmarkObservation
import com.intramap.vision.matchLandmarkText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkScannerScreen(
    building: Building,
    onBack: () -> Unit,
    onUseObservation: ((CameraLandmarkObservation) -> Unit)? = null
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    if (building.floors.isEmpty()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp)) {
            Text("No floors configured.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var floorId by remember(building) { mutableStateOf(building.floors.first().floorId) }
    var busy by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Keep still; frame one mapped sign and scan it twice.") }
    var nodeId by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableIntStateOf(0) }
    var capturedAt by remember { mutableLongStateOf(-1L) }
    var generation by remember { mutableIntStateOf(0) }
    var submitted by remember { mutableStateOf(false) }
    val alive = remember { mutableStateOf(true) }
    val currentOnUse by rememberUpdatedState(onUseObservation)
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    fun clearCandidate() {
        nodeId = null
        matches = 0
        capturedAt = -1L
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                active = true
                permissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            } else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                active = false
                generation++
                clearCandidate()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(recognizer) {
        alive.value = true
        onDispose { alive.value = false; recognizer.close() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
        status = if (it) "Permission granted. Wait for preview." else "Camera permission denied."
    }
    DisposableEffect(permissionGranted, owner, previewView) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        cameraReady = false
        if (permissionGranted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed) {
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider
                        if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            status = "No rear camera available."
                        } else {
                            preview.setSurfaceProvider(previewView.surfaceProvider)
                            cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                            cameraReady = true
                            status = "Preview ready. Frame a mapped sign."
                        }
                    } catch (e: Exception) { status = "Camera error: ${e.message}" }
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { disposed = true; provider?.unbind(preview) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Landmark Scanner") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (onUseObservation == null) "Recognition only; no correction is submitted."
            else "A confirmed candidate is sent to the calling screen. That screen may accept or reject the correction.")
            Text("Known current floor")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                building.floors.forEach { floor ->
                    FilterChip(selected = floorId == floor.floorId, enabled = !busy && !submitted,
                        onClick = {
                            floorId = floor.floorId
                            generation++
                            clearCandidate()
                            text = ""
                            status = "Floor changed. Scan twice on this floor."
                        }, label = { Text(floor.floorName) })
                }
            }
            if (permissionGranted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().height(300.dp))
            } else Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            Button(enabled = active && permissionGranted && cameraReady && !busy && !submitted,
                onClick = {
                    val bitmap = previewView.bitmap
                    if (bitmap == null) status = "Preview not ready; try again shortly."
                    else {
                        busy = true
                        val requestGeneration = generation
                        val scannedFloor = floorId
                        val timestamp = SystemClock.elapsedRealtime()
                        try {
                            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                .addOnSuccessListener { result ->
                                    if (alive.value && active && generation == requestGeneration) {
                                        text = result.text
                                        val candidates = matchLandmarkText(building = building, recognizedText = result.text, floorId = scannedFloor)
                                        if (result.text.isBlank() || candidates.size != 1) {
                                            clearCandidate()
                                            status = when {
                                                result.text.isBlank() -> "No readable text. Improve focus or lighting."
                                                candidates.isEmpty() -> "No mapped sign matched."
                                                else -> "Ambiguous text. Frame only one sign."
                                            }
                                        } else {
                                            val candidate = candidates.single()
                                            matches = if (nodeId == candidate.nodeId &&
                                                timestamp - capturedAt in 0L..30_000L) matches + 1 else 1
                                            nodeId = candidate.nodeId
                                            capturedAt = timestamp
                                            status = if (matches >= 2) "Consistent candidate: ${candidate.name}. No correction applied yet."
                                            else "Candidate: ${candidate.name}. Scan again."
                                        }
                                    }
                                }
                                .addOnFailureListener { error ->
                                    if (alive.value && generation == requestGeneration) {
                                        clearCandidate()
                                        status = "OCR failed: ${error.message}"
                                    }
                                }
                                .addOnCompleteListener {
                                    bitmap.recycle()
                                    if (alive.value) busy = false
                                }
                        } catch (e: Exception) {
                            bitmap.recycle()
                            busy = false
                            clearCandidate()
                            status = "Could not scan: ${e.message}"
                        }
                    }
                }) { Text(if (busy) "Reading…" else "Scan visible sign") }
            if (onUseObservation != null) {
                Button(enabled = active && !busy && !submitted && nodeId != null && matches >= 2,
                    onClick = {
                        val id = nodeId
                        val age = SystemClock.elapsedRealtime() - capturedAt
                        if (id == null || matches < 2 || capturedAt < 0L || age !in 0L..30_000L) {
                            clearCandidate()
                            status = "Observation expired or invalid. Scan twice again."
                        } else {
                            submitted = true
                            currentOnUse?.invoke(CameraLandmarkObservation(id, floorId, text, capturedAt, matches))
                        }
                    }) { Text("Use this observation") }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(status)
                    Text("Consecutive matching scans: $matches")
                }
            }
            Text("Recognized text", style = MaterialTheme.typography.titleMedium)
            Text(text.ifBlank { "No text scanned yet." })
            Text("Expected signs on this floor", style = MaterialTheme.typography.titleMedium)
            building.nodes.filter { it.floorId == floorId && it.type != "JUNCTION" }.forEach { Text("• ${it.name}") }
        }
    }
}
