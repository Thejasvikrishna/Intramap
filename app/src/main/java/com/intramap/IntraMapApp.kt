package com.intramap

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.intramap.data.repository.MapRepository
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.ui.lab.LocalizationLabScreen
import com.intramap.ui.map.MapScreen
import com.intramap.ui.pdr.PdrScreen
import com.intramap.ui.vision.LandmarkScannerScreen
import com.intramap.vision.CameraLandmarkObservation

@Composable
fun IntraMapApp(repo: MapRepository) {
    val nav = rememberNavController()

    // Retains each Lab engine during navigation within this app composition.
    // This does not persist experiments through process death.
    val labEngines = remember(repo) {
        mutableMapOf<String, LocalizationLabEngine>()
    }

    NavHost(
        navController = nav,
        startDestination = "buildings"
    ) {

        // Building selection
        composable("buildings") {
            val buildings = remember(repo) {
                repo.getBuildings()
            }

            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Select a building",
                    style = MaterialTheme.typography.headlineSmall
                )

                if (buildings.isEmpty()) {
                    Text("No maps found in assets/maps")
                } else {
                    LazyColumn {
                        items(
                            items = buildings,
                            key = { it.buildingId }
                        ) { building ->
                            ListItem(
                                headlineContent = {
                                    Text(building.buildingName)
                                },
                                supportingContent = {
                                    Text(building.description)
                                },
                                modifier = Modifier.clickable {
                                    nav.navigate(
                                        "floors/${building.buildingId}"
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Floor selection and module launch buttons
        composable("floors/{bid}") { entry ->
            val buildingId =
                entry.arguments?.getString("bid").orEmpty()

            val building = repo.getBuilding(buildingId)

            if (building == null) {
                NavigationErrorScreen(
                    message = "Building not found.",
                    onBack = { nav.popBackStack() }
                )
            } else {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    TextButton(
                        onClick = { nav.popBackStack() }
                    ) {
                        Text("Back")
                    }

                    Text(
                        text = building.buildingName,
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Text("Select your floor or open a development module.")

                    LazyColumn {
                        item {
                            TextButton(
                                enabled = building.nodes.isNotEmpty() &&
                                        building.floors.isNotEmpty(),
                                onClick = {
                                    nav.navigate(
                                        "lab/${building.buildingId}"
                                    )
                                }
                            ) {
                                Text("Open Localization Lab")
                            }
                        }

                        item {
                            TextButton(
                                enabled = building.floors.isNotEmpty(),
                                onClick = {
                                    nav.navigate(
                                        "scanner/${building.buildingId}"
                                    )
                                }
                            ) {
                                Text("Open Landmark Scanner")
                            }
                        }

                        item {
                            Text("Available floors")
                        }

                        items(
                            items = building.floors,
                            key = { it.floorId }
                        ) { floor ->
                            ListItem(
                                headlineContent = {
                                    Text(floor.floorName)
                                },
                                supportingContent = {
                                    Text("Open indoor map")
                                },
                                modifier = Modifier.clickable {
                                    nav.navigate(
                                        "map/${building.buildingId}/${floor.floorId}"
                                    )
                                }
                            )
                        }

                        item {
                            val initialFloor =
                                building.floors.firstOrNull()

                            TextButton(
                                enabled = initialFloor != null,
                                onClick = {
                                    initialFloor?.let { floor ->
                                        nav.navigate(
                                            "pdr/${building.buildingId}/${floor.floorId}"
                                        )
                                    }
                                }
                            ) {
                                Text("Sensor test (walk and track)")
                            }
                        }
                    }
                }
            }
        }

        // Indoor map and routing
        composable("map/{bid}/{fid}") { entry ->
            val buildingId =
                entry.arguments?.getString("bid").orEmpty()

            val floorId =
                entry.arguments?.getString("fid").orEmpty()

            val building = repo.getBuilding(buildingId)

            if (
                building == null ||
                building.floors.none { it.floorId == floorId }
            ) {
                NavigationErrorScreen(
                    message = "Building or floor not found.",
                    onBack = { nav.popBackStack() }
                )
            } else {
                MapScreen(
                    building = building,
                    initialFloorId = floorId,
                    onBack = { nav.popBackStack() }
                )
            }
        }

        // Independent live PDR test
        composable("pdr/{bid}/{fid}") { entry ->
            val buildingId =
                entry.arguments?.getString("bid").orEmpty()

            val floorId =
                entry.arguments?.getString("fid").orEmpty()

            val building = repo.getBuilding(buildingId)

            if (
                building == null ||
                building.floors.none { it.floorId == floorId }
            ) {
                NavigationErrorScreen(
                    message = "Building or floor not found.",
                    onBack = { nav.popBackStack() }
                )
            } else {
                PdrScreen(
                    building = building,
                    floorId = floorId,
                    onBack = { nav.popBackStack() }
                )
            }
        }

        // Standalone scanner: recognition only
        composable("scanner/{bid}") { entry ->
            val buildingId =
                entry.arguments?.getString("bid").orEmpty()

            val building = repo.getBuilding(buildingId)

            if (building == null) {
                NavigationErrorScreen(
                    message = "Building not found.",
                    onBack = { nav.popBackStack() }
                )
            } else {
                LandmarkScannerScreen(
                    building = building,
                    onBack = { nav.popBackStack() }
                )
            }
        }

        // Localization Lab with a retained engine and incoming camera result
        composable("lab/{bid}") { entry ->
            val buildingId =
                entry.arguments?.getString("bid").orEmpty()

            val building = repo.getBuilding(buildingId)

            if (
                building == null ||
                building.nodes.isEmpty() ||
                building.floors.isEmpty()
            ) {
                NavigationErrorScreen(
                    message = "Building or localization data not found.",
                    onBack = { nav.popBackStack() }
                )
            } else {
                val engine = labEngines.getOrPut(buildingId) {
                    val startingNode =
                        building.nodes.firstOrNull {
                            it.type == "ENTRANCE"
                        } ?: building.nodes.first()

                    LocalizationLabEngine(building).apply {
                        reset(startingNode.nodeId)
                    }
                }

                val resultFlow = remember(entry) {
                    entry.savedStateHandle.getStateFlow<Bundle?>(
                        CameraLandmarkObservation.RESULT_KEY,
                        null
                    )
                }

                val pendingObservation by resultFlow.collectAsState()

                LocalizationLabScreen(
                    building = building,
                    onBack = { nav.popBackStack() },
                    externalEngine = engine,
                    cameraObservation = pendingObservation,
                    onObservationConsumed = {
                        entry.savedStateHandle.set<Bundle?>(
                            CameraLandmarkObservation.RESULT_KEY,
                            null
                        )
                    },
                    onOpenScanner = {
                        nav.navigate(
                            "labscanner/${building.buildingId}"
                        )
                    }
                )
            }
        }

        // Scanner launched from the Lab: returns a confirmed observation
        composable("labscanner/{bid}") { entry ->
            val buildingId =
                entry.arguments?.getString("bid").orEmpty()

            val building = repo.getBuilding(buildingId)

            if (building == null) {
                NavigationErrorScreen(
                    message = "Building not found.",
                    onBack = { nav.popBackStack() }
                )
            } else {
                LandmarkScannerScreen(
                    building = building,
                    onBack = { nav.popBackStack() },
                    onUseObservation = { observation ->
                        nav.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set<Bundle>(
                                CameraLandmarkObservation.RESULT_KEY,
                                observation.toBundle()
                            )

                        nav.popBackStack()
                    }
                )
            }
        }
    }
}

@Composable
private fun NavigationErrorScreen(
    message: String,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(message)

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}