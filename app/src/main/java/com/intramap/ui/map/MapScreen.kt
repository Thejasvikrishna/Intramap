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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.domain.search.MapDestination
import com.intramap.domain.search.destinationCatalog
import com.intramap.navigation.NavigationSession
import com.intramap.ui.navigation.ArPoseResult
import com.intramap.ui.navigation.FloorChangeControls
import com.intramap.ui.navigation.NavigationMotionControls
import com.intramap.ui.vision.LandmarkScannerScreen
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    building: Building,initialFloorId: String,versionId: String? = null,
    pendingArPose: ArPoseResult.Pose? = null,onArPoseConsumed: () -> Unit = {},
    onOpenArPositioning: () -> Unit = {},onBack: () -> Unit,onOpenDiagnostics: () -> Unit = {}
) {
    val saver = remember(building) { Saver<NavigationSession,Bundle>(save = { it.snapshot() },restore = { saved -> NavigationSession(building).apply { restore(saved) } }) }
    val session = rememberSaveable(building.buildingId,saver = saver) { NavigationSession(building) }
    val catalogResult = remember(building) { runCatching { building.destinationCatalog() } }
    val catalog = catalogResult.getOrDefault(emptyList())
    val accent = MaterialTheme.colorScheme.primary
    var floorId by rememberSaveable(building.buildingId) { mutableStateOf(initialFloorId) }
    var query by rememberSaveable(building.buildingId) { mutableStateOf("") }
    var previewId by rememberSaveable(building.buildingId) { mutableStateOf<String?>(null) }
    var routeSelectionId by rememberSaveable(building.buildingId) { mutableStateOf<String?>(null) }
    var pointChoicesId by remember { mutableStateOf<String?>(null) }
    var pickingStart by remember { mutableStateOf(false) }
    var startSelectionId by remember { mutableStateOf<String?>(null) }
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
    val previewEntry = catalog.firstOrNull { it.selectionId == previewId }
    val preview = previewEntry?.previewNode(building) ?: building.nodes.firstOrNull { it.nodeId == previewId || "node:${it.nodeId}" == previewId }
    val routeEntry = catalog.firstOrNull { it.selectionId == routeSelectionId && it.anchorNodeId == session.destinationId }
    val routeDestination = routeEntry?.previewNode(building) ?: session.destination
    val selected = preview ?: routeDestination
    val floorName = building.floors.firstOrNull { it.floorId == floorId }?.floorName.orEmpty()
    val density = LocalDensity.current
    val paused = pickingStart || scanning || options || confirmFloor || confirmExit || pointChoicesId != null
    val maxSheet = (LocalConfiguration.current.screenHeightDp * if (expanded) 0.60f else 0.36f).dp
    val guidance = if (session.arrived && routeEntry?.independentPlaceId != null) "Estimated arrival near ${routeEntry.name}. Verify the entrance or sign." else session.instruction
    fun floorLabel(id: String) = building.floors.firstOrNull { it.floorId == id }?.floorName ?: id
    fun chooseStart() {
        session.pauseMotion()
        startSelectionId = catalog.firstOrNull { it.anchorNodeId == session.selectedStartId }?.selectionId
        pickingStart = true
    }
    fun leave() { session.pauseMotion(); if (transfer != null) confirmExit = true else onBack() }
    fun select(entry: MapDestination) {
        if (transfer != null) return
        previewId = entry.selectionId; floorId = entry.floorId; following = false; query = ""; pointChoicesId = null
    }
    fun selectPoint(node: LocationNode) {
        if (transfer != null) return
        val choices = catalog.filter { it.anchorNodeId == node.nodeId }
        when {
            choices.size > 1 -> { session.pauseMotion(); pointChoicesId = node.nodeId }
            choices.size == 1 -> select(choices.single())
            else -> { previewId = "node:${node.nodeId}"; floorId = node.floorId; following = false; query = "" }
        }
    }
    LaunchedEffect(estimate?.floorId) { estimate?.let { floorId = it.floorId } }
    LaunchedEffect(paused) { if (paused) session.pauseMotion() }
    LaunchedEffect(pendingArPose) {
        pendingArPose?.let {
            if (session.pendingFloorArrival == null) session.applyReferencePose(it.floorId,it.x,it.y,it.headingDeg)
            onArPoseConsumed()
        }
    }
    LaunchedEffect(transfer?.nodeId) { if (transfer == null) confirmFloor = false }
    MapVoiceGuidance(voice && session.route != null,guidance,paused)
    BackHandler(enabled = !scanning && !pickingStart && !options && !confirmExit && !confirmFloor && pointChoicesId == null) {
        when { query.isNotEmpty() -> query = ""; previewId != null -> previewId = null; expanded -> expanded = false; else -> leave() }
    }
    if (scanning) {
        LandmarkScannerScreen(building = building,onBack = { scanning = false },onUseObservation = {
            if (session.pendingFloorArrival == null) session.applyObservation(it)
            scanning = false
        })
        return
    }
    if (pointChoicesId != null) AlertDialog(onDismissRequest = { pointChoicesId = null },title = { Text("Choose a place") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("These places share a mapped route access point.",style = MaterialTheme.typography.bodySmall)
            catalog.filter { it.anchorNodeId == pointChoicesId }.forEach { entry ->
                ListItem(headlineContent = { Text(entry.name) },supportingContent = { Text("${entry.category.lowercase()} · ${floorLabel(entry.floorId)}") },modifier = Modifier.clickable(enabled = transfer == null) { select(entry) })
            }
        } },confirmButton = { TextButton(onClick = { pointChoicesId = null }) { Text("Close") } })
    if (pickingStart) AlertDialog(onDismissRequest = { pickingStart = false },title = { Text("Where are you standing?") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("Confirm only when physically at the mapped access point. This sets a starting estimate, not an automatic location fix.")
            catalog.sortedBy { if (it.floorId == floorId) 0 else 1 }.forEach { entry ->
                Row(Modifier.fillMaxWidth().clickable { startSelectionId = entry.selectionId }.padding(vertical = 4.dp),verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(startSelectionId == entry.selectionId,{ startSelectionId = entry.selectionId })
                    Column { Text(entry.name); Text(floorLabel(entry.floorId),style = MaterialTheme.typography.bodySmall) }
                }
            }
        } },confirmButton = { TextButton(enabled = catalog.any { it.selectionId == startSelectionId } && session.errors.isEmpty() && transfer == null,onClick = {
            catalog.firstOrNull { it.selectionId == startSelectionId }?.let { session.chooseStart(it.anchorNodeId); session.confirmStart() }
            pickingStart = false; following = true; recenter++
        }) { Text("I am here") } },dismissButton = { TextButton(onClick = { pickingStart = false }) { Text("Cancel") } })
    if (confirmFloor && transfer != null) AlertDialog(onDismissRequest = { confirmFloor = false },title = { Text("Confirm floor arrival") },
        text = { Text("Confirm only after physically reaching ${transfer.name} on the destination floor. Tracking restarts there.") },
        confirmButton = { TextButton(onClick = { session.confirmTransition(); confirmFloor = false; following = true; recenter++ }) { Text("I have arrived") } },
        dismissButton = { TextButton(onClick = { confirmFloor = false }) { Text("Not yet") } })
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },title = { Text("Leave navigation?") },
        text = { Text("A floor change is pending. Leaving cancels it without confirming your arrival.") },
        confirmButton = { TextButton(onClick = { session.cancelFloorTransition(); confirmExit = false; onBack() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } })
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Navigation tools",style = MaterialTheme.typography.headlineSmall)
            Text(session.status,style = MaterialTheme.typography.bodySmall)
            Text("Movement sensors: ${if (running) "running" else "paused"}",style = MaterialTheme.typography.bodySmall)
            estimate?.let { Text("Position spread: %.1f m".format(it.spreadM),style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = versionId != null && transfer == null,onClick = { options = false; onOpenArPositioning() }) { Text("Scan AR reference for position") }
            TextButton(enabled = session.ready && transfer == null,onClick = { options = false; scanning = true }) { Text("Scan a mapped sign") }
            TextButton(enabled = transfer == null,onClick = { options = false; chooseStart() }) { Text("Correct starting location") }
            TextButton(enabled = session.route != null && !session.arrived && transfer == null,onClick = { session.reportBlocked(); options = false }) { Text("Report next path blocked and reroute") }
            TextButton(enabled = session.blocked.isNotEmpty(),onClick = { session.clearBlocked(); options = false }) { Text("Clear local blocked paths") }
            TextButton(onClick = { options = false; onOpenDiagnostics() }) { Text("Diagnostics") }
            Spacer(Modifier.height(16.dp))
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        InteractiveIndoorMap(building,floorId,session.routeNodes,selected?.nodeId,
            estimate?.takeIf { it.floorId == floorId }?.let { Offset(it.x.toFloat(),it.y.toFloat()) },
            estimate?.spreadM,session.blocked,following,recenter,topHeight.toFloat(),bottomHeight.toFloat(),
            onGesture = { following = false },onPlace = { selectPoint(it) },modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topHeight = it.height }.padding(12.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(32.dp),shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp,vertical = 2.dp),verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { leave() },contentPadding = PaddingValues(8.dp)) { Text("Back",color = accent) }
                    TextField(query,{ query = it },enabled = transfer == null,placeholder = { Text("Search rooms, shops and places") },singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,unfocusedContainerColor = Color.Transparent,disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,unfocusedIndicatorColor = Color.Transparent,disabledIndicatorColor = Color.Transparent),modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (query.isNotBlank()) query = "" else options = true },contentPadding = PaddingValues(8.dp)) { Text(if (query.isNotBlank()) "Clear" else "Tools",color = accent) }
                }
            }
            if (query.isNotBlank() && transfer == null) Surface(shape = RoundedCornerShape(20.dp),shadowElevation = 6.dp) {
                val term = query.trim()
                val results = catalog.filter { it.name.contains(term,true) || it.keywords.any { word -> word.contains(term,true) } }.take(30)
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    if (results.isEmpty()) Text("No mapped places match your search.",Modifier.padding(20.dp))
                    results.forEach { entry -> key(entry.selectionId) {
                        ListItem(headlineContent = { Text(entry.name) },supportingContent = { Text("${entry.category.lowercase()} · ${floorLabel(entry.floorId)}") },modifier = Modifier.clickable { select(entry) })
                    } }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = { leave() },label = { Text(building.buildingName) },colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surface))
                FilterChip(selected = session.stepFree,enabled = transfer == null,onClick = { session.updateAccessibilityPreference(!session.stepFree) },label = { Text("Step-free") },
                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface,selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
            }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).heightIn(max = 200.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(6.dp)) {
            building.floors.sortedByDescending { it.floorNumber }.forEach { floor ->
                Surface(onClick = { floorId = floor.floorId; following = false },shape = RoundedCornerShape(16.dp),shadowElevation = 3.dp,
                    color = if (floor.floorId == floorId) accent else MaterialTheme.colorScheme.surface,
                    contentColor = if (floor.floorId == floorId) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface) {
                    Text(floor.floorName,Modifier.widthIn(min = 48.dp,max = 112.dp).padding(12.dp),style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        SmallFloatingActionButton(onClick = { if (estimate == null) chooseStart() else { floorId = estimate.floorId; following = true; recenter++ } },
            containerColor = MaterialTheme.colorScheme.surface,contentColor = accent,shape = CircleShape,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp,bottom = with(density) { bottomHeight.toDp() } + 12.dp)) {
            Text(if (estimate == null) "Locate" else "Recenter",Modifier.padding(horizontal = 12.dp))
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomHeight = it.height },shape = RoundedCornerShape(topStart = 28.dp,topEnd = 28.dp),shadowElevation = 12.dp) {
            Column(Modifier.heightIn(max = maxSheet).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { expanded = !expanded },modifier = Modifier.align(Alignment.CenterHorizontally).height(40.dp)) { Text(if (expanded) "Less" else "Details",color = accent,style = MaterialTheme.typography.labelMedium) }
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(when {
                            session.errors.isNotEmpty() -> "Map needs correction"
                            preview != null -> preview.name
                            session.arrived -> "Estimated arrival"
                            routeDestination != null -> routeDestination.name
                            else -> "Explore ${building.buildingName}"
                        },style = MaterialTheme.typography.headlineSmall,fontWeight = FontWeight.SemiBold)
                        Text(if (preview != null) floorLabel(preview.floorId) else if (routeDestination != null) floorLabel(routeDestination.floorId) else floorName,
                            style = MaterialTheme.typography.bodyMedium,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (preview != null) TextButton(onClick = { previewId = null }) { Text("Close",color = accent) }
                }
                if (catalogResult.isFailure) Text("Place metadata needs correction. Independent destination search is unavailable.",color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall)
                if (preview != null) {
                    preview.imageUrl?.let { url ->
                        AsyncImage(model = url,contentDescription = "Reference photo at the mapped route point",contentScale = ContentScale.Crop,modifier = Modifier.fillMaxWidth().height(170.dp))
                        if (previewEntry?.independentPlaceId != null) Text("Shared route-point reference photo",style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Mapped ${preview.type.lowercase().replace('_',' ')}",style = MaterialTheme.typography.bodySmall)
                    if (previewEntry?.independentPlaceId != null) Text("Directions end at this place's mapped access point. Verify its entrance or sign.",style = MaterialTheme.typography.bodySmall)
                    Button(enabled = transfer == null && session.errors.isEmpty(),shape = RoundedCornerShape(24.dp),modifier = Modifier.fillMaxWidth(),onClick = {
                        session.pauseMotion(); routeSelectionId = previewEntry?.selectionId; session.setDestination(preview.nodeId); previewId = null
                        if (!session.ready) chooseStart()
                    }) { Text("Directions") }
                } else if (session.route != null) {
                    if (routeEntry?.independentPlaceId != null) Text("Mapped access point for ${routeEntry.name}",style = MaterialTheme.typography.labelLarge)
                    Surface(shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.primaryContainer,contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(guidance,Modifier.fillMaxWidth().padding(14.dp),style = MaterialTheme.typography.titleMedium)
                    }
                    Text("%.0f m mapped walking distance · transfers excluded".format(session.route!!.totalDistance),style = MaterialTheme.typography.bodySmall)
                } else Text(if (session.ready) "Search for a room or shop, or tap a mapped place." else "Confirm your position or scan an AR reference to get directions.",style = MaterialTheme.typography.bodyMedium)
                if (!session.ready && preview == null) Button(enabled = session.errors.isEmpty() && transfer == null,onClick = { chooseStart() },modifier = Modifier.fillMaxWidth()) { Text("Set my location") }
                if (session.ready && (expanded || transfer != null || session.transitionTarget != null)) FloorChangeControls(session,
                    onConfirmArrival = { session.pauseMotion(); confirmFloor = true },onCorrectLocation = { if (transfer == null) chooseStart() })
                if (session.ready && session.route != null && !session.arrived && transfer == null) key(session.motionEpoch) {
                    NavigationMotionControls(enabled = !paused && session.transitionTarget == null,onStep = session::walk,onRunningChanged = { running = it })
                }
                if (expanded) {
                    Text(if (session.ready) "Estimated position, not a verified live fix. Scan a mapped reference if uncertain." else session.status,style = MaterialTheme.typography.bodySmall)
                    Text(session.status,style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = voice,onClick = { voice = !voice },label = { Text("Voice guidance") })
                        TextButton(onClick = { options = true }) { Text("Navigation tools",color = accent) }
                    }
                }
                session.errors.forEach { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun MapVoiceGuidance(enabled: Boolean,instruction: String,paused: Boolean) {
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
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); if (!resumed) currentSpeech?.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled,instruction,paused,resumed,ready) {
        val tts = speech
        if (enabled && !paused && resumed && ready && tts != null) {
            val language = tts.setLanguage(Locale.getDefault())
            if (language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED) tts.speak(instruction,TextToSpeech.QUEUE_FLUSH,null,"intramap-guidance")
        } else tts?.stop()
    }
}
