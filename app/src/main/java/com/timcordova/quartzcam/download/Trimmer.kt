package com.timcordova.quartzcam.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Cuts a stretch out of one or more back-to-back camera files and joins it into a single MP4, without re-encoding:
 * the video and sound are copied as they are, so it's quick and loses no quality. A cut can only start on a
 * keyframe, so the result may begin up to a second or so before the requested start.
 */
object Trimmer {
    /** One camera file, and the clock time (ms) its first frame was recorded. */
    class Part(val file: File, val startMs: Long)

    /**
     * Writes [startMs]..[endMs] (clock times) from [parts] (in order, back to back) to [out].
     * [onProgress] gets 0–1 and may throw to cancel.
     */
    fun cut(parts: List<Part>, startMs: Long, endMs: Long, out: File, onProgress: (Float) -> Unit) {
        require(parts.isNotEmpty())
        val first = tracks(parts.first().file)
        val videoFormat = first.video ?: throw IOException("no video in the clip")
        // Sound goes along only if every file has it in a form MP4 can hold (it's left out otherwise)
        val audioFormat = first.audio?.takeIf { a ->
            a.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC &&
                parts.all { tracks(it.file).audio?.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
        }

        out.delete()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            val vOut = muxer.addTrack(videoFormat)
            val aOut = audioFormat?.let { muxer.addTrack(it) } ?: -1
            muxer.start()
            started = true

            var buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            /** Where the current file starts in the output. */
            var offsetUs = 0L
            var lastVideoUs = -1L
            var lastAudioUs = -1L
            val total = (endMs - startMs).coerceAtLeast(1) * 1000f

            parts.forEachIndexed { i, part ->
                val video = extractor(part.file, video = true)
                val audio = if (aOut >= 0) extractor(part.file, video = false) else null
                try {
                    // Where to start and stop in this file (only the first and last files are cut)
                    val wantFrom = if (i == 0) ((startMs - part.startMs) * 1000).coerceAtLeast(0) else 0L
                    val until = if (i == parts.lastIndex) (endMs - part.startMs) * 1000 else Long.MAX_VALUE
                    video.seekTo(wantFrom, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val base = video.sampleTime.coerceAtLeast(0)
                    audio?.seekTo(base, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (audio != null && audio.sampleTime in 0 until base) audio.advance()

                    var lastInFile = base
                    var step = 33_333L
                    // Copy both tracks in time order
                    while (true) {
                        val vt = video.sampleTime.takeIf { it >= 0 && it <= until }
                        val at = audio?.sampleTime?.takeIf { it >= 0 && it <= until }
                        if (vt == null && at == null) break
                        val useVideo = at == null || (vt != null && vt <= at)
                        val ex = if (useVideo) video else audio!!
                        val t = if (useVideo) vt!! else at!!
                        val size = ex.sampleSize.toInt()
                        if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size + 1024 * 1024)
                        buffer.clear()
                        val n = ex.readSampleData(buffer, 0)
                        if (n < 0) {
                            ex.advance()
                            continue
                        }
                        val outUs = offsetUs + (t - base)
                        val key = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        if (useVideo) {
                            if (outUs > lastVideoUs) {
                                info.set(0, n, outUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                muxer.writeSampleData(vOut, buffer, info)
                                lastVideoUs = outUs
                            }
                            if (t > lastInFile) {
                                step = (t - lastInFile).coerceIn(1_000, 200_000)
                                lastInFile = t
                            }
                            onProgress(((outUs) / total).coerceIn(0f, 1f))
                        } else if (outUs > lastAudioUs) {
                            info.set(0, n, outUs, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            muxer.writeSampleData(aOut, buffer, info)
                            lastAudioUs = outUs
                        }
                        ex.advance()
                    }
                    // The next file carries on right after this one's last frame
                    offsetUs += (lastInFile - base) + step
                } finally {
                    video.release()
                    audio?.release()
                }
            }
            if (lastVideoUs < 0) throw IOException("nothing between the marks")
        } finally {
            runCatching { if (started) muxer.stop() }
            muxer.release()
        }
    }

    private class Tracks(val video: MediaFormat?, val audio: MediaFormat?)

    private fun tracks(file: File): Tracks {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            var v: MediaFormat? = null
            var a: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (v == null && mime.startsWith("video/")) v = f
                if (a == null && mime.startsWith("audio/")) a = f
            }
            return Tracks(v, a)
        } finally {
            ex.release()
        }
    }

    /** An extractor on the file's video (or sound) track. */
    private fun extractor(file: File, video: Boolean): MediaExtractor {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val prefix = if (video) "video/" else "audio/"
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix) }
        if (track == null) {
            ex.release()
            throw IOException("a clip has no ${if (video) "video" else "sound"}")
        }
        ex.selectTrack(track)
        return ex
    }
}
