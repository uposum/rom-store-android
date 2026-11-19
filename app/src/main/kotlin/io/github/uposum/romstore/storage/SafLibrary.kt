package io.github.uposum.romstore.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import io.github.uposum.romstore.core.model.Systems
import io.github.uposum.romstore.core.store.FileNode
import io.github.uposum.romstore.core.store.FolderNode
import io.github.uposum.romstore.core.store.RomLibrary
import io.github.uposum.romstore.core.store.StoreSettings
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

/**
 * The ROM library on folders the user picked with the system folder picker
 * (storage access framework). Children are listed with one query each, which
 * is far faster than DocumentFile.listFiles().
 */
open class SafNode(
    protected val resolver: ContentResolver,
    val treeUri: Uri,
    val documentId: String,
    override val name: String,
    private val mime: String,
    override val size: Long,
    override val modified: Long,
) : FileNode {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    override val id: String get() = uri.toString()
    override val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR

    override fun openRead(): InputStream = resolver.openInputStream(uri) ?: throw FileNotFoundException(name)

    override fun openWrite(append: Boolean): OutputStream =
        resolver.openOutputStream(uri, if (append) "wa" else "wt") ?: throw FileNotFoundException(name)

    override fun openSeekable(): SeekableByteChannel {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(name)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).channel
    }

    override fun rename(newName: String): FileNode? {
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, uri, newName) }.getOrNull() ?: return null
        val docId = DocumentsContract.getDocumentId(renamed)
        return SafLibrary.stat(resolver, treeUri, docId)
    }

    override fun delete(): Boolean = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
}

class SafFolder(
    resolver: ContentResolver, treeUri: Uri, documentId: String, name: String, modified: Long,
    private val free: () -> Long?,
) : SafNode(resolver, treeUri, documentId, name, Document.MIME_TYPE_DIR, 0, modified), FolderNode {

    override fun list(): List<FileNode> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val out = mutableListOf<FileNode>()
        resolver.query(children, SafLibrary.COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val n = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val mod = if (c.isNull(4)) 0L else c.getLong(4)
                out += if (mime == Document.MIME_TYPE_DIR) SafFolder(resolver, treeUri, id, n, mod, free)
                else SafNode(resolver, treeUri, id, n, mime, size, mod)
            }
        }
        return out
    }

    override fun createFile(name: String): FileNode {
        val created = DocumentsContract.createDocument(resolver, uri, "application/octet-stream", name)
            ?: throw FileNotFoundException("Could not create $name")
        return SafLibrary.stat(resolver, treeUri, DocumentsContract.getDocumentId(created))
            ?: SafNode(resolver, treeUri, DocumentsContract.getDocumentId(created), name, "application/octet-stream", 0, System.currentTimeMillis())
    }

    override fun createFolder(name: String): FolderNode {
        (list().firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) } as? FolderNode)?.let { return it }
        val created = DocumentsContract.createDocument(resolver, uri, Document.MIME_TYPE_DIR, name)
            ?: throw FileNotFoundException("Could not create the folder $name")
        return SafFolder(resolver, treeUri, DocumentsContract.getDocumentId(created), name, System.currentTimeMillis(), free)
    }

    override fun asFolder(node: FileNode): FolderNode? = node as? SafFolder
    override fun freeSpace(): Long? = free()
}

class SafLibrary(private val context: Context, private val settings: () -> StoreSettings) : RomLibrary {
    private val resolver get() = context.contentResolver

    companion object {
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )

        fun stat(resolver: ContentResolver, treeUri: Uri, docId: String): FileNode? {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            return runCatching {
                resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val mime = c.getString(2) ?: ""
                    val n = c.getString(1) ?: docId.substringAfterLast('/')
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val mod = if (c.isNull(4)) 0L else c.getLong(4)
                    if (mime == Document.MIME_TYPE_DIR) SafFolder(resolver, treeUri, docId, n, mod) { null }
                    else SafNode(resolver, treeUri, docId, n, mime, size, mod)
                }
            }.getOrNull()
        }

        /** "primary:ROMs/gba" -> "Internal storage/ROMs/gba"; "1234-ABCD:ROMs" -> "SD card/ROMs". */
        fun describe(treeUri: String?): String? {
            treeUri ?: return null
            val docId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull() ?: return treeUri
            val volume = docId.substringBefore(':')
            val path = docId.substringAfter(':', "")
            val label = if (volume == "primary") "Internal storage" else if (volume.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}"))) "SD card" else volume
            return if (path.isEmpty()) label else "$label/$path"
        }
    }

    /** A folder picked with the picker, or null when access was lost. */
    fun rootOf(treeUriString: String?): SafFolder? {
        treeUriString ?: return null
        val treeUri = Uri.parse(treeUriString)
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val node = stat(resolver, treeUri, docId) ?: return null
        if (!node.isDirectory) return null
        return SafFolder(resolver, treeUri, docId, node.name, node.modified) { freeSpaceOf(docId) }
    }

    /** Free space on the volume a document lives on. */
    private fun freeSpaceOf(docId: String): Long? {
        val volume = docId.substringBefore(':')
        return runCatching {
            if (volume == "primary") return Environment.getExternalStorageDirectory().usableSpace
            if (Build.VERSION.SDK_INT >= 30) {
                val sm = context.getSystemService(StorageManager::class.java)
                sm.storageVolumes.firstOrNull { it.uuid.equals(volume, ignoreCase = true) }?.directory?.usableSpace
            } else {
                File("/storage/$volume").takeIf { it.isDirectory }?.usableSpace
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    override val description: String? get() = describe(settings().libraryUri)

    override fun freeSpace(): Long? = rootOf(settings().libraryUri)?.freeSpace()

    private fun folderName(system: String, available: List<String>): String =
        settings().folderNames[system]?.takeIf { it.isNotBlank() } ?: Systems.folderFor(system, available)

    override fun consoleFolder(system: String, create: Boolean): FolderNode? {
        val s = settings()
        s.consoleFolders[system]?.let { return rootOf(it) }
        val root = rootOf(s.libraryUri) ?: return null
        val children = root.list().filter { it.isDirectory }
        val name = folderName(system, children.map { it.name })
        (children.firstOrNull { it.name.equals(name, ignoreCase = true) } as? FolderNode)?.let { return it }
        return if (create) root.createFolder(name) else null
    }

    override fun consoleFolders(): List<Pair<String, FolderNode>> {
        val s = settings()
        val out = mutableListOf<Pair<String, FolderNode>>()
        val seen = HashSet<String>()
        for ((system, uri) in s.consoleFolders) rootOf(uri)?.let { if (seen.add(it.id)) out += system to it }
        val root = rootOf(s.libraryUri) ?: return out
        val children = root.list().filter { it.isDirectory }
        val byName = children.associateBy { it.name.lowercase() }
        for (info in Systems.known()) {
            if (info.key in s.consoleFolders) continue
            val name = s.folderNames[info.key]?.takeIf { it.isNotBlank() } ?: Systems.folderFor(info.key, children.map { it.name }, strict = true)
            val folder = byName[name.lowercase()] as? FolderNode ?: continue
            if (seen.add(folder.id)) out += info.key to folder
        }
        return out
    }

    override fun folderById(id: String): FolderNode? {
        val uri = Uri.parse(id)
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val tree = runCatching { DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri)) }.getOrNull() ?: return null
        val node = stat(resolver, tree, docId) ?: return null
        if (!node.isDirectory) return null
        return SafFolder(resolver, tree, docId, node.name, node.modified) { freeSpaceOf(docId) }
    }

    /** The document URI of a file in the library, for "Open with". */
    fun fileUri(fileId: String): Uri = Uri.parse(fileId)
}
