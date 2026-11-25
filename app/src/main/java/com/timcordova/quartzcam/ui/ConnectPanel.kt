package com.timcordova.quartzcam.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.timcordova.quartzcam.cam.CamConnection

/** Shown until the dash cam answers on its Wi-Fi. */
@Composable
fun ConnectPanel(status: CamConnection.Status, padding: PaddingValues) {
    val context = LocalContext.current
    val searching = status == CamConnection.Status.SEARCHING
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        QuartzCard(Modifier.padding(20.dp).widthIn(max = 440.dp)) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            ) {
                Icon(
                    if (searching) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    if (searching) "Looking for your dash cam…" else "Dash cam not found",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Turn on the camera's Wi-Fi and join it from your phone's Wi-Fi settings. " +
                        "If Android says the network has no internet, choose to stay connected. " +
                        "Your other apps keep using mobile data.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { context.startActivity(Intent(Settings.Panel.ACTION_WIFI)) }) { Text("Wi-Fi settings") }
                    OutlinedButton(onClick = { CamConnection.retry() }, enabled = !searching) { Text("Try again") }
                }
                if (searching) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }
        }
    }
}
