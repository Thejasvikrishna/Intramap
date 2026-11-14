package com.intramap.localization.lab

import com.intramap.data.model.Building
import com.intramap.data.model.LocationNode
import kotlin.math.*
import kotlin.random.Random

data class LabParticle(
    val x: Double,
    val y: Double,
    val floorId: String,
    val weight: Double
)

data class LabEstimate(
    val x: Double,
    val y: Double,
    val floorId: String,
    val spreadM: Double,
    val effectiveSampleSize: Double,
    val distanceSinceCorrectionM: Double
)

data class LabUpdate(
    val accepted: Boolean,
    val message: String
)

class LocalizationLabEngine(
    private val building: Building,
    private val particleCount: Int = 300,
    seed: Int = 42,
    private val corridorHalfWidthM: Double = 1.2
) {
    private val random = Random(seed)
    private val nodes = building.nodes.associateBy { it.nodeId }

    private val availableEdges = building.edges.filter {
        it.isAvailable &&
                it.sourceNodeId in nodes &&
                it.destinationNodeId in nodes
    }

    private val components = mutableMapOf<String, Int>()

    private var cloud = emptyList<LabParticle>()
    private var distanceSinceCorrectionM = 0.0
    private var activeComponent = -1

    init {
        require(particleCount >= 20)
        require(corridorHalfWidthM > 0.0)
        require(building.nodes.isNotEmpty())

        val adjacency = building.nodes.associate {
            it.nodeId to mutableListOf<String>()
        }

        availableEdges.forEach { edge ->
            val a = nodes.getValue(edge.sourceNodeId)
            val b = nodes.getValue(edge.destinationNodeId)

            if (a.floorId == b.floorId) {
                adjacency.getValue(a.nodeId).add(b.nodeId)
                adjacency.getValue(b.nodeId).add(a.nodeId)
            }
        }

        var component = 0

        building.nodes.forEach { start ->
            if (start.nodeId !in components) {
                val queue = ArrayDeque<String>()
                queue.add(start.nodeId)
                components[start.nodeId] = component

                while (queue.isNotEmpty()) {
                    val current = queue.removeFirst()

                    adjacency.getValue(current).forEach { next ->
                        if (next !in components) {
                            components[next] = component
                            queue.add(next)
                        }
                    }
                }

                component++
            }
        }
    }

    fun particles(): List<LabParticle> = cloud.toList()

    fun reset(nodeId: String) {
        val node = nodes[nodeId]
            ?: error("Unknown start node: $nodeId")

        activeComponent = components.getValue(nodeId)
        distanceSinceCorrectionM = 0.0
        cloud = List(particleCount) {
            sampleNear(node, 0.25)
        }
    }

    fun estimate(): LabEstimate {
        check(cloud.isNotEmpty()) {
            "Call reset before requesting an estimate."
        }

        val totalWeight = cloud.sumOf { it.weight }

        check(totalWeight.isFinite() && totalWeight > 0.0) {
            "Particle weights are invalid."
        }

        val floorId = cloud.first().floorId

        check(cloud.all { it.floorId == floorId }) {
            "The Lab engine expects particles on one floor."
        }

        val meanX =
            cloud.sumOf { it.x * it.weight } / totalWeight

        val meanY =
            cloud.sumOf { it.y * it.weight } / totalWeight

        val variance = cloud.sumOf { particle ->
            val normalizedWeight = particle.weight / totalWeight

            normalizedWeight * (
                    (particle.x - meanX).pow(2) +
                            (particle.y - meanY).pow(2)
                    )
        }

        val squaredWeightSum = cloud.sumOf { particle ->
            val normalizedWeight = particle.weight / totalWeight
            normalizedWeight * normalizedWeight
        }

        // A mean can lie outside the corridor model at a junction.
        // In that case, choose a valid particle nearest to the mean.
        val meanIsValid = validPoint(meanX, meanY, floorId)

        val representative = if (meanIsValid) {
            null
        } else {
            cloud.minByOrNull { particle ->
                (particle.x - meanX).pow(2) +
                        (particle.y - meanY).pow(2)
            }
        }

        return LabEstimate(
            x = representative?.x ?: meanX,
            y = representative?.y ?: meanY,
            floorId = floorId,
            spreadM = sqrt(variance.coerceAtLeast(0.0)),
            effectiveSampleSize = 1.0 / squaredWeightSum,
            distanceSinceCorrectionM = distanceSinceCorrectionM
        )
    }

    fun predict(
        stepLengthM: Double,
        mapHeadingDeg: Double
    ): LabUpdate {
        require(stepLengthM.isFinite() && stepLengthM > 0.0)
        require(mapHeadingDeg.isFinite())
        check(cloud.isNotEmpty())

        val drift = min(distanceSinceCorrectionM / 25.0, 2.0)
        val lengthSigma = 0.06 + 0.05 * drift
        val headingSigmaDeg = 4.0 + 5.0 * drift

        var moved = 0

        cloud = cloud.map { particle ->
            var accepted: LabParticle? = null

            repeat(8) {
                if (accepted == null) {
                    val length = (
                            stepLengthM + gaussian() * lengthSigma
                            ).coerceAtLeast(0.05)

                    val heading = Math.toRadians(
                        mapHeadingDeg + gaussian() * headingSigmaDeg
                    )

                    val x = particle.x + length * sin(heading)
                    val y = particle.y - length * cos(heading)

                    if (validMovement(particle, x, y)) {
                        accepted = particle.copy(x = x, y = y)
                    }
                }
            }

            if (accepted != null) {
                moved++
                accepted!!
            } else {
                particle
            }
        }

        if (moved > 0) {
            distanceSinceCorrectionM += stepLengthM
        }

        return LabUpdate(
            accepted = moved > 0,
            message = if (moved > 0) {
                "Motion applied: $moved/$particleCount particles moved."
            } else {
                "Motion rejected by the provisional corridor model."
            }
        )
    }

    fun observeLandmark(
        nodeId: String,
        simulatedQuality: Double,
        adaptive: Boolean = true
    ): LabUpdate {
        check(cloud.isNotEmpty())

        val landmark = nodes[nodeId]
            ?: return LabUpdate(false, "Unknown landmark.")

        if (!simulatedQuality.isFinite() ||
            simulatedQuality !in 0.0..1.0
        ) {
            return LabUpdate(false, "Invalid simulated quality.")
        }

        val estimate = estimate()

        if (landmark.floorId != estimate.floorId) {
            return LabUpdate(
                false,
                "Rejected: observation is on another floor."
            )
        }

        if (components[landmark.nodeId] != activeComponent) {
            return LabUpdate(
                false,
                "Rejected: landmark is in a disconnected map component."
            )
        }

        if (adaptive && simulatedQuality < 0.25) {
            return LabUpdate(
                false,
                "Rejected: simulated visual quality is too low."
            )
        }

        // Provisional observation-noise model.
        // Quality is manually supplied, not a calibrated probability.
        val sigma = if (adaptive) {
            0.8 + 4.0 * (1.0 - simulatedQuality)
        } else {
            2.5
        }

        val residual = hypot(
            estimate.x - landmark.x.toDouble(),
            estimate.y - landmark.y.toDouble()
        )

        val gateM = 3.0 * sqrt(
            estimate.spreadM.pow(2) + sigma.pow(2)
        )

        if (residual > gateM) {
            return LabUpdate(
                false,
                "Rejected: landmark conflicts with current position belief."
            )
        }

        val logWeights = cloud.map { particle ->
            val distanceSquared =
                (particle.x - landmark.x.toDouble()).pow(2) +
                        (particle.y - landmark.y.toDouble()).pow(2)

            ln(particle.weight.coerceAtLeast(1e-300)) -
                    distanceSquared / (2.0 * sigma * sigma)
        }

        val maxLog = logWeights.max()
        val weights = logWeights.map { exp(it - maxLog) }
        val total = weights.sum()

        if (!total.isFinite() || total <= 0.0) {
            return LabUpdate(
                false,
                "Rejected: numerical normalization failure."
            )
        }

        cloud = cloud.mapIndexed { index, particle ->
            particle.copy(weight = weights[index] / total)
        }

        // Weak observations only partially reduce the drift accumulator.
        distanceSinceCorrectionM *= if (adaptive) {
            1.0 - 0.8 * simulatedQuality
        } else {
            0.6
        }

        val ess = 1.0 / cloud.sumOf {
            it.weight * it.weight
        }

        if (ess < particleCount / 2.0) {
            systematicResample()
        }

        return LabUpdate(
            true,
            "Landmark Observation accepted; sigma = %.2f m.".format(sigma)
        )
    }

    fun confirmFloorTransition(
        targetNodeId: String
    ): LabUpdate {
        check(cloud.isNotEmpty())

        val target = nodes[targetNodeId]
            ?: return LabUpdate(false, "Unknown target connector.")

        if (target.type !in setOf("STAIRS", "ELEVATOR")) {
            return LabUpdate(false, "Target is not a mapped connector.")
        }

        val current = estimate()

        val source = availableEdges.asSequence().mapNotNull { edge ->
            val otherId = when (targetNodeId) {
                edge.sourceNodeId -> edge.destinationNodeId
                edge.destinationNodeId -> edge.sourceNodeId
                else -> null
            }

            otherId?.let { nodes[it] }
        }.firstOrNull { candidate ->
            candidate.floorId == current.floorId &&
                    candidate.floorId != target.floorId &&
                    candidate.type == target.type &&
                    components[candidate.nodeId] == activeComponent &&
                    hypot(
                        candidate.x.toDouble() - current.x,
                        candidate.y.toDouble() - current.y
                    ) <= 2.0
        }

        if (source == null) {
            return LabUpdate(
                false,
                "Rejected: move near a directly connected source connector first."
            )
        }

        reset(target.nodeId)

        return LabUpdate(
            true,
            "Manual floor confirmation: ${source.name} → ${target.floorId}. " +
                    "Position reinitialized at the confirmed connector."
        )
    }

    private fun systematicResample() {
        val output = ArrayList<LabParticle>(particleCount)
        val offset = random.nextDouble() / particleCount

        var index = 0
        var cumulative = cloud[0].weight

        repeat(particleCount) { sample ->
            val threshold = offset + sample.toDouble() / particleCount

            while (threshold > cumulative && index < cloud.lastIndex) {
                index++
                cumulative += cloud[index].weight
            }

            output.add(
                cloud[index].copy(weight = 1.0 / particleCount)
            )
        }

        cloud = output
    }

    private fun sampleNear(
        node: LocationNode,
        sigma: Double
    ): LabParticle {
        repeat(40) {
            val x = node.x.toDouble() + gaussian() * sigma
            val y = node.y.toDouble() + gaussian() * sigma

            if (validPoint(x, y, node.floorId)) {
                return LabParticle(
                    x, y, node.floorId, 1.0 / particleCount
                )
            }
        }

        return LabParticle(
            node.x.toDouble(),
            node.y.toDouble(),
            node.floorId,
            1.0 / particleCount
        )
    }

    private fun validMovement(
        start: LabParticle,
        endX: Double,
        endY: Double
    ): Boolean {
        val length = hypot(endX - start.x, endY - start.y)
        val samples = ceil(length / 0.15).toInt().coerceAtLeast(1)

        return (1..samples).all { index ->
            val fraction = index.toDouble() / samples

            validPoint(
                start.x + fraction * (endX - start.x),
                start.y + fraction * (endY - start.y),
                start.floorId
            )
        }
    }

    private fun validPoint(
        x: Double,
        y: Double,
        floorId: String
    ): Boolean {
        val floor = building.floors.firstOrNull {
            it.floorId == floorId
        } ?: return false

        if (x < 0.0 || y < 0.0 ||
            x > floor.widthM.toDouble() ||
            y > floor.heightM.toDouble()
        ) {
            return false
        }

        val onCorridor = availableEdges.any { edge ->
            val a = nodes.getValue(edge.sourceNodeId)
            val b = nodes.getValue(edge.destinationNodeId)

            a.floorId == floorId &&
                    b.floorId == floorId &&
                    components[a.nodeId] == activeComponent &&
                    segmentDistance(x, y, a, b) <= corridorHalfWidthM
        }

        if (onCorridor) return true

        return building.nodes.any { node ->
            node.floorId == floorId &&
                    components[node.nodeId] == activeComponent &&
                    hypot(
                        x - node.x.toDouble(),
                        y - node.y.toDouble()
                    ) <= corridorHalfWidthM
        }
    }

    private fun segmentDistance(
        x: Double,
        y: Double,
        a: LocationNode,
        b: LocationNode
    ): Double {
        val ax = a.x.toDouble()
        val ay = a.y.toDouble()
        val dx = b.x.toDouble() - ax
        val dy = b.y.toDouble() - ay
        val lengthSquared = dx * dx + dy * dy

        val fraction = if (lengthSquared == 0.0) {
            0.0
        } else {
            (
                    ((x - ax) * dx + (y - ay) * dy) / lengthSquared
                    ).coerceIn(0.0, 1.0)
        }

        return hypot(
            x - (ax + fraction * dx),
            y - (ay + fraction * dy)
        )
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()

        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}