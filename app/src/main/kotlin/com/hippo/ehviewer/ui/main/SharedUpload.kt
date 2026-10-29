package com.hippo.ehviewer.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.ehviewer.core.files.toOkioPath
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.getParcelableExtraCompat
import kotlinx.coroutines.CancellationException

internal fun Intent.isUploadShare(): Boolean = component?.className?.endsWith(".ui.UploadShareTarget") == true &&
    (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE)

/** Read metadata while the receiving activity holds the sender's URI grants. */
internal fun sharedUploadFiles(context: Context, intent: Intent): List<TransferFile> {
    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
        IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    } else {
        listOfNotNull(intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM))
    }
    val clip = intent.clipData
    val uris = (streams + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri })
        .filter { it.scheme == "content" }.distinct()
    return uris.mapIndexed { index, uri ->
        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        var name: String? = null
        var size = -1L
        var mtime = 0L
        // Query the provider's default columns: FileProvider need only expose name and size.
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                    fun long(column: String): Long? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                    name = string(OpenableColumns.DISPLAY_NAME)
                    size = long(OpenableColumns.SIZE) ?: -1
                    mtime = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED) ?: 0
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Providers may only support opening; their URI still remains usable.
        }
        if (size < 0) context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
        val title = FileUtils.sanitizeFilename(name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Shared file ${index + 1}")
        TransferFile(TransferLocation(ExplorerWindows.Kind.Local, 0, uri.toOkioPath().toString()), title, false, size, mtime)
    }
}
