package com.intramap.domain.mapping

import kotlin.math.cos
import kotlin.math.sin

data class WalkingSample(
    val x: Double,
    val y: Double,
    val timeMs: Long,
    val headingDeg: Double,
    val marker: String? = null
)

/** Relative PDR trace, not an absolute fix or a published routing graph. */
class WalkingTrace(startTimeMs: Long) {
    private val samples = mutableListOf(
        WalkingSample(0.0, 0.0, startTimeMs, 0.0, "START")
    )
    var distanceM: Double = 0.0
        private set

    init { require(startTimeMs >= 0) }

    fun snapshot(): List<WalkingSample> = samples.toList()

    fun step(lengthM: Double, headingDeg: Double, timeMs: Long): Boolean {
        val last = samples.last()
        if (!lengthM.isFinite() || lengthM !in 0.1..2.0 ||
            !headingDeg.isFinite() || timeMs <= last.timeMs || samples.size >= 10000) {
            return false
        }
        val heading = ((headingDeg % 360.0) + 360.0) % 360.0
        val radians = Math.toRadians(heading)
        samples += WalkingSample(
            last.x + lengthM * sin(radians),
            last.y - lengthM * cos(radians),
            timeMs,
            heading
        )
        distanceM += lengthM
        return true
    }

    fun mark(type: String, name: String): Boolean {
        if (type !in setOf("ROOM", "ENTRANCE", "STAIRS", "ELEVATOR", "JUNCTION", "TURN") ||
            name.isBlank() || name.length > 120) return false
        samples[samples.lastIndex] = samples.last().copy(marker = "$type:${name.trim()}")
        return true
    }
}
