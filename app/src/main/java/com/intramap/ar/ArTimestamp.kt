package com.intramap.ar

import android.os.SystemClock
import kotlin.math.roundToLong

/**
 * Converts an ARCore frame timestamp to Android elapsed milliseconds.
 * Returns null for invalid, future, or stale frames rather than fabricating a capture time.
 */
internal fun arFrameTimestampToElapsedMs(
    frameTimestampNanos: Long,
    nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    nowElapsedMs: Long = SystemClock.elapsedRealtime()
): Long? {
    if (frameTimestampNanos <= 0L) return null

    val ageMs = (nowElapsedNanos - frameTimestampNanos) / 1_000_000.0
    if (ageMs < 0.0 || ageMs > 500.0) return null

    return (nowElapsedMs - ageMs).roundToLong()
}
