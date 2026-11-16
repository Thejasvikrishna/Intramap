package com.intramap.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building

@Composable
fun BuildingLandingScreen(
    buildings: List<Building>, status: String, loading: Boolean,
    onOpen: (Building, String) -> Unit, onAccount: () -> Unit, onRefresh: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val matches = buildings.filter { it.buildingName.contains(query, true) || it.description.contains(query, true) }
    val selected = matches.firstOrNull { it.buildingId == selectedId } ?: matches.firstOrNull()
    var chosenFloor by rememberSaveable(selected?.buildingId) { mutableStateOf<String?>(null) }
    val floor = selected?.floors?.firstOrNull { it.floorId == chosenFloor } ?: selected?.floors?.firstOrNull()
    var top by remember { mutableIntStateOf(0) }
    var bottom by remember { mutableIntStateOf(0) }
    var reset by remember { mutableIntStateOf(0) }
    val maxPanel = (LocalConfiguration.current.screenHeightDp * 0.43f).dp
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF1F5F9)) {}
        if (selected != null && floor != null) InteractiveIndoorMap(
            building = selected, floorId = floor.floorId, route = emptyList(), destinationId = null,
            position = null, spreadM = null, blocked = emptySet(), follow = false, recenterToken = reset,
            topInsetPx = top.toFloat(), bottomInsetPx = bottom.toFloat(), onGesture = {},
            onPlace = { onOpen(selected, it.floorId) }, modifier = Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { top = it.height }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(30.dp), shadowElevation = 8.dp) {
                Column(Modifier.padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("IntraMap", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onAccount) { Text("Login / account", color = Color(0xFF1967D2)) }
                    }
                    TextField(query, { query = it }, placeholder = { Text("Search indoor maps") }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), modifier = Modifier.fillMaxWidth())
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (selected != null) Surface(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), shape = RoundedCornerShape(20.dp), shadowElevation = 4.dp) {
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
                selected.floors.forEach { f ->
                    FilterChip(selected = floor?.floorId == f.floorId, onClick = { chosenFloor = f.floorId; reset++ }, label = { Text(f.floorName) })
                }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottom = it.height },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 10.dp) {
            Column(Modifier.heightIn(max = maxPanel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Explore indoors", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !loading, onClick = onRefresh) { Text("Refresh") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (selected != null && floor != null) Button(onClick = { onOpen(selected, floor.floorId) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1967D2))) { Text("Open ${selected.buildingName}") }
                Text("Preview only · your position has not been determined", style = MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    if (matches.isEmpty()) item { Text("No matching published maps.", Modifier.padding(12.dp)) }
                    items(matches, key = { it.buildingId }) { b ->
                        ListItem(headlineContent = { Text(b.buildingName) }, supportingContent = { Text(b.description) },
                            modifier = Modifier.clickable { selectedId = b.buildingId; chosenFloor = null; reset++ })
                    }
                }
            }
        }
    }
}
