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
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    OnPad { if (it == PadButton.SELECT && vm.browser.value == null) vm.clearFinished(); true }
    if (jobs.isEmpty()) {
        EmptyState(Icons.Rounded.DownloadDone, "No downloads", "Games you download appear here with their progress.")
        return
    }
    val running = jobs.filter { it.active }
    val finished = jobs.filterNot { it.active }
    // A removed row takes its focused button with it: focus goes to the row that takes its place.
    val actions = remember { mutableMapOf<String, FocusRequester>() }
    var refocus by remember { mutableStateOf<String?>(null) }
    val order = running + finished
    fun removing(ids: Set<String>) {
        val at = order.indexOfFirst { it.id in ids }.takeIf { it >= 0 } ?: return
        refocus = (order.drop(at + 1) + order.take(at).reversed()).firstOrNull { it.id !in ids }?.id
    }
    LaunchedEffect(refocus, jobs) {
        val id = refocus ?: return@LaunchedEffect
        if (jobs.none { it.id == id }) { refocus = null; return@LaunchedEffect }
        withFrameNanos { }
        runCatching { actions[id]?.requestFocus() }
        refocus = null
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (running.isNotEmpty()) {
            item { SectionTitle("Downloading", "${running.size}") }
            items(running, key = { it.id }) { JobRow(vm, it, actions.getOrPut(it.id) { FocusRequester() }) { removing(setOf(it.id)) } }
        }
        if (finished.isNotEmpty()) {
            item {
                Spacer(Modifier.height(if (running.isNotEmpty()) 14.dp else 0.dp))
                SectionTitle("Finished", "${finished.size}") {
                    Btn("Clear", { removing(finished.map { it.id }.toSet()); vm.clearFinished() }, kind = ButtonKind.Ghost, icon = Icons.Rounded.DeleteSweep)
                }
            }
            items(finished, key = { it.id }) { JobRow(vm, it, actions.getOrPut(it.id) { FocusRequester() }) { removing(setOf(it.id)) } }
        }
    }
}

@Composable
private fun JobRow(vm: AppViewModel, job: DownloadJob, action: FocusRequester, onRemoving: () -> Unit) {
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
        // The close button is one control throughout (Cancel while running, Remove after),
        // so a download that stops under focus keeps focus on it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (job.status == JobStatus.ERROR || job.status == JobStatus.CANCELLED) {
                if (job.status == JobStatus.ERROR && !linkIsDead(job.error) && (job.pageUrl != null || job.error?.contains("browser check") == true)) {
                    // The link wanted a browser (a check, a file host's page): get it through the site's page.
                    Btn("Open site", { vm.openBrowser(job) }, kind = ButtonKind.Primary, icon = Icons.Rounded.Language)
                    Spacer(Modifier.width(8.dp))
                }
                IconBtn(Icons.Rounded.Refresh, "Try again", { vm.retryDownload(job.id) })
                Spacer(Modifier.width(8.dp))
            }
            IconBtn(
                Icons.Rounded.Close,
                when {
                    job.active -> "Cancel"
                    job.status == JobStatus.ERROR || job.status == JobStatus.CANCELLED -> "Remove"
                    else -> "Remove from list"
                },
                { if (job.active) vm.cancelDownload(job.id) else { onRemoving(); vm.removeDownload(job.id) } },
                Modifier.focusRequester(action),
            )
        }
    }
}

private fun statusLine(job: DownloadJob): String = job.note ?: when (job.status) {
    JobStatus.QUEUED -> "Waiting…"
    JobStatus.EXTRACTING -> "Unpacking… ${formatBytes(job.received)}"
    else -> buildList {
        add(if (job.total > 0) "${formatBytes(job.received)} of ${formatBytes(job.total)}" else formatBytes(job.received).ifEmpty { "Starting…" })
        if (job.speed > 0) add("${formatBytes(job.speed.toLong())}/s")
        if (job.speed > 0 && job.total > job.received) add("${formatEta((job.total - job.received) / job.speed)} left")
    }.joinToString("  ·  ")
}

/** The site's link is expired or the file gone: its page cannot help either. */
fun linkIsDead(error: String?): Boolean = error != null && ("expired" in error || "is gone" in error)
