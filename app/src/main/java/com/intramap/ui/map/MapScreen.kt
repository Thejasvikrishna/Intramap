package com.intramap.ui.map

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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

private val MapsBlue = Color(0xFF1967D2)

@OptIn(ExperimentalMaterial3Api::class)
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
        Saver<NavigationSession, Bundle>(save = { it.snapshot() }, restore = { saved ->
            NavigationSession(building).apply { restore(saved) }
        })
    }
    val session = rememberSaveable(building.buildingId, saver = saver) { NavigationSession(building) }
    var floorId by rememberSaveable(building.buildingId) { mutableStateOf(initialFloorId) }
    var query by rememberSaveable(building.buildingId) { mutableStateOf("") }
    var previewId by rememberSaveable(building.buildingId) { mutableStateOf<String?>(null) }
    var pickingStart by remember { mutableStateOf(false) }
    var startId by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(building.buildingId) { mutableStateOf(false) }
    var confirmFloor by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var voice by rememberSaveable { mutableStateOf(false) }
    var following by rememberSaveable { mutableStateOf(true) }
    var recenter by remember { mutableIntStateOf(0) }
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    val estimate = session.estimate
    val transfer = session.pendingFloorArrival
    val preview = building.nodes.firstOrNull { it.nodeId == previewId }
    val selected = preview ?: session.destination
    val floorName = building.floors.firstOrNull { it.floorId == floorId }?.floorName.orEmpty()
    val density = LocalDensity.current
    val paused = pickingStart || scanning || options || confirmFloor || confirmExit
    val maxSheet = (LocalConfiguration.current.screenHeightDp * if (expanded) 0.60f else 0.36f).dp

    fun chooseStart() {
        session.pauseMotion()
        startId = session.selectedStartId
        pickingStart = true
    }
    fun leave() {
        session.pauseMotion()
        if (transfer != null) confirmExit = true else onBack()
    }
    fun select(node: LocationNode) {
        if (transfer != null) return
        previewId = node.nodeId
        floorId = node.floorId
        following = false
        query = ""
    }

    LaunchedEffect(estimate?.floorId) { estimate?.let { floorId = it.floorId } }
    LaunchedEffect(paused) { if (paused) session.pauseMotion() }
    LaunchedEffect(pendingArPose) {
        pendingArPose?.let {
            if (session.pendingFloorArrival == null) session.applyReferencePose(it.floorId, it.x, it.y, it.headingDeg)
            onArPoseConsumed()
        }
    }
    LaunchedEffect(transfer?.nodeId) { if (transfer == null) confirmFloor = false }
    MapVoiceGuidance(voice && session.route != null, session.instruction, paused)
    BackHandler(enabled = !scanning && !pickingStart && !options && !confirmExit && !confirmFloor) {
        when {
            query.isNotEmpty() -> query = ""
            previewId != null -> previewId = null
            expanded -> expanded = false
            else -> leave()
        }
    }

    if (scanning) {
        LandmarkScannerScreen(building = building, onBack = { scanning = false }, onUseObservation = {
            if (session.pendingFloorArrival == null) session.applyObservation(it)
            scanning = false
        })
        return
    }
    if (pickingStart) AlertDialog(
        onDismissRequest = { pickingStart = false },
        title = { Text("Where are you standing?") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text("Select a landmark only when physically there. This confirms a starting estimate, not an automatic location fix.")
                session.landmarks.sortedBy { if (it.floorId == floorId) 0 else 1 }.forEach { node ->
                    Row(Modifier.fillMaxWidth().clickable { startId = node.nodeId }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(startId == node.nodeId, { startId = node.nodeId })
                        Column {
                            Text(node.name)
                            Text(building.floors.firstOrNull { it.floorId == node.floorId }?.floorName.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = startId != null && session.errors.isEmpty() && transfer == null, onClick = {
            startId?.let { session.chooseStart(it); session.confirmStart() }
            pickingStart = false; following = true; recenter++
        }) { Text("I am here") } },
        dismissButton = { TextButton(onClick = { pickingStart = false }) { Text("Cancel") } }
    )
    if (confirmFloor && transfer != null) AlertDialog(
        onDismissRequest = { confirmFloor = false }, title = { Text("Confirm floor arrival") },
        text = { Text("Confirm only after physically reaching ${transfer.name} on the destination floor. Tracking will restart there.") },
        confirmButton = { TextButton(onClick = { session.confirmTransition(); confirmFloor = false; following = true; recenter++ }) { Text("I have arrived") } },
        dismissButton = { TextButton(onClick = { confirmFloor = false }) { Text("Not yet") } }
    )
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false }, title = { Text("Leave navigation?") },
        text = { Text("A floor change is pending. Leaving cancels it without confirming your arrival.") },
        confirmButton = { TextButton(onClick = { session.cancelFloorTransition(); confirmExit = false; onBack() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } }
    )
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Navigation tools", style = MaterialTheme.typography.headlineSmall)
            Text(session.status, style = MaterialTheme.typography.bodySmall)
            Text("Movement sensors: ${if (running) "running" else "paused"}", style = MaterialTheme.typography.bodySmall)
            estimate?.let { Text("Position spread: %.1f m".format(it.spreadM), style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = versionId != null && transfer == null, onClick = { options = false; onOpenArPositioning() }) { Text("Scan AR reference for position") }
            TextButton(enabled = session.ready && transfer == null, onClick = { options = false; scanning = true }) { Text("Scan a mapped sign") }
            TextButton(enabled = transfer == null, onClick = { options = false; chooseStart() }) { Text("Correct starting location") }
            TextButton(enabled = session.route != null && !session.arrived && transfer == null, onClick = { session.reportBlocked(); options = false }) { Text("Report next path blocked and reroute") }
            TextButton(enabled = session.blocked.isNotEmpty(), onClick = { session.clearBlocked(); options = false }) { Text("Clear local blocked paths") }
            TextButton(onClick = { options = false; onOpenDiagnostics() }) { Text("Diagnostics") }
            Spacer(Modifier.height(16.dp))
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        InteractiveIndoorMap(
            building = building, floorId = floorId, route = session.routeNodes,
            destinationId = selected?.nodeId,
            position = estimate?.takeIf { it.floorId == floorId }?.let { Offset(it.x.toFloat(), it.y.toFloat()) },
            spreadM = estimate?.spreadM, blocked = session.blocked, follow = following,
            recenterToken = recenter, topInsetPx = topHeight.toFloat(), bottomInsetPx = bottomHeight.toFloat(),
            onGesture = { following = false }, onPlace = { select(it) }, modifier = Modifier.fillMaxSize()
        )
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { leave() }, contentPadding = PaddingValues(8.dp)) { Text("Back", color = MapsBlue) }
                    TextField(value = query, onValueChange = { query = it }, enabled = transfer == null,
                        placeholder = { Text("Search this building") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (query.isNotBlank()) query = "" else options = true }, contentPadding = PaddingValues(8.dp)) {
                        Text(if (query.isNotBlank()) "Clear" else "Tools", color = MapsBlue)
                    }
                }
            }
            if (query.isNotBlank() && transfer == null) Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp) {
                val term = query.trim()
                val results = session.landmarks.filter { it.name.contains(term, true) || it.keywords.any { word -> word.contains(term, true) } }.take(12)
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    if (results.isEmpty()) Text("No mapped places match your search.", Modifier.padding(20.dp))
                    results.forEach { node ->
                        ListItem(headlineContent = { Text(node.name) }, supportingContent = {
                            Text(building.floors.firstOrNull { it.floorId == node.floorId }?.floorName.orEmpty())
                        }, modifier = Modifier.clickable { select(node) })
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { leave() }, label = { Text(building.buildingName) }, colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surface))
                FilterChip(selected = session.stepFree, enabled = transfer == null, onClick = { session.updateAccessibilityPreference(!session.stepFree) }, label = { Text("Step-free") },
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface, selectedContainerColor = Color(0xFFD3E3FD), selectedLabelColor = Color(0xFF174EA6)))
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            building.floors.reversed().forEach { floor ->
                Surface(onClick = { floorId = floor.floorId; following = false }, shape = RoundedCornerShape(16.dp), shadowElevation = 3.dp,
                    color = if (floor.floorId == floorId) MapsBlue else MaterialTheme.colorScheme.surface,
                    contentColor = if (floor.floorId == floorId) Color.White else MaterialTheme.colorScheme.onSurface) {
                    Text(floor.floorName, Modifier.widthIn(min = 48.dp, max = 112.dp).padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        SmallFloatingActionButton(onClick = {
            if (estimate == null) chooseStart() else { floorId = estimate.floorId; following = true; recenter++ }
        }, containerColor = MaterialTheme.colorScheme.surface, contentColor = MapsBlue, shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = with(density) { bottomHeight.toDp() } + 12.dp)) {
            Text(if (estimate == null) "Locate" else "Recenter", Modifier.padding(horizontal = 12.dp))
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = maxSheet).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.CenterHorizontally).height(40.dp)) {
                    Text(if (expanded) "Less" else "Details", color = MapsBlue, style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(when {
                            session.errors.isNotEmpty() -> "Map needs correction"
                            preview != null -> preview.name
                            session.arrived -> "You have arrived"
                            session.destination != null -> session.destination!!.name
                            else -> "Explore ${building.buildingName}"
                        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (preview != null) building.floors.firstOrNull { it.floorId == preview.floorId }?.floorName.orEmpty() else floorName,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (preview != null) TextButton(onClick = { previewId = null }) { Text("Close", color = MapsBlue) }
                }
                if (preview != null) {
                    Text("Mapped ${preview.type.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = transfer == null && session.errors.isEmpty(), colors = ButtonDefaults.buttonColors(containerColor = MapsBlue),
                        shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth(), onClick = {
                            session.pauseMotion(); session.setDestination(preview.nodeId); previewId = null
                            if (!session.ready) chooseStart()
                        }) { Text("Directions") }
                } else if (session.route != null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFE8F0FE), contentColor = Color(0xFF174EA6)) {
                        Text(session.instruction, Modifier.fillMaxWidth().padding(14.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    Text("%.0f m mapped walking distance · transfers excluded".format(session.route!!.totalDistance), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(if (session.ready) "Search for a room or tap a place on the map." else "Confirm your position or scan an AR reference to get directions.", style = MaterialTheme.typography.bodyMedium)
                }
                if (!session.ready && preview == null) Button(enabled = session.errors.isEmpty() && transfer == null,
                    onClick = { chooseStart() }, colors = ButtonDefaults.buttonColors(containerColor = MapsBlue), modifier = Modifier.fillMaxWidth()) { Text("Set my location") }
                if (session.ready && (expanded || transfer != null || session.transitionTarget != null)) FloorChangeControls(session,
                    onConfirmArrival = { session.pauseMotion(); confirmFloor = true }, onCorrectLocation = { if (transfer == null) chooseStart() })
                if (session.ready && session.route != null && !session.arrived && transfer == null) key(session.motionEpoch) {
                    NavigationMotionControls(enabled = !paused && session.transitionTarget == null, onStep = session::walk, onRunningChanged = { running = it })
                }
                if (expanded) {
                    Text(if (session.ready) "Estimated position, not a verified live fix. Scan a mapped reference if uncertain." else session.status, style = MaterialTheme.typography.bodySmall)
                    Text(session.status, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = voice, onClick = { voice = !voice }, label = { Text("Voice guidance") })
                        TextButton(onClick = { options = true }) { Text("Navigation tools", color = MapsBlue) }
                    }
                }
                session.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val currentSpeech by rememberUpdatedState(speech)
    DisposableEffect(context) {
        var disposed = false
        val tts = TextToSpeech(context.applicationContext) { result -> if (!disposed) ready = result == TextToSpeech.SUCCESS }
        speech = tts
        onDispose { disposed = true; tts.stop(); tts.shutdown() }
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
            if (language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED)
                tts.speak(instruction, TextToSpeech.QUEUE_FLUSH, null, "intramap-guidance")
        } else tts?.stop()
    }
}
