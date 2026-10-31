package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.TransferFiles
import com.hippo.ehviewer.library.TransferLocation
import com.hippo.ehviewer.library.decodedTrashPath
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.safTrashParentId
import kotlinx.coroutines.CancellationException

internal enum class GalleryFileAction { Images, Upload, Delete }

/** Storage/share roots have no movable parent; retain image-by-image deletion there. */
private fun canMoveGalleryFolder(root: TransferFile): Boolean = if (root.source.kind == ExplorerWindows.Kind.Local) {
    val path = decodedTrashPath(root.source.path)
    if ("/tree/" !in path || "/document/" !in path) {
        false
    } else {
        val tree = path.substringAfter("/tree/").substringBefore("/document/")
        val document = path.substringAfter("/document/")
        runCatching { safTrashParentId(tree, document) }.isSuccess
    }
} else {
    root.source.path.trim('/').isNotEmpty()
}

/** Collect direct images once; uploads retain the gallery directory as their destination root. */
internal suspend fun collectGalleryTransferFiles(
    file: TransferFile,
    gallery: Boolean,
    action: GalleryFileAction = GalleryFileAction.Images,
    children: suspend (TransferLocation) -> List<TransferFile> = { TransferFiles.children(it) },
): List<TransferFile> {
    val root = uploadRoot(file.source, file.relative, file.directory, file.size, file.mtime)
    // ZIP-as-directory roots keep their existing physical-archive transfer behavior.
    if (!gallery || !root.directory) return listOf(root)
    val listed = children(root.source)
    val images = listed.filter { !it.directory && isImageFileName(it.relative) }
        .sortedWith { a, b -> naturalCompare(a.relative, b.relative) }
    return when (action) {
        GalleryFileAction.Images -> images
        GalleryFileAction.Upload -> if (images.isEmpty()) emptyList() else listOf(root.copy(galleryImages = images))
        GalleryFileAction.Delete -> if (canMoveGalleryFolder(root) && listed.none { it.directory } && listed.size < images.size + 3) listOf(root) else images
    }
}

internal suspend fun collectTransferFilesForAction(
    requests: List<Pair<TransferFile, Boolean>>,
    snackbar: SnackbarHostState,
    context: Context,
    action: GalleryFileAction = GalleryFileAction.Images,
): List<TransferFile>? = try {
    withIOContext {
        requests.flatMap { (file, gallery) -> collectGalleryTransferFiles(file, gallery, action) }.distinctBy { it.source }
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
