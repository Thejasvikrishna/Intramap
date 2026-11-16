package com.intramap.navigation

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.data.model.PathEdge
import com.intramap.domain.navigation.generateInstructions
import com.intramap.domain.routing.AccessibilityMode
import com.intramap.domain.routing.IndoorGraph
import com.intramap.domain.routing.Route
import com.intramap.domain.routing.findRoute
import com.intramap.localization.lab.LabEstimate
import com.intramap.localization.lab.LabParticle
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.vision.CameraLandmarkObservation
import kotlin.math.hypot

enum class FloorTravelMode {
    AUTO,
    STAIRS,
    ELEVATOR
}

class NavigationSession(
    val building: Building,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    var floorTravelMode by mutableStateOf(FloorTravelMode.AUTO)
        private set

    var pendingFloorArrivalId by mutableStateOf<String?>(null)
        private set

    val pendingFloorArrival: LocationNode?
        get() = nodes[pendingFloorArrivalId]

    fun updateFloorTravelMode(mode: FloorTravelMode) {
        if (pendingFloorArrivalId != null) {
            status = "Finish or cancel the current floor change first."
            return
        }

        if (stepFree && mode == FloorTravelMode.STAIRS) {
            status = "Turn off Step-free before choosing stairs."
            return
        }

        pauseMotion()
        floorTravelMode = mode
        reroute("Floor travel preference updated.")
    }

    val errors = validateNavigationMap(building)
    val landmarks = building.nodes.filter { it.type != "JUNCTION" }
    private val nodes = building.nodes.associateBy { it.nodeId }
    private val engine by lazy { LocalizationLabEngine(building) }

    var estimate by mutableStateOf<LabEstimate?>(null)
        private set
    var particles by mutableStateOf<List<LabParticle>>(emptyList())
        private set
    var selectedStartId by mutableStateOf<String?>(null)
        private set
    var destinationId by mutableStateOf<String?>(null)
        private set
    var stepFree by mutableStateOf(false)
        private set
    var blocked by mutableStateOf<Set<String>>(emptySet())
        private set
    var route by mutableStateOf<Route?>(null)
        private set
    var routeNodes by mutableStateOf<List<LocationNode>>(emptyList())
        private set
    var nextIndex by mutableStateOf(0)
        private set
    var arrived by mutableStateOf(false)
        private set
    var status by mutableStateOf("Confirm your actual starting location, then choose a destination.")
        private set
    var motionEpoch by mutableStateOf(0)
        private set
    var restoredNote by mutableStateOf<String?>(null)
        private set
    var matchedDistanceM by mutableStateOf<Double?>(null)
        private set

    private var componentAnchorId: String? = null
    private var instructions = emptyList<com.intramap.domain.navigation.Instruction>()
    private var splitToOriginal = emptyMap<String, String>()
    private var reachedCount = 0
    private var arrivalCount = 0
    private var offRouteCount = 0
    private var blockedMotionCount = 0
    private var lastRerouteMs = Long.MIN_VALUE

    val ready: Boolean get() = estimate != null && errors.isEmpty()
    val destination: LocationNode? get() = nodes[destinationId]
    val nextNode: LocationNode? get() = routeNodes.getOrNull(nextIndex)

    val instruction: String get() {
        if (arrived) return "Arrived at ${destination?.name.orEmpty()}."
        pendingFloorArrival?.let { target ->
            val floorName = building.floors
                .firstOrNull { it.floorId == target.floorId }
                ?.floorName
                ?: target.floorId

            return "Tracking paused. Use the ${target.type.lowercase()} " +
                    "to $floorName, then confirm arrival."
        }
        val e = estimate ?: return "Confirm your starting location."
        if (route == null) return "Choose a reachable destination."
        val n = nextNode ?: return "Check your destination."
        if (n.floorId != e.floorId) {
            val floorName = building.floors
                .firstOrNull { it.floorId == n.floorId }
                ?.floorName
                ?: n.floorId

            return if (transitionTarget != null) {
                "At the ${n.type.lowercase()}. Start the floor change to $floorName."
            } else {
                "Approach the mapped ${n.type.lowercase()} for $floorName. " +
                        "Floor change is waiting for a reliable connector position."
            }
        }
        val generated = instructions.lastOrNull {
            it.routeIndex <= (nextIndex - 1).coerceAtLeast(0)
        }?.text.orEmpty()
        return "$generated\nNext: ${n.name}, approximately %.1f m away.".format(distance(e, n))
    }

    fun chooseStart(id: String) {
        if (id in nodes) selectedStartId = id
    }

    fun confirmStart() {
        val id = selectedStartId ?: run {
            status = "Choose a starting landmark first."
            return
        }
        if (errors.isNotEmpty()) {
            status = errors.joinToString("\n")
            return
        }
        motionEpoch++
        engine.reset(id)
        componentAnchorId = id
        refresh()
        pendingFloorArrivalId = null
        restoredNote = null
        arrived = false
        blockedMotionCount = 0
        reroute("Starting position explicitly confirmed at ${nodes.getValue(id).name}.")
    }

    fun setDestination(id: String) {
        if (id !in nodes) return
        destinationId = id
        arrived = false
        reroute("Destination selected.")
    }

    fun updateAccessibilityPreference(value: Boolean) {
        if (pendingFloorArrivalId != null) {
            status = "Finish or cancel the current floor change first."
            return
        }

        pauseMotion()
        stepFree = value

        if (value && floorTravelMode == FloorTravelMode.STAIRS) {
            floorTravelMode = FloorTravelMode.AUTO
        }

        reroute("Accessibility preference updated.")
    }

    fun beginFloorTransition() {
        val target = transitionTarget ?: run {
            status =
                "Move near the mapped source connector and correct your " +
                        "position before starting the floor change."
            return
        }

        pauseMotion()
        pendingFloorArrivalId = target.nodeId

        val floorName = building.floors
            .firstOrNull { it.floorId == target.floorId }
            ?.floorName
            ?: target.floorId

        status =
            "Tracking paused. Use the ${target.type.lowercase()} to " +
                    "$floorName. Confirm arrival only after physically reaching it."
    }

    fun cancelFloorTransition() {
        pendingFloorArrivalId = null
        pauseMotion()

        status =
            "Floor change cancelled. Position remains on the previous floor. " +
                    "If you have moved floors, explicitly confirm your actual location."
    }

    fun clearBlocked() {
        blocked = emptySet()
        reroute("Local blocked-path reports cleared.")
    }

    fun pauseMotion() {
        motionEpoch++
    }

    fun walk(lengthM: Double, headingDeg: Double) {
        if (!ready || arrived || pendingFloorArrivalId != null) return
        if (!lengthM.isFinite() || lengthM !in 0.1..2.0 || !headingDeg.isFinite()) return
        val update = engine.predict(lengthM, headingDeg)
        refresh()
        if (!update.accepted) {
            blockedMotionCount++
            status = "${update.message} Check heading/start position; scan or explicitly relocalize if needed."
            if (blockedMotionCount >= 5) pauseMotion().also { blockedMotionCount = 0 }
            return
        }
        blockedMotionCount = 0
        status = update.message
        updateProgress()
    }

    fun applyObservation(observation: CameraLandmarkObservation): Boolean {
        if (!ready) {
            status = "Confirm a starting location first."
            return false
        }
        val n = nodes[observation.nodeId]
        val age = nowMs() - observation.capturedAtElapsedMs
        if (n == null || n.floorId != observation.floorId || observation.observedText.isBlank() ||
            observation.matchingScans < 2 || observation.capturedAtElapsedMs < 0L || age !in 0L..30_000L
        ) {
            status = "Rejected camera result: invalid landmark, floor, text, consistency or timestamp."
            return false
        }
        val update = engine.observeLandmark(n.nodeId, 0.5, adaptive = false)
        refresh()
        if (update.accepted) {
            arrived = false
            reroute("Camera observation accepted: ${n.name}. Fixed 2.5 m observation sigma; not calibrated confidence.")
        } else status = update.message + " You can explicitly confirm a new start if the belief is wrong."
        return update.accepted
    }

    fun applyReferencePose(
        floorId: String,
        x: Double,
        y: Double,
        headingDeg: Double
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || !headingDeg.isFinite()) {
            status = "Rejected reference pose: invalid geometry."
            return false
        }

        if (errors.isNotEmpty()) {
            status = errors.joinToString("\n")
            return false
        }

        val update = engine.relocalize(floorId, x, y)
        refresh()

        if (!update.accepted) {
            status = update.message
            return false
        }

        componentAnchorId = update.anchorNodeId
        arrived = false
        pendingFloorArrivalId = null
        restoredNote = null

        reroute("Reference pose accepted. Route recalculated from your scanned position.")
        return true
    }

    val transitionTarget: LocationNode? get() {
        val e = estimate ?: return null
        val n = nextNode ?: return null
        val source = routeNodes.getOrNull(nextIndex - 1) ?: return null
        return n.takeIf {
            it.floorId != e.floorId && source.floorId == e.floorId &&
                    source.type in setOf("STAIRS", "ELEVATOR") && source.type == it.type &&
                    distance(e, source) <= 2.0 && e.spreadM <= 2.0
        }
    }

    fun confirmTransition() {
        val target = pendingFloorArrival ?: run {
            status = "Start the floor change at the mapped connector first."
            return
        }

        val update = engine.confirmFloorTransition(target.nodeId)
        status = update.message

        if (update.accepted) {
            pendingFloorArrivalId = null
            pauseMotion()
            componentAnchorId = target.nodeId
            refresh()

            reroute(
                update.message +
                        " Restart sensors and recalibrate on this floor."
            )
        }
    }

    fun confirmNextManually() {
        val e = estimate ?: return
        val n = nextNode ?: return
        if (n.floorId != e.floorId || distance(e, n) > 2.0 || e.spreadM > 2.0) {
            status = "Manual progress requires a nearby same-floor waypoint. Relocalize if your estimate is wrong."
            return
        }
        if (nextIndex == routeNodes.lastIndex) {
            arrived = true
            pauseMotion()
            status = "Arrival manually confirmed."
        } else {
            nextIndex++
            reachedCount = 0
            status = "Waypoint manually confirmed; position was not reset."
        }
    }

    fun reportBlocked() {
        val r = route ?: return
        val edgeId = r.edgeIds.getOrNull((nextIndex - 1).coerceAtLeast(0)) ?: return
        val original = splitToOriginal[edgeId] ?: edgeId
        if (original !in building.edges.map { it.edgeId }) return
        blocked = blocked + original
        reroute("Path $original reported blocked locally. Routing from your projected live position.")
    }

    fun reroute(message: String = "Route updated.") {
        route = null
        routeNodes = emptyList()
        instructions = emptyList()
        nextIndex = 0
        reachedCount = 0
        arrivalCount = 0
        offRouteCount = 0
        matchedDistanceM = null
        splitToOriginal = emptyMap()
        val e = estimate ?: run {
            status = "$message Confirm your starting position."
            return
        }
        val goal = destinationId ?: run {
            status = "$message Choose a destination."
            return
        }
        val component = mutableSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        componentAnchorId?.let { component.add(it); queue.add(it) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            building.edges.filter { it.isAvailable }.forEach { edge ->
                val other = when (id) {
                    edge.sourceNodeId -> edge.destinationNodeId
                    edge.destinationNodeId -> edge.sourceNodeId
                    else -> null
                }
                if (other != null && nodes[other]?.floorId == e.floorId && component.add(other)) queue.add(other)
            }
        }
        val allowed = building.edges.filter { edge ->
            val from = nodes[edge.sourceNodeId]
            val to = nodes[edge.destinationNodeId]

            if (from == null || to == null) {
                false
            } else {
                val crossesFloor = from.floorId != to.floorId

                val usesStairs =
                    edge.accessibilityType == "STAIRS_ONLY" ||
                            (crossesFloor &&
                                    (from.type == "STAIRS" || to.type == "STAIRS"))

                val matchesPreference = when (floorTravelMode) {
                    FloorTravelMode.AUTO -> true

                    FloorTravelMode.STAIRS ->
                        !crossesFloor ||
                                (from.type == "STAIRS" && to.type == "STAIRS")

                    FloorTravelMode.ELEVATOR ->
                        !crossesFloor ||
                                (from.type == "ELEVATOR" && to.type == "ELEVATOR")
                }

                edge.isAvailable &&
                        edge.edgeId !in blocked &&
                        (!stepFree || !usesStairs) &&
                        matchesPreference
            }
        }
        val pairs = allowed.mapNotNull { edge ->
            val a = nodes[edge.sourceNodeId] ?: return@mapNotNull null
            val b = nodes[edge.destinationNodeId] ?: return@mapNotNull null
            if (a.floorId != e.floorId || b.floorId != e.floorId || a.nodeId !in component) return@mapNotNull null
            edge to projectPoint(e.x, e.y, a, b)
        }
        val nearest = pairs.minByOrNull { it.second.distance }
        val closeNode = building.nodes.filter { it.floorId == e.floorId && it.nodeId in component }
            .minByOrNull { distance(e, it) }
        val graph: IndoorGraph
        val startId: String
        if (closeNode != null && distance(e, closeNode) <= 0.6) {
            graph = IndoorGraph(building.copy(edges = allowed))
            startId = closeNode.nodeId
            matchedDistanceM = distance(e, closeNode)
        } else if (nearest != null && nearest.second.distance <= 2.0) {
            val (edge, p) = nearest
            val anchor = LocationNode(
                "__nav_position", e.floorId, "Current position", "JUNCTION",
                p.x.toFloat(), p.y.toFloat()
            )
            val aEdge = PathEdge(
                "__nav_a", edge.sourceNodeId, anchor.nodeId,
                (edge.distance * p.t).toFloat(), edge.accessibilityType
            )
            val bEdge = PathEdge(
                "__nav_b", anchor.nodeId, edge.destinationNodeId,
                (edge.distance * (1.0 - p.t)).toFloat(), edge.accessibilityType
            )
            graph = IndoorGraph(
                building.copy(
                    nodes = building.nodes + anchor,
                    edges = allowed.filterNot { it.edgeId == edge.edgeId } + listOf(aEdge, bEdge)
                )
            )
            startId = anchor.nodeId
            splitToOriginal = mapOf(aEdge.edgeId to edge.edgeId, bEdge.edgeId to edge.edgeId)
            matchedDistanceM = p.distance
        } else {
            status = "$message Cannot safely associate position with an available path. Scan or confirm your location."
            return
        }
        val r = findRoute(
            graph, startId, goal,
            if (stepFree) AccessibilityMode.STEP_FREE else AccessibilityMode.STANDARD,
            useAStar = false
        )
        if (r == null) {
            status = "$message No available route for these preferences."
            return
        }
        route = r
        routeNodes = r.nodeIds.map { graph.nodes.getValue(it) }
        instructions = generateInstructions(graph, r, building.floors.associate { it.floorId to it.floorName })
        nextIndex = if (routeNodes.size > 1) 1 else 0
        lastRerouteMs = nowMs()
        arrived = false
        status = message
    }

    private fun updateProgress() {
        val e = estimate ?: return
        if (route == null) return
        if (e.spreadM > 3.0) {
            status = "Position belief is dispersed. Scan a mapped sign before relying on automatic progress."
            return
        }
        val next = nextNode ?: return
        if (next.floorId == e.floorId && distance(e, next) <= 1.5) reachedCount++ else reachedCount = 0
        if (reachedCount >= 2 && nextIndex < routeNodes.lastIndex) {
            nextIndex++
            reachedCount = 0
        }
        val dest = destination
        val reachedLast = nextIndex == routeNodes.lastIndex
        if (dest != null && reachedLast && dest.floorId == e.floorId &&
            distance(e, dest) <= 1.5 && e.spreadM <= 2.0
        ) arrivalCount++ else arrivalCount = 0
        if (arrivalCount >= 3) {
            arrived = true
            status = "Estimated arrival at ${dest?.name}. Verify the destination sign."
            pauseMotion()
            return
        }
        val distances = routeNodes.zipWithNext().mapNotNull { (a, b) ->
            if (a.floorId == e.floorId && b.floorId == e.floorId)
                projectPoint(e.x, e.y, a, b).distance else null
        }
        val d = distances.minOrNull() ?: routeNodes.filter { it.floorId == e.floorId }
            .minOfOrNull { distance(e, it) } ?: return
        if (d > 2.5) offRouteCount++ else offRouteCount = 0
        if (offRouteCount >= 3 && nowMs() - lastRerouteMs >= 8_000L) {
            reroute("Sustained route deviation detected. Route recalculated.")
        }
    }

    private fun refresh() {
        estimate = engine.estimate()
        particles = engine.particles()
    }

    private fun distance(e: LabEstimate, n: LocationNode) = hypot(e.x - n.x, e.y - n.y)

    fun snapshot(): Bundle = Bundle().apply {
        putString("floorTravelMode", floorTravelMode.name)
        putString("building", building.buildingId)
        putString("destination", destinationId)
        putString("start", selectedStartId)
        putBoolean("stepFree", stepFree)
        putStringArrayList("blocked", ArrayList(blocked))
        estimate?.let {
            putString("lastFloor", it.floorId)
            putDouble("lastX", it.x)
            putDouble("lastY", it.y)
        }
    }

    fun restore(bundle: Bundle) {
        pendingFloorArrivalId = null
        if (bundle.getString("building") != building.buildingId) return
        selectedStartId = bundle.getString("start")?.takeIf { it in nodes }
        destinationId = bundle.getString("destination")?.takeIf { it in nodes }
        stepFree = bundle.getBoolean("stepFree")
        floorTravelMode = runCatching {
            FloorTravelMode.valueOf(
                bundle.getString("floorTravelMode")
                    ?: FloorTravelMode.AUTO.name
            )
        }.getOrDefault(FloorTravelMode.AUTO)

        if (stepFree && floorTravelMode == FloorTravelMode.STAIRS) {
            floorTravelMode = FloorTravelMode.AUTO
        }
        val validIds = building.edges.map { it.edgeId }.toSet()
        blocked = bundle.getStringArrayList("blocked").orEmpty().filter { it in validIds }.toSet()
        val f = bundle.getString("lastFloor")
        restoredNote = if (f != null) {
            "Last known: $f (%.1f, %.1f m). Not a live fix. Confirm your actual location to resume."
                .format(bundle.getDouble("lastX"), bundle.getDouble("lastY"))
        } else "Session settings restored. Confirm your starting location."
        estimate = null
        particles = emptyList()
        route = null
        status = restoredNote.orEmpty()
    }
}
