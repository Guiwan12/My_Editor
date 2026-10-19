package com.example.videoeditor

import android.graphics.Bitmap
import android.net.Uri

/**
 * One clip on the timeline: a source video plus the in/out trim points and the
 * speed curve the user set. The source file is never modified — trimming and
 * speed just record how this clip should play, and the exporter applies them
 * (non-destructive editing, the same idea CapCut's engine uses, far simpler).
 */
data class Clip(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val thumbnail: Bitmap? = null,
    val speedCurve: List<SpeedPoint> = DEFAULT_SPEED_CURVE,
    /** Keep the audio pitch steady when speeding up/slowing down (vs. chipmunk/deep). */
    val maintainPitch: Boolean = false,
    /** EXPERIMENTAL: GPU frame blending to soften slow-motion stutter. */
    val frameBlend: Boolean = false,
    /** Blend strength 0..1 (how much of the previous frame to mix in). */
    val frameBlendAmount: Float = 0.5f,
) {
    /** Length of this clip after trimming (before speed is applied). */
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    val hasSpeedChange: Boolean get() = !speedCurve.isFlat()
}

/** Format milliseconds as m:ss (or h:mm:ss for long clips). */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%d:%02d", m, s)
}
