package com.intramap.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.domain.mapping.*

/** Caller must checkpoint onChange and supply frozen state once any upload is attempted. */
@Composable
fun RoutePlaceEditor(
    edits: RoutePlaceEdits,
    currentSampleIndex: Int,
    enabled: Boolean,
    onChange: (RoutePlaceEdits) -> Unit
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(RoutePlaceCategory.ROOM) }
    var error by remember { mutableStateOf<String?>(null) }
    val editable = enabled && !edits.frozen
    fun applyEdit(operation: () -> RoutePlaceEdits): Boolean = try {
        onChange(operation()); error = null; true
    } catch (e: IllegalArgumentException) { error = e.message ?: "Invalid place change"; false }
      catch (e: IllegalStateException) { error = e.message ?: "Place editing is locked"; false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Places on this route",style = MaterialTheme.typography.titleMedium)
        Text("Add rooms, shops and offices at observed route positions. Several places can share one position without overwriting each other.",style = MaterialTheme.typography.bodySmall)
        if (edits.frozen) Text("Place edits are locked because upload has started.",style = MaterialTheme.typography.bodySmall)
        Button(enabled = editable && currentSampleIndex >= 0,onClick = {
            adding = true; editingId = null; name = ""; category = RoutePlaceCategory.ROOM; error = null
        }) { Text("Add place here") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = editable && edits.canUndo,onClick = { applyEdit { edits.undo() } }) { Text("Undo place edit") }
            TextButton(enabled = editable && edits.canRedo,onClick = { applyEdit { edits.redo() } }) { Text("Redo") }
        }
        if (edits.places.isEmpty()) Text("No places marked yet.",style = MaterialTheme.typography.bodySmall)
        edits.places.forEach { place ->
            key(place.placeId) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(place.name,style = MaterialTheme.typography.titleSmall)
                        Text("${place.category.name.lowercase().replaceFirstChar { it.uppercase() }} · route point ${place.sampleIndex + 1}",style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(enabled = editable,onClick = { editingId = place.placeId; adding = false; name = place.name; category = place.category; error = null }) { Text("Edit") }
                            TextButton(enabled = editable && currentSampleIndex >= 0,onClick = { applyEdit { edits.moveToSample(place.placeId,currentSampleIndex) } }) { Text("Move to selected point") }
                            TextButton(enabled = editable,onClick = { deletingId = place.placeId }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        error?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
    }
    if (adding || editingId != null) AlertDialog(
        onDismissRequest = { adding = false; editingId = null },
        title = { Text(if (adding) "Add place" else "Edit place") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name,{ name = it.take(120); error = null },label = { Text("Place name") },singleLine = true,enabled = editable)
                RoutePlaceCategory.entries.forEach { kind ->
                    FilterChip(selected = kind == category,enabled = editable,onClick = { category = kind },label = { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }) })
                }
                error?.let { Text(it,color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = editable && name.isNotBlank(),onClick = {
            val id = editingId
            val saved = if (adding) applyEdit { edits.add(currentSampleIndex,name,category) }
                else if (id != null) applyEdit { edits.rename(id,name,category) } else false
            if (saved) { adding = false; editingId = null }
        }) { Text("Save place") } },
        dismissButton = { TextButton(onClick = { adding = false; editingId = null }) { Text("Cancel") } }
    )
    val deleting = edits.places.firstOrNull { it.placeId == deletingId }
    if (deleting != null) AlertDialog(onDismissRequest = { deletingId = null },title = { Text("Delete ${deleting.name}?") },
        text = { Text("Only the place marker is removed. The walked path is unchanged. You can undo this local edit.") },
        confirmButton = { TextButton(enabled = editable,onClick = { if (applyEdit { edits.remove(deleting.placeId) }) deletingId = null }) { Text("Delete place") } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Cancel") } })
}
