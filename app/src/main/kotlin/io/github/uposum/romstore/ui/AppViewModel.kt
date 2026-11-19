package io.github.uposum.romstore.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.uposum.romstore.RomStoreApp
import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.MergedRom
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.store.DownloadOption
import io.github.uposum.romstore.core.store.Regions
import io.github.uposum.romstore.core.store.ShelfItem
import io.github.uposum.romstore.core.store.SourceInfo
import io.github.uposum.romstore.core.store.StartResult
import io.github.uposum.romstore.core.store.StoreSettings
import io.github.uposum.romstore.storage.SafLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Tab(val label: String) { Store("Store"), Library("Library"), Downloads("Downloads"), Settings("Settings") }

/** What the Store tab shows when it is not the home screen. */
data class Results(
    val query: String = "",
    /** A console page (browse), or the console filter of a search. */
    val system: String? = null,
    val browse: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val all: List<MergedRom> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val region: String = "all",
    val source: String = "all",
    val sort: String = "relevance",
    val systems: List<String> = emptyList(),
) {
    /** The results after the region and source filters. */
    val shown: List<MergedRom>
        get() = all.filter { Regions.includes(it, region) && (source == "all" || source in it.sources) }
}

/** The full-screen game page. */
data class GamePage(
    val rom: MergedRom,
    /** The list it was opened from, for previous/next (L1/R1). */
    val list: List<MergedRom> = listOf(rom),
    val index: Int = 0,
    val loading: Boolean = true,
    val options: List<DownloadOption> = emptyList(),
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as RomStoreApp
    val store = appRef.store
    private val library: SafLibrary = appRef.library

    val settings: StateFlow<StoreSettings> = store.settings.state
    val shelves = store.shelves.state
    val downloads = store.downloads.state
    val speedTest = store.speedTest.status

    private val _tab = MutableStateFlow(Tab.Store)
    val tab = _tab.asStateFlow()

    private val _results = MutableStateFlow<Results?>(null)
    val results = _results.asStateFlow()

    private val _game = MutableStateFlow<GamePage?>(null)
    val game = _game.asStateFlow()

    private val _consoles = MutableStateFlow<List<String>>(emptyList())
    val consoles = _consoles.asStateFlow()

    private val _installed = MutableStateFlow<List<LibraryGame>>(emptyList())
    val installed = _installed.asStateFlow()
    private val _libraryLoading = MutableStateFlow(false)
    val libraryLoading = _libraryLoading.asStateFlow()

    private val _libraryGame = MutableStateFlow<LibraryGame?>(null)
    val libraryGame = _libraryGame.asStateFlow()

    private val _sources = MutableStateFlow<List<SourceInfo>>(emptyList())
    val sources = _sources.asStateFlow()

    /** The cover that has focus in the results (named in the bar at the bottom). */
    val focused = MutableStateFlow<MergedRom?>(null)

    /** The query typed in the search field (also when it was not run yet). */
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    /** Ask the shell to put the cursor in the search field. */
    val focusSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** One-line messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Download job ids started from a game page, by game. */
    private val _jobsByGame = MutableStateFlow<Map<String, String>>(emptyMap())
    val jobsByGame = _jobsByGame.asStateFlow()

    private var searchJob: Job? = null
    private var gameJob: Job? = null

    init {
        refreshConsoles()
        refreshSources()
        refreshLibrary()
    }

    fun gameKey(rom: MergedRom) = "${rom.system}|${matchTitle(rom.title)}"

    // ------------------------------------------------------------------ tabs

    fun setTab(tab: Tab) {
        _tab.value = tab
        when (tab) {
            Tab.Library -> refreshLibrary()
            Tab.Settings -> refreshSources()
            else -> {}
        }
    }

    fun stepTab(delta: Int) {
        val all = Tab.entries
        setTab(all[(all.indexOf(_tab.value) + delta + all.size) % all.size])
    }

    // ---------------------------------------------------------------- search

    fun setQuery(text: String) { _query.value = text }

    fun search(text: String = _query.value, system: String? = _results.value?.system?.takeIf { !(_results.value?.browse ?: false) }) {
        val q = text.trim()
        _query.value = q
        if (q.isEmpty()) { goHome(); return }
        val previous = _results.value
        _results.value = Results(
            query = q, system = system, loading = true,
            region = previous?.region ?: "all", source = previous?.source ?: "all", sort = previous?.sort ?: "relevance",
        )
        _tab.value = Tab.Store
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val sort = _results.value?.sort ?: "relevance"
            val result = runCatching { store.search(q, system = system, sort = sort) }
            result.onSuccess { r ->
                _results.update { it?.copy(loading = false, all = r.results, page = r.page, hasMore = r.hasMore, errors = r.errors, systems = r.systems) }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@launch
                android.util.Log.e("RomStore", "search failed", e)
                _results.update { it?.copy(loading = false, errors = mapOf("store" to (e.message ?: "Search failed"))) }
            }
        }
    }

    fun loadMore() {
        val current = _results.value ?: return
        if (current.browse || !current.hasMore || current.loadingMore || current.loading) return
        _results.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            val r = runCatching { store.search(current.query, system = current.system, sort = current.sort, page = current.page + 1) }.getOrNull()
            _results.update { now ->
                if (now == null || now.query != current.query) return@update now
                if (r == null) now.copy(loadingMore = false, hasMore = false)
                else {
                    val known = now.all.map { gameKey(it) }.toSet()
                    now.copy(loadingMore = false, all = now.all + r.results.filter { gameKey(it) !in known }, page = r.page, hasMore = r.hasMore)
                }
            }
        }
    }

    fun browse(system: String) {
        _query.value = ""
        _tab.value = Tab.Store
        _results.value = Results(system = system, browse = true, loading = true)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val r = runCatching { store.browse(system) }
            r.onSuccess { res -> _results.update { it?.copy(loading = false, all = res.results, errors = res.errors) } }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _results.update { it?.copy(loading = false, errors = mapOf("store" to (e.message ?: "Failed"))) } }
        }
    }

    fun goHome() {
        searchJob?.cancel()
        _results.value = null
        _query.value = ""
        refreshConsoles()
    }

    fun setConsoleFilter(system: String?) {
        val r = _results.value ?: return
        if (r.browse) { if (system != null) browse(system) else goHome(); return }
        search(r.query, system)
    }

    fun setRegion(region: String) = _results.update { it?.copy(region = region) }
    fun setSource(source: String) = _results.update { it?.copy(source = source) }

    fun setSort(sort: String) {
        val r = _results.value ?: return
        _results.value = r.copy(sort = sort, all = io.github.uposum.romstore.core.model.Dedupe.sort(r.all, sort).let { if (sort == "relevance") r.all else it })
        if (!r.browse && sort == "relevance") search(r.query, r.system)
    }

    fun refreshConsoles() {
        viewModelScope.launch { _consoles.value = runCatching { store.consoles() }.getOrDefault(emptyList()) }
    }

    // ------------------------------------------------------------- game page

    fun openGame(rom: MergedRom, list: List<MergedRom> = listOf(rom)) {
        val index = list.indexOfFirst { it.id == rom.id }.coerceAtLeast(0)
        showGame(GamePage(rom, list.ifEmpty { listOf(rom) }, index))
    }

    private fun showGame(page: GamePage) {
        _game.value = page
        val rom = page.rom
        store.shelves.addRecent(rom.title, rom.system, rom.cover)
        gameJob?.cancel()
        if (rom.id.startsWith("stub:")) return
        gameJob = viewModelScope.launch {
            val detailed = runCatching { store.details(rom) }
            _game.update { now ->
                if (now == null || now.rom.id != rom.id) return@update now
                detailed.fold(
                    onSuccess = { d ->
                        val options = store.downloadOptions(d)
                        now.copy(rom = d, loading = false, options = options, error = if (options.isEmpty()) d.entries.firstNotNullOfOrNull { it.error } ?: "No download link found" else null)
                    },
                    onFailure = { e -> now.copy(loading = false, error = e.message ?: "Could not load the game") },
                )
            }
        }
    }

    fun stepGame(delta: Int) {
        val page = _game.value ?: return
        if (page.list.size < 2) return
        val next = (page.index + delta + page.list.size) % page.list.size
        showGame(GamePage(page.list[next], page.list, next))
    }

    fun closeGame() {
        gameJob?.cancel()
        _game.value = null
    }

    /** A shelf tile opens at once from what the shelf knows; the links are looked up meanwhile. */
    fun openShelfItem(item: ShelfItem) {
        val stub = MergedRom(id = "stub:${item.key}", title = item.title, system = item.system, media = io.github.uposum.romstore.core.model.GameMedia(cover = item.cover))
        _game.value = GamePage(stub)
        store.shelves.addRecent(item.title, item.system, item.cover)
        gameJob?.cancel()
        gameJob = viewModelScope.launch {
            val found = runCatching { store.search(item.title, system = item.system) }.getOrNull()
            val key = matchTitle(item.title)
            val rom = found?.results?.firstOrNull { matchTitle(it.title) == key && it.system == item.system }
                ?: found?.results?.firstOrNull { matchTitle(it.title) == key }
            if (rom == null) {
                _game.update { if (it?.rom?.id == stub.id) it.copy(loading = false, error = "Not found on your sources right now.") else it }
                return@launch
            }
            if (_game.value?.rom?.id == stub.id) showGame(GamePage(rom))
        }
    }

    fun download(option: DownloadOption) {
        val page = _game.value ?: return
        viewModelScope.launch {
            when (val r = store.startDownload(page.rom, option.entry, option.file)) {
                is StartResult.Started -> {
                    _jobsByGame.update { it + (gameKey(page.rom) to r.job.id) }
                    messages.tryEmit("Downloading ${r.job.title}")
                }
                is StartResult.Failed -> messages.tryEmit(r.error)
            }
        }
    }

    fun toggleFavorite(title: String, system: String, cover: String?) {
        val now = store.shelves.toggleFavorite(title, system, cover)
        messages.tryEmit(if (now) "Added to favourites" else "Removed from favourites")
    }

    // ---------------------------------------------------------------- library

    fun refreshLibrary() {
        viewModelScope.launch {
            _libraryLoading.value = true
            _installed.value = runCatching { store.installed() }.getOrDefault(emptyList())
            _libraryLoading.value = false
            _libraryGame.update { open -> open?.let { g -> _installed.value.firstOrNull { it.id == g.id } ?: g } }
        }
    }

    fun openLibraryGame(game: LibraryGame?) { _libraryGame.value = game }

    fun uninstall(game: LibraryGame) {
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.uninstall(game.id) }
            messages.tryEmit(if (r.ok) "Deleted ${game.name ?: game.title} (${io.github.uposum.romstore.formatBytes(r.freed)})" else (r.error ?: "Could not delete"))
            _libraryGame.value = null
            refreshLibrary()
        }
    }

    fun unpack(game: LibraryGame) {
        messages.tryEmit("Unpacking ${game.name ?: game.title}…")
        viewModelScope.launch {
            val r = store.unpackGame(game.id)
            messages.tryEmit(if (r.ok) "Unpacked ${game.name ?: game.title}" else (r.error ?: "Could not unpack"))
            refreshLibrary()
        }
    }

    /** "Open with…": hand the game's main file to an emulator. */
    fun openWith(game: LibraryGame): Intent? {
        val files = game.files.filterNot { it.folder }
        val main = files.firstOrNull { f -> listOf(".m3u", ".cue", ".gdi").any { f.name.lowercase().endsWith(it) } }
            ?: files.maxByOrNull { it.size } ?: return null
        val uri = Uri.parse(main.uri)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        return Intent.createChooser(view, "Open ${game.name ?: game.title} with")
    }

    /** Look an installed game up in the store. */
    fun storePage(game: LibraryGame) {
        _libraryGame.value = null
        search(game.name ?: game.title, system = game.system)
    }

    // -------------------------------------------------------------- downloads

    fun cancelDownload(id: String) = store.downloads.cancel(id)
    fun retryDownload(id: String) { store.downloads.retry(id); appRef.startDownloadService() }
    fun removeDownload(id: String) = store.downloads.remove(id)
    fun clearFinished() = store.downloads.clearFinished()

    // --------------------------------------------------------------- settings

    fun updateSettings(change: (StoreSettings) -> StoreSettings) {
        store.settings.update(change)
    }

    fun setLibraryFolder(uri: Uri) {
        updateSettings { it.copy(libraryUri = uri.toString()) }
        store.libraryIndex.clear()
        refreshLibrary()
        messages.tryEmit("ROMs go to ${SafLibrary.describe(uri.toString())}")
    }

    fun setConsoleFolder(system: String, uri: Uri?) {
        updateSettings { s -> s.copy(consoleFolders = if (uri == null) s.consoleFolders - system else s.consoleFolders + (system to uri.toString())) }
        store.libraryIndex.clear()
        refreshLibrary()
    }

    fun setSourceAddress(id: String, address: String) {
        updateSettings { s ->
            val enabled = if (address.isNotBlank() && id !in s.enabledSources) s.enabledSources + id else s.enabledSources
            s.copy(sourceAddresses = s.sourceAddresses + (id to address.trim()), enabledSources = enabled)
        }
        store.resetHealth(id)
        store.clearCache()
        refreshSources()
        refreshConsoles()
    }

    fun setSourceEnabled(id: String, on: Boolean) {
        updateSettings { s -> s.copy(enabledSources = if (on) (s.enabledSources + id).distinct() else s.enabledSources - id) }
        refreshSources()
        refreshConsoles()
    }

    fun refreshSources() { _sources.value = store.describeSources() }

    fun startSpeedTest() {
        if (!store.startSpeedTest()) messages.tryEmit("Set up a source first.")
        viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            store.speedTest.status.first { !it.running }
            refreshSources()
        }
    }

    fun clearCache() {
        val removed = store.clearCache()
        messages.tryEmit("Cleared $removed cached pages")
    }

    fun libraryDescription(): String? = library.description

    fun folderDescription(system: String): String? {
        val s = settings.value
        s.consoleFolders[system]?.let { return SafLibrary.describe(it) }
        val root = SafLibrary.describe(s.libraryUri) ?: return null
        val name = s.folderNames[system]?.takeIf { it.isNotBlank() } ?: io.github.uposum.romstore.core.model.Systems.defaultFolder(system)
        return "$root/$name"
    }
}
