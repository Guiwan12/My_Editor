package com.example.videoeditor

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/**
 * Turns the timeline (a list of trimmed clips, in order) into a single exported
 * MP4 using Media3 Transformer.
 *
 * How it maps to the editor idea:
 *   - each clip's trim becomes a MediaItem.ClippingConfiguration (in/out points)
 *   - each becomes an EditedMediaItem
 *   - the ordered list becomes one EditedMediaItemSequence -> Composition
 *   - Transformer concatenates and transcodes them into one output file,
 *     re-encoding only as needed to make the clips line up.
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
            // removeAudio = false keeps each clip's audio; flip if you want silent output.
            EditedMediaItem.Builder(mediaItem).build()
        }

        val sequence = EditedMediaItemSequence(editedItems)
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
