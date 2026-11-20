package io.github.uposum.romstore.core.store

import com.github.junrar.Archive
import io.github.uposum.romstore.core.net.Html
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacking downloaded archives where the emulator needs it (backend/unpack.py).
 *
 * The sites publish many games as .zip or .7z. Cartridge emulators mostly open
 * those directly, disc emulators do not (DuckStation, AetherSX2/NetherSX2,
 * Dolphin, Vita3K, Citra/Azahar, the Switch emulators). Folders inside the
 * archive are flattened into the console folder and nothing existing is
 * overwritten (the provider picks a free name).
 */
object Unpack {
    val ARCHIVES = listOf(".zip", ".7z", ".rar")

    /** Consoles whose emulators read disc images, not archives. */
    val DISC_SYSTEMS = setOf(
        "psp", "psx", "ps2", "ps3", "psvita", "gc", "wii", "wiiu", "switch", "3ds", "dreamcast", "saturn", "segacd",
        "pcenginecd", "neogeocd", "3do", "xbox", "xbox360", "pcfx",
    )

    fun archiveExt(name: String?): String? {
        val lower = (name ?: "").lowercase()
        return ARCHIVES.firstOrNull { lower.endsWith(it) }
    }

    /** True when this archive has to be unpacked for the console's emulator. */
    fun needsUnpack(system: String, filename: String): Boolean = archiveExt(filename) != null && system in DISC_SYSTEMS

    class UnpackError(message: String) : Exception(message)

    /**
     * Extract every file of `archive` into `destination` (flat). Returns the
     * new files. `scratch` is a folder for a temporary copy (RAR needs one).
     * `onProgress` gets the bytes written so far.
     */
    fun unpack(
        archive: FileNode,
        destination: FolderNode,
        scratch: File?,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
        /** Where a file goes by its name (updates and DLC into their folders). */
        route: (String) -> FolderNode = { destination },
    ): List<FileNode> {
        val produced = mutableListOf<FileNode>()
        var written = 0L
        fun copyEntry(name: String, input: InputStream) {
            val clean = Html.safeFilename(name.replace('\\', '/').substringAfterLast('/'), "")
            if (clean.isEmpty()) return
            val node = route(clean).createFile(clean)
            produced += node
            node.openWrite(append = false).use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    if (cancelled()) throw UnpackError("Cancelled")
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    written += r
                    onProgress(written)
                }
            }
        }
        try {
            when (archiveExt(archive.name) ?: sniff(archive)) {
                ".zip" -> ZipInputStream(archive.openRead().buffered(1 shl 16)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) copyEntry(entry.name, zip)
                    }
                }
                ".7z" -> archive.openSeekable().use { channel ->
                    SevenZFile.builder().setSeekableByteChannel(channel).get().use { sz ->
                        while (true) {
                            val entry = sz.nextEntry ?: break
                            if (entry.isDirectory || entry.isAntiItem) continue
                            copyEntry(entry.name, sz.getInputStream(entry))
                        }
                    }
                }
                ".rar" -> {
                    val dir = scratch ?: throw UnpackError("No room for a temporary copy of the archive")
                    dir.mkdirs()
                    val temp = File.createTempFile("unpack", ".rar", dir)
                    try {
                        archive.openRead().use { input -> temp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                        Archive(temp).use { rar ->
                            for (header in rar.fileHeaders) {
                                if (header.isDirectory) continue
                                rar.getInputStream(header).use { copyEntry(header.fileName, it) }
                            }
                        }
                    } finally {
                        temp.delete()
                    }
                }
                else -> throw UnpackError("Not an archive this app can open")
            }
        } catch (e: UnpackError) {
            produced.forEach { runCatching { it.delete() } }
            throw e
        } catch (e: Exception) {
            produced.forEach { runCatching { it.delete() } }
            throw UnpackError("Could not unpack the archive: ${e.message ?: e.javaClass.simpleName}")
        }
        if (produced.isEmpty()) throw UnpackError("The archive is empty")
        return produced
    }

    private fun sniff(node: FileNode): String? {
        val head = ByteArray(8)
        val n = node.openRead().use { it.read(head) }
        if (n < 4) return null
        return when {
            head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> ".zip"
            head[0] == '7'.code.toByte() && head[1] == 'z'.code.toByte() -> ".7z"
            head[0] == 'R'.code.toByte() && head[1] == 'a'.code.toByte() && head[2] == 'r'.code.toByte() -> ".rar"
            else -> null
        }
    }

    /** What to launch among unpacked files: a playlist, cue or the biggest. */
    fun mainFile(files: List<FileNode>): FileNode? {
        for (ext in listOf(".m3u", ".cue", ".gdi")) files.firstOrNull { it.name.lowercase().endsWith(ext) }?.let { return it }
        return files.maxByOrNull { it.size }
    }
}
