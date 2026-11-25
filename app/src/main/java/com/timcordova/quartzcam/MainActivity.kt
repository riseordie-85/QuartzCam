package com.timcordova.quartzcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.ui.CamRoot
import com.timcordova.quartzcam.ui.CamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent {
            CamTheme { CamRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Wi-Fi settings: look for the camera again
        if (CamConnection.state.value.status != CamConnection.Status.CONNECTED) CamConnection.retry()
    }
}
