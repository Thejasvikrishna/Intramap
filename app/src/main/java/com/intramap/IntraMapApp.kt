package com.intramap

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.intramap.data.repository.MapRepository
import com.intramap.data.supabase.ReferenceRegistrationSource
import com.intramap.data.supabase.SupabaseClientFactory
import com.intramap.domain.localization.RegisteredReferencePose
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.navigation.NavigationSession
import com.intramap.ui.admin.AdminMappingScreen
import com.intramap.ui.admin.PathRecordingScreen
import com.intramap.ui.auth.EditorAuthGate
import com.intramap.ui.diagnostics.NavigationDiagnosticsScreen
import com.intramap.ui.lab.LocalizationLabScreen
import com.intramap.ui.map.BuildingLandingScreen
import com.intramap.ui.map.MapScreen
import com.intramap.ui.navigation.ArPoseResult
import com.intramap.ui.navigation.ArReferenceCameraScreen
import com.intramap.ui.pdr.PdrScreen
import com.intramap.ui.vision.LandmarkScannerScreen
import com.intramap.vision.CameraLandmarkObservation
import kotlinx.coroutines.CancellationException

@Composable
fun IntraMapApp(repo: MapRepository) {
    val nav = rememberNavController()
    val labEngines = remember(repo) { mutableMapOf<String, LocalizationLabEngine>() }
    fun mapRoute(b: String, f: String) = "map/${Uri.encode(b)}/${Uri.encode(f)}"
    NavHost(navController = nav, startDestination = "buildings") {
        composable("buildings") {
            var buildings by remember(repo) { mutableStateOf(repo.getBuildings()) }
            var message by remember { mutableStateOf("Loading published maps…") }
            var refresh by remember { mutableIntStateOf(0) }
            var loading by remember { mutableStateOf(false) }
            LaunchedEffect(repo, refresh) {
                loading = true
                try {
                    repo.refreshPublishedMaps().onSuccess { count ->
                        buildings = repo.getBuildings()
                        message = if (count == 0) "No remote published maps available." else "$count published map(s) refreshed."
                    }.onFailure {
                        buildings = repo.getBuildings()
                        message = "Offline maps shown. Remote refresh unavailable."
                    }
                } finally { loading = false }
            }
            BuildingLandingScreen(buildings, message, loading,
                onOpen = { b, f -> nav.navigate(mapRoute(b.buildingId, f)) },
                onAccount = { nav.navigate("account") }, onRefresh = { refresh++ })
        }
        composable("account") {
            EditorAuthGate(onBack = { nav.popBackStack() }) {
                Column(Modifier.padding(24.dp)) {
                    Text("Editor workspace", style = MaterialTheme.typography.headlineMedium)
                    Text("Create maps by walking. Draft access depends on building ownership or editor membership.")
                    Button(onClick = { nav.navigate("admin") }) { Text("Open mapping studio") }
                    TextButton(onClick = { nav.popBackStack() }) { Text("Browse as visitor") }
                }
            }
        }
        composable("floors/{bid}", arguments = listOf(navArgument("bid") { type = NavType.StringType })) { entry ->
            val building = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())
            if (building == null) NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            else BuildingLandingScreen(listOf(building), "Choose a floor on the map.", false,
                onOpen = { b, f -> nav.navigate(mapRoute(b.buildingId, f)) },
                onAccount = { nav.navigate("account") }, onRefresh = { nav.popBackStack() })
        }
        composable("map/{bid}/{fid}", arguments = listOf(navArgument("bid") { type = NavType.StringType }, navArgument("fid") { type = NavType.StringType })) { entry ->
            val bid = entry.arguments?.getString("bid").orEmpty()
            val fid = entry.arguments?.getString("fid").orEmpty()
            val building = repo.getBuilding(bid)
            val published = repo.getPublishedMap(bid)
            val flow = remember(entry) { entry.savedStateHandle.getStateFlow<Bundle?>(ArPoseResult.KEY, null) }
            val pending by flow.collectAsState()
            if (building == null || building.floors.none { it.floorId == fid }) NavigationErrorScreen("Building or floor not found.") { nav.popBackStack() }
            else MapScreen(building = building, initialFloorId = fid, versionId = published?.versionId,
                pendingArPose = ArPoseResult.fromBundle(pending),
                onArPoseConsumed = { entry.savedStateHandle.set<Bundle?>(ArPoseResult.KEY, null) },
                onOpenArPositioning = { nav.navigate("ar/${Uri.encode(bid)}") },
                onBack = { nav.popBackStack() },
                onOpenDiagnostics = { nav.navigate("diagnostics/${Uri.encode(bid)}/${Uri.encode(fid)}") })
        }
        composable("ar/{bid}", arguments = listOf(navArgument("bid") { type = NavType.StringType })) { entry ->
            val bid = entry.arguments?.getString("bid").orEmpty()
            val building = repo.getBuilding(bid)
            val published = repo.getPublishedMap(bid)
            val client = SupabaseClientFactory.client
            var registrations by remember(bid, published?.versionId) { mutableStateOf<List<RegisteredReferencePose>?>(null) }
            var message by remember(bid) { mutableStateOf<String?>(null) }
            LaunchedEffect(bid, published?.versionId, client) {
                val version = published?.versionId
                when {
                    building == null -> message = "Building not found."
                    version == null -> message = "AR positioning requires a published map version."
                    client == null -> message = "Supabase is not configured."
                    else -> try {
                        registrations = ReferenceRegistrationSource(client).registrations(bid, version)
                        message = if (registrations.isNullOrEmpty()) "No reference images registered for this version." else null
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Could not load reference registrations." }
                }
            }
            if (building == null) NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            else Column(Modifier.padding(16.dp)) {
                TextButton(onClick = { nav.popBackStack() }) { Text("Back") }
                message?.let { Text(it) }
                val version = published?.versionId
                if (version != null && registrations != null) ArReferenceCameraScreen(building, version, registrations.orEmpty()) { floor, pose ->
                    nav.previousBackStackEntry?.savedStateHandle?.set<Bundle>(ArPoseResult.KEY,
                        ArPoseResult.bundle(ArPoseResult.Pose(floor, pose.x, pose.y, pose.headingDeg)))
                    nav.popBackStack()
                }
            }
        }
        composable("diagnostics/{bid}/{fid}", arguments = listOf(navArgument("bid") { type = NavType.StringType }, navArgument("fid") { type = NavType.StringType })) { entry ->
            val b = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())
            val f = entry.arguments?.getString("fid").orEmpty()
            if (b == null || b.floors.none { it.floorId == f }) NavigationErrorScreen("Building or floor not found.") { nav.popBackStack() }
            else NavigationDiagnosticsScreen(building = b, initialFloorId = f, session = NavigationSession(b), onBack = { nav.popBackStack() })
        }
        composable("admin") {
            EditorAuthGate(onBack = { nav.popBackStack() }) { AdminMappingScreen(onBack = { nav.popBackStack() }) }
        }
        composable("record/{bid}/{fid}", arguments = listOf(navArgument("bid") { type = NavType.StringType }, navArgument("fid") { type = NavType.StringType })) { entry ->
            val b = entry.arguments?.getString("bid").orEmpty()
            val f = entry.arguments?.getString("fid").orEmpty()
            if (b.isBlank() || f.isBlank()) NavigationErrorScreen("Building or floor identifier missing.") { nav.popBackStack() }
            else EditorAuthGate(onBack = { nav.popBackStack() }) { PathRecordingScreen(buildingId = b, floorKey = f, startAnchorKey = null, onBack = { nav.popBackStack() }) }
        }
        composable("pdr/{bid}/{fid}", arguments = listOf(navArgument("bid") { type = NavType.StringType }, navArgument("fid") { type = NavType.StringType })) { entry ->
            val b = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())
            val f = entry.arguments?.getString("fid").orEmpty()
            if (b == null || b.floors.none { it.floorId == f }) NavigationErrorScreen("Building or floor not found.") { nav.popBackStack() }
            else PdrScreen(b, f) { nav.popBackStack() }
        }
        composable("scanner/{bid}", arguments = listOf(navArgument("bid") { type = NavType.StringType })) { entry ->
            val b = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())
            if (b == null) NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            else LandmarkScannerScreen(building = b, onBack = { nav.popBackStack() })
        }
        composable("lab/{bid}", arguments = listOf(navArgument("bid") { type = NavType.StringType })) { entry ->
            val bid = entry.arguments?.getString("bid").orEmpty()
            val b = repo.getBuilding(bid)
            if (b == null || b.nodes.isEmpty() || b.floors.isEmpty()) NavigationErrorScreen("Localization data not found.") { nav.popBackStack() }
            else {
                val engine = labEngines.getOrPut(bid) { LocalizationLabEngine(b).apply { reset((b.nodes.firstOrNull { it.type == "ENTRANCE" } ?: b.nodes.first()).nodeId) } }
                val flow = remember(entry) { entry.savedStateHandle.getStateFlow<Bundle?>(CameraLandmarkObservation.RESULT_KEY, null) }
                val observation by flow.collectAsState()
                LocalizationLabScreen(building = b, onBack = { nav.popBackStack() }, externalEngine = engine,
                    cameraObservation = observation, onObservationConsumed = { entry.savedStateHandle.set<Bundle?>(CameraLandmarkObservation.RESULT_KEY, null) },
                    onOpenScanner = { nav.navigate("labscanner/${Uri.encode(bid)}") })
            }
        }
        composable("labscanner/{bid}", arguments = listOf(navArgument("bid") { type = NavType.StringType })) { entry ->
            val b = repo.getBuilding(entry.arguments?.getString("bid").orEmpty())
            if (b == null) NavigationErrorScreen("Building not found.") { nav.popBackStack() }
            else LandmarkScannerScreen(building = b, onBack = { nav.popBackStack() }, onUseObservation = {
                nav.previousBackStackEntry?.savedStateHandle?.set<Bundle>(CameraLandmarkObservation.RESULT_KEY, it.toBundle())
                nav.popBackStack()
            })
        }
    }
}

@Composable
private fun NavigationErrorScreen(message: String, onBack: () -> Unit) {
    Column(Modifier.padding(16.dp)) { Text(message); TextButton(onClick = onBack) { Text("Back") } }
}
