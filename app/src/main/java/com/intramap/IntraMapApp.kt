package com.intramap

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.intramap.data.repository.MapRepository
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.ui.admin.AdminMappingScreen
import com.intramap.ui.admin.PathRecordingScreen
import com.intramap.ui.diagnostics.NavigationDiagnosticsScreen
import com.intramap.ui.lab.LocalizationLabScreen
import com.intramap.ui.map.MapScreen
import com.intramap.ui.pdr.PdrScreen
import com.intramap.ui.vision.LandmarkScannerScreen
import com.intramap.vision.CameraLandmarkObservation

@Composable
fun IntraMapApp(repo: MapRepository) {
    val nav = rememberNavController()

    val labEngines = remember(repo) {
        mutableMapOf<String, LocalizationLabEngine>()
    }

    NavHost(
        navController = nav,
        startDestination = "buildings"
    ) {
        composable("buildings") {
            val buildings = remember(repo) { repo.getBuildings() }

            Column(Modifier.padding(16.dp)) {
                Text("IntraMap", style = MaterialTheme.typography.headlineMedium)
                Text("Find places and navigate indoors.")

                TextButton(onClick = { nav.navigate("admin") }) {
                    Text("Admin: add building or record path")
                }

                if (buildings.isEmpty()) {
                    Text("No published maps are available yet.")
                } else {
                    LazyColumn {
                        items(buildings, key = { it.buildingId }) { building ->
                            ListItem(
                                headlineContent = { Text(building.buildingName) },
                                supportingContent = { Text(building.description) },
                                modifier = Modifier.clickable {
                                    nav.navigate("floors/${building.buildingId}")
                                }
                            )
                        }
                    }
                }
            }
        }

        composable(
            route = "floors/{bid}",
            arguments = listOf(navArgument("bid") { type = NavType.StringType })
        ) { entry ->
            val buildingId = entry.arguments?.getString("bid").orEmpty()
            val building = repo.getBuilding(buildingId)

            if (building == null) {
                NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            } else {
                Column(Modifier.padding(16.dp)) {
                    TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                    Text(building.buildingName, style = MaterialTheme.typography.headlineSmall)
                    Text("Select a floor")

                    LazyColumn {
                        items(building.floors, key = { it.floorId }) { floor ->
                            ListItem(
                                headlineContent = { Text(floor.floorName) },
                                supportingContent = { Text("Open indoor map") },
                                modifier = Modifier.clickable {
                                    nav.navigate("map/${building.buildingId}/${floor.floorId}")
                                }
                            )
                        }
                    }
                }
            }
        }

        composable(
            route = "map/{bid}/{fid}",
            arguments = listOf(
                navArgument("bid") { type = NavType.StringType },
                navArgument("fid") { type = NavType.StringType }
            )
        ) { entry ->
            val buildingId = entry.arguments?.getString("bid").orEmpty()
            val floorId = entry.arguments?.getString("fid").orEmpty()
            val building = repo.getBuilding(buildingId)

            if (building == null || building.floors.none { it.floorId == floorId }) {
                NavigationErrorScreen("Building or floor not found.") { nav.popBackStack() }
            } else {
                MapScreen(
                    building = building,
                    initialFloorId = floorId,
                    onBack = { nav.popBackStack() },
                    onOpenDiagnostics = {
                        nav.navigate("diagnostics/$buildingId/$floorId")
                    }
                )
            }
        }

        composable(
            route = "diagnostics/{bid}/{fid}",
            arguments = listOf(
                navArgument("bid") { type = NavType.StringType },
                navArgument("fid") { type = NavType.StringType }
            )
        ) { entry ->
            val buildingId = entry.arguments?.getString("bid").orEmpty()
            val floorId = entry.arguments?.getString("fid").orEmpty()
            val building = repo.getBuilding(buildingId)

            if (building == null || building.floors.none { it.floorId == floorId }) {
                NavigationErrorScreen("Building or floor not found.") { nav.popBackStack() }
            } else {
                NavigationDiagnosticsScreen(
                    building = building,
                    initialFloorId = floorId,
                    session = NavigationSession(building),
                    onBack = { nav.popBackStack() }
                )
            }
        }

        composable("admin") {
            AdminMappingScreen(onBack = { nav.popBackStack() })
        }

        composable(
            route = "record/{bid}/{fid}",
            arguments = listOf(
                navArgument("bid") { type = NavType.StringType },
                navArgument("fid") { type = NavType.StringType }
            )
        ) { entry ->
            PathRecordingScreen(
                buildingId = entry.arguments?.getString("bid").orEmpty(),
                floorKey = entry.arguments?.getString("fid").orEmpty(),
                startAnchorKey = null,
                onBack = { nav.popBackStack() }
            )
        }

        composable(
            route = "pdr/{bid}/{fid}",
            arguments = listOf(
                navArgument("bid") { type = NavType.StringType },
                navArgument("fid") { type = NavType.StringType }
            )
        ) { entry ->
            val buildingId = entry.arguments?.getString("bid").orEmpty()
            val floorId = entry.arguments?.getString("fid").orEmpty()
            val building = repo.getBuilding(buildingId)

            if (building == null || building.floors.none { it.floorId == floorId }) {
                NavigationErrorScreen("Building or floor not found.") { nav.popBackStack() }
            } else {
                PdrScreen(building, floorId) { nav.popBackStack() }
            }
        }

        composable(
            route = "scanner/{bid}",
            arguments = listOf(navArgument("bid") { type = NavType.StringType })
        ) { entry ->
            val building = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())

            if (building == null) {
                NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            } else {
                LandmarkScannerScreen(building) { nav.popBackStack() }
            }
        }

        composable(
            route = "lab/{bid}",
            arguments = listOf(navArgument("bid") { type = NavType.StringType })
        ) { entry ->
            val buildingId = entry.arguments?.getString("bid").orEmpty()
            val building = repo.getBuilding(buildingId)

            if (building == null || building.nodes.isEmpty() || building.floors.isEmpty()) {
                NavigationErrorScreen("Building or localization data not found.") { nav.popBackStack() }
            } else {
                val engine = labEngines.getOrPut(buildingId) {
                    val startingNode = building.nodes.firstOrNull { it.type == "ENTRANCE" } ?: building.nodes.first()
                    LocalizationLabEngine(building).apply { reset(startingNode.nodeId) }
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
                        entry.savedStateHandle.set<Bundle?>(CameraLandmarkObservation.RESULT_KEY, null)
                    },
                    onOpenScanner = { nav.navigate("labscanner/${building.buildingId}") }
                )
            }
        }

        composable(
            route = "labscanner/{bid}",
            arguments = listOf(navArgument("bid") { type = NavType.StringType })
        ) { entry ->
            val building = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())

            if (building == null) {
                NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            } else {
                LandmarkScannerScreen(
                    building = building,
                    onBack = { nav.popBackStack() },
                    onUseObservation = { observation ->
                        nav.previousBackStackEntry?.savedStateHandle?.set<Bundle>(
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
    Column(Modifier.padding(16.dp)) {
        Text(message)
        TextButton(onClick = onBack) { Text("Back") }
    }
}
