package io.github.uposum.romstore.core.store

import io.github.uposum.romstore.core.model.LibraryFile
import io.github.uposum.romstore.core.model.LibraryAddon
import io.github.uposum.romstore.core.model.ContentKind
import io.github.uposum.romstore.core.model.Content
import io.github.uposum.romstore.core.model.LibraryGame
import io.github.uposum.romstore.core.model.matchTitle
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * What is in the ROM library: the "In library" badge, the list of installed
 * games, and uninstalling them (backend/library.py).
 */
object LibraryScan {
    // Not games: frontends' notes in every console folder, saves, artwork, configs.
    private val SKIP_NAMES = setOf("systeminfo.txt", "metadata.txt", "readme.txt", "_info.txt", "desktop.ini", "thumbs.db", ".gitkeep", ".nomedia")
    private val SKIP_EXTENSIONS = setOf(
        "txt", "md", "nfo", "xml", "json", "ini", "cfg", "db", "dat", "log",
        "png", "jpg", "jpeg", "webp", "gif", "mp4",
        "srm", "sav", "state", "sta", "mcr", "dsv", "eep", "fla", "sra", "rtc",
        "part", "tmp",
    )
    private val SKIP_FOLDERS = setOf("media", "images", "videos", "manuals", "screenshots", "saves", "states", "snap", "downloaded_images")

    // Emulator storage that can sit inside console folders.
    private val EMULATOR_FOLDERS = setOf(
        "installedgames", "mlc01", "roms", "nand", "sys", "usr", "sdcard", "ux0",
        "bios", "firmware", "keys", "shaders", "shadercache", "cache", "content",
        "update", "updates", "dlc", "patches", "textures", "cheats", "mods",
        "config", "configs", "logs", "temp", "tmp",
    )

    /** No game is smaller, all its files together. */
    const val MIN_GAME_BYTES = 1024L

    /** A file name that can belong to a game (not a note, save or picture). */
    fun counts(name: String): Boolean {
        val lower = name.lowercase()
        val ext = if ('.' in lower) lower.substringAfterLast('.') else ""
        return !(lower.startsWith(".") || lower in SKIP_NAMES || ext in SKIP_EXTENSIONS)
    }

    private fun hasGameData(folder: FolderNode, depth: Int = 0): Boolean {
        var total = 0L
        for (node in folder.list()) {
            if (node.name.startsWith(".")) continue
            if (node.isDirectory) {
                if (depth < 4) folder.asFolder(node)?.let { if (hasGameData(it, depth + 1)) return true }
                continue
            }
            if (!counts(node.name)) continue
            total += node.size
            if (total >= MIN_GAME_BYTES) return true
        }
        return false
    }

    private fun isGame(parent: FolderNode, node: FileNode): Boolean {
        val name = node.name.lowercase()
        if (!counts(name)) return false
        if (node.isDirectory) {
            if (name in SKIP_FOLDERS || name in EMULATOR_FOLDERS) return false
            return parent.asFolder(node)?.let { hasGameData(it) } ?: false
        }
        return true
    }

    fun sizeOf(parent: FolderNode, node: FileNode, depth: Int = 0): Long {
        if (!node.isDirectory) return node.size
        val folder = parent.asFolder(node) ?: return 0
        if (depth > 6) return 0
        return folder.list().sumOf { sizeOf(folder, it, depth + 1) }
    }

    fun gameId(system: String, key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$system|$key".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Every game in the console folders of a library, newest first. Files
     * that belong to one game share a title and are listed together. */
    fun installedGames(library: RomLibrary): List<LibraryGame> {
        val games = mutableListOf<LibraryGame>()
        for ((system, folder) in library.consoleFolders()) {
            val grouped = LinkedHashMap<String, MutableList<FileNode>>()
            val children = try { folder.list() } catch (_: Exception) { continue }
            val addonSystem = system in Content.ADDON_SYSTEMS
            // Updates and DLC: in updates/ and dlc/, or loose next to the games.
            val addons = mutableListOf<Pair<FileNode, LibraryAddon>>()
            for (node in children) {
                if (addonSystem && node.isDirectory && node.name.lowercase() in ADDON_FOLDERS) {
                    val sub = folder.asFolder(node) ?: continue
                    val fallback = if (node.name.lowercase().startsWith("dlc")) ContentKind.DLC else ContentKind.UPDATE
                    for (file in runCatching { sub.list() }.getOrDefault(emptyList())) {
                        if (file.isDirectory || !counts(file.name)) continue
                        val info = Content.classify(file.name)
                        val kind = if (info.isAddon) info.kind else fallback
                        addons += file to LibraryAddon(file.name, file.size, kind, info.version, file.id, node.name)
                    }
                    continue
                }
                if (!isGame(folder, node)) continue
                if (addonSystem && !node.isDirectory) {
                    val info = Content.classify(node.name)
                    if (info.isAddon) {
                        addons += node to LibraryAddon(node.name, node.size, info.kind, info.version, node.id, null)
                        continue
                    }
                }
                val key = matchTitle(node.name).ifEmpty { node.name.lowercase() }
                grouped.getOrPut(key) { mutableListOf() }.add(node)
            }
            val folderGames = mutableListOf<LibraryGame>()
            for ((key, nodes) in grouped) {
                nodes.sortWith(compareBy({ it.name.length }, { it.name }))
                val files = nodes.map { LibraryFile(it.name, sizeOf(folder, it), it.isDirectory, it.id) }
                val size = files.sumOf { it.size }
                if (size < MIN_GAME_BYTES) continue
                val first = nodes.first()
                val title = if (first.isDirectory) first.name else first.name.substringBeforeLast('.', first.name)
                folderGames += LibraryGame(
                    id = gameId(system, key),
                    system = system,
                    title = title,
                    folder = folder.id,
                    files = files,
                    size = size,
                    modified = nodes.maxOf { it.modified },
                )
            }
            // Each add-on joins its game: the same Switch title, else the same name.
            val byGame = HashMap<String, MutableList<LibraryAddon>>()
            for ((node, addon) in addons) {
                val tid = Content.titleId(addon.name)?.let { Content.baseTitleId(it) }
                val game = folderGames.firstOrNull { g -> tid != null && g.files.any { f -> Content.titleId(f.name)?.let { Content.baseTitleId(it) } == tid } }
                    ?: Content.baseKey(addon.name.substringBeforeLast('.')).let { key ->
                        folderGames.firstOrNull { g -> Content.baseKey(g.title) == key }
                            // Sites shorten names ("Kirby Update" for "Kirby and the Forgotten Land"): one game that starts with it.
                            ?: folderGames.filter { g -> key.length >= 4 && Content.baseKey(g.title).startsWith(key) }.singleOrNull()
                    }
                if (game != null) {
                    byGame.getOrPut(game.id) { mutableListOf() } += addon
                } else if (addon.subfolder == null) {
                    // A loose add-on without its game stays visible (so it can be deleted).
                    folderGames += LibraryGame(
                        id = gameId(system, "addon:" + addon.name), system = system,
                        title = node.name.substringBeforeLast('.', node.name), folder = folder.id,
                        files = listOf(LibraryFile(node.name, node.size, false, node.id)),
                        size = node.size, modified = node.modified,
                    )
                }
            }
            games += folderGames.map { g ->
                val own = byGame[g.id] ?: return@map g
                g.copy(addons = own.sortedWith(compareBy({ it.kind.ordinal }, { it.name })), size = g.size + own.sumOf { it.size })
            }
        }
        games.sortByDescending { it.modified }
        return games
    }

    private val ADDON_FOLDERS = setOf("updates", "update", "dlc", "dlcs")

    data class UninstallResult(val ok: Boolean, val removed: Int = 0, val freed: Long = 0, val title: String? = null, val error: String? = null)

    /** Delete one installed game's files. The game is looked up again by id,
     * and only entries directly inside its console folder are removed. */
    fun uninstall(library: RomLibrary, gameId: String): UninstallResult {
        val game = installedGames(library).firstOrNull { it.id == gameId }
            ?: return UninstallResult(false, error = "That game is no longer in the library.")
        val folder = library.folderById(game.folder) ?: return UninstallResult(false, error = "The console folder is gone.")
        val children = folder.list().associateBy { it.name }
        var removed = 0
        var freed = 0L
        val errors = mutableListOf<String>()
        for (file in game.files) {
            val node = children[file.name]
            if (node == null) {
                errors += "${file.name}: not found"
                continue
            }
            if (node.delete()) {
                removed++
                freed += file.size
            } else {
                errors += "${file.name}: could not be deleted"
            }
        }
        // Its updates and DLC are of no use without it.
        for (addon in game.addons) {
            val parent = addon.subfolder?.let { sub -> children[sub]?.let { folder.asFolder(it) } } ?: folder
            val node = runCatching { parent.child(addon.name) }.getOrNull()
            if (node != null && node.delete()) { removed++; freed += addon.size } else errors += "${addon.name}: could not be deleted"
        }
        return UninstallResult(errors.isEmpty(), removed, freed, game.title, errors.joinToString("; ").ifEmpty { null })
    }
}

/**
 * Matching keys of the files in each console folder. A folder is only listed
 * again when its modification time changes, so marking a page of results
 * costs one lookup per console.
 */
class LibraryIndex {
    private val folders = ConcurrentHashMap<String, Pair<Long, Set<String>>>()

    fun keys(folder: FolderNode?): Set<String> {
        folder ?: return emptySet()
        val mtime = folder.modified
        folders[folder.id]?.let { if (it.first == mtime && mtime != 0L) return it.second }
        val keys = try {
            folder.list()
                .filterNot { it.name.startsWith(".") || it.name.endsWith(".part") }
                .mapNotNull { matchTitle(it.name).ifEmpty { null } }
                .toSet()
        } catch (_: Exception) {
            return emptySet()
        }
        folders[folder.id] = mtime to keys
        return keys
    }

    fun contains(folder: FolderNode?, title: String): Boolean {
        val key = matchTitle(title)
        return key.isNotEmpty() && key in keys(folder)
    }

    fun clear() = folders.clear()
}
