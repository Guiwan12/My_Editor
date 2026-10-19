package com.example.videoeditor

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class EditorState(
    val clips: List<Clip> = emptyList(),
    val selectedId: Long? = null,
    val speedEditorId: Long? = null,   // clip whose speed-curve editor is open
    val exporting: Boolean = false,
    val exportProgress: Int = 0,
    val lastOutput: File? = null,
    val message: String? = null,
) {
    val selected: Clip? get() = clips.firstOrNull { it.id == selectedId }
    val speedEditorClip: Clip? get() = clips.firstOrNull { it.id == speedEditorId }
    val totalMs: Long get() = clips.sumOf { it.trimmedDurationMs }
}

@UnstableApi
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val exporter = VideoExporter(app)
    private var nextId = 1L

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    fun addClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val newClips = withContext(Dispatchers.IO) { uris.mapNotNull { buildClip(it) } }
            val combined = _state.value.clips + newClips
            _state.value = _state.value.copy(
                clips = combined,
                selectedId = _state.value.selectedId ?: combined.firstOrNull()?.id,
            )
        }
    }

    private fun buildClip(uri: Uri): Clip? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication(), uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return null
            val name = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment ?: "clip"
            val thumb: Bitmap? = runCatching {
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }.getOrNull()
            Clip(
                id = nextId++,
                uri = uri,
                displayName = name,
                durationMs = durationMs,
                trimStartMs = 0L,
                trimEndMs = durationMs,
                thumbnail = thumb,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun select(id: Long) { _state.value = _state.value.copy(selectedId = id) }

    fun remove(id: Long) {
        val remaining = _state.value.clips.filterNot { it.id == id }
        _state.value = _state.value.copy(
            clips = remaining,
            selectedId = if (_state.value.selectedId == id) remaining.firstOrNull()?.id else _state.value.selectedId,
        )
    }

    fun move(id: Long, up: Boolean) {
        val list = _state.value.clips.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        _state.value = _state.value.copy(clips = list)
    }

    fun setTrim(id: Long, startMs: Long, endMs: Long) {
        val list = _state.value.clips.map { clip ->
            if (clip.id != id) clip
            else {
                val s = startMs.coerceIn(0L, clip.durationMs)
                val e = endMs.coerceIn(0L, clip.durationMs)
                clip.copy(trimStartMs = s.coerceAtMost(e), trimEndMs = e.coerceAtLeast(s))
            }
        }
        _state.value = _state.value.copy(clips = list)
    }

    // --- Speed curve ---

    fun openSpeedEditor(id: Long) { _state.value = _state.value.copy(speedEditorId = id) }
    fun closeSpeedEditor() { _state.value = _state.value.copy(speedEditorId = null) }

    fun setSpeedCurve(id: Long, curve: List<SpeedPoint>) {
        val cleaned = curve
            .map { SpeedPoint(it.t.coerceIn(0f, 1f), it.speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
            .sortedBy { it.t }
        val list = _state.value.clips.map { if (it.id == id) it.copy(speedCurve = cleaned) else it }
        _state.value = _state.value.copy(clips = list)
    }

    fun resetSpeedCurve(id: Long) = setSpeedCurve(id, DEFAULT_SPEED_CURVE)

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    fun export() {
        val clips = _state.value.clips
        if (clips.isEmpty()) {
            _state.value = _state.value.copy(message = "Add at least one clip first")
            return
        }
        val outFile = File(Output.outputDir(getApplication()), "export_${System.currentTimeMillis()}.mp4")
        _state.value = _state.value.copy(exporting = true, exportProgress = 0)
        exporter.export(
            clips = clips,
            outputFile = outFile,
            onProgress = { p -> _state.value = _state.value.copy(exportProgress = p) },
            onDone = { file ->
                viewModelScope.launch {
                    val savedToGallery = withContext(Dispatchers.IO) {
                        runCatching { Output.saveToGallery(getApplication(), file) != null }.getOrDefault(false)
                    }
                    _state.value = _state.value.copy(
                        exporting = false,
                        exportProgress = 100,
                        lastOutput = file,
                        message = if (savedToGallery) "Exported to your gallery (Movies/MyVideoEditor)"
                                  else "Exported: ${file.name}",
                    )
                }
            },
            onError = { err ->
                _state.value = _state.value.copy(exporting = false, message = "Export failed: $err")
            },
        )
    }

    fun cancelExport() {
        exporter.cancel()
        _state.value = _state.value.copy(exporting = false, message = "Export cancelled")
    }

    override fun onCleared() {
        exporter.cancel()
        super.onCleared()
    }
}
