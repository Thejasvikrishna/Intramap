package com.intramap.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.*
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMappingDataSource
import com.intramap.data.supabase.WalkingMapWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminMappingScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = SupabaseClientFactory.client
    val workspace = remember(client) { client?.let { WalkingMapWorkspace(it) } }
    val writer = remember(client) { client?.let { SupabaseMappingDataSource(it) } }
    var buildings by remember { mutableStateOf(emptyList<MappingBuilding>()) }
    var building by remember { mutableStateOf<MappingBuilding?>(null) }
    var version by remember { mutableStateOf<MappingVersion?>(null) }
    var floors by remember { mutableStateOf(emptyList<MappingFloor>()) }
    var nodes by remember { mutableStateOf(emptyList<MappingNode>()) }
    var recordings by remember { mutableStateOf(emptyList<PathRecording>()) }
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var floorKey by remember { mutableStateOf("") }
    var floorName by remember { mutableStateOf("") }
    var floorNumber by remember { mutableStateOf("0") }
    var start by remember { mutableStateOf<String?>(null) }
    var facing by remember { mutableStateOf<String?>(null) }
    var end by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadedFloor by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Open your building, choose a floor and walk to record its routes.") }
    var reviewing by remember { mutableStateOf(false) }

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Operation failed. Check the migration, sign-in and permissions; refresh before retrying." }
            finally { busy = false }
        }
    }
    fun clearSelection() {
        version = null; floors = emptyList(); nodes = emptyList(); recordings = emptyList(); loadedFloor = null
        start = null; facing = null; end = null; floorKey = ""; floorName = ""; floorNumber = "0"
    }
    suspend fun refreshFloor() {
        val w = requireNotNull(workspace); val v = requireNotNull(version); val b = requireNotNull(building)
        nodes = w.nodes(v.id,floorKey.trim()); recordings = w.recordings(b.id,floorKey.trim()); floors = w.floors(v.id)
        loadedFloor = floorKey.trim(); start = null; facing = null; end = null
    }
    fun open(selected: MappingBuilding) = perform {
        clearSelection(); building = selected
        version = requireNotNull(workspace).draft(selected.id)
        floors = workspace.floors(requireNotNull(version).id)
        status = "Draft opened. A new draft does not copy an older published map."
    }
    LaunchedEffect(workspace) {
        if (workspace != null) {
            try { buildings = workspace.ownedBuildings() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Could not load your buildings. Check sign-in and connectivity." }
        }
    }
    if (recording) {
        PathRecordingScreen(buildingId = requireNotNull(building).id,floorKey = floorKey.trim(),startAnchorKey = null,
            onBack = { recording = false; loadedFloor = null })
        return
    }
    if (reviewing && client != null && building != null && version != null) {
        DraftMapReviewScreen(client,requireNotNull(building),requireNotNull(version).id,
            onBack = { reviewing = false; loadedFloor = null },
            onPublished = { reviewing = false; clearSelection(); status = "Published reviewed map. Visitors can refresh the building list to download it." })
        return
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Walking map studio") },navigationIcon = { TextButton(enabled = !busy,onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (workspace == null) { Text("Supabase is not configured."); return@Column }
            Text(status)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Your buildings",style = MaterialTheme.typography.titleMedium)
            buildings.forEach { item -> OutlinedButton(enabled = !busy,onClick = { open(item) },modifier = Modifier.fillMaxWidth()) { Text(item.name) } }
            OutlinedTextField(key,{ key = it },label = { Text("New building ID") },enabled = !busy,modifier = Modifier.fillMaxWidth())
            OutlinedTextField(name,{ name = it },label = { Text("New building name") },enabled = !busy,modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && key.isNotBlank() && name.isNotBlank(),onClick = {
                perform {
                    clearSelection()
                    val current = workspace.ownedBuildings()
                    val created = current.firstOrNull { it.buildingKey == key.trim() } ?: requireNotNull(writer).createBuilding(key.trim(),name.trim(),"")
                    building = created; buildings = workspace.ownedBuildings(); version = workspace.draft(created.id)
                    floors = workspace.floors(requireNotNull(version).id)
                    status = "Building and draft ready. Choose a floor below."
                }
            }) { Text("Create or resume building") }
            if (version != null) {
                HorizontalDivider()
                Text("${building?.name} · draft ${version?.versionNo}",style = MaterialTheme.typography.titleMedium)
                OutlinedButton(enabled = !busy && floors.isNotEmpty(),onClick = { reviewing = true }) { Text("Open draft map for review / publication") }
                floors.forEach { floor -> TextButton(enabled = !busy,onClick = {
                    floorKey = floor.floorKey; floorName = floor.name; floorNumber = floor.floorNumber.toString(); loadedFloor = null
                }) { Text(floor.name) } }
                OutlinedTextField(floorKey,{ floorKey = it; loadedFloor = null },label = { Text("Floor ID") },enabled = !busy,modifier = Modifier.fillMaxWidth())
                OutlinedTextField(floorName,{ floorName = it },label = { Text("Floor name") },enabled = !busy,modifier = Modifier.fillMaxWidth())
                OutlinedTextField(floorNumber,{ floorNumber = it },label = { Text("Floor number") },enabled = !busy,modifier = Modifier.fillMaxWidth())
                Text("No X/Y or distance entry. Floor display bounds are derived from imported walking traces.")
                OutlinedButton(enabled = !busy && floorKey.isNotBlank(),onClick = { perform { refreshFloor(); status = "Floor recordings loaded." } }) { Text("Load / refresh floor") }
                Button(enabled = !busy && floorKey.isNotBlank() && floorName.isNotBlank() && floorNumber.toIntOrNull() != null,onClick = { recording = true }) { Text("Walk and record a route") }
                if (loadedFloor == floorKey.trim()) {
                    if (nodes.isNotEmpty()) {
                        Text("Joining an existing floor requires the actual start and facing landmarks. During recording, align Forward while facing the chosen landmark. Proximity alone never joins paths.")
                        WorkspaceNodePicker("Starting landmark",nodes,start) { start = it }
                        WorkspaceNodePicker("Facing landmark",nodes.filter { it.nodeKey != start },facing) { facing = it }
                        WorkspaceNodePicker("Optional end join (only if physically reached)",nodes,end) { end = it }
                    } else Text("First route: its starting position and Forward direction define this floor's local frame.")
                    Text("Submitted recordings",style = MaterialTheme.typography.titleMedium)
                    recordings.forEach { item -> OutlinedButton(enabled = !busy && floorName.isNotBlank() && floorNumber.toIntOrNull() != null &&
                        (nodes.isEmpty() || (start != null && facing != null && start != facing)),onClick = {
                        perform {
                            workspace.importRecording(item.id,requireNotNull(version).id,floorName.trim(),floorNumber.toInt(),start,facing,end)
                            refreshFloor(); status = "Trace imported into draft. Open the draft map to review all floors before publishing."
                        }
                    },modifier = Modifier.fillMaxWidth()) { Text("Import recording ${item.id.take(8)}") } }
                    if (recordings.isEmpty()) Text("No submitted recordings on this floor. Record a walk, submit, then refresh.")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceNodePicker(label: String,nodes: List<MappingNode>,selected: String?,choose: (String?) -> Unit) {
    Text(label,style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null,onClick = { choose(null) },label = { Text("None") })
        nodes.forEach { node -> FilterChip(selected = selected == node.nodeKey,onClick = { choose(node.nodeKey) },label = { Text("${node.name} · ${node.nodeKey.takeLast(8)}") }) }
    }
}
