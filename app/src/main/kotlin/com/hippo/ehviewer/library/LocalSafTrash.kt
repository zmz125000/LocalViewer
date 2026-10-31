package com.hippo.ehviewer.library

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ehviewer.core.files.toUri
import com.ehviewer.core.util.logcat
import java.io.FileNotFoundException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath
import splitties.init.appCtx

/** Provider-side document moves only: folders are moved as one document, never copied. */
internal object LocalSafTrash {
    private val lock = Mutex()
    private val resolver get() = appCtx.contentResolver
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
    private data class Entry(val id: String, val name: String, val directory: Boolean, val flags: Int)

    private fun query(uri: Uri): List<Entry> = resolver.query(uri, projection, null, null, null)?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2) == Document.MIME_TYPE_DIR, cursor.getInt(3)))
            }
        }
    } ?: throw FileNotFoundException("Cannot query $uri")

    private fun entry(uri: Uri) = query(uri).singleOrNull() ?: throw FileNotFoundException("Document no longer exists: $uri")
    private fun children(uri: Uri) = query(DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri)))
    private fun document(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun directory(parent: Uri, name: String): Uri {
        val existing = children(parent).firstOrNull { it.name == name }
        if (existing != null) {
            check(existing.directory) { "$name is not a directory" }
            return document(parent, existing.id)
        }
        val created = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
            ?: throw UnsupportedOperationException("Provider cannot create trash directory")
        check(entry(created).name == name) { "Trash directory name changed during creation" }
        return created
    }

    suspend fun move(file: TransferFile, date: String, control: TransferControl): String = lock.withLock {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        require(canTrash(file.source)) { "Not a SAF tree document" }
        var source = file.source.path.toPath().toUri()
        val treeId = DocumentsContract.getTreeDocumentId(source)
        val sourceId = DocumentsContract.getDocumentId(source)
        requireOutsideTrash(sourceId)
        val parentId = safTrashParentId(treeId, sourceId)
        val parent = document(source, parentId)
        val original = entry(source)
        if (original.flags and Document.FLAG_SUPPORTS_MOVE == 0) throw UnsupportedOperationException("Provider does not support document moves")
        control.checkpoint()
        val target = directory(directory(parent, ".trash"), date)
        moveToTrashWithoutOverwrite(original.name, original.directory) { candidate ->
            control.checkpoint()
            if (children(target).any { it.name == candidate }) throw TransferNameConflict(candidate)
            var renamed = false
            var moved = false
            try {
                if (candidate != original.name) {
                    if (original.flags and Document.FLAG_SUPPORTS_RENAME == 0) throw UnsupportedOperationException("Provider cannot rename conflicting documents")
                    if (children(parent).any { it.name == candidate }) throw TransferNameConflict(candidate)
                    source = DocumentsContract.renameDocument(resolver, source, candidate)
                        ?: throw UnsupportedOperationException("Provider could not rename document")
                    renamed = true
                    check(entry(source).name == candidate) { "Provider changed the requested document name" }
                }
                control.checkpoint()
                // No copy/delete fallback and no retries after a potentially committed move.
                DocumentsContract.moveDocument(resolver, source, parent, target)
                    ?: throw UnsupportedOperationException("Provider could not move document")
                moved = true
            } catch (error: Throwable) {
                if (renamed && !moved) {
                    runCatching {
                        if (children(parent).none { it.name == original.name }) {
                            DocumentsContract.renameDocument(resolver, source, original.name)
                        }
                    }.onFailure { logcat("FileDelete", it) }
                }
                throw error
            }
        }
    }
}
