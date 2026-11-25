package com.timcordova.quartzcam.cam

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The dash cam's own web API (XMSilicon / "Viidure" style), worked out from the camera itself:
 *
 *   GET /app/getproductinfo                          → {"result":0,"info":{"model","company","soc","sp"}}
 *   GET /app/getsdinfo                               → {"result":0,"info":{"status","free","total"}}  (MB)
 *   GET /app/getfilelist?folder=loop&start=0&end=49  → files in a folder, oldest first, start..end inclusive
 *       folders: loop (normal recording), emr (locked SOS clips), event; park and photo answer result 1 (none)
 *       each file: {"name": "/mnt/tfcard/record/record_0/REC_20261117150316_2144_F.mp4",
 *                   "size": 1046530 (KB), "createtimestr": "20261117150316", "type": 2}
 *   GET /app/getthumbnail?file=<name>                → a still from the clip
 *   GET <name>                                       → the clip itself (plain HTTP download)
 *   GET /app/getmediainfo                            → {"info":{"rtsp":"rtsp://192.168.169.1/streamtype=0","transport":"tcp","port":2222}}
 *   GET /app/enterrecorder                           → puts the camera in live mode; needed before the RTSP stream sends
 *                                                      anything (seen in a capture of the Viidure app)
 *   GET /app/setsystime?date=yyyyMMddHHmmss          → sets the camera's clock (Viidure does this on every connect)
 *   RTSP rtsp://192.168.169.1:554/streamtype=0       → live view, H.265 video + G.711 (PCMA) audio, RTP over TCP
 *   TCP 192.168.169.1:2222                           → the camera pushes JSON events here ({"msgid":"rec",...}); unused
 *
 * Also seen, not used yet: /app/getdeviceattr (Wi-Fi name, firmware), /app/getparamitems?param=all and
 * /app/getparamvalue?param=all (camera settings), /app/setparamvalue?param=rec|mic&value=0|1, /app/snapshot.
 *
 * No login: anyone on the camera's Wi-Fi can use it, so its Wi-Fi password matters.
 */

data class ProductInfo(val model: String, val company: String, val soc: String, val sp: String)

/** Sizes in MB, as the camera reports them. */
data class SdInfo(val status: Int, val freeMb: Long, val totalMb: Long) {
    val usedMb: Long get() = (totalMb - freeMb).coerceAtLeast(0)
}

data class MediaInfo(val rtsp: String, val transport: String, val port: Int)

/** The camera's folders. Loop is normal driving; SOS clips are locked (impact or the button). */
enum class Folder(val api: String, val label: String) {
    LOOP("loop", "Loop"),
    SOS("emr", "SOS"),
    EVENT("event", "Event"),
}

/** One file on the camera's card. */
data class CamFile(
    /** Full path on the camera, which is also its download path. */
    val path: String,
    val folder: Folder,
    /** The camera reports sizes in KB. */
    val sizeKb: Long,
    val start: LocalDateTime,
) {
    val name: String get() = path.substringAfterLast('/')

    /** Which camera lens: "F" front, "R" rear (if there is one). */
    val lens: String get() = name.substringBeforeLast('.').substringAfterLast('_', "")

    val startMs: Long get() = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val sizeBytes: Long get() = sizeKb * 1024L
}

class CamException(message: String) : IOException(message)

object CamApi {
    /** The camera's address on its own Wi-Fi. */
    @Volatile
    var host: String = DEFAULT_HOST

    const val DEFAULT_HOST = "192.168.169.1"

    val base: String get() = "http://$host"

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun fileUrl(f: CamFile): String = base + f.path

    fun thumbUrl(f: CamFile): String = "$base/app/getthumbnail?file=${f.path}"

    /** A GET that returns the camera's JSON reply, or throws. */
    fun getJson(path: String, timeoutMs: Int = 6000): JSONObject {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.useCaches = false
        try {
            val code = conn.responseCode
            if (code != 200) throw CamException("Camera answered $code for $path")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    fun productInfo(timeoutMs: Int = 4000): ProductInfo {
        val info = getJson("/app/getproductinfo", timeoutMs).ok().optJSONObject("info") ?: JSONObject()
        return ProductInfo(
            model = info.optString("model"),
            company = info.optString("company"),
            soc = info.optString("soc"),
            sp = info.optString("sp"),
        )
    }

    fun sdInfo(): SdInfo {
        val info = getJson("/app/getsdinfo").ok().optJSONObject("info") ?: JSONObject()
        return SdInfo(info.optInt("status"), info.optLong("free"), info.optLong("total"))
    }

    fun mediaInfo(): MediaInfo? = runCatching {
        val info = getJson("/app/getmediainfo").ok().optJSONObject("info") ?: return null
        MediaInfo(info.optString("rtsp"), info.optString("transport"), info.optInt("port"))
    }.getOrNull()

    /** Switches the camera to live mode, so its RTSP stream starts sending video. */
    fun enterRecorder(): Boolean = runCatching { getJson("/app/enterrecorder").optInt("result", -1) == 0 }.getOrDefault(false)

    /** Sets the camera's clock to the phone's, so clip times are right. */
    fun setClock(): Boolean = runCatching {
        val now = LocalDateTime.now().format(stamp)
        getJson("/app/setsystime?date=$now").optInt("result", -1) == 0
    }.getOrDefault(false)

    /** Every file in [folder], oldest first. Folders the camera doesn't have come back empty. */
    fun files(folder: Folder): List<CamFile> {
        val out = ArrayList<CamFile>()
        var start = 0
        // A 128 GB card holds a few hundred clips; the cap only guards against a camera that never stops paging
        repeat(200) {
            val end = start + PAGE - 1
            val json = getJson("/app/getfilelist?folder=${folder.api}&start=$start&end=$end", 10_000)
            if (json.optInt("result", -1) != 0) return out
            val groups = json.optJSONArray("info") ?: return out
            var got = 0
            for (g in 0 until groups.length()) {
                val files = groups.optJSONObject(g)?.optJSONArray("files") ?: continue
                for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    got++
                    parse(f, folder)?.let { out += it }
                }
            }
            if (got < PAGE) return out
            start += PAGE
        }
        return out
    }

    private fun parse(f: JSONObject, folder: Folder): CamFile? {
        val path = f.optString("name").takeIf { it.isNotBlank() } ?: return null
        val time = parseStamp(f.optString("createtimestr"))
            // Fall back to the time in the file name: REC_20261117150316_2144_F.mp4
            ?: Regex("""_(\d{14})_""").find(path)?.groupValues?.get(1)?.let { parseStamp(it) }
            ?: return null
        return CamFile(path, folder, f.optLong("size"), time)
    }

    private fun parseStamp(s: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(s, stamp) }.getOrNull()

    private fun JSONObject.ok(): JSONObject {
        if (optInt("result", -1) != 0) throw CamException("Camera said no (result ${optInt("result", -1)})")
        return this
    }

    private const val PAGE = 50
}
