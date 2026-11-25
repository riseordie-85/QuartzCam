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
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.FileOutputStream
import java.io.IOException
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
        val kb = add.sumOf { it.sizeKb }
        val what = if (add.size == 1) "clip" else "${add.size} clips"
        toast(context, "Saving $what (${sizeText(kb)}). Progress is under Downloads.")
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

    fun retry(context: Context, job: DownloadJob) = enqueue(context, listOf(job.file))

    private fun sizeText(kb: Long): String =
        if (kb >= 1024L * 1024) "%.1f GB".format(kb / (1024.0 * 1024)) else "%.0f MB".format(kb / 1024.0)

    internal fun next(): DownloadJob? = _jobs.value.firstOrNull { it.state == JobState.QUEUED }

    internal fun set(path: String, change: (DownloadJob) -> DownloadJob) {
        _jobs.update { list -> list.map { if (it.file.path == path) change(it) else it } }
    }

    private fun markSaved(name: String) {
        val next = _saved.value + name
        _saved.value = next
        prefs?.edit()?.putStringSet("saved", HashSet(next))?.apply()
    }

    /**
     * Saves one clip; runs on a background thread.
     *
     * The camera's web server cuts long transfers off after a while, so the clip is fetched in pieces (HTTP range
     * requests), each on its own short connection. When a piece is cut off, it carries on from the last byte saved.
     * It only gives up after several tries in a row that get nowhere. Cameras that can't send pieces get the whole
     * file in one go, with a few full retries.
     */
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
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull()
        if (uri == null) {
            set(f.path) { it.copy(state = JobState.FAILED, error = "Couldn't create the file on the phone") }
            return
        }
        cancelled = null
        set(f.path) { it.copy(state = JobState.RUNNING, bytes = 0, error = null) }
        var done = 0L
        var total = -1L
        /** True once the camera has said how big the file is (the file list's size is only close). */
        var exact = false
        /** Null until the camera's first answer shows whether it can send pieces. */
        var pieces: Boolean? = null
        var stuck = 0
        var wholeTries = 0
        var lastUpdate = 0L
        try {
            resolver.openFileDescriptor(uri, "rw")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    while (total < 0 || done < total) {
                        if (cancelled == f.path) throw CancelledDownload()
                        val before = done
                        try {
                            val c = URL(CamApi.fileUrl(f)).openConnection() as HttpURLConnection
                            c.connectTimeout = 10_000
                            c.readTimeout = 20_000
                            // A fresh connection each time, no compression: the camera's little server copes best with that
                            c.setRequestProperty("Connection", "close")
                            c.setRequestProperty("Accept-Encoding", "identity")
                            if (pieces != false) {
                                // Never ask past the end of the file: this camera hangs up instead of trimming the range.
                                // After a failed try, ask for "everything from here on" instead of a fixed piece.
                                val end = if (exact) minOf(done + PIECE, total) - 1 else done + PIECE - 1
                                c.setRequestProperty("Range", if (stuck > 0) "bytes=$done-" else "bytes=$done-$end")
                            }
                            try {
                                when (val code = c.responseCode) {
                                    206 -> {
                                        pieces = true
                                        // "bytes 0-25165823/912345678": the number after the slash is the whole file
                                        c.getHeaderField("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull()?.let {
                                            total = it
                                            exact = true
                                        }
                                    }
                                    200 -> {
                                        // No pieces: the whole file, from the start
                                        pieces = false
                                        wholeTries++
                                        done = 0
                                        out.channel.truncate(0)
                                        c.contentLengthLong.takeIf { it > 0 }?.let {
                                            total = it
                                            exact = true
                                        }
                                    }
                                    // Asked past the end: everything's already here
                                    416 -> if (done > 0) {
                                        total = done
                                        break
                                    } else {
                                        throw IOException("the camera answered 416")
                                    }
                                    else -> throw IOException("the camera answered $code")
                                }
                                if (total <= 0) total = f.sizeBytes
                                val expected = c.contentLengthLong
                                val t = total
                                set(f.path) { it.copy(total = t) }
                                out.channel.position(done)
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
                                            set(f.path) { it.copy(bytes = d, error = null) }
                                            onProgress()
                                        }
                                    }
                                }
                                // A piece shorter than asked for, delivered in full, is the end of the file
                                val got = done - before
                                if (pieces == true && !exact && got in 1 until PIECE && (expected <= 0 || got >= expected)) {
                                    total = done
                                    exact = true
                                }
                            } finally {
                                c.disconnect()
                            }
                            if (pieces == false && done < total) throw IOException("the connection dropped")
                            if (done > before) stuck = 0
                        } catch (e: IOException) {
                            // Cut off: fine as long as it's still getting somewhere
                            if (done > before && pieces != false) stuck = 0 else stuck++
                            val raw = e.message ?: e.javaClass.simpleName
                            val reason = if ("end of stream on" in raw) "the camera closed the connection" else raw
                            // Out of pieces right at the size the file list gave: the file was just a little smaller
                            if (pieces == true && !exact && stuck >= 2 && done >= total * 97 / 100) {
                                total = done
                                break
                            }
                            val reached = if (total > 0) " at ${done * 100 / total}% (${sizeText(done / 1024)} of ${sizeText(total / 1024)}, ${"%,d".format(total - done)} bytes left)" else ""
                            val gaveUp = if (pieces == false) wholeTries >= WHOLE_TRIES else stuck >= STUCK_TRIES
                            if (gaveUp) {
                                val note = if (pieces == false) " (this camera can't resume downloads)" else ""
                                throw IOException("$reason$reached$note")
                            }
                            set(f.path) { it.copy(bytes = done, error = "reconnecting") }
                            onProgress()
                            Thread.sleep(if (stuck == 0) 300L else 1500L * stuck)
                        }
                    }
                    out.flush()
                }
            }
            val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            markSaved(f.name)
            val d = done
            set(f.path) { it.copy(state = JobState.DONE, bytes = d, uri = uri, error = null) }
        } catch (e: CancelledDownload) {
            runCatching { resolver.delete(uri, null, null) }
            set(f.path) { it.copy(state = JobState.CANCELLED, error = null) }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            set(f.path) { it.copy(state = JobState.FAILED, error = e.message ?: "Download failed") }
            CamConnection.reportFailure()
        } finally {
            if (cancelled == f.path) cancelled = null
        }
    }

    /** Bytes asked for per request: 16 MB, a few seconds over the camera's Wi-Fi. */
    private const val PIECE = 16L * 1024 * 1024

    /** Give up after this many tries in a row that save nothing new. */
    private const val STUCK_TRIES = 6

    /** Full-file tries, for cameras that can't send pieces. */
    private const val WHOLE_TRIES = 4

    /** A short message at the bottom of the screen, from any thread. */
    fun toast(context: Context, text: String) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
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
                val handled = LinkedHashSet<String>()
                while (true) {
                    val job = Downloads.next() ?: break
                    handled += job.file.path
                    Downloads.download(this@DownloadService, job) { update() }
                    update()
                }
                // Say how it went, so a failure never goes unnoticed
                val results = Downloads.jobs.value.filter { it.file.path in handled }
                val saved = results.count { it.state == JobState.DONE }
                val failed = results.filter { it.state == JobState.FAILED }
                val msg = when {
                    failed.isEmpty() && saved > 0 ->
                        if (saved == 1) "Clip saved to Movies/${Downloads.FOLDER}" else "$saved clips saved to Movies/${Downloads.FOLDER}"
                    failed.isNotEmpty() ->
                        "${if (failed.size == 1) "A clip" else "${failed.size} clips"} couldn't be saved: ${failed.first().error}. Retry from Downloads."
                    else -> null
                }
                msg?.let { Downloads.toast(this@DownloadService, it) }
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
