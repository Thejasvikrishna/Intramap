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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intramap.BuildConfig
import com.intramap.data.model.PathRecordingPoint
import com.intramap.data.repository.SavedWalkingDraft
import com.intramap.data.repository.WalkingDraftStore
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMappingDataSource
import com.intramap.domain.mapping.*
import com.intramap.ui.navigation.NavigationMotionControls
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.*

@Composable
fun PathRecordingScreen(buildingId: String,floorKey: String,startAnchorKey: String?,onBack: () -> Unit) {
    val client = SupabaseClientFactory.client
    val userId = client?.auth?.currentUserOrNull()?.id
    if (client == null || userId == null || buildingId.isBlank() || floorKey.isBlank()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text("Sign in and select a building and floor before recording.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    key(userId,buildingId,floorKey) { RecoverableRecorder(client,"${BuildConfig.SUPABASE_URL}|$userId",buildingId,floorKey,startAnchorKey,onBack) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoverableRecorder(client: SupabaseClient,account: String,buildingId: String,floorKey: String,startAnchorKey: String?,onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val source = remember(client) { SupabaseMappingDataSource(client) }
    val store = remember(account,buildingId,floorKey) { WalkingDraftStore.open(File(context.noBackupFilesDir,"walking_drafts"),account,buildingId,floorKey) }
    var trace by remember { mutableStateOf<WalkingTrace?>(null) }
    var points by remember { mutableStateOf(emptyList<WalkingSample>()) }
    var places by remember { mutableStateOf<RoutePlaceEdits?>(null) }
    var checkpointFormat by remember { mutableIntStateOf(1) }
    var selectedSample by remember { mutableIntStateOf(0) }
    var recordingId by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableLongStateOf(0L) }
    var savedRevision by remember { mutableLongStateOf(-1L) }
    var savedCount by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var uploadAttempted by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var resume by remember { mutableStateOf(false) }
    var cameraMode by remember { mutableStateOf(true) }
    var cameraEpoch by remember { mutableIntStateOf(0) }
    var markerName by remember { mutableStateOf("") }
    var markerType by remember { mutableStateOf("ROOM") }
    var status by remember { mutableStateOf("Checking saved recording…") }
    fun persist(): Deferred<Unit>? {
        val id = recordingId ?: return null
        revision++
        val snapshot = SavedWalkingDraft(account,buildingId,floorKey,id,points.toList(),revision,uploadAttempted,submitted,
            formatVersion = checkpointFormat,places = places?.places.orEmpty().toList())
        val ticket = store.save(snapshot)
        scope.launch {
            try {
                ticket.await()
                if (recordingId != snapshot.recordingId) return@launch
                if (snapshot.revision >= savedRevision) { savedRevision = snapshot.revision; savedCount = snapshot.samples.size }
                if (savedRevision >= revision) saveError = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (recordingId == snapshot.recordingId && snapshot.revision > savedRevision) {
                    saveError = true; reviewing = true; status = "Local save failed. Tracking paused. Retry saving before leaving."
                }
            }
        }
        return ticket
    }
    LaunchedEffect(store,reload) {
        loaded = false; loadError = false
        try {
            val saved = store.load().await()
            if (saved != null) {
                trace = WalkingTrace.restore(saved.samples); points = saved.samples
                places = RoutePlaceEdits.restore(points,saved.places,frozen = saved.uploadAttempted)
                checkpointFormat = saved.formatVersion; selectedSample = points.lastIndex
                recordingId = saved.recordingId; revision = saved.revision; savedRevision = saved.revision
                savedCount = points.size; uploadAttempted = saved.uploadAttempted; submitted = saved.submitted
                cameraMode = saved.samples.firstOrNull()?.marker != "START"
                val emptyCamera = cameraMode && points.size == 1 && !uploadAttempted && !submitted
                reviewing = !emptyCamera
                status = if (emptyCamera) "Open the camera and align Forward to begin." else "Route and ${saved.places.size} independent places recovered for review."
            } else status = "Choose a capture source and create a recording. Your path draws here as you walk."
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true; status = "Saved route could not be read. Retry recovery; the original file has not been overwritten." }
    }
    fun interrupt(message: String) {
        if (recordingId == null || submitted) return
        running = false
        if (cameraMode && points.size <= 1 && !uploadAttempted && !saveError) {
            reviewing = false; cameraEpoch++; status = "Camera setup interrupted before movement. Open the camera again."
        } else { reviewing = true; status = message }
        persist()
    }
    val pause by rememberUpdatedState(newValue = { interrupt("Recording paused. Review this segment; camera frames cannot bridge interruptions.") })
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun back() {
        if (busy) return
        if (recordingId != null || loadError) { reviewing = true; leave = true } else onBack()
    }
    BackHandler { back() }
    if (leave) AlertDialog(onDismissRequest = { leave = false },title = { Text("Save and leave?") },
        text = { Text("Route geometry and independent places are checkpointed locally. Upload and import are separate steps.") },
        confirmButton = { TextButton(enabled = !busy,onClick = {
            busy = true
            scope.launch {
                try { if (recordingId != null) persist()?.await(); leave = false; onBack() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Could not save the latest route and places. Stay here and retry saving." }
                finally { busy = false }
            }
        }) { Text("Save and leave") } },dismissButton = { TextButton(onClick = { leave = false }) { Text("Stay") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false },title = { Text("Remove local recording?") },
        text = { Text("This deletes active local geometry and place edits. Archived submissions and server data are unchanged. This cannot be undone.") },
        confirmButton = { TextButton(enabled = !busy,onClick = {
            busy = true
            scope.launch {
                try {
                    store.deleteLocalCopy().await()
                    trace = null; points = emptyList(); places = null; checkpointFormat = 1; selectedSample = 0
                    recordingId = null; revision = 0; savedRevision = -1; savedCount = 0
                    submitted = false; uploadAttempted = false; reviewing = false; saveError = false; loadError = false; loaded = true; cameraEpoch++
                    discard = false; status = "Active local copy removed. Choose a source for the next route."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Could not remove the local copy." }
                finally { busy = false }
            }
        }) { Text("Remove local copy") } },dismissButton = { TextButton(onClick = { discard = false }) { Text("Cancel") } })
    if (resume) AlertDialog(onDismissRequest = { resume = false },title = { Text("Resume at the saved endpoint?") },
        text = { Text("Stand at the last recorded position and align the original direction. A saved route does not locate you automatically.") },
        confirmButton = { TextButton(onClick = { reviewing = false; resume = false; status = "Restart sensors and align the original direction." }) { Text("I am at the endpoint") } },
        dismissButton = { TextButton(onClick = { resume = false }) { Text("Cancel") } })
    Scaffold(topBar = { TopAppBar(title = { Text("Record route · $floorKey") },navigationIcon = { TextButton(enabled = !busy,onClick = { back() }) { Text("Back") } }) },bottomBar = {
        Surface(tonalElevation = 2.dp,shape = RoundedCornerShape(topStart = 24.dp,topEnd = 24.dp)) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().heightIn(max = 400.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(status,style = MaterialTheme.typography.bodyMedium)
                Text("%.1f m · %d independent places".format(trace?.distanceM ?: 0.0,places?.places?.size ?: 0),style = MaterialTheme.typography.titleMedium)
                if (recordingId != null) Text("Checkpoint: $savedCount / ${points.size} route samples" + if (savedRevision < revision) " · saving route and places" else " · saved",style = MaterialTheme.typography.labelSmall)
                if (!loaded && !loadError) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (loadError) TextButton(onClick = { reload++ }) { Text("Retry recovery") }
                if (saveError) Button(enabled = !busy,onClick = { persist() }) { Text("Retry local save") }
                if (recordingId == null && loaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cameraMode,enabled = !busy,onClick = { cameraMode = true },label = { Text("Camera") })
                        FilterChip(selected = !cameraMode,enabled = !busy,onClick = { cameraMode = false },label = { Text("Sensors") })
                    }
                    Button(enabled = !busy,onClick = {
                        busy = true
                        scope.launch {
                            var created = false
                            try {
                                val row = source.startRecording(buildingId,floorKey,startAnchorKey)
                                recordingId = row.id; created = true
                                val initial = WalkingSample(0.0,0.0,SystemClock.elapsedRealtime(),0.0,if (cameraMode) "CAMERA_START" else "START")
                                trace = WalkingTrace.restore(listOf(initial)); points = trace!!.snapshot()
                                places = RoutePlaceEdits.restore(points); checkpointFormat = 1
                                persist()?.await()
                                status = if (cameraMode) "Open the camera, wait for tracking, then align Forward and start capture." else "Start sensors and align Forward before walking."
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) {
                                if (created) { saveError = true; reviewing = true; status = "Recording created but checkpoint failed. Retry local save instead of creating another." }
                                else status = "Recording creation failed. Check session, building permissions and connection."
                            } finally { busy = false }
                        }
                    },modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else "Create recording") }
                }
                if (recordingId != null && !submitted) {
                    if (!reviewing && !uploadAttempted) {
                        val recordMotion: (Double,Double) -> Unit = { length,heading ->
                            if (!busy && !reviewing && !saveError && !leave && !discard && !resume && trace?.step(length,heading,maxOf(SystemClock.elapsedRealtime(),points.last().timeMs+1)) == true) {
                                points = trace!!.snapshot()
                                places = RoutePlaceEdits.restore(points,places?.places.orEmpty())
                                persist()
                                if (points.size >= 10000) { reviewing = true; selectedSample = points.lastIndex; status = "Sample limit reached. Review this segment." }
                            }
                        }
                        val active = loaded && !busy && !leave && !discard && !resume && !saveError
                        if (cameraMode) key(cameraEpoch) {
                            CameraMappingControls(active,recordMotion,onInterrupted = { interrupt(it) },onRunningChanged = { running = it })
                        } else NavigationMotionControls(enabled = active,onStep = recordMotion,onRunningChanged = { running = it })
                        OutlinedTextField(markerName,{ markerName = it.take(120) },label = { Text("Legacy route landmark name") },enabled = !busy && !saveError,modifier = Modifier.fillMaxWidth())
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ROOM","ENTRANCE","JUNCTION","TURN","STAIRS","ELEVATOR").forEach { type -> FilterChip(selected = markerType == type,onClick = { markerType = type },label = { Text(type) }) }
                        }
                        OutlinedButton(enabled = !busy && !saveError && markerName.isNotBlank() && (!cameraMode || running),onClick = {
                            if (trace?.mark(markerType,markerName) == true) {
                                points = trace!!.snapshot(); places = RoutePlaceEdits.restore(points,places?.places.orEmpty()); markerName = ""; persist()
                            }
                        }) { Text("Mark legacy landmark") }
                        Text("Stop to review and add independent rooms or shops at any recorded point.",style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && points.size > 1,onClick = { reviewing = true; selectedSample = points.lastIndex; persist() },modifier = Modifier.fillMaxWidth()) { Text("Stop and review places") }
                    } else {
                        Text("Review the route and its places. Import and map review remain required before publication.",style = MaterialTheme.typography.bodyMedium)
                        val currentPlaces = places
                        if (currentPlaces != null) {
                            Text("Selected route point ${(selectedSample.coerceIn(0,points.lastIndex) + 1)} / ${points.size}",style = MaterialTheme.typography.labelLarge)
                            if (points.size > 1) Slider(value = selectedSample.coerceIn(0,points.lastIndex).toFloat(),onValueChange = { selectedSample = it.roundToInt().coerceIn(0,points.lastIndex) },
                                valueRange = 0f..points.lastIndex.toFloat(),enabled = !busy && !uploadAttempted && !saveError)
                            RoutePlaceEditor(currentPlaces,selectedSample.coerceIn(0,points.lastIndex),enabled = !busy && !saveError && !uploadAttempted,onChange = { updated ->
                                if (!busy && !saveError && !uploadAttempted) { places = updated; checkpointFormat = 2; persist(); status = "Place edits checkpointed locally. Upload integration is still pending." }
                            })
                        }
                        if (!uploadAttempted && cameraMode && points.size == 1 && !saveError) OutlinedButton(enabled = !busy,onClick = { reviewing = false; cameraEpoch++ }) { Text("Retry camera setup") }
                        if (cameraMode && points.size > 1) Text("A new camera frame will not be joined automatically.",style = MaterialTheme.typography.bodySmall)
                        if (!uploadAttempted && !cameraMode) OutlinedButton(enabled = !busy && !saveError,onClick = { resume = true }) { Text("Resume sensors at endpoint") }
                        val hasIndependentPlaces = places?.places?.isNotEmpty() == true
                        if (hasIndependentPlaces) Text("Submission paused: independent place upload/import is not integrated yet. Save and leave to retain all place edits. The app will not silently drop them.",color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall)
                        if (points.size <= 1) Text("Walk a route before submitting; a starting point is not a path.",style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && !saveError && points.size > 1 && !hasIndependentPlaces,onClick = {
                            busy = true; uploadAttempted = true; places = places?.freeze()
                            val id = requireNotNull(recordingId); val snapshot = points.toList()
                            scope.launch {
                                try {
                                    persist()?.await()
                                    snapshot.mapIndexed { index,p -> PathRecordingPoint(id,index,p.x.toFloat(),p.y.toFloat(),p.timeMs,p.headingDeg.toFloat(),p.marker) }
                                        .chunked(100).forEach { batch -> client.from("path_recording_points").upsert(batch) { onConflict = "recording_id,seq" } }
                                    source.submitRecording(id); submitted = true; persist()?.await()
                                    status = "Submitted. Return to the studio to import and review the route."
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { status = if (submitted) "Server submission succeeded. Retry local save for the receipt." else "Upload incomplete. Geometry is frozen; retry sends the same keys." }
                                finally { busy = false }
                            }
                        },modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Saving / uploading…" else if (uploadAttempted) "Retry submission" else "Submit recording") }
                    }
                }
                if (submitted) Button(onClick = { back() },modifier = Modifier.fillMaxWidth()) { Text("Return to studio") }
                if (recordingId != null || loadError) TextButton(enabled = !busy,onClick = { reviewing = true; discard = true }) { Text("Remove active local copy") }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when { submitted -> "Submitted route"; reviewing -> "Route and place review"; running -> "Recording movement"; recordingId != null -> "Ready for capture setup"; else -> "Live route preview" },style = MaterialTheme.typography.titleSmall)
            Text(if (cameraMode) "Relative camera trace · not a reconstructed floor plan" else "Sensor estimate · assumed 0.70 m stride",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
            WalkingTracePreview(points,places?.places.orEmpty(),if (reviewing) selectedSample else null,Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun WalkingTracePreview(points: List<WalkingSample>,places: List<RecordedRoutePlace>,selectedSample: Int?,modifier: Modifier = Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var follow by remember { mutableStateOf(true) }
    var fit by remember { mutableStateOf(false) }
    val background = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val route = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val markerColor = MaterialTheme.colorScheme.error
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _,delta,factor,_ -> follow = false; zoom = (zoom*factor).coerceIn(0.25f,8f); pan += delta } }) {
            drawRect(background)
            if (size.minDimension <= 0) return@Canvas
            val minX = points.minOfOrNull { it.x } ?: 0.0; val maxX = points.maxOfOrNull { it.x } ?: 0.0
            val minY = points.minOfOrNull { it.y } ?: 0.0; val maxY = points.maxOfOrNull { it.y } ?: 0.0
            val scale = (if (fit) min(size.width/(maxX-minX+6).toFloat(),size.height/(maxY-minY+6).toFloat()) else 24.dp.toPx())*zoom
            val centerX = if (follow) points.lastOrNull()?.x ?: 0.0 else if (fit) (minX+maxX)/2 else 0.0
            val centerY = if (follow) points.lastOrNull()?.y ?: 0.0 else if (fit) (minY+maxY)/2 else 0.0
            val offset = if (follow) Offset.Zero else pan
            fun position(p: WalkingSample) = Offset(size.width/2+offset.x+((p.x-centerX)*scale).toFloat(),size.height/2+offset.y+((p.y-centerY)*scale).toFloat())
            val spacing = ((if (scale < 12.dp.toPx()) 5 else 1)*scale)
            val ox = size.width/2+offset.x-(centerX*scale).toFloat(); val oy = size.height/2+offset.y-(centerY*scale).toFloat()
            var gx = ((ox%spacing)+spacing)%spacing
            while (gx < size.width) { drawLine(grid,Offset(gx,0f),Offset(gx,size.height),1f); gx += spacing }
            var gy = ((oy%spacing)+spacing)%spacing
            while (gy < size.height) { drawLine(grid,Offset(0f,gy),Offset(size.width,gy),1f); gy += spacing }
            points.zipWithNext().forEach { (a,b) -> drawLine(route,position(a),position(b),4.dp.toPx(),StrokeCap.Round) }
            points.firstOrNull()?.let { drawCircle(startColor,7.dp.toPx(),position(it)) }
            points.drop(1).filter { it.marker != null }.forEach { drawCircle(markerColor,4.dp.toPx(),position(it)) }
            places.forEach { place -> points.getOrNull(place.sampleIndex)?.let { drawCircle(markerColor,6.dp.toPx(),position(it)) } }
            selectedSample?.let { points.getOrNull(it) }?.let { drawCircle(startColor.copy(alpha = 0.3f),14.dp.toPx(),position(it)); drawCircle(startColor,5.dp.toPx(),position(it)) }
            points.lastOrNull()?.let { drawCircle(route.copy(alpha = 0.15f),16.dp.toPx(),position(it)); drawCircle(background,10.dp.toPx(),position(it)); drawCircle(route,7.dp.toPx(),position(it)) }
        }
        Surface(Modifier.align(Alignment.TopStart).padding(8.dp),shape = MaterialTheme.shapes.small,tonalElevation = 2.dp) {
            Text(if (points.size <= 1) "Start capture, then walk to draw your path" else "${places.size} places · select recorded points in review",Modifier.padding(8.dp),style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(8.dp),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { follow = false; fit = true; zoom = 1f; pan = Offset.Zero }) { Text("Fit route") }
            FilledTonalButton(onClick = { follow = true; fit = false; zoom = 1f; pan = Offset.Zero }) { Text(if (follow) "Following" else "Recenter") }
        }
    }
}
