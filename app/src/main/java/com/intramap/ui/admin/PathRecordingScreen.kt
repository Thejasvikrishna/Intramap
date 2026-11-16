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
import com.intramap.domain.mapping.WalkingSample
import com.intramap.domain.mapping.WalkingTrace
import com.intramap.ui.navigation.NavigationMotionControls
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min

@Composable
fun PathRecordingScreen(buildingId: String, floorKey: String, startAnchorKey: String?, onBack: () -> Unit) {
    val client = SupabaseClientFactory.client
    val userId = client?.auth?.currentUserOrNull()?.id
    if (client == null || userId == null || buildingId.isBlank() || floorKey.isBlank()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text("Sign in and select a building and floor before recording.")
            TextButton(onClick = onBack) { Text("Back") }
        }
        return
    }
    key(userId,buildingId,floorKey) {
        RecoverableRecorder(client,"${BuildConfig.SUPABASE_URL}|$userId",buildingId,floorKey,startAnchorKey,onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoverableRecorder(client: SupabaseClient, account: String, buildingId: String, floorKey: String, startAnchorKey: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val source = remember(client) { SupabaseMappingDataSource(client) }
    val store = remember(account,buildingId,floorKey) {
        WalkingDraftStore.open(File(context.noBackupFilesDir,"walking_drafts"),account,buildingId,floorKey)
    }
    var trace by remember { mutableStateOf<WalkingTrace?>(null) }
    var points by remember { mutableStateOf(emptyList<WalkingSample>()) }
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
    var markerName by remember { mutableStateOf("") }
    var markerType by remember { mutableStateOf("ROOM") }
    var status by remember { mutableStateOf("Checking saved recording...") }

    fun persist(): Deferred<Unit>? {
        val id = recordingId ?: return null
        revision++
        val snapshot = SavedWalkingDraft(account,buildingId,floorKey,id,points.toList(),revision,uploadAttempted,submitted)
        val ticket = store.save(snapshot)
        scope.launch {
            try {
                ticket.await()
                if (recordingId != snapshot.recordingId) return@launch
                if (snapshot.revision >= savedRevision) { savedRevision = snapshot.revision; savedCount = snapshot.samples.size }
                if (savedRevision >= revision) saveError = false
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (recordingId != snapshot.recordingId) return@launch
                if (snapshot.revision > savedRevision) {
                    saveError = true; reviewing = true
                    status = "Local save failed. Tracking paused. Retry saving before leaving."
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
                recordingId = saved.recordingId; revision = saved.revision; savedRevision = saved.revision
                savedCount = points.size; uploadAttempted = saved.uploadAttempted; submitted = saved.submitted
                cameraMode = saved.samples.firstOrNull()?.marker != "START"
                reviewing = true
                status = if (submitted) "Saved submission receipt recovered. The server copy remains available."
                    else "Saved trace recovered for review. Unknown/camera sources cannot resume a new camera frame."
            } else status = "Choose camera or sensors, then create a recording."
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = true; status = "Saved trace could not be read. It has not been overwritten. Retry recovery or explicitly discard the local copy." }
    }
    val pause by rememberUpdatedState(newValue = {
        if (recordingId != null && !submitted) {
            reviewing = true; persist()
            status = "Recording paused. Camera segments cannot bridge session interruptions."
        }
    })
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun back() { if (recordingId != null || busy || loadError) { reviewing = true; leave = true } else onBack() }
    BackHandler { back() }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("Save and leave?") },
        text = { Text("A local checkpoint is saved before leaving. Remote upload is separate. If recovery failed, leaving preserves the existing file without overwriting it.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { if (recordingId != null) persist()?.await(); leave = false; onBack() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Could not save the latest trace. Stay here and retry saving." }
                finally { busy = false }
            }
        }) { Text("Save and leave") } }, dismissButton = { TextButton(onClick = { leave = false }) { Text("Stay") } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Remove local recording copy?") },
        text = { Text("This removes only this account's saved trace for this floor. Any server recording remains unchanged. Unuploaded movement cannot be recovered after removal.") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    store.deleteLocalCopy().await()
                    trace = null; points = emptyList(); recordingId = null; revision = 0; savedRevision = -1; savedCount = 0
                    submitted = false; uploadAttempted = false; reviewing = false; saveError = false; loadError = false; loaded = true
                    discard = false; status = "Local copy removed. Server data was not deleted."
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = "Could not remove the local copy." }
                finally { busy = false }
            }
        }) { Text("Remove local copy") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Cancel") } })
    if (resume) AlertDialog(onDismissRequest = { resume = false }, title = { Text("Resume from the saved endpoint?") },
        text = { Text("Stand at the last recorded physical position. Align the phone to the same original local map direction when restarting sensor tracking. A saved trace cannot detect where you are now.") },
        confirmButton = { TextButton(onClick = { reviewing = false; resume = false; status = "Restart sensors and align the original local direction." }) { Text("I am at the endpoint") } },
        dismissButton = { TextButton(onClick = { resume = false }) { Text("Cancel") } })

    Scaffold(topBar = { TopAppBar(title = { Text("Walk to map · $floorKey") }, navigationIcon = { TextButton(onClick = { back() }) { Text("Back") } }) },
        bottomBar = {
            Surface(tonalElevation = 4.dp,shape = RoundedCornerShape(topStart = 24.dp,topEnd = 24.dp)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status)
                    Text("${(points.size-1).coerceAtLeast(0)} motion samples · %.1f m estimated".format(trace?.distanceM ?: 0.0))
                    if (recordingId != null) Text("Local checkpoint: $savedCount / ${points.size} samples" + if (savedRevision < revision) " · saving" else " · saved",style = MaterialTheme.typography.labelSmall)
                    if (!loaded && !loadError) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (loadError) TextButton(onClick = { reload++ }) { Text("Retry recovery") }
                    if (saveError) Button(enabled = !busy,onClick = { persist() }) { Text("Retry local save") }
                    if (recordingId == null && loaded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = cameraMode, enabled = !busy, onClick = { cameraMode = true }, label = { Text("Live camera") })
                            FilterChip(selected = !cameraMode, enabled = !busy, onClick = { cameraMode = false }, label = { Text("Sensors") })
                        }
                        Button(enabled = !busy,onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val created = source.startRecording(buildingId,floorKey,startAnchorKey)
                                    recordingId = created.id
                                    val initial = WalkingSample(0.0,0.0,SystemClock.elapsedRealtime(),0.0,if (cameraMode) "CAMERA_START" else "START")
                                    trace = WalkingTrace.restore(listOf(initial)); points = trace!!.snapshot()
                                    persist()?.await()
                                    status = "Start tracking. Align Forward to this recording's intended local map-up direction."
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { status = "Could not create or checkpoint recording. Check sign-in, connectivity and storage." }
                                finally { busy = false }
                            }
                        }) { Text("Create walking recording") }
                    }
                    if (recordingId != null && !submitted) {
                        if (!reviewing && !uploadAttempted) {
                            val recordMotion: (Double,Double) -> Unit = { length,heading ->
                                if (!busy && !reviewing && !saveError && !leave && !discard && !resume &&
                                    trace?.step(length,heading,maxOf(SystemClock.elapsedRealtime(),points.last().timeMs+1)) == true) {
                                    points = trace!!.snapshot(); persist()
                                    if (points.size >= 10000) { reviewing = true; status = "Sample limit reached. Review and submit this segment." }
                                }
                            }
                            val active = loaded && !busy && !leave && !discard && !resume && !saveError
                            if (cameraMode) CameraMappingControls(enabled = active, onStep = recordMotion,
                                onInterrupted = { message -> reviewing = true; persist(); status = message }, onRunningChanged = { running = it })
                            else NavigationMotionControls(enabled = active,onStep = recordMotion,onRunningChanged = { running = it })
                            OutlinedTextField(markerName,{ markerName = it.take(120) },label = { Text("Place or junction name") },enabled = !busy && !saveError,modifier = Modifier.fillMaxWidth())
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ROOM","ENTRANCE","JUNCTION","TURN","STAIRS","ELEVATOR").forEach { type ->
                                    FilterChip(selected = markerType == type,onClick = { markerType = type },label = { Text(type) })
                                }
                            }
                            OutlinedButton(enabled = !busy && !saveError && markerName.isNotBlank() && (!cameraMode || running),onClick = {
                                if (trace?.mark(markerType,markerName) == true) { points = trace!!.snapshot(); markerName = ""; persist() }
                            }) { Text("Mark current place") }
                            Button(enabled = !busy && points.size > 1,onClick = { reviewing = true; persist() }) { Text("Stop and review trace") }
                        } else {
                            Text("Review the relative trace. It requires alignment and explicit joins before publication.")
                            if (cameraMode) Text("Camera session ended. Submit this segment or start another; a new camera frame is not joined automatically.",style = MaterialTheme.typography.bodySmall)
                            if (!uploadAttempted && !cameraMode) OutlinedButton(enabled = !busy && !saveError,onClick = { resume = true }) { Text("Resume sensors at saved endpoint") }
                            Button(enabled = !busy && !saveError && points.size > 1,onClick = {
                                busy = true; uploadAttempted = true
                                val id = requireNotNull(recordingId); val snapshot = points.toList()
                                scope.launch {
                                    try {
                                        persist()?.await()
                                        snapshot.mapIndexed { index,p -> PathRecordingPoint(id,index,p.x.toFloat(),p.y.toFloat(),p.timeMs,p.headingDeg.toFloat(),p.marker) }
                                            .chunked(100).forEach { batch -> client.from("path_recording_points").upsert(batch) { onConflict = "recording_id,seq" } }
                                        source.submitRecording(id)
                                        submitted = true; persist()?.await()
                                        status = "Submitted and checkpointed. Import and review it in the draft workspace."
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { status = if (submitted) "Server submission succeeded; retry local save for its receipt." else "Upload incomplete. Saved geometry is frozen; retry sends the same point keys." }
                                    finally { busy = false }
                                }
                            }) { Text(if (busy) "Saving / uploading..." else if (uploadAttempted) "Retry submission" else "Submit recording") }
                        }
                    }
                    if (submitted) TextButton(onClick = { back() }) { Text("Done") }
                    if (recordingId != null || loadError) TextButton(enabled = !busy,onClick = { reviewing = true; discard = true }) { Text("Remove local copy / start another") }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Text(if (cameraMode) "Camera-derived relative route · not a wall/floor-plan reconstruction" else "Sensor relative estimate · assumed 0.70 m stride",style = MaterialTheme.typography.labelSmall)
            Text(if (running && !reviewing) "Tracking active; review geometry and direction alignment." else "Tracking paused or awaiting alignment.",style = MaterialTheme.typography.labelSmall)
            WalkingTracePreview(points,Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun WalkingTracePreview(points: List<WalkingSample>, modifier: Modifier = Modifier) {
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.pointerInput(Unit) { detectTransformGestures { _,delta,factor,_ -> zoom = (zoom*factor).coerceIn(0.5f,8f); pan += delta } }) {
        drawRect(Color(0xFFF3F6FA))
        if (points.isEmpty() || size.minDimension <= 0) return@Canvas
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val scale = min(size.width/(maxX-minX+6).toFloat(),size.height/(maxY-minY+6).toFloat())*zoom
        fun position(p: WalkingSample) = Offset(size.width/2+pan.x+(p.x-(minX+maxX)/2).toFloat()*scale,size.height/2+pan.y+(p.y-(minY+maxY)/2).toFloat()*scale)
        points.zipWithNext().forEach { (a,b) -> drawLine(Color(0xFF2563EB),position(a),position(b),5.dp.toPx(),StrokeCap.Round) }
        points.filter { it.marker != null }.forEach { drawCircle(Color(0xFFDC2626),5.dp.toPx(),position(it)) }
        drawCircle(Color.White,10.dp.toPx(),position(points.last())); drawCircle(Color(0xFF2563EB),7.dp.toPx(),position(points.last()))
    }
}
