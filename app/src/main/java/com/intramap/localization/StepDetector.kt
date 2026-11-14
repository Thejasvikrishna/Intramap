package com.intramap.localization

import kotlin.math.sqrt

data class StepEvent(val timestampNs: Long, val peak: Float)

class StepDetector(
    private val minIntervalNs: Long = 300_000_000L,
    private val minPeak: Float = 1.0f
) {
    private var gravity = 9.81f
    private var smooth = 0f
    private var prev2 = 0f
    private var prev1 = 0f
    private var lastStepNs = Long.MIN_VALUE / 2

    fun reset() { gravity = 9.81f; smooth = 0f; prev2 = 0f; prev1 = 0f; lastStepNs = Long.MIN_VALUE / 2 }

    fun update(tNs: Long, ax: Float, ay: Float, az: Float): StepEvent? {
        val mag = sqrt(ax * ax + ay * ay + az * az)
        gravity = 0.99f * gravity + 0.01f * mag
        val dyn = mag - gravity
        val cur = 0.8f * smooth + 0.2f * dyn
        var step: StepEvent? = null
        if (prev1 > prev2 && prev1 >= cur && prev1 > minPeak && tNs - lastStepNs > minIntervalNs) {
            step = StepEvent(tNs, prev1)
            lastStepNs = tNs
        }
        prev2 = prev1; prev1 = cur; smooth = cur
        return step
    }
}