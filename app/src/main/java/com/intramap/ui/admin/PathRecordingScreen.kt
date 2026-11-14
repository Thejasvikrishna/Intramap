package com.intramap.ui.admin

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.model.PathRecordingPoint
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMappingDataSource
import kotlinx.coroutines.launch

@Composable
fun PathRecordingScreen(
    buildingId: String,
    floorKey: String,
    startAnchorKey: String?,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dataSource = SupabaseClientFactory.client?.let { SupabaseMappingDataSource(it) }

    var recordingId by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var sequence by remember { mutableIntStateOf(0) }
    var x by remember { mutableStateOf(0f) }
    var y by remember { mutableStateOf(0f) }
    var heading by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("Confirm the starting anchor, then start recording.") }
    val points = remember { mutableStateListOf<PathRecordingPoint>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Record path") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (dataSource == null) {
                Text("Supabase is not configured.")
                return@Column
            }

            Text("Record a real walking path. Mark doors, turns and connector entrances as you pass them.")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !recording,
                    onClick = {
                        scope.launch {
                            runCatching {
                                val result = dataSource.startRecording(buildingId, floorKey, startAnchorKey)
                                recordingId = result.id
                                sequence = 0
                                points.clear()
                                recording = true
                                status = "Recording. Walk the route and add markers when needed."
                            }.onFailure { status = it.message ?: "Could not start recording." }
                        }
                    }
                ) { Text("Start") }

                OutlinedButton(
                    enabled = recording,
                    onClick = {
                        scope.launch {
                            runCatching {
                                val id = recordingId ?: error("No active recording.")
                                dataSource.appendRecordingPoint(id, sequence, x, y, SystemClock.elapsedRealtime(), heading, "TURN")
                                points += PathRecordingPoint(id, sequence, x, y, SystemClock.elapsedRealtime(), heading, "TURN")
                                sequence++
                                status = "Turn marker added."
                            }.onFailure { status = it.message ?: "Could not add marker." }
                        }
                    }
                ) { Text("Mark turn") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = recording,
                    onClick = {
                        scope.launch {
                            runCatching {
                                val id = recordingId ?: error("No active recording.")
                                dataSource.appendRecordingPoint(id, sequence, x, y, SystemClock.elapsedRealtime(), heading, "DOOR")
                                points += PathRecordingPoint(id, sequence, x, y, SystemClock.elapsedRealtime(), heading, "DOOR")
                                sequence++
                                status = "Door marker added."
                            }.onFailure { status = it.message ?: "Could not add marker." }
                        }
                    }
                ) { Text("Mark door") }

                OutlinedButton(
                    enabled = recording,
                    onClick = {
                        scope.launch {
                            runCatching {
                                val id = recordingId ?: error("No active recording.")
                                dataSource.submitRecording(id)
                                recording = false
                                status = "Recording submitted for review."
                            }.onFailure { status = it.message ?: "Could not submit recording." }
                        }
                    }
                ) { Text("Finish and submit") }
            }

            Text(status)
            Text("Recorded points: ${points.size}")

            LazyColumn(Modifier.weight(1f)) {
                items(points) { point ->
                    ListItem(
                        headlineContent = { Text("Point ${point.seq + 1}") },
                        supportingContent = {
                            Text("(${"%.1f".format(point.x)}, ${"%.1f".format(point.y)}) m · ${point.marker ?: "movement"}")
                        }
                    )
                }
            }
        }
    }
}
