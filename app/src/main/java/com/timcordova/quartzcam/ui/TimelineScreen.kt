package com.timcordova.quartzcam.ui

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.timcordova.quartzcam.LibraryState
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamFile
import com.timcordova.quartzcam.cam.Clip
import com.timcordova.quartzcam.cam.Drive
import com.timcordova.quartzcam.quartz.quartzHeadingColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** How much time the fine strip shows across its width. */
private const val ZOOM_MS = 30 * 60 * 1000L

/** Width of one minute of video in a drive's strip of thumbnails. */
private val MINUTE = 38.dp

/**
 * A day at a glance. The top stays put: a preview of the video at the cursor, the whole day as a bar, and a zoomed
 * strip for fine scrubbing. Drag either to scrub; tap the preview to open the player right there. The drives scroll
 * underneath and follow the cursor.
 */
@Composable
fun TimelineScreen(
    state: LibraryState,
    listState: LazyListState,
    padding: PaddingValues,
    onRefresh: () -> Unit,
    onPlay: (Drive, Long) -> Unit,
    onSave: (List<CamFile>) -> Unit,
) {
    val lib = state.library
    var dayText by rememberSaveable { mutableStateOf<String?>(null) }
    val day = dayText?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { it in lib.days } ?: lib.days.firstOrNull()
    val drives = remember(lib, day) { if (day == null) emptyList() else lib.drivesOn(day).sortedByDescending { it.startMs } }
    val sos = remember(lib, day) { if (day == null) emptyList() else lib.sosOn(day) }
    val dayClips = remember(drives) { drives.flatMap { it.clips }.sortedBy { it.startMs } }
    val dayStart = remember(day) { (day ?: LocalDate.now()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    // The cursor starts at the day's latest drive
    var cursor by rememberSaveable(day) { mutableLongStateOf(drives.firstOrNull()?.startMs ?: (dayStart + DAY_MS / 2)) }
    var confirm by remember { mutableStateOf<Drive?>(null) }

    val activeDrive = drives.firstOrNull { cursor in it.startMs..it.endMs }
    fun setCursor(t: Long) {
        cursor = t.coerceIn(dayStart, dayStart + DAY_MS - 1)
    }

    // Keep the card for the drive under the cursor in view
    LaunchedEffect(activeDrive?.id) {
        val i = drives.indexOfFirst { it.id == activeDrive?.id }
        if (i >= 0) listState.animateScrollToItem(i)
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        val wide = maxWidth > 720.dp
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(lib.days) { d ->
                            FilterChip(selected = d == day, onClick = { dayText = d.toString() }, label = { Text(shortDay(d)) })
                        }
                    }
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                when {
                    day != null -> ScrubPanel(
                        day = day,
                        dayStart = dayStart,
                        drives = drives,
                        sos = sos,
                        clips = dayClips,
                        cursor = cursor,
                        onCursor = ::setCursor,
                        onOpen = { activeDrive?.let { onPlay(it, cursor) } },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    state.loaded -> Text("Nothing recorded on the card yet.", modifier = Modifier.padding(16.dp))
                    state.loading -> Text("Reading the camera's card…", modifier = Modifier.padding(16.dp))
                }
            }
        }
        val list: @Composable (Modifier) -> Unit = { m ->
            DriveList(
                drives = drives,
                sos = sos,
                activeId = activeDrive?.id,
                listState = listState,
                bottom = padding.calculateBottomPadding(),
                onScrub = ::setCursor,
                onPlay = onPlay,
                onSaveDrive = { confirm = it },
                onSaveClip = { onSave(listOf(it.file)) },
                modifier = m,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                panel(Modifier.weight(0.55f).padding(bottom = padding.calculateBottomPadding()))
                list(Modifier.weight(0.45f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                panel(Modifier.fillMaxWidth())
                list(Modifier.weight(1f))
            }
        }
    }

    confirm?.let { drive ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Save this drive?") },
            text = {
                Text(
                    "${drive.clips.size} clips, ${sizeKb(drive.sizeKb)}. Over the camera's Wi-Fi that takes a while; " +
                        "it keeps going if you leave the app.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onSave(drive.clips.map { it.file })
                    confirm = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ the pinned panel

@Composable
private fun ScrubPanel(
    day: LocalDate,
    dayStart: Long,
    drives: List<Drive>,
    sos: List<Clip>,
    clips: List<Clip>,
    cursor: Long,
    onCursor: (Long) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val inDrive = drives.any { cursor in it.startMs..it.endMs }
    QuartzCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Preview(clips, cursor, inDrive, onOpen)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(dayTitle(day), fontWeight = FontWeight.Bold, color = quartzHeadingColor(), modifier = Modifier.weight(1f))
                Text(clockSeconds(cursor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            DayBar(dayStart, drives, sos, cursor, onCursor)
            Spacer(Modifier.height(10.dp))
            FineStrip(drives, sos, cursor, onCursor)
            Spacer(Modifier.height(4.dp))
            Text(
                "Drag the bars to scrub · tap the picture to play from there",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The frame at the cursor. The clip's thumbnail shows straight away; the actual frame follows as soon as the
 * camera sends it (a paused player that seeks to the nearest keyframe, at most a few times a second).
 */
@OptIn(UnstableApi::class)
@Composable
private fun Preview(clips: List<Clip>, cursor: Long, inDrive: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val clipIndex = clips.indexOfLast { it.startMs <= cursor }.takeIf { it >= 0 && cursor <= clips[it].endMs }
    val clip = clipIndex?.let { clips[it] }
    var frameFor by remember { mutableStateOf<String?>(null) }

    val player = remember(clips) {
        ExoPlayer.Builder(context)
            // Only fetch a moment of video around the cursor
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1000, 2000, 250, 500).build())
            .build()
            .apply {
                volume = 0f
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                playWhenReady = false
                if (clips.isNotEmpty()) {
                    setMediaItems(clips.map { MediaItem.fromUri(CamApi.fileUrl(it.file)) })
                    prepare()
                }
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // A paused player shows the frame it seeked to once it's ready
                if (state == Player.STATE_READY) frameFor = clips.getOrNull(player.currentMediaItemIndex)?.file?.path
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val latest by rememberUpdatedState(cursor)
    LaunchedEffect(player) {
        snapshotFlow { latest }.conflate().collect { t ->
            val i = clips.indexOfLast { it.startMs <= t }
            if (i >= 0 && t <= clips[i].endMs) {
                if (i != player.currentMediaItemIndex) frameFor = null
                player.seekTo(i, (t - clips[i].startMs).coerceAtLeast(0))
            }
            delay(250)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .clickable(enabled = inDrive, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (clip != null) ClipThumb(clip.file, Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            // Shown once it has the frame for this clip; until then the thumbnail shows through
            modifier = Modifier.fillMaxSize().alpha(if (clip != null && frameFor == clip.file.path) 1f else 0f),
        )
        if (clip == null) {
            Text("No recording at this time", color = Color.White.copy(alpha = 0.8f))
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Play from here", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The whole day, midnight to midnight: drives as blocks, SOS clips as red marks, the cursor as a line. */
@Composable
private fun DayBar(dayStart: Long, drives: List<Drive>, sos: List<Clip>, cursor: Long, onCursor: (Long) -> Unit) {
    val driveColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val tick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val ink = MaterialTheme.colorScheme.onSurface
    val move by rememberUpdatedState(onCursor)
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(dayStart) {
                    detectTapGestures { p -> move(dayStart + (p.x / size.width * DAY_MS).toLong()) }
                }
                .pointerInput(dayStart) {
                    detectDragGestures { change, _ ->
                        move(dayStart + (change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * DAY_MS).toLong())
                    }
                },
        ) {
            drawRoundRect(track, cornerRadius = CornerRadius(10.dp.toPx()))
            for (h in 1 until 24) {
                val x = size.width * h / 24f
                val len = if (h % 6 == 0) size.height else size.height * 0.25f
                drawLine(tick, Offset(x, size.height - len), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            for (d in drives) {
                val x0 = ((d.startMs - dayStart).toFloat() / DAY_MS * size.width).coerceIn(0f, size.width)
                val x1 = ((d.endMs - dayStart).toFloat() / DAY_MS * size.width).coerceIn(0f, size.width)
                drawRoundRect(
                    driveColor,
                    topLeft = Offset(x0, 6.dp.toPx()),
                    size = Size((x1 - x0).coerceAtLeast(3.dp.toPx()), size.height - 12.dp.toPx()),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            for (s in sos) {
                val x = ((s.startMs - dayStart).toFloat() / DAY_MS * size.width).coerceIn(0f, size.width)
                drawLine(SosRed, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx())
            }
            val cx = ((cursor - dayStart).toFloat() / DAY_MS * size.width).coerceIn(0f, size.width)
            drawLine(ink, Offset(cx, -2.dp.toPx()), Offset(cx, size.height + 2.dp.toPx()), strokeWidth = 2.5.dp.toPx())
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM").forEach {
                Text(it, fontSize = 10.sp, color = ink.copy(alpha = 0.65f))
            }
        }
    }
}

/**
 * A zoomed-in 30 minutes around the cursor, which stays in the middle. Drag it like a jog wheel to move a few
 * seconds at a time; tap to jump to that spot.
 */
@Composable
private fun FineStrip(drives: List<Drive>, sos: List<Clip>, cursor: Long, onCursor: (Long) -> Unit) {
    val measurer = rememberTextMeasurer()
    val driveColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val ink = MaterialTheme.colorScheme.onSurface
    val latest by rememberUpdatedState(cursor)
    val move by rememberUpdatedState(onCursor)
    val labelStyle = TextStyle(fontSize = 10.sp, color = ink.copy(alpha = 0.7f))
    val minuteFmt = remember { DateTimeFormatter.ofPattern("h:mm") }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dx ->
                    move(latest - (dx / size.width * ZOOM_MS).toLong())
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { p -> move(latest + ((p.x / size.width - 0.5f) * ZOOM_MS).toLong()) }
            },
    ) {
        val barTop = 0f
        val barH = size.height - 16.dp.toPx()
        val from = cursor - ZOOM_MS / 2
        fun x(t: Long) = (t - from).toFloat() / ZOOM_MS * size.width
        drawRoundRect(track, topLeft = Offset(0f, barTop), size = Size(size.width, barH), cornerRadius = CornerRadius(10.dp.toPx()))
        for (d in drives) {
            if (d.endMs < from || d.startMs > from + ZOOM_MS) continue
            // Each clip as its own block, so the 3-minute files show
            for (c in d.clips) {
                val x0 = x(c.startMs).coerceIn(0f, size.width)
                val x1 = x(c.endMs).coerceIn(0f, size.width)
                if (x1 <= x0) continue
                drawRoundRect(
                    driveColor.copy(alpha = 0.85f),
                    topLeft = Offset(x0 + 1f, barTop + 5.dp.toPx()),
                    size = Size((x1 - x0 - 2f).coerceAtLeast(1f), barH - 10.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        for (s in sos) {
            val sx = x(s.startMs)
            if (sx in 0f..size.width) drawLine(SosRed, Offset(sx, barTop), Offset(sx, barTop + barH), strokeWidth = 3.dp.toPx())
        }
        // A tick every minute, a time every 5
        val firstMinute = (from / 60_000 + 1) * 60_000
        var t = firstMinute
        while (t < from + ZOOM_MS) {
            val tx = x(t)
            val five = (t / 60_000) % 5 == 0L
            drawLine(
                ink.copy(alpha = if (five) 0.5f else 0.22f),
                Offset(tx, barTop + barH - (if (five) 12.dp else 6.dp).toPx()),
                Offset(tx, barTop + barH),
                strokeWidth = 1.dp.toPx(),
            )
            if (five) {
                val label = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(minuteFmt)
                val layout = measurer.measure(label, labelStyle)
                val lx = (tx - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(lx, barTop + barH + 2.dp.toPx()))
            }
            t += 60_000
        }
        // The cursor, fixed in the middle
        val cx = size.width / 2f
        drawLine(ink, Offset(cx, barTop - 2.dp.toPx()), Offset(cx, barTop + barH + 2.dp.toPx()), strokeWidth = 2.5.dp.toPx())
    }
}

// ------------------------------------------------------------------ the drives below

@Composable
private fun DriveList(
    drives: List<Drive>,
    sos: List<Clip>,
    activeId: String?,
    listState: LazyListState,
    bottom: androidx.compose.ui.unit.Dp,
    onScrub: (Long) -> Unit,
    onPlay: (Drive, Long) -> Unit,
    onSaveDrive: (Drive) -> Unit,
    onSaveClip: (Clip) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = bottom + 16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(drives, key = { it.id }) { drive ->
            DriveCard(
                drive = drive,
                sos = sos.filter { it.startMs in drive.startMs..drive.endMs },
                active = drive.id == activeId,
                onScrub = onScrub,
                onPlay = { onPlay(drive, drive.startMs) },
                onSave = { onSaveDrive(drive) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // SOS clips recorded outside any drive (parked, for example)
        val loose = sos.filter { s -> drives.none { s.startMs in it.startMs..it.endMs } }
        if (loose.isNotEmpty()) {
            item(key = "loose") {
                Text(
                    "Other SOS clips",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(loose, key = { "sos-" + it.file.path }) { clip ->
                val single = Drive(listOf(clip))
                DriveCard(
                    drive = single,
                    sos = listOf(clip),
                    active = false,
                    onScrub = { onPlay(single, it) },
                    onPlay = { onPlay(single, clip.startMs) },
                    onSave = { onSaveClip(clip) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** One drive: times, size, and its clips as a scrollable strip of thumbnails sized by length. */
@Composable
private fun DriveCard(
    drive: Drive,
    sos: List<Clip>,
    active: Boolean,
    onScrub: (Long) -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    QuartzCard(
        modifier
            .fillMaxWidth()
            .then(if (active) Modifier.border(2.5.dp, accent, RoundedCornerShape(24.dp)) else Modifier),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${clock(drive.startMs)} – ${clock(drive.endMs)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    val clips = if (drive.clips.size == 1) "1 clip" else "${drive.clips.size} clips"
                    Text("${longDuration(drive.durationMs)} · $clips · ${sizeKb(drive.sizeKb)}", style = MaterialTheme.typography.bodySmall)
                }
                if (sos.isNotEmpty()) Tag(if (sos.size == 1) "SOS" else "${sos.size} SOS", SosRed)
            }
            Spacer(Modifier.height(10.dp))
            DriveStrip(drive, sos, onScrub)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onSave) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (drive.clips.size == 1) "Save clip" else "Save drive")
                }
            }
        }
    }
}

/** The drive's clips side by side; tap one to move the cursor (and the preview) there. */
@Composable
private fun DriveStrip(drive: Drive, sos: List<Clip>, onScrub: (Long) -> Unit) {
    val stripHeight = 60.dp
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val scrub by rememberUpdatedState(onScrub)
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            drive.clips.forEach { clip ->
                val w = MINUTE * (clip.durationMs / 60_000f).coerceAtLeast(0.6f)
                Column(Modifier.width(w)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(stripHeight)
                            .clip(RoundedCornerShape(10.dp))
                            .pointerInput(clip.file.path) {
                                detectTapGestures { p ->
                                    scrub(clip.startMs + (p.x / size.width * clip.durationMs).toLong())
                                }
                            },
                    ) {
                        ClipThumb(clip.file, Modifier.fillMaxSize())
                        sos.filter { it.startMs in clip.startMs until clip.endMs }.forEach { s ->
                            val frac = (s.startMs - clip.startMs).toFloat() / clip.durationMs
                            Box(
                                Modifier
                                    .offset(x = w * frac)
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(SosRed),
                            )
                        }
                    }
                    Text(clock(clip.startMs), fontSize = 11.sp, color = labelColor, maxLines = 1, modifier = Modifier.padding(top = 3.dp, start = 2.dp))
                }
            }
        }
    }
}
