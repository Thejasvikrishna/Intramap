package com.intramap.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.data.supabase.SupabaseMappingDataSource
import kotlinx.coroutines.launch
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminMappingScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var buildingKey by remember { mutableStateOf("") }
    var buildingName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var floorKey by remember { mutableStateOf("") }
    var floorName by remember { mutableStateOf("") }
    var floorNumber by remember { mutableStateOf("1") }
    var width by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var placeName by remember { mutableStateOf("") }
    var placeType by remember { mutableStateOf("ROOM") }
    var x by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var buildingId by remember { mutableStateOf<String?>(null) }
    var versionId by remember { mutableStateOf<String?>(null) }

    val dataSource = SupabaseClientFactory.client?.let { SupabaseMappingDataSource(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map building") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (dataSource == null) {
                Text("Supabase is not configured. Add SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY to local.properties.")
                return@Column
            }

            OutlinedTextField(value = buildingKey, onValueChange = { buildingKey = it }, label = { Text("Building ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = buildingName, onValueChange = { buildingName = it }, label = { Text("Building name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val building = dataSource.createBuilding(buildingKey.trim(), buildingName.trim(), description.trim())
                            val version = dataSource.createDraftVersion(building.id)
                            buildingId = building.id
                            versionId = version.id
                            status = "Building and draft map version created."
                        }.onFailure { status = it.message ?: "Could not create building." }
                    }
                },
                enabled = buildingKey.isNotBlank() && buildingName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create building and draft map") }

            HorizontalDivider()

            OutlinedTextField(value = floorKey, onValueChange = { floorKey = it }, label = { Text("Floor ID") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = floorName, onValueChange = { floorName = it }, label = { Text("Floor name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = floorNumber, onValueChange = { floorNumber = it }, label = { Text("Floor number") }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = width, onValueChange = { width = it }, label = { Text("Width (m)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = height, onValueChange = { height = it }, label = { Text("Height (m)") }, modifier = Modifier.weight(1f))
            }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val currentVersion = versionId ?: error("Create a building first.")
                            dataSource.addFloor(
                                currentVersion,
                                floorKey.trim(),
                                floorNumber.toIntOrNull() ?: 1,
                                floorName.trim(),
                                width.toFloatOrNull() ?: error("Enter width"),
                                height.toFloatOrNull() ?: error("Enter height")
                            )
                            status = "Floor added."
                        }.onFailure { status = it.message ?: "Could not add floor." }
                    }
                },
                enabled = versionId != null && floorKey.isNotBlank() && floorName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add floor") }

            HorizontalDivider()

            OutlinedTextField(value = placeName, onValueChange = { placeName = it }, label = { Text("Place name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = placeType, onValueChange = { placeType = it }, label = { Text("Type (ROOM, ENTRANCE, STAIRS, ELEVATOR)") }, modifier = Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = x, onValueChange = { x = it }, label = { Text("X (m)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = y, onValueChange = { y = it }, label = { Text("Y (m)") }, modifier = Modifier.weight(1f))
            }

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val currentVersion = versionId ?: error("Create a building first.")
                            val currentFloor = floorKey.ifBlank { error("Add a floor first.") }

                            dataSource.addNode(
                                currentVersion,
                                placeName.trim().lowercase().replace(" ", "_"),
                                currentFloor,
                                placeName.trim(),
                                placeType.trim().uppercase(),
                                x.toFloatOrNull() ?: error("Enter X"),
                                y.toFloatOrNull() ?: error("Enter Y")
                            )

                            status = "Place added."
                        }.onFailure { status = it.message ?: "Could not add place." }
                    }
                },
                enabled = versionId != null && placeName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Add place") }

            HorizontalDivider()

            Button(
                onClick = {
                    scope.launch {
                        runCatching {
                            val currentVersion = versionId ?: error("Create a building first.")
                            dataSource.publishVersion(currentVersion)
                            status = "Map version published."
                        }.onFailure { status = it.message ?: "Could not publish map." }
                    }
                },
                enabled = versionId != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Publish map version") }

            if (status.isNotBlank()) {
                Text(status, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
