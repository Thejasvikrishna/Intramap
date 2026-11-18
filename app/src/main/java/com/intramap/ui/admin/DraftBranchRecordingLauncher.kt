package com.intramap.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.intramap.BuildConfig
import com.intramap.data.model.MappingBuilding
import com.intramap.data.repository.WalkingDraftStore
import com.intramap.data.supabase.DraftMapReviewSource
import com.intramap.data.supabase.DraftReviewSnapshot
import com.intramap.domain.mapping.branchAnchorProblem
import com.intramap.ui.map.InteractiveIndoorMap
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Selects real imported graph anchors. Never edits or truncates the parent route. */
@Composable
fun DraftBranchRecordingLauncher(client: SupabaseClient,owner: MappingBuilding,versionId: String,floorKey: String,enabled: Boolean,onReady: (String,String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val launchRecording by rememberUpdatedState(onReady)
    var snapshot by remember(versionId,floorKey) { mutableStateOf<DraftReviewSnapshot?>(null) }
    var start by remember(versionId,floorKey) { mutableStateOf<String?>(null) }
    var facing by remember(versionId,floorKey) { mutableStateOf<String?>(null) }
    var chooseFacing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Load the saved path to select a branch start.") }
    val graph = remember(snapshot,owner) { snapshot?.asBuilding(owner) }
    val floorNodes = snapshot?.nodes.orEmpty().filter { it.floorKey == floorKey && it.versionId == versionId }
    val a = floorNodes.firstOrNull { it.nodeKey == start }
    val b = floorNodes.firstOrNull { it.nodeKey == facing }
    val problem = branchAnchorProblem(snapshot?.nodes.orEmpty(),versionId,floorKey,start,facing)
    val valid = snapshot != null && problem == null
    LaunchedEffect(client,versionId,floorKey,reload) {
        busy = true; snapshot = null; start = null; facing = null; confirm = false; chooseFacing = false
        try {
            snapshot = DraftMapReviewSource(client).load(versionId)
            message = "Tap the saved path for the start, then choose Facing and tap a different point. Only the new leg will be recorded."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Could not load saved route anchors. Check your connection and editor access, then reload." }
        finally { busy = false }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!busy) confirm = false },title = { Text("Start a new branch here?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stand at ${a?.name.orEmpty()} and align Forward toward ${b?.name.orEmpty()}. Walk back without recording if you need to reach that point. Tapping does not locate you. Existing paths and their places remain unchanged.")
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking local work, sign-in and current map…") }
        } },
        confirmButton = { TextButton(enabled = enabled && !busy && valid,onClick = {
            val baseline = snapshot
            val selectedStart = start
            val selectedFacing = facing
            val user = client.auth.currentUserOrNull()?.id
            if (user == null) { confirm = false; message = "Sign in before starting a branch." }
            else if (baseline != null && selectedStart != null && selectedFacing != null) {
                busy = true
                scope.launch {
                    try {
                        val store = WalkingDraftStore.open(File(context.noBackupFilesDir,"walking_drafts"),"${BuildConfig.SUPABASE_URL}|$user",owner.id,floorKey)
                        if (store.load().await() != null) {
                            message = "Unfinished local recording found. Open Walk and record a route to resume it; submit and import it before starting a new branch. Nothing was overwritten."
                            confirm = false
                            return@launch
                        }
                        val fresh = DraftMapReviewSource(client).load(versionId)
                        if (fresh.fingerprint != baseline.fingerprint) {
                            snapshot = null; start = null; facing = null; confirm = false
                            message = "The saved map changed. Reload and select the anchors again. No recording was started."
                            return@launch
                        }
                        val freshProblem = branchAnchorProblem(fresh.nodes,versionId,floorKey,selectedStart,selectedFacing)
                        if (freshProblem != null) {
                            confirm = false; message = freshProblem
                            return@launch
                        }
                        if (!latestEnabled || client.auth.currentUserOrNull()?.id != user) {
                            confirm = false; message = "Workspace or sign-in changed during setup. Check the selected building and account, then try again."
                            return@launch
                        }
                        confirm = false
                        launchRecording(selectedStart,selectedFacing)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { confirm = false; message = "Branch setup could not be verified. Check sign-in, connection and local storage; no local recording was replaced." }
                    finally { busy = false }
                }
            }
        }) { Text(if (busy) "Checking…" else "I am here and facing this direction") } },
        dismissButton = { TextButton(enabled = !busy,onClick = { confirm = false }) { Text("Cancel") } })
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Record a branch from a saved point",style = MaterialTheme.typography.titleMedium)
            Text(message,style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(enabled = enabled && !busy,onClick = { reload++ }) { Text("Reload saved route anchors") }
            val building = graph
            if (building != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !chooseFacing,enabled = enabled && !busy,onClick = { chooseFacing = false },label = { Text("1. Start point") })
                    FilterChip(selected = chooseFacing,enabled = enabled && !busy,onClick = { chooseFacing = true },label = { Text("2. Facing point") })
                }
                InteractiveIndoorMap(building,floorKey,emptyList(),if (chooseFacing) facing else start,start,null,emptySet(),false,reload,0f,0f,
                    onGesture = {},onPlace = { node ->
                        if (enabled && !busy && node.floorId == floorKey && floorNodes.any { it.nodeKey == node.nodeId }) {
                            if (chooseFacing) facing = node.nodeId
                            else { start = node.nodeId; if (facing == start) facing = null }
                        }
                    },modifier = Modifier.fillMaxWidth().height(220.dp),showJunctions = true)
                Text("Start: ${a?.let { "${it.name} · ${it.nodeKey.takeLast(8)}" } ?: "Tap a saved point"}",style = MaterialTheme.typography.labelSmall)
                Text("Facing: ${b?.let { "${it.name} · ${it.nodeKey.takeLast(8)}" } ?: "Choose a different saved point"}",style = MaterialTheme.typography.labelSmall)
                if (problem != null) Text(problem,style = MaterialTheme.typography.bodySmall,color = if (start != null && facing != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = enabled && !busy && valid,onClick = { confirm = true }) { Text("Record new leg from selected start") }
            }
            Text("Submit and import the current route first. This starts a separate recording at an imported anchor; it does not branch an unsubmitted local trace or draw an unwalked connection.",style = MaterialTheme.typography.bodySmall)
        }
    }
}
