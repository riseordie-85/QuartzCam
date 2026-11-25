package com.timcordova.quartzcam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timcordova.quartzcam.CamViewModel
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.quartz.QuartzLook
import com.timcordova.quartzcam.quartz.QuartzScaffold
import com.timcordova.quartzcam.quartz.QuartzTopBar
import com.timcordova.quartzcam.quartz.WallConfig
import com.timcordova.quartzcam.quartz.WallKind
import com.timcordova.quartzcam.quartz.WallPresets
import com.timcordova.quartzcam.quartz.WallRenderer
import com.timcordova.quartzcam.settings.AppSettings
import com.timcordova.quartzcam.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: CamViewModel, onBack: () -> Unit) {
    val conn by CamConnection.state.collectAsState()
    val lib by vm.state.collectAsState()
    val theme by AppSettings.theme.collectAsState()
    val look by AppSettings.look.collectAsState()
    val host by AppSettings.host.collectAsState()
    val syncClock by AppSettings.syncClock.collectAsState()
    val camSettings by vm.settings.collectAsState()
    var confirmStop by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    QuartzScaffold(
        scroll = { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset },
        topBar = {
            QuartzTopBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Section("Dash cam") {
                    val connected = conn.status == CamConnection.Status.CONNECTED
                    Text(if (connected) "Connected" else "Not connected", fontWeight = FontWeight.Bold)
                    conn.info?.let { info ->
                        val name = listOf(info.company, info.model).filter { it.isNotBlank() && it != "na" }.joinToString(" ")
                        Text(name.ifBlank { "Unknown model" } + if (info.sp.isNotBlank()) " · ${info.sp}" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    lib.library.sd?.let { sd ->
                        Spacer(Modifier.height(10.dp))
                        Text("Card: ${sizeMb(sd.usedMb)} used, ${sizeMb(sd.freeMb)} free of ${sizeMb(sd.totalMb)}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (sd.totalMb > 0) sd.usedMb.toFloat() / sd.totalMb else 0f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Set the camera's clock", fontWeight = FontWeight.Bold)
                            Text("From your phone, each time it connects, so clip times are right", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = syncClock, onCheckedChange = { AppSettings.setSyncClock(it) })
                    }
                    Spacer(Modifier.height(14.dp))
                    var address by remember(host) { mutableStateOf(host) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Camera address") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                AppSettings.setHost(address)
                                CamConnection.retry()
                            },
                            enabled = address.trim() != host,
                        ) { Text("Save") }
                    }
                    if (host != CamApi.DEFAULT_HOST) {
                        TextButton(onClick = {
                            AppSettings.setHost(CamApi.DEFAULT_HOST)
                            CamConnection.retry()
                        }) { Text("Reset to ${CamApi.DEFAULT_HOST}") }
                    }
                }
            }
            if (conn.status == CamConnection.Status.CONNECTED) {
                item {
                    Section("Camera settings") {
                        CameraSettingsList(camSettings) { name, value ->
                            if (name == "rec" && value == 0) confirmStop = true else vm.setSetting(name, value)
                        }
                    }
                }
            }
            item {
                Section("Theme") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = theme == mode,
                                onClick = { AppSettings.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
            }
            item {
                Section("Background") {
                    val plain = WallConfig(kind = WallKind.THEME)
                    val options = listOf(WallPresets.Preset("Plain", plain)) + WallPresets.all
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(options, key = { it.name }) { p ->
                            Swatch(
                                name = p.name,
                                config = p.config,
                                selected = look.background == p.config,
                                onClick = { AppSettings.setLook(look.copy(background = p.config)) },
                            )
                        }
                    }
                }
            }
            item {
                Section("Top bar") {
                    BarChoices(look.topBar) { AppSettings.setLook(look.copy(topBar = it)) }
                }
            }
            item {
                Section("Tab bar") {
                    BarChoices(look.navBar) { AppSettings.setLook(look.copy(navBar = it)) }
                }
            }
            item {
                Section("Privacy") {
                    Text(
                        "Quartz Cam only talks to your dash cam, over the camera's own Wi-Fi. No ads, no accounts, " +
                            "no tracking, nothing sent anywhere else.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The camera itself has no login, so anyone on its Wi-Fi can see your clips. Give it a strong Wi-Fi password.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (confirmStop) {
        StopRecordingDialog(
            onConfirm = {
                confirmStop = false
                vm.setSetting("rec", 0)
            },
            onDismiss = { confirmStop = false },
        )
    }
}


@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    QuartzCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun BarChoices(current: com.timcordova.quartzcam.quartz.BarStyle, onPick: (com.timcordova.quartzcam.quartz.BarStyle) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(QuartzLook.BAR_PRESETS, key = { it.first }) { (name, style) ->
            FilterChip(selected = current == style, onClick = { onPick(style) }, label = { Text(name) })
        }
    }
}

/** A small picture of a background. */
@Composable
private fun Swatch(name: String, config: WallConfig, selected: Boolean, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, config) {
        if (!config.drawn) return@produceState
        value = withContext(Dispatchers.Default) { WallRenderer.render(360, 640, 0.25f, config).asImageBitmap() }
    }
    val shape = RoundedCornerShape(14.dp)
    val ring = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier
                .size(width = 64.dp, height = 112.dp)
                .clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) ring else MaterialTheme.colorScheme.outline, shape)
                .clickable(onClick = onClick),
        ) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}
