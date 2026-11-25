package com.timcordova.quartzcam.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.Clip
import com.timcordova.quartzcam.cam.Drive
import kotlinx.coroutines.delay

/**
 * Plays a drive straight off the camera, clip after clip with no gap, as one long video with the real clock
 * time. Mark a start and end to save just the clips around a moment.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(drive: Drive, startMs: Long, sos: List<Clip>, onBack: () -> Unit) {
    val context = LocalContext.current
    val save = rememberSaver()
    SystemBarIcons(darkBackground = true)
    val player = remember(drive.id) {
        ExoPlayer.Builder(context).build().apply {
            val (index, offset) = drive.locate(startMs)
            setMediaItems(drive.clips.map { MediaItem.fromUri(CamApi.fileUrl(it.file)) }, index, offset)
            playWhenReady = true
            prepare()
        }
    }
    var now by remember { mutableStateOf(startMs) }
    var playing by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var markA by remember { mutableStateOf<Long?>(null) }
    var markB by remember { mutableStateOf<Long?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Couldn't play from the camera (${e.errorCodeName})"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // The clock time on screen follows the video
    LaunchedEffect(player) {
        while (true) {
            val i = player.currentMediaItemIndex
            drive.clips.getOrNull(i)?.let { now = it.startMs + player.currentPosition }
            delay(200)
        }
    }

    fun seek(timeMs: Long) {
        val (i, off) = drive.locate(timeMs)
        player.seekTo(i, off)
        now = drive.clips[i].startMs + off
    }

    val span = drive.durationMs.coerceAtLeast(1)
    val shown = dragging?.let { drive.startMs + (it * span).toLong() } ?: now
    val current = drive.clips.getOrNull(drive.locate(shown).first)

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(dayTitle(java.time.Instant.ofEpochMilli(drive.startMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()), color = Color.White, fontWeight = FontWeight.Bold)
                Text("${clock(drive.startMs)} – ${clock(drive.endMs)} · ${drive.clips.size} clips", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            if (current != null) {
                IconButton(onClick = { save(listOf(current.file)) }) {
                    Icon(Icons.Outlined.Download, contentDescription = "Save this clip", tint = Color.White)
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            error?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.background(Color.Black.copy(alpha = 0.7f)).padding(16.dp)) {
                    Text(it, color = Color.White)
                    TextButton(onClick = {
                        error = null
                        player.prepare()
                        player.play()
                    }) { Text("Try again") }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(clockSeconds(shown), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            RangeStrip(drive, sos, markA, markB)
            Slider(
                value = dragging ?: ((now - drive.startMs).toFloat() / span).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { seek(drive.startMs + (it * span).toLong()) }
                    dragging = null
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(drive.startMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Text(clock(drive.endMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = { seek(now - 10_000) }) { Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White) }
                Spacer(Modifier.width(16.dp))
                FilledIconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.size(60.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(16.dp))
                IconButton(onClick = { seek(now + 10_000) }) { Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White) }
            }
            if (drive.clips.size > 1) {
                val a = markA
                val b = markB
                val inRange = if (a != null && b != null) drive.clipsBetween(a, b) else emptyList()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = { markA = now }, label = { Text(if (a == null) "Mark start" else "Start ${clock(a)}") })
                    AssistChip(onClick = { markB = now }, label = { Text(if (b == null) "Mark end" else "End ${clock(b)}") })
                    if (inRange.isNotEmpty()) {
                        AssistChip(
                            onClick = {
                                save(inRange.map { it.file })
                                markA = null
                                markB = null
                            },
                            label = { Text("Save ${inRange.size} ${if (inRange.size == 1) "clip" else "clips"}") },
                            leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
    }
}

/** Above the slider: where each clip starts, the marked range, and SOS moments. */
@Composable
private fun RangeStrip(drive: Drive, sos: List<Clip>, markA: Long?, markB: Long?) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(10.dp)) {
        val span = drive.durationMs.coerceAtLeast(1).toFloat()
        fun x(t: Long) = ((t - drive.startMs) / span * size.width).coerceIn(0f, size.width)
        if (markA != null || markB != null) {
            val a = x(markA ?: markB!!)
            val b = x(markB ?: markA!!)
            drawRect(accent.copy(alpha = 0.6f), topLeft = Offset(minOf(a, b), 0f), size = Size((kotlin.math.abs(b - a)).coerceAtLeast(3f), size.height))
        }
        drive.clips.drop(1).forEach { c ->
            val cx = x(c.startMs)
            drawLine(Color.White.copy(alpha = 0.35f), Offset(cx, size.height * 0.3f), Offset(cx, size.height), strokeWidth = 1.5f)
        }
        sos.filter { it.startMs in drive.startMs..drive.endMs }.forEach { s ->
            val sx = x(s.startMs)
            drawLine(SosRed, Offset(sx, 0f), Offset(sx, size.height), strokeWidth = 3f)
        }
    }
}
