package com.timcordova.quartzcam.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.timcordova.quartzcam.download.DownloadJob
import com.timcordova.quartzcam.download.Downloads
import com.timcordova.quartzcam.download.JobState
import com.timcordova.quartzcam.quartz.QuartzScaffold
import com.timcordova.quartzcam.quartz.QuartzTopBar

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val jobs by Downloads.jobs.collectAsState()
    val context = LocalContext.current
    val list = rememberLazyListState()
    QuartzScaffold(
        scroll = { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset },
        topBar = {
            QuartzTopBar(
                title = { Text("Downloads") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (jobs.any { !it.active }) {
                        IconButton(onClick = { Downloads.clearFinished() }) { Icon(Icons.Outlined.ClearAll, contentDescription = "Clear finished") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "Clips are saved to Movies/${Downloads.FOLDER} on your phone, so they show up in Quartz Gallery.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (jobs.isEmpty()) {
                item { Text("Nothing downloading.", modifier = Modifier.padding(20.dp)) }
            }
            items(jobs.reversed(), key = { it.file.path }) { job ->
                JobRow(
                    job,
                    onOpen = {
                        job.uri?.let { uri ->
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { context.startActivity(view) }
                        }
                    },
                    onCancel = { Downloads.cancel(job.file.path) },
                    onRetry = { Downloads.retry(context, job) },
                )
            }
        }
    }
}

@Composable
private fun JobRow(job: DownloadJob, onOpen: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit) {
    QuartzCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = job.state == JobState.DONE, onClick = onOpen)
                .padding(8.dp),
        ) {
            ClipThumb(job.file, Modifier.height(54.dp).width(96.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("${dayTitle(java.time.Instant.ofEpochMilli(job.file.startMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate())}, ${clock(job.file.startMs)}", fontWeight = FontWeight.Bold)
                val status = when (job.state) {
                    JobState.QUEUED -> "Waiting · ${sizeKb(job.file.sizeKb)}"
                    JobState.RUNNING -> "${sizeBytes(job.bytes)} of ${sizeBytes(job.total)}" + (job.error?.let { " · $it" } ?: "")
                    JobState.DONE -> "Saved · tap to play"
                    JobState.FAILED -> "Failed: ${job.error ?: "unknown error"}"
                    JobState.CANCELLED -> "Cancelled"
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (job.state == JobState.RUNNING) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                }
            }
            if (job.active) {
                IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel") }
            } else if (job.state == JobState.FAILED || job.state == JobState.CANCELLED) {
                IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = "Try again") }
            }
        }
    }
}
