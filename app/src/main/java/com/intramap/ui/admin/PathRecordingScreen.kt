package com.intramap.ui.admin

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.intramap.data.model.PathRecordingPoint
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMappingDataSource
import com.intramap.domain.mapping.WalkingSample
import com.intramap.domain.mapping.WalkingTrace
import com.intramap.ui.navigation.NavigationMotionControls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.github.jan.supabase.postgrest.from
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathRecordingScreen(
    buildingId: String,
    floorKey: String,
    startAnchorKey: String?,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val client = SupabaseClientFactory.client
    val source = remember(client) { client?.let { SupabaseMappingDataSource(it) } }
    var trace by remember(buildingId, floorKey) { mutableStateOf<WalkingTrace?>(null) }
    var points by remember(buildingId, floorKey) { mutableStateOf(emptyList<WalkingSample>()) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var markerName by remember { mutableStateOf("") }
    var markerType by remember { mutableStateOf("ROOM") }
    var status by remember { mutableStateOf("Create a recording, then start tracking and align its local direction.") }
    val unsaved = recordingId != null && !submitted

    fun back() {
        if (unsaved || busy) leave = true else onBack()
    }
    BackHandler { back() }

    if (leave) {
        AlertDialog(
            onDismissRequest = { leave = false },
            title = { Text("Leave this recording?") },
            text = { Text("Unsubmitted movement is held in memory, not recovered after leaving or process death. Any uploaded draft remains private. Submit before leaving to preserve the trace on the server.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { leave = false; onBack() }) {
                    Text("Leave without submitting")
                }
            },
            dismissButton = { TextButton(onClick = { leave = false }) { Text("Stay") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Walk to map · $floorKey") },
                navigationIcon = { TextButton(onClick = { back() }) { Text("Back") } }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Text("${(points.size - 1).coerceAtLeast(0)} measured step events · %.1f m estimated".format(trace?.distanceM ?: 0.0))
                    if (recordingId == null) {
                        Button(
                            enabled = source != null && !busy && buildingId.isNotBlank() && floorKey.isNotBlank(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        val created = requireNotNull(source).startRecording(buildingId, floorKey, startAnchorKey)
                                        recordingId = created.id
                                        val started = WalkingTrace(SystemClock.elapsedRealtime())
                                        trace = started
                                        points = started.snapshot()
                                        status = "Start tracking below. Forward defines this recording's local map-up direction."
                                    } catch (e: CancellationException) { throw e
                                    } catch (_: Exception) {
                                        status = "Could not create recording. Check sign-in, connection and database permissions."
                                    } finally { busy = false }
                                }
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text(if (busy) "Creating recording..." else "Create walking recording") }
                    }
                    if (recordingId != null && !submitted) {
                        if (!reviewing) {
                            NavigationMotionControls(
                                enabled = !busy && !leave,
                                onStep = { length, heading ->
                                    val current = trace
                                    if (!busy && !reviewing && !leave && current != null &&
                                        current.step(length, heading, SystemClock.elapsedRealtime())) {
                                        points = current.snapshot()
                                    }
                                },
                                onRunningChanged = { running = it }
                            )
                            OutlinedTextField(
                                value = markerName, onValueChange = { markerName = it.take(120) },
                                label = { Text("Place or junction name") }, singleLine = true,
                                enabled = !busy, modifier = Modifier.fillMaxWidth()
                            )
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ROOM", "ENTRANCE", "JUNCTION", "TURN", "STAIRS", "ELEVATOR").forEach { type ->
                                    FilterChip(selected = markerType == type, onClick = { markerType = type }, label = { Text(type) })
                                }
                            }
                            OutlinedButton(
                                enabled = !busy && markerName.isNotBlank(),
                                onClick = {
                                    if (trace?.mark(markerType, markerName) == true) {
                                        points = trace!!.snapshot()
                                        markerName = ""
                                        status = "Marker attached to the last measured position. Mark while physically at the place."
                                    }
                                }
                            ) { Text("Mark current place") }
                            Button(enabled = points.size > 1 && !busy, onClick = { reviewing = true }) {
                                Text("Stop and review trace")
                            }
                        } else {
                            Text("Review the blue trace and red markers. This is an unregistered PDR recording, not a published navigation map.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(enabled = !busy, onClick = { reviewing = false }) { Text("Resume") }
                                Button(enabled = !busy && client != null, onClick = {
                                    busy = true
                                    val id = requireNotNull(recordingId)
                                    val snapshot = points.toList()
                                    scope.launch {
                                        try {
                                            val remote = requireNotNull(client)
                                            snapshot.mapIndexed { index, point ->
                                                PathRecordingPoint(id, index, point.x.toFloat(), point.y.toFloat(), point.timeMs, point.headingDeg.toFloat(), point.marker)
                                            }.chunked(100).forEach { batch ->
                                                remote.from("path_recording_points").upsert(batch) {
                                                    onConflict = "recording_id,seq"
                                                }
                                            }
                                            requireNotNull(source).submitRecording(id)
                                            submitted = true
                                            status = "Recording submitted. It must be aligned, joined and reviewed before map publication."
                                        } catch (e: CancellationException) { throw e
                                        } catch (_: Exception) {
                                            status = "Upload incomplete. Keep this screen open and retry; point keys are reused, not duplicated."
                                        } finally { busy = false }
                                    }
                                }) { Text(if (busy) "Uploading..." else "Submit recording") }
                            }
                        }
                    }
                    if (submitted) TextButton(onClick = onBack) { Text("Done") }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Text("Relative sensor estimate · assumed 0.70 m stride · drift is not calibrated", style = MaterialTheme.typography.labelSmall)
            Text(if (running && !reviewing) "Tracking session started; movement is recorded only after alignment." else "Tracking paused or awaiting alignment.", style = MaterialTheme.typography.labelSmall)
            WalkingTracePreview(points, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun WalkingTracePreview(points: List<WalkingSample>, modifier: Modifier = Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.pointerInput(Unit) {
        detectTransformGestures { _, delta, factor, _ ->
            zoom = (zoom * factor).coerceIn(0.5f, 8f)
            pan += delta
        }
    }) {
        drawRect(Color(0xFFF3F6FA))
        if (points.isEmpty() || size.minDimension <= 0f) return@Canvas
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val scale = min(size.width / (maxX - minX + 6).toFloat(), size.height / (maxY - minY + 6).toFloat()) * zoom
        fun position(p: WalkingSample) = Offset(
            size.width / 2 + pan.x + (p.x - (minX + maxX) / 2).toFloat() * scale,
            size.height / 2 + pan.y + (p.y - (minY + maxY) / 2).toFloat() * scale
        )
        points.zipWithNext().forEach { (a, b) ->
            drawLine(Color(0xFF2563EB), position(a), position(b), 5.dp.toPx(), StrokeCap.Round)
        }
        points.filter { it.marker != null }.forEach {
            drawCircle(Color(0xFFDC2626), 5.dp.toPx(), position(it))
        }
        drawCircle(Color.White, 10.dp.toPx(), position(points.last()))
        drawCircle(Color(0xFF2563EB), 7.dp.toPx(), position(points.last()))
    }
}
