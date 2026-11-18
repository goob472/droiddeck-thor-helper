package com.droiddeck.fake

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes this app's data folder the way DroidDeck's documents provider does: one root, document
 * IDs are absolute paths inside the folder, and create/write/delete/rename are supported. Seeds a
 * fake DroidDeck layout on first use. Test fixture only.
 */
class FakeFilesProvider : DocumentsProvider() {
    private val base: File get() = context!!.dataDir

    override fun onCreate(): Boolean {
        Seed.ensure(context!!.dataDir)
        return true
    }

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: ROOT_COLS).apply {
        newRow().add(Root.COLUMN_ROOT_ID, "app").add(Root.COLUMN_DOCUMENT_ID, base.absolutePath)
            .add(Root.COLUMN_TITLE, "DroidDeck").add(Root.COLUMN_ICON, android.R.drawable.ic_menu_manage)
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLS).also { row(it, file(documentId)) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOC_COLS).also { c -> file(parentDocumentId).listFiles()?.forEach { row(c, it) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        var f = File(parent, displayName)
        var n = 2
        while (f.exists()) f = File(parent, "$displayName (${n++})")
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        if (!ok) throw FileNotFoundException("could not create ${f.path}")
        return f.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (f == base || !f.deleteRecursively()) throw FileNotFoundException("could not delete $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val f = file(documentId)
        val t = File(f.parentFile, displayName)
        if (t.exists() || !f.renameTo(t)) throw FileNotFoundException("could not rename $documentId")
        return t.absolutePath
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    private fun file(id: String): File {
        val f = File(id)
        val b = base.absolutePath
        if (!f.isAbsolute || f.normalize().path != id || !(id == b || id.startsWith("$b/"))) throw FileNotFoundException("$id is outside")
        if (!f.exists()) throw FileNotFoundException("$id not found")
        return f
    }

    private fun row(c: MatrixCursor, f: File) {
        val dir = f.isDirectory
        var flags = if (dir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (f != base) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        val mime = if (dir) Document.MIME_TYPE_DIR
        else MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, f.absolutePath)
            .add(Document.COLUMN_DISPLAY_NAME, if (f == base) "DroidDeck" else f.name)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_FLAGS, flags)
            .add(Document.COLUMN_SIZE, if (dir) null else f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
    }

    companion object {
        private val ROOT_COLS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES)
        private val DOC_COLS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
