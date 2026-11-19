package io.github.uposum.romstore.core.store

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Files the app reads and writes in the user's ROM library. On Android this is
 * a folder the user picked through the storage access framework (content://
 * documents); the tests use plain files. Everything that touches the library —
 * downloads, unpacking, the installed-games list, uninstalling — goes through
 * these two interfaces.
 */
interface FileNode {
    /** Stable identifier (a document URI or an absolute path). */
    val id: String
    val name: String
    val isDirectory: Boolean
    val size: Long
    val modified: Long

    fun openRead(): InputStream
    fun openWrite(append: Boolean): OutputStream

    /** Random access, for 7z archives. */
    fun openSeekable(): SeekableByteChannel

    /** The renamed node, or null when the rename failed. The name may come
     * back changed if the provider had to make it unique. */
    fun rename(newName: String): FileNode?

    /** Delete this file, or this folder and everything in it. */
    fun delete(): Boolean
}

interface FolderNode : FileNode {
    fun list(): List<FileNode>
    fun child(name: String): FileNode? = list().firstOrNull { it.name == name }
    fun createFile(name: String): FileNode
    fun createFolder(name: String): FolderNode
    fun asFolder(node: FileNode): FolderNode?
    fun freeSpace(): Long?
}

/** The user's ROM library: one root with a folder per console, and
 * optionally a folder of the user's choice for some consoles. */
interface RomLibrary {
    /** Shown in the UI ("Internal storage/ROMs"). Null: no folder chosen. */
    val description: String?

    /** The folder a console's games go into; created when `create`. */
    fun consoleFolder(system: String, create: Boolean): FolderNode?

    /** Every console folder that exists, with its console key. */
    fun consoleFolders(): List<Pair<String, FolderNode>>

    /** A folder by its id, as recorded in a download job. */
    fun folderById(id: String): FolderNode?

    fun freeSpace(): Long?
}

// --------------------------------------------------------------- plain files

open class JavaFileNode(val file: File) : FileNode {
    override val id: String get() = file.absolutePath
    override val name: String get() = file.name
    override val isDirectory: Boolean get() = file.isDirectory
    override val size: Long get() = if (file.isFile) file.length() else 0L
    override val modified: Long get() = file.lastModified()
    override fun openRead(): InputStream = file.inputStream()
    override fun openWrite(append: Boolean): OutputStream = java.io.FileOutputStream(file, append)
    override fun openSeekable(): SeekableByteChannel = Files.newByteChannel(file.toPath(), StandardOpenOption.READ)
    override fun rename(newName: String): FileNode? {
        val target = File(file.parentFile, newName)
        if (target.exists()) return null
        return if (file.renameTo(target)) wrap(target) else null
    }

    override fun delete(): Boolean = if (file.isDirectory) file.deleteRecursively() else file.delete()

    companion object {
        fun wrap(f: File): FileNode = if (f.isDirectory) JavaFolderNode(f) else JavaFileNode(f)
    }
}

class JavaFolderNode(file: File) : JavaFileNode(file), FolderNode {
    override fun list(): List<FileNode> = file.listFiles()?.map { wrap(it) } ?: emptyList()
    override fun createFile(name: String): FileNode {
        val target = uniqueFile(file, name)
        target.createNewFile()
        return JavaFileNode(target)
    }

    override fun createFolder(name: String): FolderNode {
        val target = File(file, name)
        target.mkdirs()
        return JavaFolderNode(target)
    }

    override fun asFolder(node: FileNode): FolderNode? = (node as? JavaFileNode)?.file?.takeIf { it.isDirectory }?.let { JavaFolderNode(it) }
    override fun freeSpace(): Long? = file.usableSpace.takeIf { it > 0 }
}

/** "Game.zip" -> "Game (2).zip" while the name is taken. */
fun uniqueFile(dir: File, name: String): File {
    var target = File(dir, name)
    if (!target.exists()) return target
    val stem = name.substringBeforeLast('.', name)
    val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
    var index = 2
    while (true) {
        target = File(dir, "$stem ($index)$ext")
        if (!target.exists()) return target
        index++
    }
}

/** A library on plain files (tests, and a fallback). */
class JavaFileLibrary(private val root: File, private val overrides: Map<String, File> = emptyMap()) : RomLibrary {
    override val description: String get() = root.absolutePath

    override fun consoleFolder(system: String, create: Boolean): FolderNode? {
        overrides[system]?.let { if (create) it.mkdirs(); return if (it.isDirectory) JavaFolderNode(it) else null }
        val available = root.list()?.filter { File(root, it).isDirectory } ?: emptyList()
        val name = io.github.uposum.romstore.core.model.Systems.folderFor(system, available)
        val dir = File(root, name)
        if (create) dir.mkdirs()
        return if (dir.isDirectory) JavaFolderNode(dir) else null
    }

    override fun consoleFolders(): List<Pair<String, FolderNode>> {
        val available = root.list()?.filter { File(root, it).isDirectory } ?: emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<Pair<String, FolderNode>>()
        for ((system, dir) in overrides) if (dir.isDirectory && seen.add(dir.absolutePath)) out += system to JavaFolderNode(dir)
        for (info in io.github.uposum.romstore.core.model.Systems.known()) {
            if (info.key in overrides) continue
            val name = io.github.uposum.romstore.core.model.Systems.folderFor(info.key, available, strict = true)
            val dir = File(root, name)
            if (dir.isDirectory && seen.add(dir.absolutePath)) out += info.key to JavaFolderNode(dir)
        }
        return out
    }

    override fun folderById(id: String): FolderNode? = File(id).takeIf { it.isDirectory }?.let { JavaFolderNode(it) }
    override fun freeSpace(): Long? = root.usableSpace.takeIf { it > 0 }
}

/** Random access over a file for code that wants a RandomAccessFile. */
internal fun File.seekable(): SeekableByteChannel = RandomAccessFile(this, "r").channel
