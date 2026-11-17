package com.intramap.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.*
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMappingDataSource
import com.intramap.data.supabase.WalkingMapWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

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
    var key by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf("") }
    var floorKey by remember { mutableStateOf("") }
    var floorName by remember { mutableStateOf("") }
    var floorNumber by remember { mutableStateOf("0") }
    var start by remember { mutableStateOf<String?>(null) }
    var facing by remember { mutableStateOf<String?>(null) }
    var end by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadedFloor by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Open a building, record a route, submit, import, then review and publish.") }
    var reviewing by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableIntStateOf(0) }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = studioFailure(e) }
            finally { busy = false }
        }
    }
    fun clearSelection() {
        version = null; floors = emptyList(); nodes = emptyList(); recordings = emptyList(); loadedFloor = null
        start = null; facing = null; end = null; floorKey = ""; floorName = ""; floorNumber = "0"
    }
    suspend fun refreshFloor() {
        val w = requireNotNull(workspace); val v = requireNotNull(version); val b = requireNotNull(building)
        val freshNodes = w.nodes(v.id,floorKey.trim())
        val freshRecordings = w.recordings(b.id,floorKey.trim())
        val freshFloors = w.floors(v.id)
        nodes = freshNodes; recordings = freshRecordings; floors = freshFloors
        loadedFloor = floorKey.trim(); start = null; facing = null; end = null
    }
    fun open(selected: MappingBuilding) = perform {
        clearSelection(); building = selected
        version = requireNotNull(workspace).draft(selected.id)
        floors = workspace.floors(requireNotNull(version).id)
        status = if (floors.isEmpty()) "Empty draft: record and submit a walk, then import it to create the first floor." else "Draft ready. Open review to inspect all floors before publishing."
    }
    LaunchedEffect(workspace,refreshToken) {
        if (workspace != null) {
            busy = true
            try {
                buildings = workspace.editableBuildings()
                if (version != null) {
                    floors = workspace.floors(requireNotNull(version).id)
                    if (floorKey.isNotBlank()) refreshFloor()
                }
                status = "Workspace refresh succeeded. Recording submission and import are separate steps."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = studioFailure(e) }
            finally { busy = false }
        }
    }
    if (recording) {
        PathRecordingScreen(requireNotNull(building).id,floorKey.trim(),null,onBack = { recording = false; loadedFloor = null; refreshToken++ })
        return
    }
    if (reviewing && client != null && building != null && version != null) {
        DraftMapReviewScreen(client,requireNotNull(building),requireNotNull(version).id,
            onBack = { reviewing = false; loadedFloor = null; refreshToken++ },
            onPublished = { reviewing = false; clearSelection(); status = "Published reviewed map. Visitors can refresh to download it." })
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Walking map studio") },navigationIcon = { TextButton(enabled = !busy,onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (workspace == null) { Text("Supabase is not configured. Check this build's Supabase URL and publishable key."); return@Column }
            Text(status)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(enabled = !busy,onClick = { refreshToken++ }) { Text("Refresh workspace / retry connection") }
            Text("Editable buildings · owned or shared",style = MaterialTheme.typography.titleMedium)
            buildings.forEach { item -> OutlinedButton(enabled = !busy,onClick = { open(item) },modifier = Modifier.fillMaxWidth()) { Text(item.name) } }
            OutlinedTextField(name,{ name = it },label = { Text("New building name") },enabled = !busy,modifier = Modifier.fillMaxWidth())
            Text("Building ID is assigned automatically.",style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && name.isNotBlank(),onClick = { perform {
                clearSelection()
                val current = workspace.editableBuildings()
                val created = current.firstOrNull { it.buildingKey == key } ?: requireNotNull(writer).createBuilding(key,name.trim(),"")
                building = created; buildings = workspace.editableBuildings(); version = workspace.draft(created.id)
                floors = workspace.floors(requireNotNull(version).id)
                key = UUID.randomUUID().toString(); name = ""
                status = "Building ready. Record, submit and import a route to create a floor."
            } }) { Text("Create building") }
            if (version != null) {
                HorizontalDivider()
                Text("${building?.name} · draft ${version?.versionNo}",style = MaterialTheme.typography.titleMedium)
                if (floors.isEmpty()) Text("Publication blocked: no imported floors yet. Enter floor details, record a walk, submit it, then import the recording below. An empty map cannot be published.")
                else Text("${floors.size} saved floors. Publication requires valid paths, an entrance, saved edits and review of every floor.")
                OutlinedButton(enabled = !busy,onClick = { reviewing = true }) { Text("Open draft review / publication checklist") }
                floors.forEach { floor -> TextButton(enabled = !busy,onClick = {
                    floorKey = floor.floorKey; floorName = floor.name; floorNumber = floor.floorNumber.toString(); loadedFloor = null
                }) { Text(floor.name) } }
                OutlinedTextField(floorKey,{ floorKey = it; loadedFloor = null },label = { Text("Floor ID") },enabled = !busy,modifier = Modifier.fillMaxWidth())
                OutlinedTextField(floorName,{ floorName = it },label = { Text("Floor name") },enabled = !busy,modifier = Modifier.fillMaxWidth())
                OutlinedTextField(floorNumber,{ floorNumber = it },label = { Text("Floor number") },enabled = !busy,modifier = Modifier.fillMaxWidth())
                Text("Floor geometry is created from imported walking traces, not by entering these labels.")
                OutlinedButton(enabled = !busy && floorKey.isNotBlank(),onClick = { perform { refreshFloor(); status = "Floor refreshed from server." } }) { Text("Load / refresh floor") }
                Button(enabled = !busy && floorKey.isNotBlank() && floorName.isNotBlank() && floorNumber.toIntOrNull() != null,onClick = { recording = true }) { Text("Walk and record a route") }
                if (loadedFloor == floorKey.trim()) {
                    if (nodes.isNotEmpty()) {
                        Text("Select the physical start and facing landmarks before importing a joining route. Proximity alone never joins paths.")
                        WorkspaceNodePicker("Starting landmark",nodes,start) { start = it; if (facing == it) facing = null }
                        WorkspaceNodePicker("Facing landmark",nodes.filter { it.nodeKey != start },facing) { facing = it }
                        WorkspaceNodePicker("Optional end join: only if physically reached",nodes,end) { end = it }
                    } else Text("First route: its start and Forward direction define the floor's local frame.")
                    Text("Submitted recordings awaiting import",style = MaterialTheme.typography.titleMedium)
                    recordings.forEach { item -> OutlinedButton(enabled = !busy && floorName.isNotBlank() && floorNumber.toIntOrNull() != null && (nodes.isEmpty() || (start != null && facing != null && start != facing)),onClick = { perform {
                        workspace.importRecording(item.id,requireNotNull(version).id,floorName.trim(),floorNumber.toInt(),start,facing,end)
                        refreshFloor(); status = "Trace imported. Open review and inspect every floor before publishing."
                    } },modifier = Modifier.fillMaxWidth()) { Text("Import recording ${item.id.take(8)}") } }
                    if (recordings.isEmpty()) Text("No submitted recordings waiting on this floor. Stop and submit your recording before returning here.")
                }
            }
        }
    }
}

private fun studioFailure(error: Exception): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    val detail = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return when {
        "42501" in detail || "row-level security" in detail || "permission denied" in detail || "403" in detail -> "Server denied this operation. This is a permission failure, not a connection failure. Verify this account can edit the selected building; refresh before retrying."
        "401" in detail || "jwt expired" in detail || "refresh token" in detail -> "Session expired or rejected. Switch / sign out, sign in again, then refresh the workspace."
        causes.any { it is java.net.UnknownHostException } -> "Cannot resolve the server address. Check Wi-Fi or mobile data and DNS, then tap Refresh workspace."
        causes.any { it is java.net.SocketTimeoutException } || "timeout" in detail -> "Server request timed out. Check your connection and refresh. A previous write may have succeeded; verify before repeating it."
        causes.any { it is javax.net.ssl.SSLException } -> "Secure connection failed. Check device date/time and network interception; TLS verification remains enabled."
        causes.any { it is java.io.IOException } -> "Network request failed. Restore the connection, then refresh to reconcile server state before retrying a write."
        else -> "Workspace operation failed (${error.javaClass.simpleName}). Refresh to reconcile server state; check backend schema and application logs if it persists."
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
