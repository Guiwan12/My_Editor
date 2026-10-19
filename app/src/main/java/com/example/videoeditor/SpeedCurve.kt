package com.example.videoeditor

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** One control point on the speed curve: [t] in 0..1 along the clip, [speed] a multiplier. */
data class SpeedPoint(val t: Float, val speed: Float)

const val MIN_SPEED = 0.1f   // 10x slower
const val MAX_SPEED = 10f    // 10x faster

/** Flat 1x curve = no speed change. */
val DEFAULT_SPEED_CURVE: List<SpeedPoint> = listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f))

/** A ready-made "slow the middle down" ramp to show the feature off. */
val SLOWMO_PRESET: List<SpeedPoint> = listOf(
    SpeedPoint(0f, 1f),
    SpeedPoint(0.4f, 0.3f),
    SpeedPoint(0.6f, 0.3f),
    SpeedPoint(1f, 1f),
)

fun List<SpeedPoint>.isFlat(): Boolean = all { abs(it.speed - 1f) < 0.001f }

/**
 * Speed at position [t] (0..1), interpolated smoothly between control points.
 *
 * Interpolation is a smoothstep in *log* speed space: speed is a multiplier, so
 * halving then doubling should feel symmetric, which log space gives you. The
 * smoothstep (ease in/out) is what makes the ramp feel gradual instead of linear.
 */
fun speedAt(curve: List<SpeedPoint>, t: Float): Float {
    if (curve.isEmpty()) return 1f
    val x = t.coerceIn(0f, 1f)
    val pts = curve.sortedBy { it.t }
    if (x <= pts.first().t) return pts.first().speed
    if (x >= pts.last().t) return pts.last().speed
    for (i in 0 until pts.size - 1) {
        val a = pts[i]
        val b = pts[i + 1]
        if (x >= a.t && x <= b.t) {
            val span = b.t - a.t
            val local = if (span <= 1e-6f) 0f else (x - a.t) / span
            val s = local * local * (3f - 2f * local) // smoothstep ease in/out
            val logSpeed = ln(a.speed) + (ln(b.speed) - ln(a.speed)) * s
            return exp(logSpeed).coerceIn(MIN_SPEED, MAX_SPEED)
        }
    }
    return pts.last().speed
}

/**
 * Turn the smooth curve into a Media3 [SpeedProvider] by sampling it into small
 * constant-speed segments (25 ms each). Fine enough that the stepping reads as a
 * continuous ramp. Built like Media3's own SegmentSpeedProvider: a sorted map of
 * startTimeUs -> speed, floorEntry for the current speed, higherKey for the next
 * change, C.TIME_UNSET when there is none.
 */
@UnstableApi
fun buildSpeedProvider(curve: List<SpeedPoint>, durationUs: Long): SpeedProvider {
    val map = TreeMap<Long, Float>()
    if (durationUs <= 0) {
        map[0L] = 1f
    } else {
        val stepUs = 25_000L
        val steps = max(1, ((durationUs + stepUs - 1) / stepUs).toInt())
        var lastSpeed = Float.NaN
        for (k in 0 until steps) {
            val startUs = k * stepUs
            val midFraction = ((startUs + stepUs / 2).toFloat() / durationUs).coerceIn(0f, 1f)
            val speed = speedAt(curve, midFraction)
            if (lastSpeed.isNaN() || abs(speed - lastSpeed) > 0.001f) {
                map[startUs] = speed
                lastSpeed = speed
            }
        }
        if (map.isEmpty()) map[0L] = 1f
    }

    return object : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float {
            val key = if (timeUs < 0) 0L else timeUs
            return map.floorEntry(key)?.value ?: map.firstEntry().value
        }

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            val key = if (timeUs < 0) 0L else timeUs
            return map.higherKey(key) ?: C.TIME_UNSET
        }
    }
}
