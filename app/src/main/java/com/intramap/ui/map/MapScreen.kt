package com.intramap.ui.map

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.navigation.NavigationSession
import com.intramap.ui.navigation.ArPoseResult
import com.intramap.ui.navigation.FloorChangeControls
import com.intramap.ui.navigation.NavigationMotionControls
import com.intramap.ui.vision.LandmarkScannerScreen
import java.util.Locale
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun MapScreen(
    building: Building,
    initialFloorId: String,
    versionId: String? = null,
    pendingArPose: ArPoseResult.Pose? = null,
    onArPoseConsumed: () -> Unit = {},
    onOpenArPositioning: () -> Unit = {},
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val saver = remember(building) {
        Saver<NavigationSession, Bundle>(
            save = { it.snapshot() },
            restore = { bundle -> NavigationSession(building).apply { restore(bundle) } }
        )
    }
    val session = rememberSaveable(building.buildingId, saver = saver) { NavigationSession(building) }
    var floorId by rememberSaveable(building.buildingId) { mutableStateOf(initialFloorId) }
    var query by rememberSaveable(building.buildingId) { mutableStateOf("") }
    var pickingStart by remember { mutableStateOf(false) }
    var selectedStart by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var confirmFloor by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var following by rememberSaveable { mutableStateOf(true) }
    var recenter by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    val estimate = session.estimate
    val transfer = session.pendingFloorArrival
    val floorName = building.floors.firstOrNull { it.floorId == floorId }?.floorName ?: floorId
    val density = LocalDensity.current
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * 0.44f).dp
    val paused = scanning || pickingStart || details || confirmFloor || confirmExit

    LaunchedEffect(estimate?.floorId) { estimate?.let { floorId = it.floorId } }
    LaunchedEffect(transfer?.nodeId) { if (transfer == null) confirmFloor = false }
    LaunchedEffect(paused) { if (paused) session.pauseMotion() }

    LaunchedEffect(pendingArPose) {
        val pose = pendingArPose ?: return@LaunchedEffect
        if (session.pendingFloorArrival == null) {
            session.applyReferencePose(pose.floorId, pose.x, pose.y, pose.headingDeg)
        }
        onArPoseConsumed()
    }

    val spokenInstruction = remember(
        session.route?.nodeIds,
        session.nextIndex,
        session.arrived,
        transfer?.nodeId
    ) { session.instruction }
    MapVoiceGuidance(voice && session.route != null, spokenInstruction, paused)

    fun chooseStart() {
        session.pauseMotion()
        selectedStart = session.selectedStartId
        pickingStart = true
    }

    fun leave() {
        session.pauseMotion()
        if (transfer != null) confirmExit = true else onBack()
    }

    fun destination(node: LocationNode) {
        if (session.pendingFloorArrival != null) return
        session.pauseMotion()
        session.setDestination(node.nodeId)
        query = ""
        if (!session.ready) chooseStart()
    }

    BackHandler(enabled = !paused) { leave() }

    if (scanning) {
        val scannerBuilding = remember(building, estimate?.floorId) {
            building.copy(
                floors = building.floors.sortedBy {
                    if (it.floorId == estimate?.floorId) 0 else 1
                }
            )
        }
        LandmarkScannerScreen(
            building = scannerBuilding,
            onBack = { scanning = false },
            onUseObservation = {
                if (session.pendingFloorArrival == null) session.applyObservation(it)
                scanning = false
            }
        )
        return
    }

    if (pickingStart) {
        AlertDialog(
            onDismissRequest = { pickingStart = false },
            title = { Text("Confirm your actual location") },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text("Choose a landmark only if you are physically there. This initializes an estimate, not an automatic location fix.")
                    session.landmarks.sortedBy { if (it.floorId == floorId) 0 else 1 }.forEach { node ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedStart = node.nodeId }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selectedStart == node.nodeId, onClick = { selectedStart = node.nodeId })
                            Column {
                                Text(node.name)
                                Text(building.floors.firstOrNull { it.floorId == node.floorId }?.floorName ?: node.floorId)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedStart != null && session.errors.isEmpty() && transfer == null,
                    onClick = {
                        selectedStart?.let {
                            session.chooseStart(it)
                            session.confirmStart()
                        }
                        pickingStart = false
                        following = true
                        recenter++
                    }
                ) { Text("I am here") }
            },
            dismissButton = { TextButton(onClick = { pickingStart = false }) { Text("Cancel") } }
        )
    }

    if (confirmFloor && transfer != null) {
        val targetName = building.floors.firstOrNull { it.floorId == transfer.floorId }?.floorName ?: transfer.floorId
        AlertDialog(
            onDismissRequest = { confirmFloor = false },
            title = { Text("Arrived on $targetName?") },
            text = {
                Text("Confirm only after physically reaching the mapped ${transfer.type.lowercase()} landing. Position will be reinitialized there.")
            },
            confirmButton = {
                TextButton(onClick = {
                    session.confirmTransition()
                    confirmFloor = false
                    following = true
                    recenter++
                }) { Text("Confirm arrival") }
            },
            dismissButton = { TextButton(onClick = { confirmFloor = false }) { Text("Not yet") } }
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Leave navigation?") },
            text = { Text("A floor change is pending. Leaving cancels it without confirming your arrival.") },
            confirmButton = {
                TextButton(onClick = {
                    session.cancelFloorTransition()
                    confirmExit = false
                    onBack()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } }
        )
    }

    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("Navigation session") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("Sensors running: $running")
                    Text("Floor: ${estimate?.floorId ?: "Unknown"}")
                    estimate?.let {
                        Text("Estimated position: %.1f, %.1f m\nSpread: %.1f m".format(it.x, it.y, it.spreadM))
                    }
                    Text("Destination: ${session.destination?.name ?: "None"}")
                    Text("Route nodes: ${session.routeNodes.size}; next index: ${session.nextIndex}")
                    Text(session.status)
                    session.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } }
        )
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        InteractiveIndoorMap(
            building = building,
            floorId = floorId,
            route = session.routeNodes,
            destinationId = session.destinationId,
            position = estimate?.takeIf { it.floorId == floorId }?.let {
                Offset(it.x.toFloat(), it.y.toFloat())
            },
            spreadM = estimate?.spreadM,
            blocked = session.blocked,
            follow = following,
            recenterToken = recenter,
            topInsetPx = topHeight.toFloat(),
            bottomInsetPx = bottomHeight.toFloat(),
            onGesture = { following = false },
            onPlace = { destination(it) },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { topHeight = it.height }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 6.dp) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { leave() }) { Text("Back") }
                        Column(Modifier.weight(1f)) {
                            Text(building.buildingName, style = MaterialTheme.typography.titleMedium)
                            Text(floorName, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    OutlinedTextField(
                        query,
                        { query = it },
                        enabled = transfer == null,
                        singleLine = true,
                        label = { Text("Where do you want to go?") },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (query.isNotBlank() && transfer == null) {
                        val text = query.trim()
                        val results = session.landmarks.filter {
                            it.name.contains(text, true) || it.keywords.any { word -> word.contains(text, true) }
                        }.take(5)
                        Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            if (results.isEmpty()) Text("No mapped place found.", Modifier.padding(8.dp))
                            results.forEach { node ->
                                ListItem(
                                    headlineContent = { Text(node.name) },
                                    supportingContent = {
                                        Text(building.floors.firstOrNull { it.floorId == node.floorId }?.floorName ?: node.floorId)
                                    },
                                    modifier = Modifier.clickable { destination(node) }
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                building.floors.forEach { floor ->
                    FilterChip(
                        selected = floorId == floor.floorId,
                        onClick = { floorId = floor.floorId; following = false },
                        label = { Text(floor.floorName) }
                    )
                }
            }
        }

        SmallFloatingActionButton(
            onClick = {
                estimate?.let { floorId = it.floorId }
                following = true
                recenter++
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                end = 16.dp,
                bottom = with(density) { bottomHeight.toDp() } + 12.dp
            )
        ) { Text("Recenter", Modifier.padding(horizontal = 10.dp)) }

        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomHeight = it.height },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 10.dp,
            tonalElevation = 3.dp
        ) {
            Column(
                Modifier.heightIn(max = maxCardHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    when {
                        session.errors.isNotEmpty() -> "Map needs correction"
                        !session.ready -> "Set your starting location"
                        session.arrived -> "You have arrived"
                        transfer != null -> session.instruction
                        session.destination == null -> "Search for a destination"
                        else -> session.instruction
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                val next = session.nextNode
                if (estimate != null && next != null && next.floorId == estimate.floorId && !session.arrived) {
                    Text(
                        "Approximately %.1f m to next waypoint".format(hypot(next.x - estimate.x, next.y - estimate.y)),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Text(
                    if (session.ready) "Estimated position · scan a mapped sign if uncertain" else session.status,
                    style = MaterialTheme.typography.bodySmall
                )
                if (session.ready) Text(session.status, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (!session.ready) {
                    Button(
                        enabled = session.errors.isEmpty() && transfer == null,
                        onClick = { chooseStart() }
                    ) { Text("Confirm my location") }
                }
                if (session.ready && (options || transfer != null || (next != null && next.floorId != estimate?.floorId))) {
                    FloorChangeControls(
                        session,
                        onConfirmArrival = { session.pauseMotion(); confirmFloor = true },
                        onCorrectLocation = { if (transfer == null) chooseStart() }
                    )
                }
                if (session.ready && session.route != null && !session.arrived && transfer == null) {
                    key(session.motionEpoch) {
                        NavigationMotionControls(
                            enabled = !paused && session.transitionTarget == null,
                            onStep = session::walk,
                            onRunningChanged = { running = it }
                        )
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = session.stepFree,
                        enabled = transfer == null,
                        onClick = { session.updateAccessibilityPreference(!session.stepFree) },
                        label = { Text("Step-free") }
                    )
                    FilterChip(
                        selected = voice,
                        onClick = { voice = !voice },
                        label = { Text("Voice") }
                    )
                    TextButton(onClick = { options = !options }) {
                        Text(if (options) "Less" else "Options")
                    }
                }
                if (options) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(
                            enabled = versionId != null && transfer == null,
                            onClick = {
                                session.pauseMotion()
                                onOpenArPositioning()
                            }
                        ) { Text("AR position") }
                        TextButton(
                            enabled = session.ready && transfer == null,
                            onClick = { session.pauseMotion(); scanning = true }
                        ) { Text("Scan sign") }
                        TextButton(
                            enabled = transfer == null,
                            onClick = { chooseStart() }
                        ) { Text("Change start") }
                        TextButton(onClick = { session.pauseMotion(); details = true }) { Text("Session details") }
                        TextButton(
                            enabled = session.route != null && !session.arrived && transfer == null,
                            onClick = {
                                session.pauseMotion()
                                session.reportBlocked()
                            }
                        ) { Text("Blocked path") }
                    }
                }
                if (session.errors.isNotEmpty()) {
                    session.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun MapVoiceGuidance(enabled: Boolean, instruction: String, paused: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var ready by remember { mutableStateOf(false) }
    var speech by remember { mutableStateOf<TextToSpeech?>(null) }
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val currentSpeech by rememberUpdatedState(speech)

    DisposableEffect(context) {
        var disposed = false
        val tts = TextToSpeech(context.applicationContext) { result ->
            if (!disposed) ready = result == TextToSpeech.SUCCESS
        }
        speech = tts
        onDispose {
            disposed = true
            tts.stop()
            tts.shutdown()
        }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) currentSpeech?.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(enabled, instruction, paused, resumed, ready) {
        val tts = speech
        if (enabled && !paused && resumed && ready && tts != null) {
            val language = tts.setLanguage(Locale.getDefault())
            if (language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.speak(instruction, TextToSpeech.QUEUE_FLUSH, null, "intramap-guidance")
            }
        } else tts?.stop()
    }
}
