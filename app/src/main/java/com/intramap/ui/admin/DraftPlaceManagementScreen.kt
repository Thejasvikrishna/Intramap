package com.intramap.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.MappingBuilding
import com.intramap.data.supabase.*
import com.intramap.domain.mapping.RoutePlaceCategory
import com.intramap.ui.map.InteractiveIndoorMap
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftPlaceManagementScreen(client: SupabaseClient,owner: MappingBuilding,versionId: String,onBack: () -> Unit) {
    val review = remember(client) { DraftMapReviewSource(client) }
    val writer = remember(client) { DraftPlaceSource(client) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(versionId) { mutableStateOf<DraftReviewSnapshot?>(null) }
    var floorId by remember(versionId) { mutableStateOf("") }
    var selectedNode by remember(versionId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var needsReload by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Loading places…") }
    var dialogOpen by remember { mutableStateOf(false) }
    var editedPlace by remember { mutableStateOf<DraftMappedPlace?>(null) }
    var newPlaceId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(RoutePlaceCategory.ROOM) }
    var anchor by remember { mutableStateOf<String?>(null) }
    var placementVerified by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DraftMappedPlace?>(null) }
    val graph = remember(snapshot,owner) { snapshot?.asBuilding(owner) }
    val allPlaces = remember(graph,versionId) { graph?.nodes.orEmpty().flatMap { n -> n.mappedPlaces.map { p ->
        DraftMappedPlace(versionId,p.placeId,n.nodeId,p.name,RoutePlaceCategory.valueOf(p.category))
    } } }
    val selected = graph?.nodes?.firstOrNull { it.nodeId == selectedNode && it.floorId == floorId }
    val editable = !busy && !needsReload && snapshot != null
    fun mutate(operation: suspend (String) -> Unit) {
        if (!editable) return
        val baseline = snapshot ?: return
        busy = true; needsReload = true
        scope.launch {
            try {
                operation(baseline.fingerprint)
                snapshot = review.load(versionId)
                needsReload = false; dialogOpen = false; deleting = null
                status = "Place change saved and reloaded. Review every floor again before publishing."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Save rejected or unconfirmed. Reload before further edits. Verify permissions, migrations and current server state." }
            finally { busy = false }
        }
    }
    LaunchedEffect(client,owner.id,versionId,reload) {
        busy = true; needsReload = true
        try {
            PlaceWorkflowSource(client).requireInstalled()
            val fresh = review.load(versionId)
            val loaded = fresh.asBuilding(owner)
            snapshot = fresh
            if (loaded.floors.none { it.floorId == floorId }) floorId = loaded.floors.firstOrNull()?.floorId.orEmpty()
            if (loaded.nodes.none { it.nodeId == selectedNode }) selectedNode = null
            needsReload = false
            status = "Select a route point to add a place, or edit an existing destination. Place labels do not create floor connections."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Place management unavailable. Install migrations 0011–0014 if missing; check sign-in and connection. No place changes were confirmed." }
        finally { busy = false }
    }
    BackHandler(enabled = busy) { }
    if (dialogOpen) AlertDialog(onDismissRequest = { if (!busy) dialogOpen = false },title = { Text(if (editedPlace == null) "Add destination" else "Edit destination") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{ name = it.take(120) },label = { Text("Room, shop or place name") },singleLine = true,enabled = editable)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoutePlaceCategory.entries.forEach { value -> FilterChip(selected = category == value,enabled = editable,onClick = { category = value },label = { Text(value.name.lowercase()) }) }
            }
            Text("Route point: ${graph?.nodes?.firstOrNull { it.nodeId == anchor }?.name ?: "Not selected"}",style = MaterialTheme.typography.bodySmall)
            Text("The destination uses this existing route point. Changing a label to stairs or elevator does not create a floor connection.",style = MaterialTheme.typography.bodySmall)
            if (selected != null && selected.nodeId != anchor) OutlinedButton(enabled = editable,onClick = { anchor = selected.nodeId; placementVerified = false }) { Text("Use selected map point") }
            if (editedPlace == null || editedPlace?.nodeKey != anchor) Row {
                Checkbox(placementVerified,onCheckedChange = { placementVerified = it },enabled = editable)
                Text("I verified this access point in the building",Modifier.padding(top = 12.dp),style = MaterialTheme.typography.bodySmall)
            }
            if (needsReload) Text("Reload required before saving another change.",color = MaterialTheme.colorScheme.error)
        } },confirmButton = { TextButton(enabled = editable && name.isNotBlank() && anchor != null && (editedPlace?.nodeKey == anchor || placementVerified),onClick = {
            val node = anchor
            if (node != null) {
                val place = DraftMappedPlace(versionId,editedPlace?.placeId ?: newPlaceId,node,name.trim(),category)
                mutate { writer.save(place,it) }
            }
        }) { Text("Save place") } },dismissButton = { TextButton(enabled = !busy,onClick = { dialogOpen = false }) { Text("Cancel") } })
    val target = deleting
    if (target != null) AlertDialog(onDismissRequest = { if (!busy) deleting = null },title = { Text("Delete ${target.name}?") },
        text = { Text("This removes only the destination label. The route and other places remain. This server change has no local undo.") },
        confirmButton = { TextButton(enabled = editable,onClick = { mutate { writer.delete(target,it) } }) { Text("Delete place") } },
        dismissButton = { TextButton(enabled = !busy,onClick = { deleting = null }) { Text("Cancel") } })
    Scaffold(topBar = { TopAppBar(title = { Text("Rooms and shops") },navigationIcon = { TextButton(enabled = !busy,onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(owner.name,style = MaterialTheme.typography.titleSmall)
            Text(status,style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy,onClick = { dialogOpen = false; deleting = null; reload++ }) { Text("Reload saved places") }
            val building = graph
            if (building != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    building.floors.sortedBy { it.floorNumber }.forEach { floor -> FilterChip(selected = floorId == floor.floorId,enabled = !busy,onClick = { floorId = floor.floorId; selectedNode = null },label = { Text(floor.floorName) }) }
                }
                InteractiveIndoorMap(building,floorId,emptyList(),selectedNode,null,null,emptySet(),false,reload,0f,0f,onGesture = {},
                    onPlace = { if (editable) selectedNode = it.nodeId },modifier = Modifier.fillMaxWidth().height(200.dp),showJunctions = true)
                Text(selected?.let { "Selected: ${it.name} · ${it.nodeId.takeLast(8)}" } ?: "Tap an existing route point before adding a destination.",style = MaterialTheme.typography.labelSmall)
                Button(enabled = editable && selected != null,onClick = {
                    editedPlace = null; newPlaceId = UUID.randomUUID().toString(); name = ""; category = RoutePlaceCategory.ROOM
                    anchor = selected?.nodeId; placementVerified = false; dialogOpen = true
                },modifier = Modifier.fillMaxWidth()) { Text("Add room or shop at selected point") }
                OutlinedTextField(query,{ query = it },label = { Text("Search saved places on this floor") },singleLine = true,modifier = Modifier.fillMaxWidth())
                val floorNodes = building.nodes.filter { it.floorId == floorId }.map { it.nodeId }.toSet()
                val visible = allPlaces.filter { it.nodeKey in floorNodes && (it.name.contains(query.trim(),true) || it.category.name.contains(query.trim(),true)) }.sortedBy { it.name.lowercase() }
                LazyColumn(Modifier.fillMaxWidth().weight(1f),verticalArrangement = Arrangement.spacedBy(8.dp),contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (visible.isEmpty()) item { Text("No matching independent places on this floor.",style = MaterialTheme.typography.bodyMedium) }
                    items(visible,key = { it.placeId }) { place ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(place.name,style = MaterialTheme.typography.titleSmall)
                                Text(place.category.name.lowercase(),style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(enabled = !busy,onClick = { selectedNode = place.nodeKey }) { Text("Locate") }
                                    TextButton(enabled = editable,onClick = {
                                        editedPlace = place; name = place.name; category = place.category; anchor = place.nodeKey
                                        placementVerified = false; dialogOpen = true
                                    }) { Text("Edit / move") }
                                    TextButton(enabled = editable,onClick = { deleting = place }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
