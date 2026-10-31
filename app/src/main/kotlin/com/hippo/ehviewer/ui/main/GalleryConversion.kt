package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.image.hdr.isStillExportFileName
import com.hippo.ehviewer.library.ExplorerWindows
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.resolveBrowsePath
import com.hippo.ehviewer.ui.reader.exportStillToDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

internal fun galleryConversionRoot(actions: BrowseOverflowActions): TransferFile? {
    if (actions.kind != BrowseOverflowKind.Gallery || !actions.selectFolder || actions.onPhotoGrid == null) return null
    val source = actions.uploadSource ?: return null
    return uploadRoot(source, actions.selectTitle.orEmpty(), true, -1, 0).takeIf { it.directory }
}

internal fun galleryConversionPick(file: TransferFile, folderKey: String): FolderPick? {
    if (!isImageFileName(file.relative) || file.directory) return null
    val source = file.source
    return FolderPick(
        key = "$folderKey|f:${source.path}",
        title = file.relative,
        shareHttp = null,
        convertStill = if (isStillExportFileName(file.relative)) {
            { dir, target ->
                exportStillToDirectory(file.relative, target, dir, localSource = source.path.toPath().takeIf { source.kind == ExplorerWindows.Kind.Local }) {
                    when (source.kind) {
                        ExplorerWindows.Kind.Local -> BrowseSaveAs.stageLocalForConvert(source.path.toPath())
                        ExplorerWindows.Kind.Smb -> BrowseSaveAs.stageSmbForConvert(source.sourceId, source.path)
                        ExplorerWindows.Kind.WebDav -> BrowseSaveAs.stageWebDavForConvert(source.sourceId, source.path)
                    }
                }
            }
        } else {
            null
        },
        saveInto = { dir ->
            when (source.kind) {
                ExplorerWindows.Kind.Local -> BrowseSaveAs.saveLocalFileInto(dir, source.path.toPath(), file.relative)
                ExplorerWindows.Kind.Smb -> BrowseSaveAs.saveSmbFileInto(dir, source.sourceId, source.path, file.relative)
                ExplorerWindows.Kind.WebDav -> BrowseSaveAs.saveWebDavFileInto(dir, source.sourceId, source.path, file.relative)
            }
        },
        listLine = BrowseListLine(typeLabel = file.relative.substringAfterLast('.', ""), sizeBytes = file.size, lastModifiedMs = file.mtime),
        uploadSource = source,
    )
}

internal fun beginGalleryConvert(scope: CoroutineScope, snackbar: SnackbarHostState, context: Context, gallery: TransferFile, target: StillExportTarget) {
    val parent = FolderSelectHost.current?.place
    scope.launch {
        val files = collectTransferFilesForAction(listOf(gallery to true), snackbar, context) ?: return@launch
        val place = withIOContext {
            if (gallery.source.kind == ExplorerWindows.Kind.Local) {
                parent?.rootPath?.let { root ->
                    val prefix = resolveBrowsePath(root.toPath(), preferMediaStore = parent.preferMediaStore).toString().trimEnd('/')
                    val path = gallery.source.path
                    if (path == prefix || path.startsWith("$prefix/")) parent.copy(relativePath = path.removePrefix(prefix).trim('/')) else null
                }
            } else {
                FolderPlace(gallery.source.kind, gallery.source.sourceId, gallery.source.path)
            }
        }
        val key = "gallery-convert:${gallery.source.kind}:${gallery.source.sourceId}:${gallery.source.path}"
        val picks = files.mapNotNull { galleryConversionPick(it, key) }
        if (picks.isEmpty()) {
            snackbar.showSnackbar(context.getString(R.string.browse_action_not_supported))
            return@launch
        }
        FolderSelectHost.rememberGallerySelection(key, gallery.relative, picks, place)
        beginConvert(scope, snackbar, context, target)
    }
}
