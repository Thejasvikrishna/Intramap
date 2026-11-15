package com.intramap.ui.map

import android.graphics.Paint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.intramap.data.model.Building
import com.intramap.navigation.NavigationSession
import com.intramap.ui.navigation.FloorChangeControls
import com.intramap.ui.navigation.NavigationMotionControls
import com.intramap.ui.vision.LandmarkScannerScreen
import java.util.Locale
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun MapScreen(
    building: Building,
    initialFloorId: String,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {}
) {
    val saver = remember(building) {
        Saver<NavigationSession, Bundle>(
            save = { it.snapshot() },
            restore = { saved ->
                NavigationSession(building).apply {
                    restore(saved)
                }
            }
        )
    }

    val session = rememberSaveable(
        building.buildingId,
        saver = saver
    ) {
        NavigationSession(building)
    }

    var floorId by rememberSaveable(building.buildingId) {
        mutableStateOf(initialFloorId)
    }

    var query by rememberSaveable {
        mutableStateOf("")
    }

    var pickingStart by remember {
        mutableStateOf(false)
    }

    var scanning by remember {
        mutableStateOf(false)
    }

    var diagnostics by remember {
        mutableStateOf(false)
    }

    var confirmFloor by remember {
        mutableStateOf(false)
    }

    var voice by rememberSaveable {
        mutableStateOf(false)
    }

    var running by remember {
        mutableStateOf(false)
    }

    val currentFloor = session.estimate?.floorId
    val transferInProgress = session.pendingFloorArrival != null

    LaunchedEffect(currentFloor) {
        currentFloor?.let {
            floorId = it
        }
    }

    // A stale arrival dialog must not remain open after its transfer ends.
    LaunchedEffect(transferInProgress) {
        if (!transferInProgress) {
            confirmFloor = false
        }
    }

    val floorName = building.floors
        .firstOrNull { it.floorId == floorId }
        ?.floorName
        ?: floorId

    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var speech by remember {
        mutableStateOf<TextToSpeech?>(null)
    }

    val currentSpeech by rememberUpdatedState(speech)

    var speechReady by remember {
        mutableStateOf(false)
    }

    var resumed by remember(owner) {
        mutableStateOf(
            owner.lifecycle.currentState.isAtLeast(
                Lifecycle.State.RESUMED
            )
        )
    }

    DisposableEffect(context) {
        val tts = TextToSpeech(context.applicationContext) { result ->
            speechReady = result == TextToSpeech.SUCCESS
        }

        speech = tts

        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = owner.lifecycle.currentState.isAtLeast(
                Lifecycle.State.RESUMED
            )

            if (
                event == Lifecycle.Event.ON_PAUSE ||
                event == Lifecycle.Event.ON_STOP
            ) {
                currentSpeech?.stop()
            }
        }

        owner.lifecycle.addObserver(observer)

        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }

    // Include pending arrival so guidance changes at both transfer stages.
    val guidanceKey =
        "${session.route?.nodeIds}:" +
                "${session.nextIndex}:" +
                "${session.arrived}:" +
                "${session.pendingFloorArrival?.nodeId}"

    LaunchedEffect(
        voice,
        speechReady,
        guidanceKey,
        scanning,
        diagnostics,
        pickingStart,
        confirmFloor,
        resumed
    ) {
        val canSpeak =
            voice &&
                    speechReady &&
                    resumed &&
                    !scanning &&
                    !diagnostics &&
                    !pickingStart &&
                    !confirmFloor &&
                    session.route != null

        if (canSpeak) {
            val tts = speech

            if (tts != null) {
                val languageResult =
                    tts.setLanguage(Locale.getDefault())

                if (
                    languageResult != TextToSpeech.LANG_MISSING_DATA &&
                    languageResult != TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts.speak(
                        session.instruction,
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "intramap-guidance"
                    )
                }
            }
        } else {
            speech?.stop()
        }
    }

    if (scanning) {
        BackHandler {
            scanning = false
        }

        val scannerBuilding = remember(building, currentFloor) {
            building.copy(
                floors = building.floors.sortedBy {
                    if (it.floorId == currentFloor) 0 else 1
                }
            )
        }

        LandmarkScannerScreen(
            building = scannerBuilding,
            onBack = {
                scanning = false
            },
            onUseObservation = { observation ->
                if (session.pendingFloorArrival == null) {
                    session.applyObservation(observation)
                }

                scanning = false
            }
        )

        return
    }

    if (pickingStart) {
        AlertDialog(
            onDismissRequest = {
                pickingStart = false
            },
            title = {
                Text("Where are you standing?")
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Select a mapped landmark only if you are " +
                                "physically there. Confirming resets your position."
                    )

                    session.landmarks
                        .sortedBy {
                            if (it.floorId == floorId) 0 else 1
                        }
                        .forEach { node ->
                            val nodeFloorName = building.floors
                                .firstOrNull {
                                    it.floorId == node.floorId
                                }
                                ?.floorName
                                ?: node.floorId

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        session.chooseStart(node.nodeId)
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                RadioButton(
                                    selected =
                                        session.selectedStartId ==
                                                node.nodeId,
                                    onClick = {
                                        session.chooseStart(node.nodeId)
                                    }
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(top = 10.dp)
                                ) {
                                    Text(node.name)

                                    Text(
                                        text = nodeFloorName,
                                        style =
                                            MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(
                    enabled =
                        session.selectedStartId != null &&
                                session.errors.isEmpty() &&
                                !transferInProgress,
                    onClick = {
                        session.pauseMotion()
                        session.confirmStart()
                        pickingStart = false
                    }
                ) {
                    Text("I am here — confirm")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pickingStart = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (confirmFloor) {
        val target = session.pendingFloorArrival

        val targetFloorName = building.floors
            .firstOrNull {
                it.floorId == target?.floorId
            }
            ?.floorName
            ?: target?.floorId.orEmpty()

        AlertDialog(
            onDismissRequest = {
                confirmFloor = false
            },
            title = {
                Text("Confirm floor arrival")
            },
            text = {
                Text(
                    if (target == null) {
                        "No floor change is currently in progress."
                    } else {
                        "Are you physically on $targetFloorName at " +
                                "the mapped stairs or elevator landing? " +
                                "Confirming will reinitialize your position " +
                                "at that connector."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = target != null,
                    onClick = {
                        session.confirmTransition()
                        confirmFloor = false
                    }
                ) {
                    Text("Yes, I have arrived")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Keep the pending transfer active.
                        confirmFloor = false
                    }
                ) {
                    Text("Not yet")
                }
            }
        )
    }

    if (diagnostics) {
        Dialog(
            onDismissRequest = {
                diagnostics = false
            }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Current navigation session",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Text(
                        "Ready: ${session.ready}\n" +
                                "Sensors running: $running\n" +
                                "Floor: ${session.estimate?.floorId ?: "Unknown"}"
                    )

                    session.estimate?.let { estimate ->
                        Text(
                            "Position: %.1f, %.1f m\nSpread: %.1f m"
                                .format(
                                    estimate.x,
                                    estimate.y,
                                    estimate.spreadM
                                )
                        )
                    }

                    Text(
                        "Destination: " +
                                "${session.destination?.name ?: "Not selected"}\n" +
                                "Route nodes: ${session.routeNodes.size}\n" +
                                "Next index: ${session.nextIndex}"
                    )

                    Text(
                        "Travel mode: ${session.floorTravelMode.name}\n" +
                                "Pending arrival: " +
                                "${session.pendingFloorArrival?.name ?: "None"}"
                    )

                    Text(session.status)

                    session.errors.forEach { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    TextButton(
                        onClick = {
                            diagnostics = false
                        }
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = building.buildingName,
                            style = MaterialTheme.typography.titleLarge
                        )

                        Text(
                            text = "$floorName · ${
                                when {
                                    transferInProgress ->
                                        "Floor change in progress"

                                    session.ready ->
                                        "Location initialized"

                                    else ->
                                        "Choose your start"
                                }
                            }",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            session.pauseMotion()
                            onBack()
                        }
                    ) {
                        Text("Back")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .heightIn(max = 330.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when {
                            session.errors.isNotEmpty() ->
                                "Map needs correction"

                            !session.ready ->
                                "1. Confirm your starting point"

                            transferInProgress ->
                                session.instruction

                            session.destination == null ->
                                "2. Choose a destination above"

                            session.arrived ->
                                "You have arrived"

                            session.route == null ->
                                "No available route"

                            else ->
                                session.instruction
                        },
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = session.status,
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (session.errors.isNotEmpty()) {
                        session.errors.forEach { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else if (!session.ready) {
                        Button(
                            onClick = {
                                pickingStart = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Choose and confirm my location")
                        }
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(
                                rememberScrollState()
                            ),
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = session.stepFree,
                                enabled = !transferInProgress,
                                onClick = {
                                    session.updateAccessibilityPreference(
                                        !session.stepFree
                                    )
                                },
                                label = {
                                    Text("Step-free")
                                }
                            )

                            FilterChip(
                                selected = voice,
                                onClick = {
                                    voice = !voice
                                },
                                label = {
                                    Text("Voice guidance")
                                }
                            )

                            TextButton(
                                enabled = !transferInProgress,
                                onClick = {
                                    session.pauseMotion()
                                    pickingStart = true
                                }
                            ) {
                                Text("Change start")
                            }

                            TextButton(
                                enabled = !transferInProgress,
                                onClick = {
                                    session.pauseMotion()
                                    scanning = true
                                }
                            ) {
                                Text("Scan to correct")
                            }
                        }

                        // Handles Auto / Stairs / Elevator and both
                        // stages of the floor-change workflow.
                        FloorChangeControls(
                            session = session,
                            onConfirmArrival = {
                                session.pauseMotion()
                                confirmFloor = true
                            },
                            onCorrectLocation = {
                                session.pauseMotion()
                                pickingStart = true
                            }
                        )

                        // The previous transitionTarget button is removed.
                        if (
                            session.route != null &&
                            !session.arrived &&
                            !transferInProgress
                        ) {
                            key(session.motionEpoch) {
                                NavigationMotionControls(
                                    enabled =
                                        !diagnostics &&
                                                !pickingStart &&
                                                !confirmFloor &&
                                                session.pendingFloorArrival ==
                                                null &&
                                                session.transitionTarget ==
                                                null,
                                    onStep = session::walk,
                                    onRunningChanged = {
                                        running = it
                                    }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(
                            rememberScrollState()
                        ),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                session.pauseMotion()
                                diagnostics = true
                            }
                        ) {
                            Text("Session details")
                        }

                        if (
                            session.route != null &&
                            !session.arrived
                        ) {
                            TextButton(
                                enabled = !transferInProgress,
                                onClick = {
                                    session.pauseMotion()
                                    session.reportBlocked()
                                }
                            ) {
                                Text("Report blocked path")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                },
                enabled = !transferInProgress,
                singleLine = true,
                label = {
                    Text("Where do you want to go?")
                },
                placeholder = {
                    Text("Search mapped rooms and places")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            if (transferInProgress) {
                Text(
                    text = "Destination changes are paused until you " +
                            "finish or cancel the floor change.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (query.isNotBlank() && !transferInProgress) {
                val searchText = query.trim()

                val results = session.landmarks.filter { node ->
                    node.name.contains(searchText, true) ||
                            node.keywords.any { keyword ->
                                keyword.contains(searchText, true)
                            }
                }.take(4)

                if (results.isEmpty()) {
                    Text("No mapped place matches this search.")
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 170.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    results.forEach { node ->
                        val destinationFloorName = building.floors
                            .firstOrNull {
                                it.floorId == node.floorId
                            }
                            ?.floorName
                            ?: node.floorId

                        ListItem(
                            headlineContent = {
                                Text(node.name)
                            },
                            supportingContent = {
                                Text(destinationFloorName)
                            },
                            modifier = Modifier.clickable(
                                enabled =
                                    session.pendingFloorArrival == null
                            ) {
                                session.pauseMotion()
                                session.setDestination(node.nodeId)
                                query = ""

                                if (!session.ready) {
                                    pickingStart = true
                                }
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(
                    rememberScrollState()
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                building.floors.forEach { floor ->
                    FilterChip(
                        selected = floorId == floor.floorId,
                        onClick = {
                            // Changes the displayed floor only.
                            floorId = floor.floorId
                        },
                        label = {
                            Text(floor.floorName)
                        }
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFFF3F6FA)
            ) {
                NavigationMapCanvas(
                    building = building,
                    floorId = floorId,
                    session = session
                )
            }

            Text(
                text =
                    "Pinch to zoom · Drag to pan · " +
                            "Blue: route / position · Red: destination",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
private fun NavigationMapCanvas(
    building: Building,
    floorId: String,
    session: NavigationSession
) {
    val floor = building.floors
        .firstOrNull {
            it.floorId == floorId
        }
        ?: return

    val nodes = remember(building) {
        building.nodes.associateBy {
            it.nodeId
        }
    }

    var zoom by remember(building.buildingId, floorId) {
        mutableFloatStateOf(1f)
    }

    var pan by remember(building.buildingId, floorId) {
        mutableStateOf(Offset.Zero)
    }

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(35, 48, 65)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(building.buildingId, floorId) {
                detectTransformGestures { _, delta, factor, _ ->
                    zoom = (zoom * factor).coerceIn(0.7f, 5f)
                    pan += delta
                }
            }
    ) {
        if (
            floor.widthM <= 0f ||
            floor.heightM <= 0f ||
            size.minDimension <= 0f
        ) {
            return@Canvas
        }

        val margin = 30.dp.toPx()

        val scale = min(
            (size.width - 2 * margin) / floor.widthM,
            (size.height - 2 * margin) / floor.heightM
        ).coerceAtLeast(0.01f) * zoom

        fun point(x: Double, y: Double): Offset {
            return Offset(
                x = size.width / 2f +
                        pan.x +
                        (x.toFloat() - floor.widthM / 2f) * scale,
                y = size.height / 2f +
                        pan.y +
                        (y.toFloat() - floor.heightM / 2f) * scale
            )
        }

        drawRect(
            color = Color.White,
            topLeft = point(0.0, 0.0),
            size = Size(
                width = floor.widthM * scale,
                height = floor.heightM * scale
            )
        )

        building.edges.forEach { edge ->
            val from = nodes[edge.sourceNodeId]
            val to = nodes[edge.destinationNodeId]

            if (
                from != null &&
                to != null &&
                from.floorId == floorId &&
                to.floorId == floorId
            ) {
                val available =
                    edge.isAvailable &&
                            edge.edgeId !in session.blocked

                drawLine(
                    color = if (available) {
                        Color(0xFFCBD5E1)
                    } else {
                        Color(0xFFDC2626)
                    },
                    start = point(
                        from.x.toDouble(),
                        from.y.toDouble()
                    ),
                    end = point(
                        to.x.toDouble(),
                        to.y.toDouble()
                    ),
                    strokeWidth = 7.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Never join route coordinates from different floors.
        session.routeNodes.zipWithNext().forEach { (from, to) ->
            if (
                from.floorId == floorId &&
                to.floorId == floorId
            ) {
                drawLine(
                    color = Color(0xFF2563EB),
                    start = point(
                        from.x.toDouble(),
                        from.y.toDouble()
                    ),
                    end = point(
                        to.x.toDouble(),
                        to.y.toDouble()
                    ),
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        labelPaint.textSize = 11.dp.toPx()

        building.nodes
            .filter {
                it.floorId == floorId &&
                        it.type != "JUNCTION"
            }
            .forEach { node ->
                val center = point(
                    node.x.toDouble(),
                    node.y.toDouble()
                )

                drawCircle(
                    color = if (
                        node.nodeId == session.destinationId
                    ) {
                        Color(0xFFDC2626)
                    } else {
                        Color(0xFF334155)
                    },
                    radius = 5.dp.toPx(),
                    center = center
                )

                drawContext.canvas.nativeCanvas.drawText(
                    node.name,
                    center.x + 7.dp.toPx(),
                    center.y - 8.dp.toPx(),
                    labelPaint
                )
            }

        session.estimate
            ?.takeIf {
                it.floorId == floorId
            }
            ?.let { estimate ->
                val center = point(
                    estimate.x,
                    estimate.y
                )

                drawCircle(
                    color = Color(0x222563EB),
                    radius = (
                            estimate.spreadM.toFloat() * scale
                            ).coerceIn(
                            minimumValue = 1f,
                            maximumValue =
                                size.maxDimension.coerceAtLeast(1f)
                        ),
                    center = center
                )

                drawCircle(
                    color = Color.White,
                    radius = 9.dp.toPx(),
                    center = center
                )

                drawCircle(
                    color = Color(0xFF2563EB),
                    radius = 6.dp.toPx(),
                    center = center
                )
            }
    }
}