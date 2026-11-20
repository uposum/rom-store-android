package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.RomFile
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.sources.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * How fast each provider delivers, before you download anything
 * (backend/speedtest.py). For every enabled source, one after the other:
 * search a common title, take a game with a real file, ask for its link the
 * way a download does, read a few seconds of it and throw it away. No site is
 * asked for more than two links per test.
 */
object SpeedTestRules {
    val TERMS = listOf("final fantasy", "mario", "zelda", "doom")
    const val SAMPLE_BYTES = 8L * 1024 * 1024
    const val SAMPLE_SECONDS = 6.0
    const val MIN_SAMPLE = 1024L * 1024
    val SKIP_SYSTEMS = setOf("mame", "arcades")
    val BIG_SYSTEMS = setOf(
        "psx", "ps2", "ps3", "psp", "psvita", "gc", "wii", "wiiu", "switch", "nds", "3ds",
        "dreamcast", "saturn", "segacd", "pcenginecd", "neogeocd", "3do", "n64",
    )

    fun order(entries: List<RomEntry>): List<RomEntry> =
        entries.filter { it.system !in SKIP_SYSTEMS }
            .sortedWith(compareBy({ it.system !in BIG_SYSTEMS }, { -(it.size ?: 0L) }))
}

data class SpeedResult(val ok: Boolean, val speed: Double = 0.0, val delay: Double = 0.0, val bytes: Long = 0, val seconds: Double = 0.0, val game: String? = null, val error: String? = null)

data class SpeedTestStatus(val running: Boolean = false, val current: String? = null, val done: List<String> = emptyList(), val total: Int = 0)

class SpeedTest(private val scope: CoroutineScope) {
    private val _status = MutableStateFlow(SpeedTestStatus())
    val status: StateFlow<SpeedTestStatus> = _status.asStateFlow()
    private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    private suspend fun picks(source: Source, count: Int = 2): List<Pair<RomEntry, RomFile>> {
        val found = mutableListOf<Pair<RomEntry, RomFile>>()
        val seen = HashSet<String>()
        var last = "no game found to test with"
        for (term in SpeedTestRules.TERMS) {
            val entries = try { source.search(term, limit = 12) } catch (e: Exception) { last = "search failed: ${e.message}"; continue }
            for (entry in SpeedTestRules.order(entries).take(3)) {
                if (!seen.add(entry.key)) continue
                val all = try { entry.files.ifEmpty { source.resolve(entry) } } catch (e: Exception) { last = "could not open a game page: ${e.message}"; continue }
                // Never spend a host's guest download (1fichier) or open one that needs a browser.
                val files = all.filter { !io.github.uposum.romstore.core.net.FileHosts.needsBrowser(it.url) && !io.github.uposum.romstore.core.net.FileHosts.resolvesLate(it.url) }
                if (files.isEmpty() && all.isNotEmpty()) { last = "its files are on hosts with a wait or a browser check"; continue }
                if (files.isNotEmpty()) {
                    found += entry to files.maxBy { it.size ?: entry.size ?: 0L }
                    if (found.size >= count) return found
                } else {
                    last = "no download link on the game pages"
                }
            }
        }
        if (found.isNotEmpty()) return found.sortedBy { it.first.system !in SpeedTestRules.BIG_SYSTEMS }
        throw IllegalStateException(last)
    }

    suspend fun testSource(source: Source, http: HttpClient): SpeedResult {
        val candidates = try { picks(source, 2) } catch (e: Exception) { return SpeedResult(false, error = e.message ?: e.javaClass.simpleName) }
        var error = "no file could be read"
        var best: SpeedResult? = null
        for ((entry, file) in candidates) {
            val result = try {
                val asked = System.nanoTime()
                val url = source.resolveDownload(file)
                val handedOut = (System.nanoTime() - asked) / 1e9
                val s = http.sample(url, maxBytes = SpeedTestRules.SAMPLE_BYTES, maxSeconds = SpeedTestRules.SAMPLE_SECONDS)
                handedOut to s
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                continue
            }
            val (handedOut, s) = result
            if (s.bytes <= 0) { error = "the file was empty"; continue }
            val measured = SpeedResult(true, s.bytes / s.seconds, handedOut + s.firstByte, s.bytes.toLong(), s.seconds, entry.title)
            if (best == null || measured.bytes > best.bytes) best = measured
            if (s.bytes >= SpeedTestRules.MIN_SAMPLE || s.seconds >= SpeedTestRules.SAMPLE_SECONDS * 0.9) break
        }
        return best ?: SpeedResult(false, error = error)
    }

    /** Measure these sources one after the other; false when a test is already running. */
    fun start(sources: List<Source>, http: HttpClient, record: (String, SpeedResult) -> Unit): Boolean {
        if (running || sources.isEmpty()) return false
        _status.value = SpeedTestStatus(true, null, emptyList(), sources.size)
        job = scope.launch {
            val done = mutableListOf<String>()
            try {
                for (source in sources) {
                    _status.value = SpeedTestStatus(true, source.id, done.toList(), sources.size)
                    record(source.id, testSource(source, http))
                    done += source.id
                }
            } finally {
                _status.value = SpeedTestStatus(false, null, done.toList(), sources.size)
            }
        }
        return true
    }
}
