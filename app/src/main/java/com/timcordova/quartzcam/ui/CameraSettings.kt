package com.timcordova.quartzcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

/** The camera's Wi-Fi name and password. Blank password = keep the current one. */
@Composable
fun CameraWifiControls(currentName: String?, onChange: (String?, String?) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val nameChanged = name.isNotBlank() && name != currentName
    val nameOk = name.length in 1..32
    val passwordOk = password.isEmpty() || password.length in 8..63
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Anyone who joins the camera's Wi-Fi can see and download your footage, so use a strong password.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(32) },
            label = { Text("Wi-Fi name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(63) },
            label = { Text("New password (leave empty to keep it)") },
            singleLine = true,
            isError = !passwordOk,
            supportingText = { if (!passwordOk) Text("At least 8 characters") },
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { show = !show }) {
                    Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (show) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onChange(name.takeIf { nameChanged }, password.ifEmpty { null }) },
            enabled = nameOk && passwordOk && (nameChanged || password.isNotEmpty()),
        ) { Text("Change Wi-Fi") }
    }
}

@Composable
fun ChangeWifiDialog(ssid: String?, password: String?, currentName: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val joinName = ssid ?: currentName ?: "the camera's Wi-Fi"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change the camera's Wi-Fi?") },
        text = {
            Text(
                "The camera restarts its Wi-Fi and your phone drops off it. Then join \"$joinName\" from your phone's Wi-Fi " +
                    "settings" + (if (password != null) " with the new password, so make sure you have it written down." else ".") +
                    " Recording pauses for a moment and starts again once you're back.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FormatCardDialog(clips: Int, sos: Int, photos: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Erase the camera's card?") },
        text = {
            Text(
                "This permanently deletes everything on the card: $clips clips, $sos SOS clips and $photos photos. " +
                    "Save anything you want to keep to your phone first. It can't be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Erase card", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
