package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferFiles
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import kotlinx.coroutines.CancellationException

/** Gallery actions target direct images, just like photo-grid mode; ordinary folders stay roots. */
internal suspend fun collectGalleryTransferFiles(
    file: TransferFile,
    gallery: Boolean,
    children: suspend (TransferLocation) -> List<TransferFile> = { TransferFiles.children(it) },
): List<TransferFile> {
    val root = uploadRoot(file.source, file.relative, file.directory, file.size, file.mtime)
    // ZIP-as-directory roots keep their existing physical-archive transfer behavior.
    if (!gallery || !root.directory) return listOf(root)
    return children(root.source).filter { !it.directory && isImageFileName(it.relative) }
        .sortedWith { a, b -> naturalCompare(a.relative, b.relative) }
}

internal suspend fun collectTransferFilesForAction(
    requests: List<Pair<TransferFile, Boolean>>,
    snackbar: SnackbarHostState,
    context: Context,
): List<TransferFile>? = try {
    withIOContext {
        requests.flatMap { (file, gallery) -> collectGalleryTransferFiles(file, gallery) }.distinctBy { it.source }
    }.takeIf { it.isNotEmpty() }.also {
        if (it == null) snackbar.showSnackbar(context.getString(R.string.browse_action_not_supported))
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    logcat("FileTransfer", error)
    snackbar.showSnackbar(context.getString(R.string.browse_open_failed) + " " + (error.message ?: error.toString()))
    null
}
