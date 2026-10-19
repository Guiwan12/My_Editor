package com.example.videoeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { EditorScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // One ExoPlayer for the whole screen; released when we leave.
    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Rebuild the preview playlist whenever the clips (or their trims) change.
    LaunchedEffect(state.clips) {
        val items = state.clips.map { clip ->
            MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()
        }
        val wasPlaying = player.isPlaying
        player.setMediaItems(items)
        player.prepare()
        if (wasPlaying) player.play()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris: List<Uri> -> vm.addClips(uris) }

    fun pickVideos() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    if (state.exporting) {
        ExportProgressDialog(progress = state.exportProgress, onCancel = vm::cancelExport)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("My Video Editor") },
                actions = {
                    state.lastOutput?.let { file ->
                        IconButton(onClick = {
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    Output.shareIntent(context, file), "Share video"
                                )
                            )
                        }) { Icon(Icons.Default.Share, contentDescription = "Share last export") }
                    }
                    IconButton(onClick = { pickVideos() }) {
                        Icon(Icons.Default.Add, contentDescription = "Add clips")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${state.clips.size} clips · ${formatDuration(state.totalMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = vm::export,
                        enabled = state.clips.isNotEmpty() && !state.exporting,
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // Preview
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black),
            )

            // Trim controls for the selected clip
            state.selected?.let { clip ->
                TrimPanel(clip = clip, onTrim = { s, e -> vm.setTrim(clip.id, s, e) })
                HorizontalDivider()
            }

            if (state.clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap + to add videos", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(
                        state.clips,
                        key = { _, clip -> clip.id },
                    ) { index, clip ->
                        ClipRow(
                            index = index,
                            total = state.clips.size,
                            clip = clip,
                            selected = clip.id == state.selectedId,
                            onSelect = { vm.select(clip.id) },
                            onUp = { vm.move(clip.id, up = true) },
                            onDown = { vm.move(clip.id, up = false) },
                            onRemove = { vm.remove(clip.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TrimPanel(clip: Clip, onTrim: (Long, Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Trim: ${clip.displayName}",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (clip.durationMs > 0) {
            RangeSlider(
                value = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat(),
                onValueChange = { range ->
                    onTrim(range.start.toLong(), range.endInclusive.toLong())
                },
                valueRange = 0f..clip.durationMs.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(clip.trimStartMs), style = MaterialTheme.typography.bodySmall)
                Text(
                    "${formatDuration(clip.trimmedDurationMs)} kept",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(formatDuration(clip.trimEndMs), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ClipRow(
    index: Int,
    total: Int,
    clip: Clip,
    selected: Boolean,
    onSelect: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        Box(
            Modifier.size(width = 64.dp, height = 44.dp).background(Color.DarkGray),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = clip.thumbnail
            if (thumb != null) {
                Image(bitmap = thumb.asImageBitmap(), contentDescription = null)
            } else {
                Icon(Icons.Default.Movie, contentDescription = null, tint = Color.LightGray)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${index + 1}. ${clip.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(clip.trimmedDurationMs)} of ${formatDuration(clip.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Reorder + select + delete
        IconButton(onClick = onUp, enabled = index > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onDown, enabled = index < total - 1) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onSelect) {
            Icon(
                if (selected) Icons.Default.Edit else Icons.Default.ContentCut,
                contentDescription = "Trim",
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove")
        }
    }
}

@Composable
private fun ExportProgressDialog(progress: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* keep open while exporting */ },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Exporting video") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("$progress%")
            }
        },
    )
}
