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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.ui.AspectRatioFrameLayout
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

    // Preview loads the FULL source clips (not pre-trimmed) and only reloads when the
    // set/order/sources change — NOT on every trim. That lets us seek to any exact
    // frame while scrubbing the trim handles, and stops the playlist from thrashing.
    val structureKey = state.clips.joinToString(",") { "${it.id}:${it.uri}" }
    LaunchedEffect(structureKey) {
        player.setMediaItems(state.clips.map { MediaItem.fromUri(it.uri) })
        player.prepare()
    }

    // Live preview of the speed curve while PLAYING: set the player's speed to the
    // curve value at the current position within the clip's kept (trimmed) region.
    LaunchedEffect(Unit) {
        while (true) {
            val clips = vm.state.value.clips
            if (player.isPlaying && clips.isNotEmpty()) {
                val clip = clips.getOrNull(player.currentMediaItemIndex)
                if (clip != null && clip.hasSpeedChange && clip.trimmedDurationMs > 0) {
                    val rel = (player.currentPosition - clip.trimStartMs).toFloat()
                    val frac = (rel / clip.trimmedDurationMs).coerceIn(0f, 1f)
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

            val resizeEditing = state.resizeEditorClip
            val resizeActive = resizeEditing != null

            // Preview. The inner frame is the OUTPUT shape: the video fills it (cropped),
            // the black bars around it change with the chosen aspect, and zoom/pan are
            // clipped to the frame so nothing bleeds onto the controls.
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(220.dp).background(Color.Black).clipToBounds()
            ) {
                val aspect = resizeEditing?.aspect ?: AspectOption.ORIGINAL
                val framed = aspect != AspectOption.ORIGINAL
                var frameW = maxWidth
                var frameH = maxHeight
                if (framed) {
                    var w = maxWidth
                    var h = maxWidth / aspect.ratio
                    if (h > maxHeight) { h = maxHeight; w = maxHeight * aspect.ratio }
                    frameW = w; frameH = h
                }

                Box(
                    Modifier.align(Alignment.Center).size(frameW, frameH).clipToBounds().then(
                        if (resizeEditing != null) Modifier
                            .graphicsLayer {
                                scaleX = resizeEditing.zoom
                                scaleY = resizeEditing.zoom
                                translationX = resizeEditing.panX * size.width / 2f
                                translationY = resizeEditing.panY * size.height / 2f
                            }
                            .pointerInput(resizeEditing.id) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    val w = size.width.toFloat(); val h = size.height.toFloat()
                                    vm.applyPinch(resizeEditing.id, gestureZoom, pan.x / (w / 2f), pan.y / (h / 2f))
                                }
                            }
                        else Modifier
                    )
                ) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx) },
                        update = {
                            it.player = player
                            it.useController = !resizeActive
                            it.resizeMode = if (framed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else AspectRatioFrameLayout.RESIZE_MODE_FIT
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            val speedEditing = state.speedEditorClip
            when {
                resizeEditing != null -> ResizePanel(
                    clip = resizeEditing,
                    onAspect = { vm.setAspect(resizeEditing.id, it) },
                    onZoom = { vm.setZoom(resizeEditing.id, it) },
                    onReset = { vm.resetResize(resizeEditing.id) },
                    onClose = vm::closeResizeEditor,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                speedEditing != null -> SpeedCurvePanel(
                    clip = speedEditing,
                    onCurve = { vm.setSpeedCurve(speedEditing.id, it) },
                    onMaintainPitch = { vm.setMaintainPitch(speedEditing.id, it) },
                    onFrameBlend = { vm.setFrameBlend(speedEditing.id, it) },
                    onFrameBlendAmount = { vm.setFrameBlendAmount(speedEditing.id, it) },
                    onClose = vm::closeSpeedEditor,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )

                else -> {
                    state.selected?.let { clip ->
                        val clipIndex = state.clips.indexOfFirst { it.id == clip.id }
                        TrimPanel(
                            clip = clip,
                            onTrim = { s, e ->
                                val movedStart = s != clip.trimStartMs
                                vm.setTrim(clip.id, s, e)
                                if (clipIndex >= 0) {
                                    player.pause()
                                    player.seekTo(clipIndex, if (movedStart) s else e)
                                }
                            },
                            onOpenSpeed = { vm.openSpeedEditor(clip.id) },
                            onOpenResize = { vm.openResizeEditor(clip.id) },
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
    }
}

@Composable
private fun TrimPanel(clip: Clip, onTrim: (Long, Long) -> Unit, onOpenSpeed: () -> Unit, onOpenResize: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (clip.durationMs > 0) {
            RangeSlider(
                value = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat(),
                onValueChange = { r -> onTrim(r.start.toLong(), r.endInclusive.toLong()) },
                valueRange = 0f..clip.durationMs.toFloat(),
            )
            // Exact cut points + the resulting (shrinking) length, all live.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatPrecise(clip.trimStartMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Kept ${formatPrecise(clip.trimmedDurationMs)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    formatPrecise(clip.trimEndMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenSpeed, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Speed, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (clip.hasSpeedChange) "Speed · on" else "Speed")
            }
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = onOpenResize, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Crop, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (clip.hasResize) "Resize · on" else "Resize")
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
    Column(
        Modifier.fillMaxWidth().background(bg).clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 56.dp, height = 36.dp).background(Color.DarkGray),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = clip.thumbnail
                if (thumb != null) Image(bitmap = thumb.asImageBitmap(), contentDescription = null)
                else Icon(Icons.Default.Movie, contentDescription = null, tint = Color.LightGray)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(clip.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatPrecise(clip.trimmedDurationMs)} / ${formatPrecise(clip.durationMs)}" +
                        if (clip.hasSpeedChange) " · speed" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUp, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onDown, enabled = index < total - 1) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
        // Thin bar: how much of the clip is kept after the cut (shrinks as you trim).
        val frac = if (clip.durationMs > 0) clip.trimmedDurationMs.toFloat() / clip.durationMs else 1f
        LinearProgressIndicator(
            progress = { frac.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 6.dp),
        )
    }
}

@Composable
private fun ResizePanel(
    clip: Clip,
    onAspect: (AspectOption) -> Unit,
    onZoom: (Float) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Resize", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a shape · pinch to zoom · drag to pan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClose) { Text("Done") }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AspectOption.entries.forEach { opt ->
                FilterChip(
                    selected = clip.aspect == opt,
                    onClick = { onAspect(opt) },
                    label = { Text(opt.label) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Zoom: ${String.format("%.1fx", clip.zoom)}", style = MaterialTheme.typography.labelSmall)
        Slider(value = clip.zoom, onValueChange = onZoom, valueRange = 1f..4f)

        TextButton(onClick = onReset, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("Reset")
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

@Composable
private fun SpeedCurvePanel(
    clip: Clip,
    onCurve: (List<SpeedPoint>) -> Unit,
    onMaintainPitch: (Boolean) -> Unit,
    onFrameBlend: (Boolean) -> Unit,
    onFrameBlendAmount: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var points by remember(clip.id) { mutableStateOf(clip.speedCurve) }
    fun update(newPoints: List<SpeedPoint>) {
        points = newPoints
        onCurve(newPoints)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speed curve", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Output ≈ ${formatDuration(estimatedOutputMs(points, clip.trimmedDurationMs))} · drag dots, tap to add, double-tap to remove",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClose) { Text("Done") }
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(190.dp)) {
            SpeedCurveEditor(
                points = points,
                onLiveChange = { points = it },
                onCommit = { onCurve(points) },
                modifier = Modifier.fillMaxSize(),
            )
            Text("10x", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopStart))
            Text("1x", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterStart))
            Text("0.1x", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomStart))
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep audio pitch (no chipmunk/deep voice)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = clip.maintainPitch, onCheckedChange = onMaintainPitch)
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Frame blend (experimental)", style = MaterialTheme.typography.bodyMedium)
                Text("Softens slow-mo stutter; may ghost on fast motion", style = MaterialTheme.typography.labelSmall)
            }
            Switch(checked = clip.frameBlend, onCheckedChange = onFrameBlend)
        }
        if (clip.frameBlend) {
            Text(
                "Blend strength: ${(clip.frameBlendAmount * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = clip.frameBlendAmount,
                onValueChange = onFrameBlendAmount,
                valueRange = 0.1f..0.9f,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { update(DEFAULT_SPEED_CURVE) }) {
                Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Reset")
            }
            TextButton(onClick = { update(SLOWMO_PRESET) }) {
                Icon(Icons.Default.SlowMotionVideo, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Slow-mo")
            }
        }
    }
}

@Composable
private fun SpeedCurveEditor(
    points: List<SpeedPoint>,
    onLiveChange: (List<SpeedPoint>) -> Unit,
    onCommit: (List<SpeedPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live snapshot so the gesture handlers (started once) always see current data.
    val current by rememberUpdatedState(points)
    var activeIndex by remember { mutableStateOf(-1) }
    val grid = Color(0xFF444444)
    val hitPx = 80f

    Canvas(
        modifier
            .background(Color(0xFF1C1C1C))
            // Stable key (Unit): the detector is NOT torn down when points change,
            // so a drag runs continuously instead of flicking once per move.
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        activeIndex = nearestIndex(current, pos, w, h, hitPx)
                    },
                    onDragEnd = { activeIndex = -1; onCommit(current) },
                    onDragCancel = { activeIndex = -1; onCommit(current) },
                    onDrag = { change, _ ->
                        change.consume()
                        val i = activeIndex
                        if (i in current.indices) {
                            onLiveChange(movePoint(current, i, change.position, size.width.toFloat(), size.height.toFloat()))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val np = addPoint(current, pos, size.width.toFloat(), size.height.toFloat(), hitPx)
                        if (np !== current) { onLiveChange(np); onCommit(np) }
                    },
                    onDoubleTap = { pos ->
                        val np = removePoint(current, pos, size.width.toFloat(), size.height.toFloat(), hitPx)
                        if (np !== current) { onLiveChange(np); onCommit(np) }
                    },
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
