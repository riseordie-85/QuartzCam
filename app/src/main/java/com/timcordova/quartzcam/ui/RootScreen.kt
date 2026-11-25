package com.timcordova.quartzcam.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timcordova.quartzcam.CamViewModel
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.cam.CamFile
import com.timcordova.quartzcam.cam.Clip
import com.timcordova.quartzcam.cam.Drive
import com.timcordova.quartzcam.download.Downloads
import com.timcordova.quartzcam.quartz.FitText
import com.timcordova.quartzcam.quartz.NavItem
import com.timcordova.quartzcam.quartz.QuartzNavBar
import com.timcordova.quartzcam.quartz.QuartzScaffold
import com.timcordova.quartzcam.quartz.QuartzTopBar

enum class Tab(val label: String) { CLIPS("Clips"), TIMELINE("Timeline"), LIVE("Live") }

/** Screens that open over the tabs. */
sealed interface Overlay {
    /** Plays [drive] (one clip or a whole drive, back to back) from [startMs]. */
    data class Player(val drive: Drive, val startMs: Long, val sos: List<Clip>) : Overlay
    data object Downloads : Overlay
    data object Settings : Overlay
}

/** Saves clips, asking once for permission to show download progress in a notification. */
@Composable
fun rememberSaver(): (List<CamFile>) -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        { files: List<CamFile> ->
            if (!hasNotePermission(context)) {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("asked_notes", false)) {
                    prefs.edit().putBoolean("asked_notes", true).apply()
                    ask.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            Downloads.enqueue(context, files)
        }
    }
}

private fun hasNotePermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
fun CamRoot(vm: CamViewModel = viewModel()) {
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    BackHandler(enabled = overlay != null) { overlay = null }
    MainBackground {
        when (val o = overlay) {
            is Overlay.Player -> PlayerScreen(o.drive, o.startMs, o.sos, onBack = { overlay = null })
            Overlay.Downloads -> DownloadsScreen(onBack = { overlay = null })
            Overlay.Settings -> SettingsScreen(vm, onBack = { overlay = null })
            null -> MainTabs(vm, open = { overlay = it })
        }
    }
}

@Composable
private fun MainTabs(vm: CamViewModel, open: (Overlay) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.TIMELINE) }
    val conn by CamConnection.state.collectAsState()
    val lib by vm.state.collectAsState()
    val camSettings by vm.settings.collectAsState()
    val jobs by Downloads.jobs.collectAsState()
    val activeDownloads = jobs.count { it.active }
    val clipsList = rememberLazyListState()
    val timelineList = rememberLazyListState()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val save = rememberSaver()
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    val connected = conn.status == CamConnection.Status.CONNECTED
    val selecting = tab == Tab.CLIPS && selected.isNotEmpty()

    fun play(clip: Clip) {
        val l = lib.library
        val drive = l.driveOf(clip) ?: Drive(listOf(clip))
        open(Overlay.Player(drive, clip.startMs, l.sos))
    }

    QuartzScaffold(
        scroll = {
            when (tab) {
                Tab.CLIPS -> clipsList.firstVisibleItemIndex to clipsList.firstVisibleItemScrollOffset
                Tab.TIMELINE -> timelineList.firstVisibleItemIndex to timelineList.firstVisibleItemScrollOffset
                Tab.LIVE -> null
            }
        },
        topBar = {
            QuartzTopBar(
                title = {
                    if (selecting) Text("${selected.size} selected") else FitText(if (tab == Tab.TIMELINE) "Quartz Cam" else tab.label)
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = {
                            val files = lib.library.all.filter { it.file.path in selected }.map { it.file }
                            save(files)
                            selected = emptySet()
                        }) { Icon(Icons.Outlined.Download, contentDescription = "Save to phone") }
                    } else {
                        IconButton(onClick = { open(Overlay.Downloads) }) {
                            BadgedBox(badge = { if (activeDownloads > 0) Badge { Text("$activeDownloads") } }) {
                                Icon(Icons.Outlined.DownloadForOffline, contentDescription = "Downloads")
                            }
                        }
                        IconButton(onClick = { open(Overlay.Settings) }) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            QuartzNavBar(
                listOf(
                    NavItem("Timeline", Icons.Outlined.Timeline, tab == Tab.TIMELINE) { tab = Tab.TIMELINE },
                    NavItem("Clips", Icons.Outlined.VideoLibrary, tab == Tab.CLIPS) { tab = Tab.CLIPS },
                    NavItem("Live", Icons.Outlined.Sensors, tab == Tab.LIVE) { tab = Tab.LIVE },
                ),
            )
        },
    ) { padding ->
      Box(Modifier.fillMaxSize()) {
        when {
            !connected -> ConnectPanel(conn.status, padding)
            tab == Tab.TIMELINE -> TimelineScreen(
                state = lib,
                listState = timelineList,
                padding = padding,
                onRefresh = vm::refresh,
                onPlay = { drive, at -> open(Overlay.Player(drive, at, lib.library.sos)) },
                onSave = save,
            )
            tab == Tab.CLIPS -> ClipsScreen(
                state = lib,
                listState = clipsList,
                padding = padding,
                selected = selected,
                onSelect = { selected = it },
                onOpen = ::play,
                onRefresh = vm::refresh,
                onSave = save,
            )
            else -> LiveScreen(padding, camSettings, onSet = vm::setSetting)
        }
        // Hard to miss: the camera isn't recording (turned off here, in the old app, or on the camera)
        if (connected && camSettings.recording == false) {
            NotRecordingBanner(
                onTurnOn = { vm.setSetting("rec", 1) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding() + 4.dp),
            )
        }
      }
    }
}
