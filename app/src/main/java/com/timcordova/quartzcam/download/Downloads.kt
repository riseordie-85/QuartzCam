package com.timcordova.quartzcam.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.timcordova.quartzcam.MainActivity
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamConnection
import com.timcordova.quartzcam.cam.CamFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val file: CamFile,
    val state: JobState = JobState.QUEUED,
    val bytes: Long = 0,
    val total: Long = file.sizeBytes,
    val error: String? = null,
    val uri: Uri? = null,
) {
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = state == JobState.QUEUED || state == JobState.RUNNING
}

/**
 * Clips being saved from the camera to the phone, one at a time, into Movies/Quartz Cam.
 * A clip is about 1 GB, so each takes a few minutes over the camera's Wi-Fi.
 */
object Downloads {
    const val FOLDER = "Quartz Cam"

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    /** Names of clips already saved to the phone. */
    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Volatile
    private var cancelled: String? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        prefs = p
        _saved.value = p.getStringSet("saved", emptySet())?.toSet() ?: emptySet()
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Clips being saved from the dash cam"
            },
        )
    }

    fun enqueue(context: Context, files: List<CamFile>) {
        val busy = _jobs.value.filter { it.active }.map { it.file.path }.toSet()
        val add = files.filter { it.path !in busy }.distinctBy { it.path }
        if (add.isEmpty()) return
        _jobs.update { list -> list.filterNot { j -> !j.active && add.any { it.path == j.file.path } } + add.map { DownloadJob(it) } }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(path: String) {
        val job = _jobs.value.firstOrNull { it.file.path == path } ?: return
        when (job.state) {
            JobState.QUEUED -> set(path) { it.copy(state = JobState.CANCELLED) }
            JobState.RUNNING -> cancelled = path
            else -> Unit
        }
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.active } }
    }

    fun isSaved(f: CamFile): Boolean = f.name in _saved.value

    internal fun next(): DownloadJob? = _jobs.value.firstOrNull { it.state == JobState.QUEUED }

    internal fun set(path: String, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.file.path == path) change(it) else it } }
    }

    private fun markSaved(name: String) {
        val next = _saved.value + name
        _saved.value = next
        prefs?.edit()?.putStringSet("saved", HashSet(next))?.apply()
    }

    /** Saves one clip; runs on a background thread. */
    internal fun download(context: Context, job: DownloadJob, onProgress: () -> Unit) {
        val f = job.file
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            put(MediaStore.Video.Media.DATE_TAKEN, f.startMs)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
        if (uri == null) {
            set(f.path) { it.copy(state = JobState.FAILED, error = "Couldn't create the file on the phone") }
            return
        }
        cancelled = null
        set(f.path) { it.copy(state = JobState.RUNNING, bytes = 0) }
        var conn: HttpURLConnection? = null
        try {
            val c = URL(CamApi.fileUrl(f)).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 20_000
            if (c.responseCode != 200) throw java.io.IOException("Camera answered ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: f.sizeBytes
            set(f.path) { it.copy(total = total) }
            var done = 0L
            var lastUpdate = 0L
            resolver.openOutputStream(uri)!!.use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled == f.path) throw CancelledDownload()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 400) {
                            lastUpdate = now
                            val d = done
                            set(f.path) { it.copy(bytes = d) }
                            onProgress()
                        }
                    }
                }
            }
            val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            markSaved(f.name)
            val d = done
            set(f.path) { it.copy(state = JobState.DONE, bytes = d, uri = uri) }
        } catch (e: CancelledDownload) {
            runCatching { resolver.delete(uri, null, null) }
            set(f.path) { it.copy(state = JobState.CANCELLED) }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            set(f.path) { it.copy(state = JobState.FAILED, error = e.message ?: "Download failed") }
            CamConnection.reportFailure()
        } finally {
            conn?.disconnect()
            if (cancelled == f.path) cancelled = null
        }
    }

    private class CancelledDownload : Exception()

    internal const val CHANNEL = "downloads"
}

/** Keeps downloads going when you leave the app, with progress in a notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isActive != true) {
            worker = scope.launch {
                while (true) {
                    val job = Downloads.next() ?: break
                    Downloads.download(this@DownloadService, job) { update() }
                    update()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun update() {
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, note())
    }

    private fun note(): Notification {
        val jobs = Downloads.jobs.value
        val running = jobs.firstOrNull { it.state == JobState.RUNNING }
        val left = jobs.count { it.active }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Downloads.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (left > 1) "Saving $left clips" else "Saving clip")
            .setContentText(running?.file?.name ?: "Starting…")
            .setProgress(1000, ((running?.progress ?: 0f) * 1000).toInt(), running == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val NOTE_ID = 1
    }
}
