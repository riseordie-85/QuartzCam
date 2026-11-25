package com.timcordova.quartzcam.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.timcordova.quartzcam.cam.CamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the camera sees right now, over RTSP (the address the camera reports, then a couple of fallbacks). */
@OptIn(UnstableApi::class)
@Composable
fun LiveScreen(padding: PaddingValues) {
    val context = LocalContext.current
    /** Each address the camera might stream on, over TCP and then UDP. */
    var candidates by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    /** What happened with each try, shown under the picture. */
    var log by remember { mutableStateOf<List<String>>(emptyList()) }

    fun label(c: Pair<String, Boolean>) = c.first + if (c.second) " (TCP)" else " (UDP)"

    val player = remember {
        ExoPlayer.Builder(context)
            // Small buffers: live view should be as close to live as possible
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build())
            .build()
            .apply { volume = 0f }
    }
    fun next(reason: String) {
        candidates.getOrNull(attempt)?.let { log = log + "${label(it)}: $reason" }
        showing = false
        if (attempt + 1 < candidates.size) attempt++ else error = "No live picture from the camera"
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                next(e.errorCodeName + (e.cause?.message?.let { " – $it" } ?: ""))
            }

            override fun onRenderedFirstFrame() {
                showing = true
                error = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(reload) {
        error = null
        showing = false
        attempt = 0
        log = emptyList()
        // The camera only sends its stream once it's switched to live mode (what the stock app does first)
        val entered = withContext(Dispatchers.IO) { CamApi.enterRecorder() }
        val info = withContext(Dispatchers.IO) { CamApi.mediaInfo() }
        val reported = info?.rtsp?.takeIf { it.startsWith("rtsp://") }
        val urls = buildList {
            // Exactly what the stock app opens; the reported address is the same thing without the port
            add("rtsp://${CamApi.host}:554/streamtype=0")
            if (reported != null) add(reported)
        }.distinct()
        log = buildList {
            if (!entered) add("The camera didn't switch to live mode (/app/enterrecorder)")
            if (info == null) add("The camera didn't answer /app/getmediainfo")
        }
        // TCP first: that's what the camera reports and what the stock app uses
        candidates = urls.map { it to true } + urls.map { it to false }
    }
    LaunchedEffect(candidates, attempt, reload) {
        val c = candidates.getOrNull(attempt) ?: return@LaunchedEffect
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(c.second)
            .setTimeoutMs(6000)
            .createMediaSource(MediaItem.fromUri(c.first))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        // Some cameras accept the connection and then send nothing: move on after a while
        delay(9000)
        if (!showing && error == null) {
            player.stop()
            next("no picture after 9 seconds")
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
            .padding(12.dp),
    ) {
        QuartzCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
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
                    if (!showing && error == null) CircularProgressIndicator(color = Color.White)
                    error?.let { Text(it, color = Color.White, modifier = Modifier.padding(16.dp)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (showing) "Live" else "Connecting…", fontWeight = FontWeight.Bold)
                        if (!showing && error == null) candidates.getOrNull(attempt)?.let { Text("Trying " + label(it), style = MaterialTheme.typography.bodySmall) }
                    }
                    IconButton(onClick = {
                        muted = !muted
                        player.volume = if (muted) 0f else 1f
                    }) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                            contentDescription = if (muted) "Sound on" else "Mute",
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { reload++ }) { Text("Reconnect") }
        }
        if (log.isNotEmpty() && !showing) {
            Spacer(Modifier.height(8.dp))
            QuartzCard(Modifier.fillMaxWidth().widthIn(max = 900.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("What was tried", fontWeight = FontWeight.Bold)
                    log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Handy for lining up the camera's angle. The picture can lag a second or two behind.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 420.dp),
        )
    }
}
