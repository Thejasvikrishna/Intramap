package com.intramap.navigation

import android.os.Bundle
import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import com.intramap.domain.routing.AccessibilityMode
import com.intramap.domain.routing.IndoorGraph
import com.intramap.domain.routing.Route
import com.intramap.domain.routing.RouteProgress
import com.intramap.domain.routing.findRoute
import com.intramap.domain.routing.selectRoutingStart
import com.intramap.localization.lab.LabEstimate
import com.intramap.localization.lab.LabParticle
import com.intramap.localization.lab.LocalizationLabEngine
import com.intramap.vision.CameraLandmarkObservation
import kotlin.math.hypot

/**
 * Visitor-facing navigation session.
 *
 * Preserves the API currently consumed by MapScreen while adding approximate
 * position routing and route-progress tracking.
 */
class NavigationSession(private val building: Building) {

    private val graph = IndoorGraph(building)
    private val nodesById = building.nodes.associateBy { it.nodeId }

    private val engine = LocalizationLabEngine(building)
    private var progress: RouteProgress? = null

    var selectedStartId: String? = null
        private set

    var destinationId: String? = null
        private set

    var route: Route? = null
        private set

    var ready = false
        private set

    var stepFree = false
        private set

    var arrived = false
        private set

    var motionEpoch = 0
        private set

    val errors: List<String> = emptyList()
    var restoredNote: String? = null
        private set

    val landmarks: List<LocationNode>
        get() = building.nodes

    val particles: List<LabParticle>
        get() = engine.particles()

    val estimate: LabEstimate?
        get() = runCatching { engine.estimate() }.getOrNull()

    val routeNodes: List<LocationNode>
        get() = route?.nodeIds?.mapNotNull { nodesById[it] }.orEmpty()

    val destination: LocationNode?
        get() = destinationId?.let { nodesById[it] }

    val transitionTarget: LocationNode?
        get() {
            val next = progress?.nextNodeId ?: return null
            val node = nodesById[next] ?: return null
            return if (node.type in setOf("STAIRS", "ELEVATOR")) node else null
        }

    val blocked: Set<String>
        get() = blockedEdges

    private val blockedEdges = mutableSetOf<String>()

    val instruction: String
        get() {
            val destination = destination ?: return "Choose a destination."
            if (arrived) return "You have arrived at ${destination.name}."
            val next = progress?.nextNodeId?.let { nodesById[it] } ?: return "Calculating route…"
            return "Head toward ${next.name}."
        }

    val status: String
        get() = when {
            !ready -> "Location not initialized."
            route == null -> "No route available."
            arrived -> "Navigation complete."
            else -> "Navigating."
        }

    fun chooseStart(nodeId: String) {
        selectedStartId = nodeId
        ready = false
        route = null
        progress = null
    }

    fun setDestination(nodeId: String) {
        destinationId = nodeId
        reroute()
    }

    fun confirmStart() {
        val start = selectedStartId ?: return
        runCatching { engine.reset(start) }
        ready = true
        reroute()
    }

    fun updateAccessibilityPreference(value: Boolean) {
        stepFree = value
        reroute()
    }

    fun reroute() {
        val destination = destinationId ?: return
        val estimate = estimate

        val start = if (ready && estimate != null) {
            selectRoutingStart(
                building = building,
                graph = graph,
                estimatedFloorId = estimate.floorId,
                x = estimate.x,
                y = estimate.y,
                headingRadians = null,
                destinationId = destination,
                accessibilityMode = if (stepFree) AccessibilityMode.STEP_FREE else AccessibilityMode.STANDARD,
                blockedEdgeIds = blockedEdges
            )
        } else {
            selectedStartId
        } ?: return

        selectedStartId = start
        val calculated = findRoute(
            graph = graph,
            startId = start,
            goalId = destination,
            mode = if (stepFree) AccessibilityMode.STEP_FREE else AccessibilityMode.STANDARD,
            blockedEdgeIds = blockedEdges
        )

        route = calculated
        progress = calculated?.let { RouteProgress(nodesById, it.nodeIds) }
        arrived = false
    }

    fun walk() {
        if (!ready || route == null || arrived) return
        val estimate = estimate ?: return
        val next = progress?.nextNodeId?.let { nodesById[it] } ?: return

        val bearing = Math.atan2(next.x - estimate.x, next.y - estimate.y)
        engine.predict(0.7, Math.toDegrees(bearing))
        motionEpoch++

        val updated = estimate
        progress?.update(updated.floorId, updated.x, updated.y)

        if (progress?.nextNodeId == null) {
            arrived = true
        }
    }

    fun pauseMotion() {
        // Reserved for sensor lifecycle control.
    }

    fun confirmNextManually() {
        progress?.let { progress ->
            if (progress.nextNodeId != null) {
                progress.replaceRoute(progress.routeNodeIds)
            }
        }
    }

    fun reportBlocked() {
        route?.edgeIds?.firstOrNull()?.let { blockedEdges.add(it) }
        reroute()
    }

    fun clearBlocked() {
        blockedEdges.clear()
        reroute()
    }

    fun applyObservation(observation: CameraLandmarkObservation) {
        val update = engine.observeLandmark(observation.nodeId, observation.quality)
        if (update.accepted) {
            ready = true
            reroute()
        }
    }

    fun confirmTransition() {
        val target = transitionTarget ?: return
        val update = engine.confirmFloorTransition(target.nodeId)
        if (update.accepted) {
            reroute()
        }
    }

    fun snapshot(): Bundle = Bundle().apply {
        putString("start", selectedStartId)
        putString("destination", destinationId)
        putBoolean("ready", ready)
        putBoolean("stepFree", stepFree)
        putBoolean("arrived", arrived)
    }

    fun restore(saved: Bundle) {
        selectedStartId = saved.getString("start")
        destinationId = saved.getString("destination")
        ready = saved.getBoolean("ready")
        stepFree = saved.getBoolean("stepFree")
        arrived = saved.getBoolean("arrived")
        restoredNote = "Navigation state restored. Recalibrate if the position is uncertain."
        reroute()
    }
}
