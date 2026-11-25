package com.timcordova.quartzcam.ui

import androidx.annotation.OptIn
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import com.timcordova.quartzcam.CamSettingsState
import com.timcordova.quartzcam.download.Downloads
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.timcordova.quartzcam.cam.CamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the camera sees right now, over RTSP (the address the camera reports, then a couple of fallbacks). */
@OptIn(UnstableApi::class)
@Composable
fun LiveScreen(
    padding: PaddingValues,
    camSettings: CamSettingsState,
    onSet: (String, Int) -> Unit,
    onSnapshot: ((com.timcordova.quartzcam.cam.CamFile?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    /** Each address the camera might stream on, over TCP and then UDP. */
    var candidates by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var showing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    /** Times the picture froze and was reconnected, shown so a flaky stream isn't a mystery. */
    var stalls by remember { mutableIntStateOf(0) }
    /** What happened with each try, shown under the picture. */
    var log by remember { mutableStateOf<List<String>>(emptyList()) }

    fun label(c: Pair<String, Boolean>) = c.first + if (c.second) " (TCP)" else " (UDP)"

    val player = remember {
        ExoPlayer.Builder(context)
            // Small buffers: live view should be as close to live as possible
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build())
            .build()
            .apply {
                // Picture only. The player keeps video in step with sound, and the camera sends no sound while
                // its microphone is off, which froze the picture on its first frame.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                volume = 0f
            }
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
            // Leave live mode, like the stock app does
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { CamApi.exitRecorder() }
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
    // If the picture stops moving, reconnect (counts the frames shown every few seconds)
    LaunchedEffect(player, showing) {
        if (!showing) return@LaunchedEffect
        var last = -1
        var still = 0
        while (true) {
            delay(2000)
            val counters = player.videoDecoderCounters ?: continue
            counters.ensureUpdated()
            val shown = counters.renderedOutputBufferCount
            still = if (shown == last) still + 1 else 0
            last = shown
            if (still >= 2) {
                stalls++
                reload++
                return@LaunchedEffect
            }
        }
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
                    // A TextureView (not a SurfaceView) so the current frame can be saved as a photo
                    AndroidView(
                        factory = { ctx ->
                            TextureView(ctx).also { tv ->
                                tv.keepScreenOn = true
                                player.setVideoTextureView(tv)
                                texture = tv
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
                        if (stalls > 0) Text("Picture froze and reconnected $stalls ${if (stalls == 1) "time" else "times"}", style = MaterialTheme.typography.bodySmall)
                        if (!showing && error == null) candidates.getOrNull(attempt)?.let { Text("Trying " + label(it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Camera controls
        val recording = camSettings.recording
        val mic = camSettings.values["mic"]?.let { it != 0 }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { if (recording == true) confirmStop = true else onSet("rec", 1) },
                enabled = recording != null && "rec" !in camSettings.busy,
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (recording == true) SosRed else Color.Gray))
                Spacer(Modifier.width(8.dp))
                Text(if (recording == true) "Recording" else "Not recording")
            }
            FilledTonalButton(
                onClick = { if (mic != null) onSet("mic", if (mic) 0 else 1) },
                enabled = mic != null && "mic" !in camSettings.busy,
            ) {
                Icon(if (mic == false) Icons.Outlined.MicOff else Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (mic == false) "Sound off" else "Sound on")
            }
            FilledTonalButton(
                onClick = {
                    // A full-resolution photo from the camera itself, saved to the phone. If the camera can't, the
                    // live picture on screen is saved instead.
                    val frame = if (showing) texture?.bitmap else null
                    Downloads.toast(context, "Taking a photo…")
                    onSnapshot { photo ->
                        if (photo != null) {
                            Downloads.enqueue(context, listOf(photo))
                        } else if (frame != null) {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { savePhoto(context, frame) }
                                Downloads.toast(context, if (ok) "Saved the live picture to Pictures/${Downloads.FOLDER}" else "Couldn't save the photo")
                            }
                        } else {
                            Downloads.toast(context, "The camera couldn't take a photo")
                        }
                    }
                },
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Photo")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { reload++ }) { Text("Reconnect") }
        }
        Text(
            "Sound and recording change what the camera records.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).widthIn(max = 420.dp),
        )
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

    if (confirmStop) {
        StopRecordingDialog(
            onConfirm = {
                confirmStop = false
                onSet("rec", 0)
            },
            onDismiss = { confirmStop = false },
        )
    }
}

/** Saves a live frame to Pictures/Quartz Cam. */
private fun savePhoto(context: Context, frame: Bitmap): Boolean = runCatching {
    val name = "QuartzCam_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".jpg"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Downloads.FOLDER)
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: return@runCatching false
    resolver.openOutputStream(uri)!!.use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    true
}.getOrDefault(false)
