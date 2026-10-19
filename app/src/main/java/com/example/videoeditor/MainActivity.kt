package com.example.videoeditor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

private val Accent = Color(0xFFF2C200) // curve color, like the reference

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

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

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

    // Live preview of the speed curve: while playing, set the player's speed to the
    // curve value at the current position of the current clip (pitch follows the
    // clip's maintain-pitch setting). ExoPlayer preview speed is capped ~0.1–8x.
    LaunchedEffect(Unit) {
        while (true) {
            val clips = vm.state.value.clips
            if (player.playbackState == Player.STATE_READY && clips.isNotEmpty()) {
                val clip = clips.getOrNull(player.currentMediaItemIndex)
                if (clip != null && clip.hasSpeedChange && clip.trimmedDurationMs > 0) {
                    val frac = (player.currentPosition.toFloat() / clip.trimmedDurationMs).coerceIn(0f, 1f)
                    val sp = speedAt(clip.speedCurve, frac).coerceIn(0.1f, 8f)
                    val pitch = if (clip.maintainPitch) 1f else sp
                    val cur = player.playbackParameters
                    if (kotlin.math.abs(cur.speed - sp) > 0.02f || kotlin.math.abs(cur.pitch - pitch) > 0.02f) {
                        player.playbackParameters = PlaybackParameters(sp, pitch)
                    }
                } else if (player.playbackParameters.speed != 1f) {
                    player.playbackParameters = PlaybackParameters(1f)
                }
            }
            delay(80)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris: List<Uri> -> vm.addClips(uris) }

    fun pickVideos() =
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    if (state.exporting) {
        ExportProgressDialog(progress = state.exportProgress, onCancel = vm::cancelExport)
    }

    state.speedEditorClip?.let { clip ->
        SpeedCurveSheet(
            clip = clip,
            onCurve = { vm.setSpeedCurve(clip.id, it) },
            onMaintainPitch = { vm.setMaintainPitch(clip.id, it) },
            onClose = vm::closeSpeedEditor,
        )
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

            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
                modifier = Modifier.fillMaxWidth().height(220.dp).background(Color.Black),
            )

            state.selected?.let { clip ->
                TrimPanel(
                    clip = clip,
                    onTrim = { s, e -> vm.setTrim(clip.id, s, e) },
                    onOpenSpeed = { vm.openSpeedEditor(clip.id) },
                )
                HorizontalDivider()
            }

            if (state.clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap + to add videos", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.clips, key = { _, clip -> clip.id }) { index, clip ->
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
private fun TrimPanel(clip: Clip, onTrim: (Long, Long) -> Unit, onOpenSpeed: () -> Unit) {
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
                onValueChange = { r -> onTrim(r.start.toLong(), r.endInclusive.toLong()) },
                valueRange = 0f..clip.durationMs.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(clip.trimStartMs), style = MaterialTheme.typography.bodySmall)
                Text("${formatDuration(clip.trimmedDurationMs)} kept", style = MaterialTheme.typography.bodySmall)
                Text(formatDuration(clip.trimEndMs), style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onOpenSpeed) {
            Icon(Icons.Default.Speed, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (clip.hasSpeedChange) "Speed curve (on)" else "Speed curve")
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
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 64.dp, height = 44.dp).background(Color.DarkGray),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = clip.thumbnail
            if (thumb != null) Image(bitmap = thumb.asImageBitmap(), contentDescription = null)
            else Icon(Icons.Default.Movie, contentDescription = null, tint = Color.LightGray)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${index + 1}. ${clip.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(clip.trimmedDurationMs)} of ${formatDuration(clip.durationMs)}" +
                    if (clip.hasSpeedChange) " · speed" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(onClick = onUp, enabled = index > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onDown, enabled = index < total - 1) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onSelect) {
            Icon(if (selected) Icons.Default.Edit else Icons.Default.ContentCut, contentDescription = "Select")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove")
        }
    }
}

@Composable
private fun ExportProgressDialog(progress: Int, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Exporting video") },
        text = {
            Column {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("$progress%")
            }
        },
    )
}

// ---------------- Speed curve editor ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedCurveSheet(
    clip: Clip,
    onCurve: (List<SpeedPoint>) -> Unit,
    onMaintainPitch: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var points by remember(clip.id) { mutableStateOf(clip.speedCurve) }

    fun update(newPoints: List<SpeedPoint>) {
        points = newPoints
        onCurve(newPoints)
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Speed curve — ${clip.displayName}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                "Output ≈ ${formatDuration(estimatedOutputMs(points, clip.trimmedDurationMs))}  ·  drag dots, tap to add, double-tap to remove",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth().height(240.dp)) {
                SpeedCurveEditor(points = points, onChange = ::update, modifier = Modifier.fillMaxSize())
                Text("10x", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart))
                Text("1x", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterStart))
                Text("0.1x", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep audio pitch (no chipmunk/deep voice)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = clip.maintainPitch, onCheckedChange = onMaintainPitch)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { update(DEFAULT_SPEED_CURVE) }) {
                    Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Reset")
                }
                TextButton(onClick = { update(SLOWMO_PRESET) }) {
                    Icon(Icons.Default.SlowMotionVideo, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Slow-mo")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClose) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SpeedCurveEditor(
    points: List<SpeedPoint>,
    onChange: (List<SpeedPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeIndex by remember { mutableStateOf(-1) }
    val grid = Color(0xFF444444)
    val hitPx = 70f

    Canvas(
        modifier
            .background(Color(0xFF1C1C1C))
            .pointerInput(points) {
                detectDragGestures(
                    onDragStart = { pos -> activeIndex = nearestIndex(points, pos, size.width.toFloat(), size.height.toFloat(), hitPx) },
                    onDragEnd = { activeIndex = -1 },
                    onDragCancel = { activeIndex = -1 },
                    onDrag = { change, _ ->
                        change.consume()
                        val i = activeIndex
                        if (i in points.indices) {
                            onChange(movePoint(points, i, change.position, size.width.toFloat(), size.height.toFloat()))
                        }
                    },
                )
            }
            .pointerInput(points) {
                detectTapGestures(
                    onTap = { pos -> onChange(addPoint(points, pos, size.width.toFloat(), size.height.toFloat(), hitPx)) },
                    onDoubleTap = { pos -> onChange(removePoint(points, pos, size.width.toFloat(), size.height.toFloat(), hitPx)) },
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))

        // Reference lines at 10x, 1x, 0.1x
        drawLine(grid, Offset(0f, yForSpeed(1f, h)), Offset(w, yForSpeed(1f, h)), strokeWidth = 2f)
        drawLine(grid, Offset(0f, yForSpeed(5f, h)), Offset(w, yForSpeed(5f, h)), strokeWidth = 1f, pathEffect = dash)
        drawLine(grid, Offset(0f, yForSpeed(0.2f, h)), Offset(w, yForSpeed(0.2f, h)), strokeWidth = 1f, pathEffect = dash)

        // Smooth curve
        val path = Path()
        val samples = 80
        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = t * w
            val y = yForSpeed(speedAt(points, t), h)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Accent, style = Stroke(width = 4f))

        // Control points
        points.forEachIndexed { i, p ->
            val c = Offset(p.t * w, yForSpeed(p.speed, h))
            drawCircle(Color.White, radius = 22f, center = c)
            drawCircle(if (i == activeIndex) Accent else Color.Black, radius = 15f, center = c)
        }
    }
}

// --- curve <-> pixel mapping (speed axis is log scale) ---

private fun yForSpeed(speed: Float, h: Float): Float {
    val norm = (ln(speed.coerceIn(MIN_SPEED, MAX_SPEED)) - ln(MIN_SPEED)) / (ln(MAX_SPEED) - ln(MIN_SPEED))
    return h * (1f - norm)
}

private fun speedForY(y: Float, h: Float): Float {
    val norm = (1f - (y / h)).coerceIn(0f, 1f)
    return exp(ln(MIN_SPEED) + norm * (ln(MAX_SPEED) - ln(MIN_SPEED))).coerceIn(MIN_SPEED, MAX_SPEED)
}

private fun nearestIndex(points: List<SpeedPoint>, pos: Offset, w: Float, h: Float, hitPx: Float): Int {
    var best = -1
    var bestD = hitPx
    points.forEachIndexed { i, p ->
        val dx = p.t * w - pos.x
        val dy = yForSpeed(p.speed, h) - pos.y
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

private fun movePoint(points: List<SpeedPoint>, i: Int, pos: Offset, w: Float, h: Float): List<SpeedPoint> {
    val list = points.toMutableList()
    val isEndpoint = (i == 0 || i == list.lastIndex)
    val newSpeed = speedForY(pos.y, h)
    val newT = when {
        i == 0 -> 0f
        i == list.lastIndex -> 1f
        else -> {
            val lo = list[i - 1].t + 0.01f
            val hi = list[i + 1].t - 0.01f
            (pos.x / w).coerceIn(lo.coerceAtMost(hi), hi.coerceAtLeast(lo))
        }
    }
    list[i] = SpeedPoint(if (isEndpoint) list[i].t else newT, newSpeed)
    return list
}

private fun addPoint(points: List<SpeedPoint>, pos: Offset, w: Float, h: Float, hitPx: Float): List<SpeedPoint> {
    if (points.size >= 8) return points
    if (nearestIndex(points, pos, w, h, hitPx) >= 0) return points // too close to existing
    val t = (pos.x / w).coerceIn(0f, 1f)
    val speed = speedForY(pos.y, h)
    return (points + SpeedPoint(t, speed)).sortedBy { it.t }
}

private fun removePoint(points: List<SpeedPoint>, pos: Offset, w: Float, h: Float, hitPx: Float): List<SpeedPoint> {
    val i = nearestIndex(points, pos, w, h, hitPx)
    if (i <= 0 || i >= points.lastIndex) return points // keep endpoints
    return points.toMutableList().also { it.removeAt(i) }
}

/** Rough output length: source length times the average of 1/speed across the curve. */
private fun estimatedOutputMs(points: List<SpeedPoint>, sourceMs: Long): Long {
    if (sourceMs <= 0) return 0
    val samples = 60
    var invSum = 0f
    for (i in 0 until samples) {
        val t = (i + 0.5f) / samples
        invSum += 1f / speedAt(points, t)
    }
    return (sourceMs * (invSum / samples)).toLong()
}
