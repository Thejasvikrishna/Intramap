package com.intramap.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.LocationNode
import com.intramap.data.model.MappingBuilding
import com.intramap.data.supabase.DraftMapReviewSource
import com.intramap.data.supabase.DraftReviewSnapshot
import com.intramap.navigation.validateNavigationMap
import com.intramap.ui.map.InteractiveIndoorMap
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftMapReviewScreen(client: SupabaseClient, owner: MappingBuilding, versionId: String, onBack: () -> Unit, onPublished: () -> Unit) {
    val api = remember(client) { DraftMapReviewSource(client) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(versionId) { mutableStateOf<DraftReviewSnapshot?>(null) }
    var floorId by remember(versionId) { mutableStateOf("") }
    var reviewed by remember(versionId) { mutableStateOf(emptySet<String>()) }
    var selected by remember(versionId) { mutableStateOf<LocationNode?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading the complete draft graph...") }
    LaunchedEffect(versionId,reload) {
        busy = true; snapshot = null; reviewed = emptySet(); selected = null
        try {
            val result = api.load(versionId)
            result.asBuilding(owner)
            snapshot = result; floorId = result.floors.minByOrNull { it.floorNumber }?.floorKey.orEmpty()
            status = "Inspect all floors. Check landmarks, route joins and physical accessibility before confirming each floor."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Could not load the draft. Check sign-in and migration 0004. If publication was interrupted, verify the published version before retrying." }
        finally { busy = false }
    }
    BackHandler { if (!busy) onBack() }
    val current = snapshot
    val building = remember(current,owner) { current?.asBuilding(owner) }
    val errors = remember(building) { building?.let { validateNavigationMap(it) }.orEmpty() }
    val complete = building != null && building.floors.isNotEmpty() && building.edges.isNotEmpty() &&
        errors.isEmpty() && reviewed.containsAll(building.floors.map { it.floorId })
    if (confirm && current != null) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Publish this reviewed snapshot?") },
        text = { Text("This publishes the entire draft. A server-side check rejects it if the graph changed after loading. Visual review does not establish sensor accuracy or create missing floor connections.") },
        confirmButton = { TextButton(enabled = !busy && complete,onClick = {
            confirm = false; busy = true
            scope.launch {
                try { api.publish(versionId,current.fingerprint); onPublished() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { reviewed = emptySet(); status = "Publication rejected or unconfirmed. Reload the draft; if it is already published, verify that version before retrying." }
                finally { busy = false }
            }
        }) { Text("Publish reviewed map") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    Scaffold(topBar = { TopAppBar(title = { Text("Review · ${owner.name}") },navigationIcon = { TextButton(enabled = !busy,onClick = onBack) { Text("Back") } }) },
        bottomBar = { Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                selected?.takeIf { it.floorId == floorId }?.let { Text("${it.name} · ${it.type}") }
                if (building != null) {
                    Text("${building.nodes.size} nodes · ${building.edges.size} paths · ${reviewed.size}/${building.floors.size} floors reviewed")
                    Text("Crossing paths are not automatically connected. Floors require explicit stairs/elevator links. No walls are inferred.",style = MaterialTheme.typography.bodySmall)
                    errors.forEach { Text(it,color = MaterialTheme.colorScheme.error) }
                    Row {
                        Checkbox(checked = floorId in reviewed,enabled = !busy && floorId.isNotBlank() && errors.isEmpty(),onCheckedChange = { checked -> reviewed = if (checked) reviewed + floorId else reviewed - floorId })
                        Text("I inspected this floor's paths, names and joins",Modifier.padding(top = 12.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy,onClick = { reload++ }) { Text("Reload draft") }
                    Button(enabled = !busy && complete,onClick = { confirm = true }) { Text("Publish") }
                }
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (building != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    building.floors.forEach { floor -> FilterChip(selected = floorId == floor.floorId,onClick = { floorId = floor.floorId; selected = null },label = { Text(floor.floorName) }) }
                }
                InteractiveIndoorMap(building,floorId,emptyList(),selected?.nodeId,null,null,emptySet(),false,reload,0f,0f,{}, { selected = it },Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
