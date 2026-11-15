package com.intramap.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.intramap.data.model.MappingBuilding
import com.intramap.data.supabase.DraftFloorLinkSource
import com.intramap.data.supabase.DraftMapEditSource
import com.intramap.data.supabase.DraftMapReviewSource
import com.intramap.data.supabase.DraftReviewSnapshot
import com.intramap.domain.mapping.DraftMapEdits
import com.intramap.navigation.validateNavigationMap
import com.intramap.ui.map.InteractiveIndoorMap
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftMapReviewScreen(client: SupabaseClient, owner: MappingBuilding, versionId: String, onBack: () -> Unit, onPublished: () -> Unit) {
    val api = remember(client) { DraftMapReviewSource(client) }
    val writer = remember(client) { DraftMapEditSource(client) }
    val linkWriter = remember(client) { DraftFloorLinkSource(client) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(versionId) { mutableStateOf<DraftReviewSnapshot?>(null) }
    var edits by remember(versionId) { mutableStateOf<DraftMapEdits?>(null) }
    var floorId by remember(versionId) { mutableStateOf("") }
    var reviewed by remember(versionId) { mutableStateOf(emptySet<String>()) }
    var selectedId by remember(versionId) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("ROOM") }
    var tools by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var joinOrigin by remember { mutableStateOf<String?>(null) }
    var joinVerified by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var requiresReload by remember { mutableStateOf(false) }
    var confirmPublish by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Loading draft...") }
    val maxHeight = (LocalConfiguration.current.screenHeightDp*0.45f).dp
    val building = edits?.current
    val selected = building?.nodes?.firstOrNull { it.nodeId == selectedId }
    val dirty = edits?.dirty == true
    val errors = remember(building) { building?.let { validateNavigationMap(it) }.orEmpty() }
    val complete = building != null && building.floors.isNotEmpty() && building.edges.isNotEmpty() && errors.isEmpty() &&
        !dirty && !requiresReload && reviewed.containsAll(building.floors.map { it.floorId })
    LaunchedEffect(versionId,owner.id,reload) {
        busy = true; snapshot = null; edits = null; reviewed = emptySet(); selectedId = null; moving = false; joinOrigin = null
        try {
            val result = api.load(versionId)
            val graph = result.asBuilding(owner)
            snapshot = result; edits = DraftMapEdits(graph); requiresReload = false
            if (graph.floors.none { it.floorId == floorId }) floorId = graph.floors.firstOrNull()?.floorId.orEmpty()
            status = "Inspect every floor. Changes remain local until Save edits; undo applies only to unsaved edits."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Could not load the draft. Check sign-in and migrations. Verify server state after any interrupted save or publication." }
        finally { busy = false }
    }
    LaunchedEffect(selected) { label = selected?.name.orEmpty(); type = selected?.type ?: "ROOM"; moving = false; joinVerified = false }
    fun edit(action: (DraftMapEdits) -> DraftMapEdits) {
        if (busy || requiresReload) return
        val before = edits ?: return
        try {
            val next = action(before)
            if (next != before) { edits = next; reviewed = emptySet(); moving = false; joinVerified = false; status = "Unsaved draft edits. Verify geometry in the building before saving." }
        } catch (e: IllegalArgumentException) { status = e.message ?: "Invalid edit." }
        catch (_: NoSuchElementException) { status = "The selected point or path is no longer present." }
    }
    fun request(action: String) {
        if (busy) return
        if (dirty) discardAction = action else if (action == "reload") reload++ else onBack()
    }
    fun changeFloorLink(operation: suspend (String) -> Unit) {
        if (busy || requiresReload || edits?.dirty == true) return
        val baseline = snapshot ?: return
        val graph = edits?.current ?: return
        if (validateNavigationMap(graph).isNotEmpty()) { status = "Correct the draft before changing floor links."; return }
        busy = true; reviewed = emptySet(); moving = false; joinOrigin = null
        status = "Saving floor-link change…"
        scope.launch {
            try {
                operation(baseline.fingerprint)
                val fresh = api.load(versionId)
                val saved = fresh.asBuilding(owner)
                snapshot = fresh; edits = DraftMapEdits(saved); requiresReload = false; reviewed = emptySet()
                status = "Floor-link change saved and reloaded. Review every floor before publishing."
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { requiresReload = true; reviewed = emptySet(); status = "Floor-link change rejected or unconfirmed. Reload before further writes. Check migration 0007, permissions, matching types and connector groups." }
            finally { busy = false }
        }
    }
    BackHandler { request("leave") }
    if (discardAction != null) AlertDialog(onDismissRequest = { discardAction = null },title = { Text("Discard the local edit preview?") },
        text = { Text("Unsaved edits and undo history are memory-only. Reloading reads server state; a previously interrupted save may already have reached the server.") },
        confirmButton = { TextButton(enabled = !busy,onClick = {
            val action = discardAction; discardAction = null
            if (action == "reload") reload++ else onBack()
        }) { Text("Discard preview and continue") } },dismissButton = { TextButton(onClick = { discardAction = null }) { Text("Stay") } })
    val current = snapshot
    if (confirmPublish && current != null) AlertDialog(onDismissRequest = { confirmPublish = false },title = { Text("Publish reviewed map?") },
        text = { Text("Publish all floors of this saved snapshot. The server rejects a changed snapshot. Visual review does not establish positioning accuracy or create missing floor links.") },
        confirmButton = { TextButton(enabled = !busy && complete,onClick = {
            confirmPublish = false; busy = true
            scope.launch {
                try { api.publish(versionId,current.fingerprint); onPublished() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { reviewed = emptySet(); requiresReload = true; status = "Publication rejected or unconfirmed. Verify the published version and reload before retrying." }
                finally { busy = false }
            }
        }) { Text("Publish") } },dismissButton = { TextButton(onClick = { confirmPublish = false }) { Text("Cancel") } })
    Scaffold(topBar = { TopAppBar(title = { Text("Draft map · ${owner.name}") },navigationIcon = { TextButton(enabled = !busy,onClick = { request("leave") }) { Text("Back") } }) },
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(status,style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(enabled = !busy && !requiresReload && edits?.canUndo == true,onClick = { edit { it.undo() } }) { Text("Undo unsaved") }
                    OutlinedButton(enabled = !busy && !requiresReload && edits?.canRedo == true,onClick = { edit { it.redo() } }) { Text("Redo") }
                    Button(enabled = !busy && !requiresReload && dirty && errors.isEmpty(),onClick = {
                        val changes = edits; val baseline = snapshot
                        if (changes != null && baseline != null) {
                            busy = true
                            scope.launch {
                                try {
                                    writer.save(versionId,baseline.fingerprint,changes)
                                    val fresh = api.load(versionId)
                                    val graph = fresh.asBuilding(owner)
                                    snapshot = fresh; edits = DraftMapEdits(graph); reviewed = emptySet(); requiresReload = false; joinOrigin = null; moving = false
                                    status = "Edits saved and reloaded. Inspect every floor again before publishing."
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { reviewed = emptySet(); requiresReload = true; status = "Save rejected or unconfirmed. Preview retained. Reload to reconcile; stale drafts, bounds or registered references may prevent the edit." }
                                finally { busy = false }
                            }
                        }
                    }) { Text("Save edits") }
                    TextButton(enabled = !busy,onClick = { request("reload") }) { Text("Reload") }
                }
                if (building != null) key(current?.fingerprint,building) {
                    DraftFloorLinkEditor(building,selected?.takeIf { it.floorId == floorId },
                        enabled = !dirty && !requiresReload && errors.isEmpty() && current != null,busy = busy,
                        onLink = { from,to,kind -> changeFloorLink { fingerprint -> linkWriter.link(versionId,fingerprint,from,to,kind,true) } },
                        onRemove = { edge -> changeFloorLink { fingerprint -> linkWriter.remove(versionId,fingerprint,edge,true) } })
                }
                selected?.takeIf { it.floorId == floorId }?.let { node ->
                    val graph = building ?: return@let
                    Text("${node.name} · ${node.type} · ${node.nodeId.takeLast(12)}")
                    TextButton(enabled = !busy && !requiresReload,onClick = { tools = !tools }) { Text(if (tools) "Hide edit tools" else "Edit selected point") }
                    if (tools) {
                        OutlinedTextField(label,{ label = it.take(120) },label = { Text("Place name") },enabled = !busy && !requiresReload,modifier = Modifier.fillMaxWidth())
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ROOM","ENTRANCE","JUNCTION","STAIRS","ELEVATOR").forEach { kind -> FilterChip(selected = type == kind,enabled = !busy && !requiresReload,onClick = { type = kind },label = { Text(kind) }) }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled = !busy && !requiresReload,onClick = { edit { it.renameNode(node.nodeId,label,type) } }) { Text("Apply name/type") }
                            TextButton(enabled = !busy && !requiresReload,onClick = { moving = !moving; joinOrigin = null }) { Text(if (moving) "Cancel move" else "Move by map tap") }
                            TextButton(enabled = !busy && !requiresReload,onClick = { joinOrigin = node.nodeId; joinVerified = false; moving = false }) { Text("Join from here") }
                        }
                        if (moving) Text("Tap the corrected position on the map. Maximum 3 m; visual-reference floors may require re-registration.")
                        graph.edges.filter { it.sourceNodeId == node.nodeId || it.destinationNodeId == node.nodeId }.forEach { edge ->
                            val otherId = if (edge.sourceNodeId == node.nodeId) edge.destinationNodeId else edge.sourceNodeId
                            val other = graph.nodes.firstOrNull { it.nodeId == otherId }
                            FilterChip(selected = edge.isAvailable,enabled = !busy && !requiresReload,
                                onClick = { edit { it.setAvailable(edge.edgeId,!edge.isAvailable) } },
                                label = { Text("${other?.name ?: otherId}: ${if (edge.isAvailable) "open" else "blocked"}") })
                        }
                    }
                    if (joinOrigin != null && joinOrigin != node.nodeId) {
                        val origin = graph.nodes.firstOrNull { it.nodeId == joinOrigin }
                        Text("Join ${origin?.name.orEmpty()} to ${node.name}: nearby same-floor gaps only, never through walls.")
                        Row { Checkbox(joinVerified,onCheckedChange = { joinVerified = it },enabled = !busy && !requiresReload); Text("I verified a direct physical connection",Modifier.padding(top = 12.dp)) }
                        Button(enabled = joinVerified && !busy && !requiresReload,onClick = {
                            val from = joinOrigin
                            if (from != null) { edit { it.joinEndpoints(from,node.nodeId,"join_${UUID.randomUUID()}",joinVerified) }; joinOrigin = null }
                        }) { Text("Stage verified join") }
                    }
                }
                if (joinOrigin != null) TextButton(onClick = { joinOrigin = null; joinVerified = false }) { Text("Cancel join selection") }
                if (building != null) {
                    Text("${building.nodes.size} nodes · ${building.edges.size} paths · ${reviewed.size}/${building.floors.size} floors reviewed",style = MaterialTheme.typography.labelSmall)
                    errors.forEach { Text(it,color = MaterialTheme.colorScheme.error) }
                    Row { Checkbox(checked = floorId in reviewed,enabled = !busy && !dirty && !requiresReload && floorId.isNotBlank() && errors.isEmpty(),onCheckedChange = { checked -> reviewed = if (checked) reviewed + floorId else reviewed - floorId }); Text("I inspected this saved floor",Modifier.padding(top = 12.dp)) }
                }
                Button(enabled = !busy && complete,onClick = { confirmPublish = true }) { Text("Publish reviewed map") }
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (building != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    building.floors.forEach { floor -> FilterChip(selected = floorId == floor.floorId,enabled = !busy,onClick = { floorId = floor.floorId; selectedId = null; moving = false; joinOrigin = null },label = { Text(floor.floorName) }) }
                }
                OutlinedTextField(query,{ query = it },label = { Text("Find a place or point ID on this floor") },singleLine = true,modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                if (query.isNotBlank()) Column(Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                    building.nodes.filter { it.floorId == floorId && (it.name.contains(query,true) || it.nodeId.contains(query,true)) }.take(8).forEach { node ->
                        ListItem(headlineContent = { Text(node.name) },supportingContent = { Text(node.nodeId.takeLast(12)) },modifier = Modifier.clickable(enabled = !busy) { selectedId = node.nodeId; tools = true; query = "" })
                    }
                }
                InteractiveIndoorMap(building = building,floorId = floorId,route = emptyList(),destinationId = selectedId,position = null,spreadM = null,
                    blocked = emptySet(),follow = false,recenterToken = reload,topInsetPx = 0f,bottomInsetPx = 0f,onGesture = {},
                    onPlace = { if (!busy) { selectedId = it.nodeId; tools = true } },modifier = Modifier.fillMaxWidth().weight(1f),showJunctions = true,
                    onMapPoint = if (moving && !busy && !requiresReload && selected != null) { { x,y -> edit { it.moveNode(selected.nodeId,x,y) } } } else null)
            }
        }
    }
}
