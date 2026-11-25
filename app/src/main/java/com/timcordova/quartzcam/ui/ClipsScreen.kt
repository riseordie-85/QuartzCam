package com.timcordova.quartzcam.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.timcordova.quartzcam.cam.CamApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timcordova.quartzcam.LibraryState
import com.timcordova.quartzcam.cam.CamFile
import com.timcordova.quartzcam.cam.Clip
import com.timcordova.quartzcam.cam.Folder
import com.timcordova.quartzcam.cam.toDate
import com.timcordova.quartzcam.download.DownloadJob
import com.timcordova.quartzcam.download.Downloads
import com.timcordova.quartzcam.download.JobState
import com.timcordova.quartzcam.quartz.quartzHeadingColor

enum class ClipFilter(val label: String) { ALL("All"), LOOP("Driving"), SOS("SOS"), PHOTOS("Photos") }

/** Every clip on the card, newest first, grouped by day. Long-press to select several. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipsScreen(
    state: LibraryState,
    listState: LazyListState,
    padding: PaddingValues,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    onOpen: (Clip) -> Unit,
    onRefresh: () -> Unit,
    onSave: (List<CamFile>) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ClipFilter.ALL) }
    val jobs by Downloads.jobs.collectAsState()
    val saved by Downloads.saved.collectAsState()
    val lib = state.library
    // Photos ride along as zero-length clips, so they list, select and save like the rest
    val photoClips = remember(lib) { lib.photos.map { Clip(it, 0) } }
    val clips = remember(lib, filter) {
        when (filter) {
            ClipFilter.ALL -> lib.all + photoClips
            ClipFilter.LOOP -> lib.loop
            ClipFilter.SOS -> lib.sos
            ClipFilter.PHOTOS -> photoClips
        }.sortedByDescending { it.startMs }
    }
    var viewing by remember { mutableStateOf<CamFile?>(null) }
    val byDay = remember(clips) { clips.groupBy { it.startMs.toDate() } }
    val jobFor = remember(jobs) { jobs.associateBy { it.file.path } }

    fun toggle(c: Clip) {
        val p = c.file.path
        onSelect(if (p in selected) selected - p else selected + p)
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                lib.sd?.let { sd ->
                    Text(
                        "Card: ${sizeMb(sd.usedMb)} used of ${sizeMb(sd.totalMb)} · ${lib.loop.size} clips · ${lib.sos.size} SOS",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (sd.totalMb > 0) sd.usedMb.toFloat() / sd.totalMb else 0f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(ClipFilter.entries.toList()) { f ->
                            FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                        }
                    }
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (state.loaded && clips.isEmpty()) {
            item(key = "empty") {
                Text(
                    when (filter) {
                        ClipFilter.SOS -> "No SOS clips on the card."
                        ClipFilter.PHOTOS -> "No photos on the card. Take one from the Live tab."
                        else -> "No clips on the card."
                    },
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        byDay.forEach { (day, dayClips) ->
            item(key = "day-$day") {
                Text(
                    dayTitle(day),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = quartzHeadingColor(),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                )
            }
            items(dayClips, key = { it.file.path }) { clip ->
                ClipRow(
                    clip = clip,
                    job = jobFor[clip.file.path],
                    saved = clip.file.name in saved,
                    selected = clip.file.path in selected,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    onClick = {
                        when {
                            selected.isNotEmpty() -> toggle(clip)
                            clip.file.isPhoto -> viewing = clip.file
                            else -> onOpen(clip)
                        }
                    },
                    onLongClick = { toggle(clip) },
                    onSave = { onSave(listOf(clip.file)) },
                    onCancel = { Downloads.cancel(clip.file.path) },
                )
            }
        }
    }
    viewing?.let { photo ->
        PhotoViewer(photo, onSave = { onSave(listOf(photo)) }, onDismiss = { viewing = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(
    clip: Clip,
    job: DownloadJob?,
    saved: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    QuartzCard(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.5.dp, accent, RoundedCornerShape(24.dp)) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(8.dp),
        ) {
            Box {
                ClipThumb(clip.file, Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(16.dp)))
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = accent,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.White, RoundedCornerShape(50)),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(clock(clip.startMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (clip.file.isPhoto) "Photo · ${sizeKb(clip.file.sizeKb)}" else "${duration(clip.durationMs)} · ${sizeKb(clip.file.sizeKb)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    if (clip.file.folder != Folder.LOOP && !clip.file.isPhoto) Tag(clip.file.folder.label, SosRed)
                    if (clip.file.lens == "R") Tag("Rear", Color(0xFF5B5BD6))
                    if (saved) Tag("Saved", SavedGreen)
                }
            }
            when {
                job != null && job.active -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(50)).clickable(onClick = onCancel),
                ) {
                    if (job.state == JobState.RUNNING) {
                        CircularProgressIndicator(progress = { job.progress }, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    } else {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                    }
                }
                saved -> IconButton(onClick = onSave) { Icon(Icons.Outlined.DownloadDone, contentDescription = "Saved — save again") }
                else -> IconButton(onClick = onSave) { Icon(Icons.Outlined.Download, contentDescription = "Save to phone") }
            }
        }
    }
}

/** A camera photo, full screen. */
@Composable
private fun PhotoViewer(photo: CamFile, onSave: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = CamApi.fileUrl(photo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            ) {
                FilledTonalButton(onClick = onSave) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save to phone")
                }
                FilledTonalButton(onClick = onDismiss) { Text("Close") }
            }
            Text(
                clockSeconds(photo.startMs),
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp),
            )
        }
    }
}
