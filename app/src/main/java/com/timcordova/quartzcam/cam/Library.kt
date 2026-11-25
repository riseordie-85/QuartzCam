package com.timcordova.quartzcam.cam

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A clip on the card, with how long it runs. */
data class Clip(val file: CamFile, val durationMs: Long) {
    val startMs: Long get() = file.startMs
    val endMs: Long get() = startMs + durationMs
}

/**
 * Clips recorded back to back: one drive. The camera starts a new file every 3 minutes, so a 40-minute commute is
 * about 14 clips; together they play as one.
 */
data class Drive(val clips: List<Clip>) {
    val startMs: Long get() = clips.first().startMs
    val endMs: Long get() = clips.last().endMs
    val durationMs: Long get() = endMs - startMs
    val sizeKb: Long get() = clips.sumOf { it.file.sizeKb }
    val id: String get() = clips.first().file.path

    /** The clip playing at [timeMs] and how far into it, for seeking. */
    fun locate(timeMs: Long): Pair<Int, Long> {
        val t = timeMs.coerceIn(startMs, endMs)
        val i = clips.indexOfLast { it.startMs <= t }.coerceAtLeast(0)
        val c = clips[i]
        return i to (t - c.startMs).coerceIn(0, (c.durationMs - 250).coerceAtLeast(0))
    }

    /** Clips that overlap [fromMs]..[toMs]. */
    fun clipsBetween(fromMs: Long, toMs: Long): List<Clip> {
        val a = minOf(fromMs, toMs)
        val b = maxOf(fromMs, toMs)
        return clips.filter { it.endMs > a && it.startMs < b }
    }
}

/** Everything on the camera's card, sorted into drives and days. */
class Library(
    val loop: List<Clip>,
    val sos: List<Clip>,
    val drives: List<Drive>,
    val sd: SdInfo?,
    /** Photos taken with the camera (its snapshot button or the app's Photo button). */
    val photos: List<CamFile> = emptyList(),
) {
    val all: List<Clip> = (loop + sos).sortedBy { it.startMs }

    /** Days with something recorded, newest first. */
    val days: List<LocalDate> = (all.map { it.startMs.toDate() } + photos.map { it.startMs.toDate() }).distinct().sortedDescending()

    fun drivesOn(day: LocalDate): List<Drive> = drives.filter { it.startMs.toDate() == day }

    fun sosOn(day: LocalDate): List<Clip> = sos.filter { it.startMs.toDate() == day }

    /** The drive a loop clip belongs to. */
    fun driveOf(clip: Clip): Drive? = drives.firstOrNull { d -> d.clips.any { it.file.path == clip.file.path } }

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), null)

        /** A gap longer than this between clips means the car was off: a new drive. */
        private const val DRIVE_GAP_MS = 60_000L

        /** The camera's clip length; consecutive clips start this far apart. */
        private const val CLIP_MS = 180_000L

        fun build(loopFiles: List<CamFile>, sosFiles: List<CamFile>, sd: SdInfo?): Library {
            // Photos come back mixed in with the videos (in the "event" folder)
            val photos = (loopFiles + sosFiles).filter { it.isPhoto }.distinctBy { it.path }.sortedBy { it.startMs }
            val loop = withDurations(loopFiles.filterNot { it.isPhoto }.sortedBy { it.startMs })
            val sos = withDurations(sosFiles.filterNot { it.isPhoto }.sortedBy { it.startMs }, consecutive = false)
            return Library(loop, sos, drives(loop), sd, photos)
        }

        /**
         * How long each clip runs. The camera doesn't say, so: back-to-back clips run until the next one starts,
         * and the last clip of a drive is worked out from its size and the camera's usual bitrate.
         */
        private fun withDurations(files: List<CamFile>, consecutive: Boolean = true): List<Clip> {
            val rate = kbPerSecond(files)
            return files.mapIndexed { i, f ->
                val fromSize = (f.sizeKb / rate * 1000).toLong().coerceIn(1_000L, CLIP_MS + 10_000L)
                val next = files.getOrNull(i + 1)
                val gap = next?.let { it.startMs - f.startMs }
                val ms = if (consecutive && gap != null && gap in 1_000L..(CLIP_MS + 15_000L)) gap else fromSize
                Clip(f, ms)
            }
        }

        /** The camera's usual bitrate, from full clips (ones followed 3 minutes later by the next). */
        private fun kbPerSecond(files: List<CamFile>): Double {
            val rates = files.zipWithNext().mapNotNull { (a, b) ->
                val gap = b.startMs - a.startMs
                if (gap in 170_000L..190_000L && a.sizeKb > 0) a.sizeKb / (gap / 1000.0) else null
            }.sorted()
            // About 870 MB per 3 minutes on the camera this was built with, if there's nothing to measure
            return if (rates.isEmpty()) 4800.0 else rates[rates.size / 2]
        }

        private fun drives(loop: List<Clip>): List<Drive> {
            val out = ArrayList<Drive>()
            var current = ArrayList<Clip>()
            for (c in loop) {
                val last = current.lastOrNull()
                if (last != null && c.startMs - last.endMs > DRIVE_GAP_MS) {
                    out += Drive(current)
                    current = ArrayList()
                }
                current += c
            }
            if (current.isNotEmpty()) out += Drive(current)
            return out
        }
    }
}

fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
