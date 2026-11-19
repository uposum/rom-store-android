package io.github.uposum.romstore.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.sources.SourceDefs
import io.github.uposum.romstore.core.store.DownloadJob
import io.github.uposum.romstore.core.store.JobStatus
import io.github.uposum.romstore.formatBytes

@Composable
fun DownloadsTab(vm: AppViewModel) {
    val jobs by vm.downloads.collectAsStateWithLifecycle()
    OnPad { if (it == PadButton.SELECT) vm.clearFinished(); true }
    if (jobs.isEmpty()) {
        EmptyState(Icons.Rounded.DownloadDone, "No downloads", "Games you download appear here with their progress.")
        return
    }
    val running = jobs.filter { it.active }
    val finished = jobs.filterNot { it.active }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (running.isNotEmpty()) {
            item { SectionTitle("Downloading", "${running.size}") }
            items(running, key = { it.id }) { JobRow(vm, it) }
        }
        if (finished.isNotEmpty()) {
            item {
                Spacer(Modifier.height(if (running.isNotEmpty()) 14.dp else 0.dp))
                SectionTitle("Finished", "${finished.size}") {
                    Btn("Clear", vm::clearFinished, kind = ButtonKind.Ghost, icon = Icons.Rounded.DeleteSweep)
                }
            }
            items(finished, key = { it.id }) { JobRow(vm, it) }
        }
    }
}

@Composable
private fun JobRow(vm: AppViewModel, job: DownloadJob) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(C.surface, shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(54.dp)) {
            Cover(job.cover, job.system, job.title, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.title, style = T.h3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(Systems.label(job.system), SourceDefs.label(job.source), job.path ?: job.filename).joinToString("  ·  "),
                style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            val fraction = if (job.total > 0) job.received.toFloat() / job.total else null
            when (job.status) {
                JobStatus.DOWNLOADING, JobStatus.QUEUED, JobStatus.EXTRACTING -> {
                    ProgressLine(if (job.status == JobStatus.QUEUED) null else fraction, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(statusLine(job), style = T.small, fontSize = 12.sp)
                }
                JobStatus.DONE -> Text(
                    job.error ?: "Saved${job.extractedTo?.let { " and unpacked" } ?: ""} · ${formatBytes(job.total)}",
                    style = T.small, color = if (job.error != null) C.warn else C.good, maxLines = 2,
                )
                JobStatus.ERROR -> Text(job.error ?: "Failed", style = T.small, color = C.bad, maxLines = 2)
                JobStatus.CANCELLED -> Text("Cancelled", style = T.small)
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            job.active -> IconBtn(Icons.Rounded.Close, "Cancel", { vm.cancelDownload(job.id) })
            job.status == JobStatus.ERROR || job.status == JobStatus.CANCELLED -> Row {
                IconBtn(Icons.Rounded.Refresh, "Try again", { vm.retryDownload(job.id) })
                Spacer(Modifier.width(8.dp))
                IconBtn(Icons.Rounded.Close, "Remove", { vm.removeDownload(job.id) })
            }
            else -> IconBtn(Icons.Rounded.Close, "Remove from list", { vm.removeDownload(job.id) })
        }
    }
}

private fun statusLine(job: DownloadJob): String = when (job.status) {
    JobStatus.QUEUED -> "Waiting…"
    JobStatus.EXTRACTING -> "Unpacking… ${formatBytes(job.received)}"
    else -> buildList {
        add(if (job.total > 0) "${formatBytes(job.received)} of ${formatBytes(job.total)}" else formatBytes(job.received).ifEmpty { "Starting…" })
        if (job.speed > 0) add("${formatBytes(job.speed.toLong())}/s")
        if (job.speed > 0 && job.total > job.received) add("${formatEta((job.total - job.received) / job.speed)} left")
    }.joinToString("  ·  ")
}
