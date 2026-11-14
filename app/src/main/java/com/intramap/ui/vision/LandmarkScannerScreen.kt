package com.intramap.ui.vision

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.intramap.data.model.Building
import com.intramap.vision.matchLandmarkText
import android.os.SystemClock
import com.intramap.vision.CameraLandmarkObservation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LandmarkScannerScreen(
    building: Building,
    onBack: () -> Unit,
    onUseObservation: ((CameraLandmarkObservation) -> Unit)? = null
)  {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    if (building.floors.isEmpty()) {
        Column(Modifier.padding(16.dp)) {
            Text("No floors are configured for this building.")
            TextButton(onClick = onBack) {
                Text("Back")
            }
        }
        return
    }
    var lastMatchedCaptureMs by remember {
        mutableLongStateOf(-1L)
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var floorId by remember(building) {
        mutableStateOf(building.floors.first().floorId)
    }

    var busy by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var recognizedText by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf("Select the known floor, then scan a sign.")
    }

    var previousNodeId by remember { mutableStateOf<String?>(null) }
    var consecutiveMatches by remember { mutableIntStateOf(0) }

    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    val recognizer = remember {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    val alive = remember { mutableStateOf(true) }

    DisposableEffect(recognizer) {
        alive.value = true

        onDispose {
            alive.value = false
            recognizer.close()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted

        status = if (granted) {
            "Camera permission granted. Starting preview."
        } else {
            "Camera permission denied. Allow it to use sign recognition."
        }
    }

    DisposableEffect(
        permissionGranted,
        lifecycleOwner,
        previewView
    ) {
        var disposed = false
        var provider: ProcessCameraProvider? = null

        val preview = Preview.Builder().build()
        cameraReady = false

        if (permissionGranted) {
            val future = ProcessCameraProvider.getInstance(context)

            future.addListener(
                {
                    if (!disposed) {
                        try {
                            val cameraProvider = future.get()
                            provider = cameraProvider

                            if (
                                !cameraProvider.hasCamera(
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                )
                            ) {
                                status = "No rear camera is available."
                            } else {
                                preview.setSurfaceProvider(
                                    previewView.surfaceProvider
                                )

                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview
                                )

                                cameraReady = true
                                status = "Preview started. Point at a mapped sign."
                            }
                        } catch (error: Exception) {
                            cameraReady = false
                            status =
                                "Camera error: ${error.message ?: "Unknown error"}"
                        }
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

        onDispose {
            disposed = true
            provider?.unbind(preview)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Landmark Scanner") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (onUseObservation == null) {
                    "Recognition only — no position correction is applied."
                } else {
                    "Confirm a consistent sign candidate to send an observation " +
                            "to the Localization Lab."
                },
                color = MaterialTheme.colorScheme.primary
            )

            Text("Known current floor")

            Row(
                modifier = Modifier.horizontalScroll(
                    rememberScrollState()
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                building.floors.forEach { floor ->
                    FilterChip(
                        selected = floorId == floor.floorId,
                        enabled = !busy,
                        onClick = {
                            floorId = floor.floorId
                            previousNodeId = null
                            consecutiveMatches = 0
                            recognizedText = ""
                            status = "Floor changed. Scan a sign on this floor."
                        },
                        label = { Text(floor.floorName) }
                    )
                }
            }

            if (permissionGranted) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )
            } else {
                Button(
                    onClick = {
                        permissionLauncher.launch(
                            Manifest.permission.CAMERA
                        )
                    }
                ) {
                    Text("Allow camera")
                }
            }

            Button(
                enabled = permissionGranted && cameraReady && !busy,
                onClick = {
                    val bitmap = previewView.bitmap

                    if (bitmap == null) {
                        status = "Preview is not ready. Wait, then try again."
                    } else {
                        busy = true
                        val scannedFloorId = floorId
                        val capturedAtMs = SystemClock.elapsedRealtime()
                        val input = InputImage.fromBitmap(bitmap, 0)
                        try {
                            recognizer.process(input)
                                .addOnSuccessListener { result ->
                                    if (alive.value) {
                                        recognizedText = result.text

                                        val matches = matchLandmarkText(
                                            building = building,
                                            recognizedText = result.text,
                                            floorId = scannedFloorId
                                        )

                                        when {
                                            result.text.isBlank() -> {
                                                previousNodeId = null
                                                consecutiveMatches = 0
                                                status =
                                                    "No readable text. Improve focus or lighting."
                                            }

                                            matches.isEmpty() -> {
                                                previousNodeId = null
                                                consecutiveMatches = 0
                                                status =
                                                    "Text read, but no mapped destination matched."
                                            }

                                            matches.size > 1 -> {
                                                previousNodeId = null
                                                consecutiveMatches = 0
                                                status =
                                                    "Ambiguous: " +
                                                            matches.joinToString {
                                                                it.name
                                                            } +
                                                            ". Frame only one sign."
                                            }

                                            else -> {
                                                val match = matches.single()

                                                consecutiveMatches =
                                                    if (
                                                        previousNodeId ==
                                                        match.nodeId
                                                    ) {
                                                        consecutiveMatches + 1
                                                    } else {
                                                        1
                                                    }

                                                previousNodeId = match.nodeId
                                                lastMatchedCaptureMs = capturedAtMs

                                                status =
                                                    if (consecutiveMatches >= 2) {
                                                        "Consistent candidate: ${match.name}. " +
                                                                "No position update applied."
                                                    } else {
                                                        "Candidate: ${match.name}. " +
                                                                "Scan again to check consistency."
                                                    }
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener { error ->
                                    if (alive.value) {
                                        previousNodeId = null
                                        consecutiveMatches = 0
                                        status =
                                            "OCR failed: ${error.message}"
                                    }
                                }
                                .addOnCompleteListener {
                                    bitmap.recycle()

                                    if (alive.value) {
                                        busy = false
                                    }
                                }
                        } catch (error: Exception) {
                            bitmap.recycle()
                            busy = false
                            previousNodeId = null
                            consecutiveMatches = 0
                            status = "Could not scan: ${error.message}"
                        }
                    }
                }
            ) {
                Text(if (busy) "Reading sign..." else "Scan visible sign")
            }
            if (onUseObservation != null) {
                Button(
                    enabled =
                        !busy &&
                                previousNodeId != null &&
                                consecutiveMatches >= 2,
                    onClick = {
                        val nodeId = previousNodeId
                        val ageMs =
                            SystemClock.elapsedRealtime() - lastMatchedCaptureMs

                        if (nodeId == null || consecutiveMatches < 2) {
                            status = "Scan the same mapped sign twice first."
                        } else if (
                            lastMatchedCaptureMs < 0L ||
                            ageMs !in 0L..30_000L
                        ) {
                            previousNodeId = null
                            consecutiveMatches = 0
                            status = "Observation expired. Scan the sign again."
                        } else {
                            onUseObservation.invoke(
                                CameraLandmarkObservation(
                                    nodeId = nodeId,
                                    floorId = floorId,
                                    observedText = recognizedText,
                                    capturedAtElapsedMs = lastMatchedCaptureMs,
                                    matchingScans = consecutiveMatches
                                )
                            )
                        }
                    }
                ) {
                    Text("Use this observation")
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Recognition status",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(status)
                    Text("Consecutive matching scans: $consecutiveMatches")
                }
            }

            Text(
                "Recognized text",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                recognizedText.ifBlank {
                    "No text scanned yet."
                }
            )

            Text(
                "Expected signs on this floor",
                style = MaterialTheme.typography.titleMedium
            )

            building.nodes.filter {
                it.floorId == floorId && it.type != "JUNCTION"
            }.forEach { node ->
                Text("• ${node.name}")
            }
        }
    }
}