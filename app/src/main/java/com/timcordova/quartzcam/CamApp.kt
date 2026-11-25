package com.timcordova.quartzcam

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.download.Downloads
import com.timcordova.quartzcam.settings.AppSettings

class CamApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Don't reuse connections to the camera: its web server closes them without warning, and a reused dead
        // connection fails with "unexpected end of stream"
        System.setProperty("http.keepAlive", "false")
        AppSettings.init(this)
        Downloads.init(this)
        CamConnection.start(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // Thumbnails are kept on the phone, so scrolling back through the card doesn't ask the camera again
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbs"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(150)
            .build()
}
