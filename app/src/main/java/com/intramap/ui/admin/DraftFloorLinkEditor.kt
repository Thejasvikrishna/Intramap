package com.intramap.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge

@Composable
fun DraftFloorLinkEditor(
    building: Building, selected: LocationNode?, enabled: Boolean, busy: Boolean,
    onLink: (String,String,String) -> Unit, onRemove: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var originId by remember { mutableStateOf<String?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<PathEdge?>(null) }
    val nodes = remember(building) { building.nodes.associateBy { it.nodeId } }
    val origin = nodes[originId]
    val target = nodes[targetId]
    val ready = enabled && !busy
    fun place(id: String): String {
        val n = nodes[id] ?: return id
        val floor = building.floors.firstOrNull { it.floorId == n.floorId }?.floorName ?: n.floorId
        return "${n.name} · $floor · ${n.nodeId.takeLast(12)}"
    }
    TextButton(enabled = !busy,onClick = { expanded = !expanded }) { Text(if (expanded) "Hide floor-link tools" else "Cross-floor links") }
    if (!expanded) return
    Text("Connections save immediately to the draft, not the published map. Save or discard point edits first. These writes are not part of local undo.",style = MaterialTheme.typography.bodySmall)
    if (!enabled) Text("A clean, reconciled, valid draft is required. Save edits or reload before changing floor links.")
    Text("First mark the actual recorded landings STAIRS or ELEVATOR using the point editor, then save. Coordinates and names alone never establish a connection.",style = MaterialTheme.typography.bodySmall)
    Button(enabled = ready && selected?.type in setOf("STAIRS","ELEVATOR"),onClick = {
        val n = selected
        if (ready && n != null && n.type in setOf("STAIRS","ELEVATOR")) { originId = n.nodeId; targetId = null; verified = false }
    }) { Text("Use selected landing as source") }
    if (origin != null) {
        Text("Source: ${place(origin.nodeId)} (${origin.type})")
        Text("Choose the matching landing on another floor:")
        val candidates = building.nodes.filter { it.floorId != origin.floorId && it.type == origin.type }
            .sortedWith(compareBy<LocationNode> { it.floorId }.thenBy { it.name }.thenBy { it.nodeId })
        if (candidates.isEmpty()) Text("No matching landing on another floor. Record and label it before linking.")
        candidates.forEach { candidate ->
            OutlinedButton(enabled = ready,onClick = { targetId = candidate.nodeId; verified = false },modifier = Modifier.fillMaxWidth()) {
                Text((if (candidate.nodeId == targetId) "Selected: " else "")+place(candidate.nodeId))
            }
        }
        Row {
            Checkbox(checked = verified,enabled = ready && target != null,onCheckedChange = { verified = it })
            Text("I verified these landings share this physical connector in both directions",Modifier.padding(top = 12.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Button(enabled = ready && verified && target != null,onClick = {
                val destination = target
                if (ready && verified && destination != null && destination.floorId != origin.floorId && destination.type == origin.type) {
                    verified = false; onLink(origin.nodeId,destination.nodeId,origin.type)
                }
            }) { Text("Save verified floor link") }
            TextButton(enabled = !busy,onClick = { originId = null; targetId = null; verified = false }) { Text("Cancel selection") }
        }
    }
    Text("Existing floor links",style = MaterialTheme.typography.titleSmall)
    val links = building.edges.filter { e ->
        val a = nodes[e.sourceNodeId]; val b = nodes[e.destinationNodeId]
        a != null && b != null && a.floorId != b.floorId
    }
    if (links.isEmpty()) Text("No explicit floor links in this draft.")
    links.forEach { edge ->
        Text("${place(edge.sourceNodeId)} ↔ ${place(edge.destinationNodeId)} · ${if (edge.isAvailable) "open" else "blocked"}")
        TextButton(enabled = ready,onClick = { removal = edge }) { Text("Remove this floor link…") }
    }
    removal?.let { edge ->
        AlertDialog(onDismissRequest = { removal = null },title = { Text("Remove this draft floor link?") },
            text = { Text("${place(edge.sourceNodeId)} ↔ ${place(edge.destinationNodeId)}\nOnly this link is removed. Landing points and recorded floor paths remain. This saves immediately and is not in local undo.") },
            confirmButton = { TextButton(enabled = ready,onClick = {
                if (ready) { removal = null; onRemove(edge.edgeId) }
            }) { Text("Remove link") } },
            dismissButton = { TextButton(enabled = !busy,onClick = { removal = null }) { Text("Cancel") } })
    }
}
