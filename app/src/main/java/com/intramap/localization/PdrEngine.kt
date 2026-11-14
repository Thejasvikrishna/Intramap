package com.intramap.localization

import kotlin.math.cos
import kotlin.math.sin

class PdrEngine(startX: Float, startY: Float, var stepLengthM: Float = 0.70f) {
    var x = startX; private set
    var y = startY; private set
    var steps = 0; private set
    var distanceM = 0f; private set
    var headingOffsetDeg = 0f

    fun reset(newX: Float, newY: Float) { x = newX; y = newY; steps = 0; distanceM = 0f }

    fun calibrate(rawAzimuthDeg: Float, knownMapHeadingDeg: Float) {
        headingOffsetDeg = rawAzimuthDeg - knownMapHeadingDeg
    }

    fun mapHeading(rawAzimuthDeg: Float): Float =
        (((rawAzimuthDeg - headingOffsetDeg) % 360f) + 360f) % 360f

    fun onStep(rawAzimuthDeg: Float) {
        val h = Math.toRadians(mapHeading(rawAzimuthDeg).toDouble())
        x += (stepLengthM * sin(h)).toFloat()
        y -= (stepLengthM * cos(h)).toFloat()
        steps++
        distanceM += stepLengthM
    }
}