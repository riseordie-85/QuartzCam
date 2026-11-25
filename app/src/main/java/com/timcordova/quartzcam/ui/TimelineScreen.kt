package com.timcordova.quartzcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.timcordova.quartzcam.LibraryState
import com.timcordova.quartzcam.cam.CamFile
import com.timcordova.quartzcam.cam.Clip
import com.timcordova.quartzcam.cam.Drive
import com.timcordova.quartzcam.quartz.quartzHeadingColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** Width of one minute of video in a drive's strip of thumbnails. */
private val MINUTE = 38.dp

/**
 * A day at a glance: a 24-hour bar with each drive and SOS clip on it, then each drive as a strip of thumbnails
 * you can tap anywhere to start playing from that moment.
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
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Drive?>(null) }
    // Items before the first drive card: day chips, the day bar
    val headerItems = 2

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "days") {
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
        }
        item(key = "bar") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (day != null) {
                    Text(
                        dayTitle(day),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = quartzHeadingColor(),
                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                    )
                    QuartzCard(Modifier.fillMaxWidth()) {
                        DayBar(
                            day = day,
                            drives = drives,
                            sos = sos,
                            modifier = Modifier.padding(14.dp),
                            onTap = { time ->
                                val hit = drives.firstOrNull { time in it.startMs..it.endMs }
                                if (hit != null) {
                                    onPlay(hit, time)
                                } else if (drives.isNotEmpty()) {
                                    val nearest = drives.indices.minBy { i ->
                                        val d = drives[i]
                                        minOf(kotlin.math.abs(d.startMs - time), kotlin.math.abs(d.endMs - time))
                                    }
                                    scope.launch { listState.animateScrollToItem(headerItems + nearest) }
                                }
                            },
                        )
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
                if (state.loaded && lib.days.isEmpty()) Text("Nothing recorded on the card yet.", modifier = Modifier.padding(8.dp))
                if (!state.loaded && state.loading) Text("Reading the camera's card…", modifier = Modifier.padding(8.dp))
            }
        }
        items(drives, key = { it.id }) { drive ->
            DriveCard(
                drive = drive,
                sos = sos.filter { it.startMs in drive.startMs..drive.endMs },
                onPlay = { at -> onPlay(drive, at) },
                onSave = { confirm = drive },
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
                DriveCard(
                    drive = Drive(listOf(clip)),
                    sos = listOf(clip),
                    onPlay = { at -> onPlay(Drive(listOf(clip)), at) },
                    onSave = { onSave(listOf(clip.file)) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
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

/** The whole day, midnight to midnight: drives as blocks, SOS clips as red marks. Tap to jump in. */
@Composable
private fun DayBar(day: LocalDate, drives: List<Drive>, sos: List<Clip>, modifier: Modifier, onTap: (Long) -> Unit) {
    val dayStart = remember(day) { day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val driveColor = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val tick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(dayStart) {
                    detectTapGestures { p -> onTap(dayStart + (p.x / size.width * DAY_MS).toLong()) }
                },
        ) {
            val r = CornerRadius(10.dp.toPx())
            drawRoundRect(track, cornerRadius = r)
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
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM").forEach {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
    }
}

/** One drive: times, size, and its clips as a scrollable strip of thumbnails sized by length. */
@Composable
private fun DriveCard(drive: Drive, sos: List<Clip>, onPlay: (Long) -> Unit, onSave: () -> Unit, modifier: Modifier) {
    QuartzCard(modifier.fillMaxWidth()) {
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
            DriveStrip(drive, sos, onPlay)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { onPlay(drive.startMs) }) {
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

@Composable
private fun DriveStrip(drive: Drive, sos: List<Clip>, onPlay: (Long) -> Unit) {
    val stripHeight = 64.dp
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
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
                                    onPlay(clip.startMs + (p.x / size.width * clip.durationMs).toLong())
                                }
                            },
                    ) {
                        ClipThumb(clip.file, Modifier.fillMaxSize())
                        // SOS moments inside this clip
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
