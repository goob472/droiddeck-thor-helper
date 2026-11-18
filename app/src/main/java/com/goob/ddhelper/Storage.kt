package com.goob.ddhelper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap

/**
 * File access through folders the user granted in Android's file picker, with no root.
 *
 * - DroidDeck's own folder comes from DroidDeck's documents provider (`<package>.documents`),
 *   whose document IDs are the files' absolute paths, so a path maps straight to a document.
 * - Steam libraries on shared or SD-card storage come from Android's storage provider, whose
 *   document IDs are `<volume>:<relative path>` (`primary:SteamLibrary/...`).
 *
 * Every operation goes through [Scope] first, and Android itself only allows the granted folders.
 * Files are created and written by the provider's own app (DroidDeck for its folder), so owners and
 * permissions are always what DroidDeck expects.
 */
object Storage {
    private const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"

    /** A granted folder: [basePath] on the device, reached through [tree]. */
    data class Mount(val basePath: String, val tree: Uri) {
        val authority: String get() = tree.authority.orEmpty()
        val isDroidDeck: Boolean get() = authority.endsWith(".documents") && authority != EXTERNAL_AUTHORITY
    }

    @Volatile private var ctx: Context? = null
    @Volatile var mounts: List<Mount> = emptyList()
        private set

    fun init(context: Context, mounts: List<Mount>) {
        ctx = context.applicationContext
        this.mounts = mounts
    }

    private val resolver get() = (ctx ?: error("Storage not initialised")).contentResolver

    // ---------- path <-> document mapping ----------

    /** Device path for a picked tree, or null if it isn't a folder this app can map to a path. */
    fun basePathOf(tree: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        return if (tree.authority == EXTERNAL_AUTHORITY) externalPath(docId) else docId.takeIf { it.startsWith("/") }?.trimEnd('/')
    }

    private fun externalPath(docId: String): String? {
        val volume = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "").trim('/')
        if (volume.isEmpty()) return null
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    private fun externalDocId(path: String): String? {
        val (volume, root) = when {
            path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/") -> "primary" to "/storage/emulated/0"
            path.startsWith("/storage/") -> path.removePrefix("/storage/").substringBefore('/').let { it to "/storage/$it" }
            else -> return null
        }
        return "$volume:" + path.removePrefix(root).trimStart('/')
    }

    private fun mountFor(path: String): Mount? =
        mounts.filter { path == it.basePath || path.startsWith(it.basePath + "/") }.maxByOrNull { it.basePath.length }

    private fun docUri(path: String): Uri? {
        Scope.check(path)
        val m = mountFor(path) ?: return null
        val docId = if (m.isDroidDeck) path else externalDocId(path) ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(m.tree, docId)
    }

    fun isGranted(path: String) = mountFor(path) != null

    // ---------- queries ----------

    private fun mimeOf(path: String): String? {
        val uri = docUri(path) ?: return null
        return try {
            resolver.query(uri, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null // the provider throws FileNotFoundException for a missing file
        }
    }

    fun exists(path: String) = mimeOf(path) != null
    fun isDir(path: String) = mimeOf(path) == Document.MIME_TYPE_DIR
    fun isFile(path: String) = mimeOf(path).let { it != null && it != Document.MIME_TYPE_DIR }

    /** Names of the entries in a folder (empty if it doesn't exist or isn't granted). */
    fun list(path: String): List<String> {
        val m = runCatching { Scope.check(path); mountFor(path) }.getOrNull() ?: return emptyList()
        val docId = if (m.isDroidDeck) path else externalDocId(path) ?: return emptyList()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(m.tree, docId)
        return try {
            resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) c.getString(0)?.let { add(it) } }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readBytes(path: String): ByteArray? {
        if (!isFile(path)) return null
        val uri = docUri(path) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }

    fun readText(path: String): String? = readBytes(path)?.toString(Charsets.UTF_8)

    // ---------- changes ----------

    data class Result(val ok: Boolean, val err: String = "")

    /** Creates any missing folders of [dir], one level at a time. */
    private fun ensureDir(dir: String): Boolean {
        if (isDir(dir)) return true
        val parent = dir.substringBeforeLast('/', "")
        if (parent.isEmpty() || mountFor(parent) == null || !ensureDir(parent)) return false
        val parentUri = docUri(parent) ?: return false
        return try {
            val made = DocumentsContract.createDocument(resolver, parentUri, Document.MIME_TYPE_DIR, dir.substringAfterLast('/'))
            made != null && isDir(dir)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Writes [bytes] to [path]: an existing file is truncated and rewritten in place, a new one is
     * created (with any missing folders) under exactly that name.
     */
    fun writeBytes(path: String, bytes: ByteArray): Result {
        return try {
            Scope.check(path)
            var uri = if (isFile(path)) docUri(path) else null
            if (uri == null) {
                val parent = path.substringBeforeLast('/')
                val name = path.substringAfterLast('/')
                if (!ensureDir(parent)) return Result(false, "could not create folder $parent")
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val created = DocumentsContract.createDocument(resolver, docUri(parent)!!, mime, name)
                    ?: return Result(false, "could not create $path")
                // Providers can adjust a new file's name (a numbered copy, an added extension).
                if (!isFile(path)) {
                    runCatching { DocumentsContract.deleteDocument(resolver, created) }
                    return Result(false, "the folder's provider renamed the new file, so $name was not written")
                }
                uri = created
            }
            resolver.openOutputStream(uri!!, "wt")?.use { it.write(bytes) } ?: return Result(false, "could not open $path")
            Result(true)
        } catch (e: Exception) {
            Result(false, e.message ?: e.toString())
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun writeText(ctx: Context, path: String, text: String) = writeBytes(path, text.toByteArray(Charsets.UTF_8))

    fun deleteFile(path: String): Result = try {
        if (!isFile(path)) Result(false, "not a file") else Result(DocumentsContract.deleteDocument(resolver, docUri(path)!!))
    } catch (e: Exception) {
        Result(false, e.message ?: e.toString())
    }

    /** Removes a folder only if it is empty. */
    fun removeEmptyDir(path: String): Result = try {
        if (!isDir(path) || list(path).isNotEmpty()) Result(false, "not an empty folder")
        else Result(DocumentsContract.deleteDocument(resolver, docUri(path)!!))
    } catch (e: Exception) {
        Result(false, e.message ?: e.toString())
    }

    /** Renames a file within its folder. */
    fun rename(from: String, to: String): Result = try {
        require(from.substringBeforeLast('/') == to.substringBeforeLast('/')) { "rename only works within a folder" }
        Scope.check(to)
        val uri = docUri(from) ?: return Result(false, "not granted")
        Result(DocumentsContract.renameDocument(resolver, uri, to.substringAfterLast('/')) != null)
    } catch (e: Exception) {
        Result(false, e.message ?: e.toString())
    }
}
