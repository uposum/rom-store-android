package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.net.DownloadSink
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.net.HttpError
import io.github.uposum.romstore.core.net.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object JobStatus {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val EXTRACTING = "extracting"
    const val DONE = "done"
    const val ERROR = "error"
    const val CANCELLED = "cancelled"
}

@Serializable
data class DownloadJob(
    val id: String,
    val title: String,
    val system: String,
    val source: String,
    val filename: String,
    val url: String,
    /** The console folder (its id in the RomLibrary). */
    val destination: String,
    val status: String = JobStatus.QUEUED,
    val received: Long = 0,
    val total: Long = 0,
    val speed: Double = 0.0,
    val error: String? = null,
    val created: Long = System.currentTimeMillis(),
    val finished: Long? = null,
    /** The finished file's name in the console folder. */
    val path: String? = null,
    val extractedTo: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val extract: Boolean = false,
    val keepArchive: Boolean = true,
    val prepareSeconds: Double = 0.0,
    val resumedFrom: Long = 0,
    val cover: String? = null,
    /** Where the console folder is, for the Downloads tab ("Internal/ROMs/gba"). */
    val folderLabel: String? = null,
    /** The site's page for this file: where the in-app browser opens when the link needs a browser. */
    val pageUrl: String? = null,
    /** The url is a file host's page: the file's own link is fetched each time the download starts. */
    val resolveLate: Boolean = false,
    /** What the job is doing before bytes flow ("Waiting for 1fichier… 42 s"). */
    val note: String? = null,
    /** The game's clean name: a single game file unpacked from the archive is renamed to it. */
    val cleanBase: String? = null,
) {
    val active: Boolean get() = status == JobStatus.QUEUED || status == JobStatus.DOWNLOADING || status == JobStatus.EXTRACTING
    val partName: String get() = Html.safeFilename(filename.ifEmpty { "$title.zip" }) + ".part"
}

/**
 * Keeps a small queue of downloads running in the background
 * (backend/downloader.py). The queue is saved to `statePath`; a download that
 * was running when the app stopped keeps its ".part" file and continues with a
 * Range request on the next start (`resumePending`).
 */
class DownloadManager(
    private val http: HttpClient,
    private val library: () -> RomLibrary?,
    private val statePath: File?,
    private val scratch: File?,
    maxConcurrent: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val jobs = ConcurrentHashMap<String, DownloadJob>()
    private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
    private val tasks = ConcurrentHashMap<String, Job>()
    private var semaphore = Semaphore(maxConcurrent.coerceIn(1, 6))
    private val counter = AtomicInteger(0)
    private var suspending = false
    private var savedAt = 0L

    private val _state = MutableStateFlow<List<DownloadJob>>(emptyList())
    /** Every job, newest first. */
    val state: StateFlow<List<DownloadJob>> = _state.asStateFlow()

    /** Picks the folder for each file unpacked from a job's archive (add-ons into their folders). */
    var router: ((DownloadJob, FolderNode, String) -> FolderNode)? = null

    /** Fetches the link of a `resolveLate` job (a file host's one-time link); `note` reports progress. */
    var resolver: (suspend (DownloadJob, (String) -> Unit) -> String)? = null

    /** Called with each job that finished. */
    var onDone: ((DownloadJob) -> Unit)? = null
    /** Called with (job, bytes, seconds) for each finished transfer (speed ranking). */
    var onMeasure: ((DownloadJob, Long, Double) -> Unit)? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        load()
    }

    private fun load() {
        val path = statePath ?: return
        if (!path.isFile) return
        val stored = runCatching { json.decodeFromString(ListSerializer(DownloadJob.serializer()), path.readText()) }.getOrNull() ?: return
        for (job in stored) {
            // A job that was running when the app stopped waits to be resumed.
            val fixed = if (job.status == JobStatus.DOWNLOADING || job.status == JobStatus.EXTRACTING) {
                job.copy(status = JobStatus.QUEUED, speed = 0.0)
            } else job
            jobs[job.id] = fixed
        }
        publish()
    }

    private fun save(force: Boolean = false) {
        val path = statePath ?: return
        val now = System.currentTimeMillis()
        if (!force && now - savedAt < 2000) return
        savedAt = now
        runCatching {
            path.parentFile?.mkdirs()
            val temp = File(path.path + ".tmp")
            temp.writeText(json.encodeToString(ListSerializer(DownloadJob.serializer()), jobs.values.toList()))
            temp.renameTo(path)
        }
    }

    private fun publish() {
        _state.value = jobs.values.sortedByDescending { it.created }
    }

    private fun update(id: String, force: Boolean = false, change: (DownloadJob) -> DownloadJob) {
        jobs.computeIfPresent(id) { _, job -> change(job) }
        publish()
        save(force)
    }

    fun get(id: String): DownloadJob? = jobs[id]

    fun setMaxConcurrent(value: Int) {
        semaphore = Semaphore(value.coerceIn(1, 6))
    }

    val hasActive: Boolean get() = jobs.values.any { it.active }

    /** Continue the downloads that were queued or running when the app stopped. */
    fun resumePending(): Int {
        var started = 0
        for (job in jobs.values) {
            if (job.status == JobStatus.QUEUED && tasks[job.id]?.isActive != true) {
                cancels[job.id] = AtomicBoolean(false)
                tasks[job.id] = scope.launch { run(job.id, resume = true) }
                started++
            }
        }
        return started
    }

    fun enqueue(
        title: String, system: String, source: String, filename: String, url: String,
        destination: String, headers: Map<String, String> = emptyMap(), extract: Boolean = false,
        keepArchive: Boolean = true, prepareSeconds: Double = 0.0, cover: String? = null, folderLabel: String? = null,
        pageUrl: String? = null, resolveLate: Boolean = false, cleanBase: String? = null,
    ): DownloadJob {
        val id = "dl-${System.currentTimeMillis() / 1000}-${counter.incrementAndGet()}"
        val job = DownloadJob(
            id = id, title = title, system = system, source = source, filename = filename, url = url,
            destination = destination, headers = headers, extract = extract, keepArchive = keepArchive,
            prepareSeconds = prepareSeconds, cover = cover, folderLabel = folderLabel, pageUrl = pageUrl,
            resolveLate = resolveLate, cleanBase = cleanBase,
        )
        jobs[id] = job
        cancels[id] = AtomicBoolean(false)
        forgetFinished()
        publish()
        save(force = true)
        tasks[id] = scope.launch { run(id, resume = false) }
        return job
    }

    /** Start a failed or cancelled download again (continuing its partial file). */
    fun retry(id: String): Boolean {
        val job = jobs[id] ?: return false
        if (job.active) return false
        update(id, force = true) { it.copy(status = JobStatus.QUEUED, error = null, finished = null, speed = 0.0) }
        cancels[id] = AtomicBoolean(false)
        tasks[id] = scope.launch { run(id, resume = true) }
        return true
    }

    fun cancel(id: String): Boolean {
        cancels[id]?.set(true)
        val job = jobs[id] ?: return false
        if (!job.active) return false
        tasks[id]?.cancel()
        update(id, force = true) { it.copy(status = JobStatus.CANCELLED, finished = System.currentTimeMillis(), speed = 0.0) }
        scope.launch { removePart(job) }
        return true
    }

    fun remove(id: String) {
        val job = jobs[id] ?: return
        if (job.active) cancel(id)
        jobs.remove(id)
        publish()
        save(force = true)
    }

    fun clearFinished() {
        jobs.values.filter { !it.active }.forEach { jobs.remove(it.id) }
        publish()
        save(force = true)
    }

    /** Stop for now, keeping partial files so the next start continues. */
    suspend fun suspendAll() {
        suspending = true
        cancels.values.forEach { it.set(true) }
        withTimeoutOrNull(8000) { tasks.values.forEach { it.join() } }
        save(force = true)
        suspending = false
    }

    private fun forgetFinished(keep: Int = 40) {
        if (jobs.size <= keep * 2) return
        val finished = jobs.values.filter { !it.active }.sortedBy { it.finished ?: 0 }
        for (job in finished.take(maxOf(0, jobs.size - keep))) {
            jobs.remove(job.id); cancels.remove(job.id); tasks.remove(job.id)
        }
    }

    private fun folderOf(job: DownloadJob): FolderNode? = library()?.folderById(job.destination)

    private fun removePart(job: DownloadJob) {
        runCatching { folderOf(job)?.child(job.partName)?.delete() }
    }

    private class NodeSink(val folder: FolderNode, val name: String) : DownloadSink {
        var node: FileNode? = folder.child(name)
        override fun existingSize(): Long = node?.size ?: 0L
        override fun open(append: Boolean): java.io.OutputStream {
            val target = node ?: folder.createFile(name).also { node = it }
            return target.openWrite(append)
        }
        override fun freeSpace(): Long? = folder.freeSpace()
    }

    private suspend fun run(id: String, resume: Boolean) {
        val cancel = cancels.getOrPut(id) { AtomicBoolean(false) }
        try {
            semaphore.withPermit {
                if (cancel.get()) {
                    if (!suspending) update(id, true) { it.copy(status = JobStatus.CANCELLED) }
                    return
                }
                val job = jobs[id] ?: return
                val folder = withContext(Dispatchers.IO) { folderOf(job) }
                    ?: throw HttpError("The download folder is not available. Check the ROM folder in Settings.")
                update(id, true) { it.copy(status = JobStatus.DOWNLOADING, error = null) }
                var url = job.url
                val resolve = resolver
                if (job.resolveLate && resolve != null) {
                    url = resolve(job) { text -> update(id) { it.copy(note = text) } }
                    update(id, true) { it.copy(note = null) }
                }
                val sink = NodeSink(folder, job.partName)
                val resumedFrom = if (resume) sink.existingSize() else 0L
                update(id) { it.copy(resumedFrom = resumedFrom) }
                val started = System.nanoTime()
                var last = System.nanoTime()
                var lastBytes = resumedFrom
                var lastPublish = 0L
                var lastError: String? = null
                for (attempt in 0 until 3) {
                    try {
                        http.download(url, sink, job.headers, resume = resume || attempt > 0, cancel = cancel) { received, total ->
                            val now = System.nanoTime()
                            val elapsed = (now - last) / 1e9
                            val speed = if (elapsed >= 0.5) (received - lastBytes) / elapsed else null
                            if (speed != null) { last = now; lastBytes = received }
                            if (now - lastPublish > 300_000_000L || received == total) {
                                lastPublish = now
                                update(id) { j -> j.copy(received = received, total = if (total > 0) total else j.total, speed = speed ?: j.speed) }
                            }
                        }
                        lastError = null
                        break
                    } catch (e: HttpError) {
                        lastError = e.message
                        if (cancel.get()) throw e
                        if (!e.retry) break
                        if (resume && e.status in setOf(401, 403, 404, 410)) {
                            lastError = "The download link has expired. Start it again from the game page."
                            break
                        }
                        if (attempt < 2) delay(1000L * (attempt + 1))
                    }
                }
                if (lastError != null) throw HttpError(lastError)
                val transferSeconds = (System.nanoTime() - started) / 1e9
                if (cancel.get()) {
                    if (suspending) { update(id, true) { it.copy(status = JobStatus.QUEUED, speed = 0.0) }; return }
                    removePart(job)
                    update(id, true) { it.copy(status = JobStatus.CANCELLED, finished = System.currentTimeMillis()) }
                    return
                }
                // Never overwrite an existing game: pick a free name.
                val wanted = Html.safeFilename(job.filename.ifEmpty { "${job.title}.zip" })
                val finalNode = withContext(Dispatchers.IO) {
                    val part = sink.node ?: folder.child(job.partName) ?: throw HttpError("The downloaded file is gone")
                    val taken = folder.list().map { it.name }.toSet()
                    part.rename(freeName(wanted, taken)) ?: throw HttpError("Could not rename the downloaded file")
                }
                update(id, true) { it.copy(path = finalNode.name, received = finalNode.size, total = if (it.total > 0) it.total else finalNode.size, speed = 0.0) }
                var current = jobs[id] ?: return
                if (current.extract && Unpack.archiveExt(finalNode.name) != null) {
                    update(id, true) { it.copy(status = JobStatus.EXTRACTING, received = 0) }
                    try {
                        val produced = runInterruptible(Dispatchers.IO) {
                            Unpack.unpack(
                                finalNode, folder, scratch, { cancel.get() },
                                onProgress = { written ->
                                    val now = System.nanoTime()
                                    if (now - lastPublish > 400_000_000L) {
                                        lastPublish = now
                                        update(id) { j -> j.copy(received = written) }
                                    }
                                },
                                route = { name -> router?.invoke(current, folder, name) ?: folder },
                            )
                        }
                        var main = Unpack.mainFile(produced)
                        // One game file from the archive: it gets the clean name too (never over another file).
                        val clean = current.cleanBase
                        if (clean != null && produced.size == 1 && main != null) {
                            val wantedName = io.github.uposum.romstore.core.model.FileNames.rename(main.name, clean, current.system)
                            if (wantedName != main.name) {
                                val parent = runCatching { router?.invoke(current, folder, main.name) ?: folder }.getOrDefault(folder)
                                val taken = runCatching { parent.list().map { it.name }.toSet() }.getOrDefault(emptySet())
                                if (wantedName !in taken) withContext(Dispatchers.IO) { main?.rename(wantedName) }?.let { main = it }
                            }
                        }
                        update(id, true) { it.copy(extractedTo = main?.name) }
                        if (main != null && !current.keepArchive) {
                            withContext(Dispatchers.IO) { finalNode.delete() }
                            update(id, true) { it.copy(path = main.name) }
                        }
                    } catch (e: Unpack.UnpackError) {
                        update(id, true) { it.copy(error = "Downloaded, but not unpacked: ${e.message}") }
                    }
                }
                update(id, true) { it.copy(status = JobStatus.DONE, finished = System.currentTimeMillis(), speed = 0.0) }
                current = jobs[id] ?: return
                runCatching { onMeasure?.invoke(current, maxOf(0L, current.received - current.resumedFrom), transferSeconds) }
                runCatching { onDone?.invoke(current) }
            }
        } catch (e: CancellationException) {
            if (suspending && jobs[id]?.status != JobStatus.CANCELLED) {
                update(id, true) { it.copy(status = JobStatus.QUEUED, speed = 0.0) }
            } else if (jobs[id]?.status != JobStatus.CANCELLED) {
                update(id, true) { it.copy(status = JobStatus.CANCELLED, finished = System.currentTimeMillis(), speed = 0.0) }
            }
            throw e
        } catch (e: Exception) {
            if (suspending) {
                update(id, true) { it.copy(status = JobStatus.QUEUED, speed = 0.0) }
                return
            }
            if (jobs[id]?.status == JobStatus.CANCELLED) return
            val message = if (e is HttpError) e.message else "${e.javaClass.simpleName}: ${e.message}"
            update(id, true) { it.copy(status = JobStatus.ERROR, error = message, finished = System.currentTimeMillis(), speed = 0.0, note = null) }
            // The partial file stays so "Try again" can continue it.
        }
    }

    companion object {
        /** "Game.zip" -> "Game (2).zip" while `taken` has the name. */
        fun freeName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val stem = name.substringBeforeLast('.', name)
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            var i = 2
            while ("$stem ($i)$ext" in taken) i++
            return "$stem ($i)$ext"
        }
    }
}
