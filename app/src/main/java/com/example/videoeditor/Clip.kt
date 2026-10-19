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
    /** Output shape. ORIGINAL keeps the source aspect. */
    val aspect: AspectOption = AspectOption.ORIGINAL,
    /** Pinch zoom (1x..4x) and pan offset in normalized coords (-1..1). */
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val hasResize: Boolean
        get() = aspect != AspectOption.ORIGINAL || zoom != 1f || panX != 0f || panY != 0f
    /** Length of this clip after trimming (before speed is applied). */
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)

    val hasSpeedChange: Boolean get() = !speedCurve.isFlat()
}

/** Output aspect-ratio choices; [ratio] is width/height, 0 = keep original. */
enum class AspectOption(val label: String, val ratio: Float) {
    ORIGINAL("Original", 0f),
    R9_16("9:16", 9f / 16f),
    R16_9("16:9", 16f / 9f),
    R1_1("1:1", 1f),
    R4_3("4:3", 4f / 3f),
    R2_1("2:1", 2f / 1f),
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

/** Precise time for cutting: m:ss.cc (centiseconds), e.g. "0:03.45". */
fun formatPrecise(ms: Long): String {
    val m = ms / 60000
    val s = (ms % 60000) / 1000
    val cs = (ms % 1000) / 10
    return String.format("%d:%02d.%02d", m, s, cs)
}
