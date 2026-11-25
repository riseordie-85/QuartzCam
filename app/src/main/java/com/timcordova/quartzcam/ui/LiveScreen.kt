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
import kotlinx.coroutines.withContext

/** What the camera sees right now, over RTSP (the address the camera reports, then a couple of fallbacks). */
@OptIn(UnstableApi::class)
@Composable
fun LiveScreen(padding: PaddingValues) {
    val context = LocalContext.current
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }

    val player = remember {
        ExoPlayer.Builder(context)
            // Small buffers: live view should be as close to live as possible
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build())
            .build()
            .apply { volume = 0f }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                showing = false
                if (attempt + 1 < candidates.size) attempt++ else error = "No live picture (${e.errorCodeName})"
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
        val info = withContext(Dispatchers.IO) { CamApi.mediaInfo() }
        val reported = info?.rtsp?.takeIf { it.startsWith("rtsp://") }
        val list = buildList {
            if (reported != null) add(reported)
            // The camera also reports a port; some models serve the stream there instead
            if (info != null && info.port > 0) add("rtsp://${CamApi.host}:${info.port}/streamtype=0")
            add("rtsp://${CamApi.host}/streamtype=0")
        }.distinct()
        candidates = list
    }
    LaunchedEffect(candidates, attempt, reload) {
        val url = candidates.getOrNull(attempt) ?: return@LaunchedEffect
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
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
                        candidates.getOrNull(attempt)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        Spacer(Modifier.height(8.dp))
        Text(
            "Handy for lining up the camera's angle. The picture can lag a second or two behind.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 420.dp),
        )
    }
}
