package com.intramap.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.domain.navigation.generateInstructions
import com.intramap.domain.routing.AccessibilityMode
import com.intramap.domain.routing.IndoorGraph
import com.intramap.domain.routing.findRoute
import com.intramap.domain.search.searchDestinations
import com.intramap.ui.map.FloorMapCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(building: Building, initialFloorId: String, onBack: () -> Unit) {
    var floorId by remember { mutableStateOf(initialFloorId) }
    var query by remember { mutableStateOf("") }
    var destId by remember { mutableStateOf<String?>(null) }
    var startId by remember {
        mutableStateOf(building.nodes.firstOrNull { it.type == "ENTRANCE" }?.nodeId)
    }
    var pickingStart by remember { mutableStateOf(false) }
    var stepFree by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(setOf<String>()) }
    var step by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val graph = remember(building) { IndoorGraph(building) }
    val floorNames = remember(building) { building.floors.associate { it.floorId to it.floorName } }
    val floor = building.floors.first { it.floorId == floorId }
    val results = remember(query) { searchDestinations(building, query).take(5) }

    val start = startId
    val dest = destId
    val route = remember(start, dest, stepFree, blocked) {
        if (start != null && dest != null)
            findRoute(graph, start, dest,
                if (stepFree) AccessibilityMode.STEP_FREE else AccessibilityMode.STANDARD, blocked)
        else null
    }
    val steps = remember(route) { route?.let { generateInstructions(graph, it, floorNames) }.orEmpty() }
    val safeStep = step.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    val current = steps.getOrNull(safeStep)

    LaunchedEffect(route) { step = 0 }
    LaunchedEffect(safeStep, route) { steps.getOrNull(safeStep)?.let { floorId = it.floorId } }

    Scaffold(topBar = { TopAppBar(title = { Text(building.buildingName) },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            TabRow(selectedTabIndex = building.floors.indexOfFirst { it.floorId == floorId }) {
                building.floors.forEach { f ->
                    Tab(selected = f.floorId == floorId, onClick = { floorId = f.floorId },
                        text = { Text(f.floorName) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !pickingStart, onClick = { pickingStart = false },
                    label = { Text("Pick destination") })
                FilterChip(selected = pickingStart, onClick = { pickingStart = true },
                    label = { Text("Pick start") })
            }
            OutlinedTextField(query, { query = it },
                label = { Text(if (pickingStart) "Search start location" else "Search destination") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            results.forEach { n ->
                val fl = floorNames[n.floorId] ?: n.floorId
                ListItem(
                    headlineContent = { Text(n.name) },
                    supportingContent = { Text("${n.type} · $fl floor") },
                    modifier = Modifier.clickable {
                        floorId = n.floorId
                        if (pickingStart) startId = n.nodeId else destId = n.nodeId
                        blocked = emptySet(); message = null; query = ""
                    })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = stepFree, onCheckedChange = { stepFree = it })
                Spacer(Modifier.width(8.dp))
                Text("Step-free route")
            }
            Text("Start: ${building.nodes.firstOrNull { it.nodeId == start }?.name ?: "not set"}")
            Text("Destination: ${building.nodes.firstOrNull { it.nodeId == dest }?.name ?: "not set"}")
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            when {
                dest == null || start == null -> Text("Choose a start and a destination.")
                route == null -> Text("No route found.")
                else -> {
                    Text("Route: %.1f m".format(route.totalDistance))
                    if (current != null) {
                        Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Step ${safeStep + 1} of ${steps.size}",
                                    style = MaterialTheme.typography.labelMedium)
                                Text(current.text, style = MaterialTheme.typography.titleMedium)
                                if (current.distanceM > 0f)
                                    Text("Then continue %.0f m".format(current.distanceM))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { step = (safeStep - 1).coerceAtLeast(0) },
                                        enabled = safeStep > 0) { Text("Previous") }
                                    Button(onClick = { step = (safeStep + 1).coerceAtMost(steps.size - 1) },
                                        enabled = safeStep < steps.size - 1) { Text("Next") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        enabled = safeStep < steps.size - 1,
                                        onClick = {
                                            val edgeId = route.edgeIds.getOrNull(current.routeIndex)
                                            if (edgeId != null) {
                                                blocked = blocked + edgeId
                                                startId = current.nodeId
                                                message = "Path $edgeId reported blocked. Rerouting from here."
                                            }
                                        }) { Text("Report blocked") }
                                    if (blocked.isNotEmpty())
                                        TextButton(onClick = { blocked = emptySet(); message = "Blocked paths cleared." }) {
                                            Text("Clear blocked")
                                        }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FloorMapCanvas(building, floor, destId, route?.nodeIds.orEmpty(), current?.nodeId)
        }
    }
}