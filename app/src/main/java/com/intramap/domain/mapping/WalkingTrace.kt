package com.intramap.domain.mapping

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class WalkingSample(val x: Double, val y: Double, val timeMs: Long, val headingDeg: Double, val marker: String? = null)

/** Relative PDR geometry; restoring it never establishes a live position fix. */
class WalkingTrace private constructor(private val samples: MutableList<WalkingSample>, initialDistance: Double) {
    constructor(startTimeMs: Long) : this(mutableListOf(WalkingSample(0.0,0.0,startTimeMs,0.0,"START")),0.0) {
        require(startTimeMs >= 0 && startTimeMs < Long.MAX_VALUE)
    }
    var distanceM = initialDistance
        private set
    fun snapshot(): List<WalkingSample> = samples.toList()
    fun step(lengthM: Double, headingDeg: Double, timeMs: Long): Boolean {
        val last = samples.last()
        if (!lengthM.isFinite() || lengthM !in 0.1..2.0 || !headingDeg.isFinite() ||
            timeMs <= last.timeMs || timeMs == Long.MAX_VALUE || samples.size >= 10000) return false
        val heading = ((headingDeg % 360.0) + 360.0) % 360.0
        val angle = Math.toRadians(heading)
        val x = last.x + lengthM * sin(angle); val y = last.y - lengthM * cos(angle)
        if (abs(x) > 10000 || abs(y) > 10000) return false
        samples += WalkingSample(x,y,timeMs,heading)
        distanceM += lengthM
        return true
    }
    fun mark(type: String, name: String): Boolean {
        if (type !in setOf("ROOM","ENTRANCE","STAIRS","ELEVATOR","JUNCTION","TURN") || name.isBlank() || name.length > 120) return false
        samples[samples.lastIndex] = samples.last().copy(marker = "$type:${name.trim()}")
        return true
    }
    companion object {
        fun restore(points: List<WalkingSample>): WalkingTrace {
            require(points.size in 1..10000)
            require(abs(points.first().x) < 1e-6 && abs(points.first().y) < 1e-6)
            require(points.all { it.x.isFinite() && it.y.isFinite() && abs(it.x) <= 10000 && abs(it.y) <= 10000 &&
                it.headingDeg.isFinite() && it.headingDeg >= 0 && it.headingDeg < 360 &&
                it.timeMs >= 0 && it.timeMs < Long.MAX_VALUE && (it.marker?.length ?: 0) <= 140 })
            var distance = 0.0
            points.zipWithNext().forEach { (a,b) ->
                require(b.timeMs > a.timeMs)
                val segment = hypot(b.x-a.x,b.y-a.y)
                require(segment in 0.099999..2.000001)
                distance += segment
            }
            return WalkingTrace(points.toMutableList(),distance)
        }
    }
}
