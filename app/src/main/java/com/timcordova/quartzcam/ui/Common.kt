package com.timcordova.quartzcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.timcordova.quartzcam.cam.CamApi
import com.timcordova.quartzcam.cam.CamFile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ------------------------------------------------------------------ formatting

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val timeSecFmt = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.getDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
private val shortDayFmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault())

fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

fun clockSeconds(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeSecFmt)

fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(dayFmt)
    }
}

fun shortDay(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(shortDayFmt)
    }
}

/** 3:05 or 1:02:30. */
fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "43 min" or "1 hr 5 min". */
fun longDuration(ms: Long): String {
    val min = ((ms + 30_000) / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
}

fun sizeKb(kb: Long): String = when {
    kb >= 1024L * 1024 -> "%.1f GB".format(kb / (1024.0 * 1024))
    kb >= 1024 -> "%.0f MB".format(kb / 1024.0)
    else -> "$kb KB"
}

fun sizeMb(mb: Long): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

fun sizeBytes(b: Long): String = sizeKb(b / 1024)

// ------------------------------------------------------------------ pieces

/** A see-through panel over the Quartz background. */
@Composable
fun QuartzCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier
            .clip(shape)
            .background(if (LocalGradientActive.current) surface.copy(alpha = 0.62f) else surface)
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
        content = content,
    )
}

/** A clip's thumbnail, straight from the camera (cached on the phone after the first look). */
@Composable
fun ClipThumb(file: CamFile, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = if (file.isPhoto) CamApi.fileUrl(file) else CamApi.thumbUrl(file),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Color.Black.copy(alpha = 0.35f)),
        loading = { ThumbPlaceholder() },
        error = { ThumbPlaceholder() },
    )
}

@Composable
private fun ThumbPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Videocam, contentDescription = null, tint = Color.White.copy(alpha = 0.4f))
    }
}

/** A small label like "SOS" or "Saved". */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

val SosRed = Color(0xFFE5484D)
val SavedGreen = Color(0xFF2F9E6B)
