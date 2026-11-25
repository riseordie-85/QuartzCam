package com.timcordova.quartzcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timcordova.quartzcam.CamSettingsState
import com.timcordova.quartzcam.cam.CamSetting

/** Plain names for the camera's settings, in the order they're shown. Unknown ones go at the end, tidied up. */
private val SETTING_INFO: List<Triple<String, String, String?>> = listOf(
    Triple("rec", "Recording", "Off means the camera isn't recording while you drive"),
    Triple("rec_resolution", "Resolution", "Higher fills the card faster"),
    Triple("rec_split_duration", "Clip length", "How long each recorded file is"),
    Triple("mic", "Record sound", null),
    Triple("osd", "Date and time on the video", null),
    Triple("gsr_sensitivity", "Impact sensor", "Locks a clip as SOS when it feels a bump"),
    Triple("parking_monitor", "Parking monitor", "Watches for bumps while the car is parked"),
    Triple("park_record_time", "Parking recording time", null),
    Triple("timelapse_rate", "Time-lapse", "Records parked time as time-lapse"),
    Triple("screen_standby", "Screen turns off after", null),
    Triple("key_tone", "Button sounds", null),
)

fun settingTitle(name: String): String =
    SETTING_INFO.firstOrNull { it.first == name }?.second
        ?: name.split('_').joinToString(" ").replaceFirstChar { it.uppercase() }

/** "1MIN" → "1 min", "middle" → "Medium", "on" → "On", "8H" → "8 hr". */
fun choiceLabel(raw: String): String {
    Regex("""^(\d+)\s*MIN$""", RegexOption.IGNORE_CASE).find(raw)?.let { return "${it.groupValues[1]} min" }
    Regex("""^(\d+)\s*H$""", RegexOption.IGNORE_CASE).find(raw)?.let { return "${it.groupValues[1]} hr" }
    return when (raw.lowercase()) {
        "middle" -> "Medium"
        "1080p" -> "1080p"
        else -> if (raw.any { it.isLowerCase() }) raw.replaceFirstChar { it.uppercase() } else raw
    }
}

/** The camera's settings, each as a row of choices. Changes go to the camera straight away. */
@Composable
fun CameraSettingsList(state: CamSettingsState, onSet: (String, Int) -> Unit) {
    val order = SETTING_INFO.map { it.first }
    val items = state.items.sortedBy { order.indexOf(it.name).let { i -> if (i < 0) order.size else i } }
    if (items.isEmpty()) {
        Text(state.error ?: "Reading the camera's settings…", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { item -> SettingRow(item, state.values[item.name], item.name in state.busy, onSet) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SettingRow(item: CamSetting, value: Int?, busy: Boolean, onSet: (String, Int) -> Unit) {
    val info = SETTING_INFO.firstOrNull { it.first == item.name }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(settingTitle(item.name), fontWeight = FontWeight.Bold)
            if (busy) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }
        info?.third?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(item.values.indices.toList()) { i ->
                val v = item.values[i]
                FilterChip(
                    selected = value == v,
                    onClick = { if (value != v) onSet(item.name, v) },
                    label = { Text(choiceLabel(item.labels[i])) },
                )
            }
        }
    }
}

/** Asked before recording is turned off, since that's easy to forget about. */
@Composable
fun StopRecordingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop recording?") },
        text = { Text("The camera won't record anything, even while you drive, until recording is turned back on.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Stop recording") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep recording") } },
    )
}

/** A red reminder at the top of the screen while the camera isn't recording. */
@Composable
fun NotRecordingBanner(onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(SosRed)
            .clickable(onClick = onTurnOn)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Color.White))
        Spacer(Modifier.width(8.dp))
        Text("The camera isn't recording · Turn on", color = Color.White, fontWeight = FontWeight.Bold)
    }
}
