package com.example.videoeditor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.SpeedParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Turns the timeline (a list of trimmed, speed-ramped clips, in order) into a
 * single exported MP4 using Media3 Transformer.
 *
 *   - each clip's trim becomes a MediaItem.ClippingConfiguration
 *   - each clip's speed curve becomes a SpeedProvider via setSpeed(...), which
 *     ramps audio AND video timestamps together
 *   - the ordered list becomes one EditedMediaItemSequence -> Composition
 *   - Transformer concatenates + transcodes them into one output file.
 *
 * Must be created and driven on the main thread (Transformer needs a Looper).
 */
@UnstableApi
class VideoExporter(private val context: Context) {

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    fun export(
        clips: List<Clip>,
        outputFile: File,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (clips.isEmpty()) {
            onError("No clips to export")
            return
        }

        val editedItems = clips.map { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()
            val builder = EditedMediaItem.Builder(mediaItem)
            if (clip.hasSpeedChange && clip.trimmedDurationMs > 0) {
                val provider = buildSpeedProvider(clip.speedCurve, clip.trimmedDurationMs * 1000)
                builder.setSpeed(SpeedParameters(provider, clip.maintainPitch))
            }
            if (clip.frameBlend) {
                val videoEffects: List<Effect> = listOf(FrameBlendEffect(clip.frameBlendAmount))
                builder.setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
            }
            builder.build()
        }

        val sequence = EditedMediaItemSequence.Builder(editedItems).build()
        val composition = Composition.Builder(sequence).build()

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    stopPolling()
                    onProgress(100)
                    onDone(outputFile)
                }

                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    stopPolling()
                    onError(exception.message ?: "Export failed")
                }
            })
            .build()

        transformer = t
        t.start(composition, outputFile.absolutePath)
        startPolling(onProgress)
    }

    private fun startPolling(onProgress: (Int) -> Unit) {
        polling = true
        val holder = ProgressHolder()
        val runnable = object : Runnable {
            override fun run() {
                if (!polling) return
                val state = transformer?.getProgress(holder) ?: return
                if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(this, 200)
            }
        }
        handler.post(runnable)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacksAndMessages(null)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }
}
