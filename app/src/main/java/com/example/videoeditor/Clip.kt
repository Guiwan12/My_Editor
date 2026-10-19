package com.example.videoeditor

import android.graphics.Bitmap
import android.net.Uri

/**
 * One clip on the timeline: a source video plus the in/out trim points the user
 * set. The source file is never modified — trimming just records where this clip
 * should start and end, and the exporter applies it (non-destructive editing,
 * the same idea CapCut's engine uses, just far simpler).
 */
data class Clip(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val thumbnail: Bitmap? = null,
) {
    /** Length of this clip after trimming. */
    val trimmedDurationMs: Long get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
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
