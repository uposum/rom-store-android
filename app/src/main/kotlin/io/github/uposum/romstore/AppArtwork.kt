package io.github.uposum.romstore

import io.github.uposum.romstore.core.art.ArtKeys
import io.github.uposum.romstore.core.art.BoxArt
import io.github.uposum.romstore.core.art.GameTdb
import io.github.uposum.romstore.core.art.IgdbCovers
import io.github.uposum.romstore.core.art.IgdbInfo
import io.github.uposum.romstore.core.art.ImageUrls
import io.github.uposum.romstore.core.art.SteamGridCovers
import io.github.uposum.romstore.core.art.SwitchTitles
import io.github.uposum.romstore.core.art.YouTube
import io.github.uposum.romstore.core.art.cleanName
import io.github.uposum.romstore.core.art.switchTitleId
import io.github.uposum.romstore.core.model.GameMedia
import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.MergedRom
import io.github.uposum.romstore.core.model.RomEntry
import io.github.uposum.romstore.core.model.flag
import io.github.uposum.romstore.core.model.matchTitle
import io.github.uposum.romstore.core.model.putStr
import io.github.uposum.romstore.core.net.HttpClient
import io.github.uposum.romstore.core.store.Artwork
import io.github.uposum.romstore.core.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The cover rules of the plugin's service.py, on top of core.art:
 * libretro-thumbnails box scans first, GameTDB for its consoles, then the
 * site's cover, then SteamGridDB, then IGDB (which crops to 3:4, so it only
 * fills gaps unless the user asks for it).
 */
class AppArtwork(
    http: HttpClient,
    cacheDir: File,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : Artwork {
    private val boxart = BoxArt(http, cacheDir, scope)
    private val gametdb = GameTdb(http, cacheDir, scope)
    private val sgdb = SteamGridCovers(http, { ArtKeys.steamGridDbKey(settings.value.steamgriddbApiKey) }, cacheDir, scope)
    private val igdb = IgdbCovers(http, cacheDir = cacheDir)
    private val youtube = YouTube(http)
    private val switchTitles by lazy { SwitchTitles() }

    private val s get() = settings.value
    private val igdbOn get() = s.igdbEnabled && igdb.enabled

    override fun prefetch() {
        runCatching { gametdb.prefetch() }
    }

    override fun alternatives(url: String): List<String> = GameTdb.alternatives(url)

    override suspend fun applyBoxart(entries: List<RomEntry>) {
        if (!s.libretroBoxart) return
        runCatching { boxart.apply(entries, resize = s.boxartResize) }
        gametdb.proxy = s.boxartResize
        val rest = entries.filter { it.system in GameTdb.PLATFORMS && it.system != "switch" && !it.extra.flag("boxart") }
        if (rest.isNotEmpty()) runCatching { gametdb.apply(rest) }
    }

    override suspend fun switchCovers(entries: List<RomEntry>) {
        val switch = entries.filter { it.system == "switch" }
        if (switch.isEmpty()) return
        val before = switch.associateWith { it.cover }
        gametdb.proxy = s.boxartResize
        runCatching { gametdb.apply(switch) }
        var missing = mutableListOf<RomEntry>()
        for (entry in switch) {
            if (entry.extra.flag("gametdb")) continue
            entry.cover = before[entry]
            val cover = entry.cover
            if (cover != null && "static.romspedia.com" in cover) continue // resized box art
            if (cover != null) entry.extra.putStr("banner", cover)
            entry.cover = null
            entry.coverFull = null
            missing += entry
        }
        if (missing.isNotEmpty()) {
            runCatching { sgdb.apply(missing) }
            missing = missing.filter { it.cover == null }.toMutableList()
        }
        if (missing.isEmpty() || !igdbOn) return
        val titles = missing.map { it.title }.distinct()
        val known = titles.associateWith { igdb.cached(it) }.toMutableMap()
        val wanted = known.filterValues { it == null }.keys.toList()
        if (wanted.isNotEmpty()) {
            val lookup = scope.async { igdb.lookupMany(wanted, limit = 14) }
            withTimeoutOrNull(3000) { lookup.await() }?.let { known.putAll(it) }
        }
        for (entry in missing) known[entry.title]?.cover?.let { entry.cover = it; entry.coverFull = it }
    }

    override suspend fun enrich(roms: List<MergedRom>) {
        runCatching { sgdb.apply(roms.flatMap { it.entries }) }
        if (!igdbOn) return
        val replace = s.igdbReplaceCovers
        val wanted = mutableListOf<String>()
        for (rom in roms) for (entry in rom.entries) {
            val info = igdb.cached(entry.title)
            if (info != null) { applyIgdb(entry, info, replace); continue }
            if ((replace || entry.cover == null) && entry.title !in wanted) wanted += entry.title
        }
        if (wanted.isEmpty()) return
        // The lookups are slow; whatever is not in after 3 s shows next time.
        val job = scope.async {
            val found = igdb.lookupMany(wanted.take(14), limit = 14)
            for (rom in roms) for (entry in rom.entries) found[entry.title]?.let { applyIgdb(entry, it, replace) }
        }
        withTimeoutOrNull(3000) { job.await() }
    }

    private fun applyIgdb(entry: RomEntry, info: IgdbInfo, replace: Boolean) {
        val url = info.cover
        if (url != null && (replace || entry.cover == null)) {
            entry.cover = url
            entry.coverFull = entry.coverFull ?: url
        }
        val rating = info.rating
        if (rating != null && rating > 0 && entry.rating == null) {
            entry.rating = rating
            entry.ratingCount = info.ratingCount ?: entry.ratingCount
        }
    }

    override suspend fun fillMissing(entries: List<RomEntry>) {
        runCatching { sgdb.apply(entries) }
    }

    override suspend fun library(games: List<LibraryGame>, knownCovers: Map<String, String>) {
        if (s.libretroBoxart) {
            runCatching { boxart.covers(games, resize = s.boxartResize) }
            val rest = games.filter { it.system in GameTdb.PLATFORMS && it.cover == null }
            if (rest.isNotEmpty()) runCatching { gametdb.covers(rest) }
        }
        val tag = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
        for (game in games.filter { it.cover == null }) {
            val bare = game.title.replace(tag, "").trim()
            knownCovers["${game.system}|${matchTitle(bare)}"]?.let { game.cover = it }
        }
        var missing = games.filter { it.cover == null }
        if (missing.isNotEmpty()) {
            val stand = missing.mapIndexed { i, g -> RomEntry(source = "library", sourceId = "$i", title = g.title, system = g.system) }
            runCatching { sgdb.apply(stand, wait = 2.0) }
            missing.zip(stand).forEach { (g, e) -> if (e.cover != null) g.cover = e.cover }
            missing = missing.filter { it.cover == null }
        }
        if (missing.isNotEmpty() && igdbOn) {
            val lookups = LinkedHashMap<String, MutableList<LibraryGame>>()
            for (game in missing) {
                val bare = game.title.replace(tag, "").trim()
                val info = if (bare.isNotEmpty()) igdb.cached(bare) else null
                if (info?.cover != null) game.cover = info.cover
                else if (bare.isNotEmpty() && game.system !in setOf("arcades", "mame")) lookups.getOrPut(bare) { mutableListOf() } += game
            }
            if (lookups.isNotEmpty()) {
                val job = scope.async { igdb.lookupMany(lookups.keys.toList(), limit = 12) }
                withTimeoutOrNull(2000) { job.await() }?.forEach { (title, info) ->
                    info?.cover?.let { cover -> lookups[title]?.forEach { it.cover = cover } }
                }
            }
        }
        for (game in games) game.name = displayName(game)
    }

    /** The game's real name: the Switch title list, libretro or GameTDB, else the file name cleaned up. */
    private fun displayName(game: LibraryGame): String {
        val cleaned = cleanName(game.title)
        runCatching {
            if (game.system == "switch") {
                val main = game.files.filterNot { it.folder }.maxByOrNull { it.size }
                val tid = main?.let { switchTitleId(it.name, null) { title -> switchTitles.find(title) } }
                switchTitles.name(tid)?.let { return it }
            }
            for (candidate in listOf(game.title, cleaned)) {
                (boxart.name(candidate, game.system) ?: gametdb.name(candidate, game.system))?.let { return it }
            }
        }
        return cleaned
    }

    override suspend fun media(entry: RomEntry, own: GameMedia?): GameMedia? {
        var media = own ?: GameMedia()
        if (igdbOn) {
            val info = runCatching { igdb.lookup(entry.title) }.getOrNull()
            if (info != null) {
                val shots = (info.shots + media.shots).take(5)
                media = media.copy(
                    cover = info.cover ?: media.cover,
                    shots = shots,
                    trailer = info.trailer ?: media.trailer,
                    summary = info.summary ?: media.summary,
                    genres = info.genres.ifEmpty { media.genres },
                    developers = info.developers.ifEmpty { media.developers },
                    released = info.released?.let { SimpleDateFormat("yyyy", Locale.US).format(Date(it * 1000)) } ?: media.released,
                )
            }
        }
        if (media.trailer == null) {
            runCatching { youtube.trailer(entry.title) }.getOrNull()?.let { media = media.copy(trailer = it) }
        }
        if (media.shots.isEmpty() && media.trailer == null && media.cover == null && media.summary == null) return own
        return media.copy(shots = media.shots.map { it.copy(thumb = ImageUrls.bestVariant(it.thumb) ?: it.thumb) })
    }
}
