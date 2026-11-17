package com.intramap.ui.map

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building

@Composable
fun BuildingLandingScreen(
    buildings: List<Building>, status: String, loading: Boolean,
    onOpen: (Building, String) -> Unit, onAccount: () -> Unit, onRefresh: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val term = query.trim()
    val matches = buildings.filter { it.buildingName.contains(term,true) || it.description.contains(term,true) }
    val selected = matches.firstOrNull { it.buildingId == selectedId } ?: matches.firstOrNull()
    var chosenFloor by rememberSaveable(selected?.buildingId) { mutableStateOf<String?>(null) }
    val floor = selected?.floors?.firstOrNull { it.floorId == chosenFloor } ?: selected?.floors?.minByOrNull { it.floorNumber }
    var top by remember { mutableIntStateOf(0) }
    var bottom by remember { mutableIntStateOf(0) }
    var reset by remember { mutableIntStateOf(0) }
    val panelHeight = (LocalConfiguration.current.screenHeightDp * 0.44f).dp
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Surface(Modifier.fillMaxSize(),color = MaterialTheme.colorScheme.surfaceVariant) {}
        if (selected != null && floor != null) InteractiveIndoorMap(
            building = selected,floorId = floor.floorId,route = emptyList(),destinationId = null,
            position = null,spreadM = null,blocked = emptySet(),follow = false,recenterToken = reset,
            topInsetPx = top.toFloat(),bottomInsetPx = bottom.toFloat(),onGesture = {},
            onPlace = { onOpen(selected,it.floorId) },modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { top = it.height }.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = MaterialTheme.shapes.large,shadowElevation = 3.dp) {
                Column(Modifier.padding(8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("IntraMap",style = MaterialTheme.typography.titleLarge)
                            Text("Find your way indoors",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onAccount) { Text("Account") }
                    }
                    OutlinedTextField(query,{ query = it },placeholder = { Text("Search buildings") },singleLine = true,
                        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } },
                        shape = MaterialTheme.shapes.medium,modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottom = it.height },
            shape = RoundedCornerShape(topStart = 24.dp,topEnd = 24.dp),shadowElevation = 6.dp) {
            Column(Modifier.heightIn(max = panelHeight).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore indoors",Modifier.weight(1f),style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !loading,onClick = onRefresh) { Text(if (loading) "Updating…" else "Refresh") }
                }
                Text(status,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    matches.isEmpty() -> {
                        Text(if (term.isNotEmpty()) "No matching buildings" else if (loading) "Finding published maps" else "No maps available",style = MaterialTheme.typography.titleMedium)
                        Text(if (term.isNotEmpty()) "Try another name or clear your search." else "Published indoor maps will appear here. Editors can manage drafts from Account.",style = MaterialTheme.typography.bodyMedium)
                    }
                    selected != null -> {
                        Text(selected.buildingName,style = MaterialTheme.typography.titleMedium)
                        if (selected.description.isNotBlank()) Text(selected.description,style = MaterialTheme.typography.bodyMedium,maxLines = 2,overflow = TextOverflow.Ellipsis)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            selected.floors.sortedBy { it.floorNumber }.forEach { f ->
                                FilterChip(selected = floor?.floorId == f.floorId,onClick = { chosenFloor = f.floorId; reset++ },label = { Text(f.floorName) })
                            }
                        }
                        if (floor != null) Button(onClick = { onOpen(selected,floor.floorId) },modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Explore ${floor.floorName}") }
                        else Text("This building has no available floors.",style = MaterialTheme.typography.bodyMedium)
                        Text("Map preview · location not set",style = MaterialTheme.typography.labelSmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (matches.size > 1) {
                            HorizontalDivider()
                            Text("Choose a building",style = MaterialTheme.typography.labelLarge)
                            matches.forEach { b ->
                                OutlinedCard(onClick = { selectedId = b.buildingId; chosenFloor = null; reset++ },modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(b.buildingName,style = MaterialTheme.typography.titleSmall)
                                            Text("${b.floors.size} floors",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (b.buildingId == selected.buildingId) Text("Selected",style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
