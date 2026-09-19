package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.library.FolderSearch
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowsePhotoGridImageItem
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import okio.Path.Companion.toPath

/** Shared cap for reader settings / small photo-grid sheets (skip partial expand). */
const val READER_SHEET_HEIGHT_FRACTION = 0.7f

fun Modifier.readerSheetExpandBox(): Modifier = fillMaxWidth().fillMaxHeight(READER_SHEET_HEIGHT_FRACTION)

/** Photo grid uses the capped box below this page count; larger galleries fill the screen. */
const val READER_PHOTO_GRID_FULL_EXPAND_MIN = 40

fun readerPhotoGridHalfScreen(pageCount: Int): Boolean = pageCount < READER_PHOTO_GRID_FULL_EXPAND_MIN

/**
 * Folder galleries and ZIP/CBZ (zip-as-dir) can open a reader photo grid.
 * RAR / 7z / PDF / EPUB / TAR keep the decode-size chrome button.
 */
fun readerGallerySupportsPhotoGrid(args: ReaderScreenArgs): Boolean = when (args) {
    is ReaderScreenArgs.LocalFolder,
    is ReaderScreenArgs.LocalZipFolder,
    is ReaderScreenArgs.SmbFolder,
    is ReaderScreenArgs.WebDavFolder,
    -> true
    is ReaderScreenArgs.Archive ->
        isZipArchiveFileName(args.path.substringAfterLast('/').substringAfterLast('\\'))
    is ReaderScreenArgs.SmbStreamArchive ->
        isZipArchiveFileName(args.remotePath.substringAfterLast('/').substringAfterLast('\\'))
    is ReaderScreenArgs.WebDavStreamArchive ->
        isZipArchiveFileName(args.remotePath.substringAfterLast('/').substringAfterLast('\\'))
}

fun readerPageFileName(args: ReaderScreenArgs, pageLoader: ReaderSession, index: Int): String {
    val fromArgs = when (args) {
        is ReaderScreenArgs.LocalFolder -> args.imageNames.getOrNull(index)
        is ReaderScreenArgs.LocalZipFolder -> args.imageNames.getOrNull(index)
        is ReaderScreenArgs.SmbFolder -> args.imageNames.getOrNull(index)
        is ReaderScreenArgs.WebDavFolder -> args.imageNames.getOrNull(index)
        else -> null
    }
    return fromArgs?.substringAfterLast('/')?.substringAfterLast('\\')?.ifEmpty { null }
        ?: pageLoader.getImageFilename(index)
        ?: "${index + 1}"
}

fun readerPageCover(args: ReaderScreenArgs, fileName: String): BrowseCover? {
    val name = fileName.replace('\\', '/').trim('/')
    if (name.isEmpty()) return null
    val base = name.substringAfterLast('/')
    return when (args) {
        is ReaderScreenArgs.LocalFolder -> BrowseCover.Local(args.path.toPath() / base)
        is ReaderScreenArgs.LocalZipFolder -> {
            val member = ZipAsDirListing.joinPrefix(args.innerRel, name)
            BrowseCover.Local(ZipPaths.encodePath(args.zipPath, member))
        }
        is ReaderScreenArgs.SmbFolder -> {
            ZipAsDirListing.zipAsDirCoverParts(args.remoteDir, "", name)?.let { (zipRel, member) ->
                BrowseCover.SmbZipMember(args.sourceId, zipRel, member)
            } ?: BrowseCover.Smb(
                args.sourceId,
                FolderSearch.joinRelative(args.remoteDir, name),
            )
        }
        is ReaderScreenArgs.WebDavFolder -> {
            ZipAsDirListing.zipAsDirCoverParts(args.remoteDir, "", name)?.let { (zipRel, member) ->
                BrowseCover.WebDavZipMember(args.sourceId, zipRel, member)
            } ?: BrowseCover.WebDav(
                args.sourceId,
                FolderSearch.joinRelative(args.remoteDir, name),
            )
        }
        is ReaderScreenArgs.Archive ->
            BrowseCover.Local(ZipPaths.encodePath(args.path, name))
        is ReaderScreenArgs.SmbStreamArchive ->
            BrowseCover.SmbZipMember(args.sourceId, args.remotePath, name)
        is ReaderScreenArgs.WebDavStreamArchive ->
            BrowseCover.WebDavZipMember(args.sourceId, args.remotePath, name)
    }
}

@Composable
fun ReaderPhotoGridSheet(
    args: ReaderScreenArgs,
    pageLoader: ReaderSession,
    currentPage: Int,
    onJumpToPage: (Int) -> Unit,
) {
    val pageCount = pageLoader.size
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = (currentPage - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
    )
    // First frame is placeholders only so the sheet can paint immediately; SMB/WebDAV
    // thumb IO starts on the next frame (folder photo-grid already has those cached).
    var allowRemoteFetch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { allowRemoteFetch = true }
    val gridSpacing = GalleryGridDefaults.spacedBy()
    val sheetModifier = if (readerPhotoGridHalfScreen(pageCount)) {
        Modifier.readerSheetExpandBox()
    } else {
        Modifier.fillMaxSize()
    }
    Box(sheetModifier) {
        FastScrollLazyVerticalGrid(
            columns = GalleryGridDefaults.columns(),
            state = gridState,
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = GalleryGridDefaults.contentPadding(),
            horizontalArrangement = gridSpacing,
            verticalArrangement = gridSpacing,
        ) {
            items(count = pageCount, key = { it }) { index ->
                val name = readerPageFileName(args, pageLoader, index)
                BrowsePhotoGridImageItem(
                    name = name,
                    cover = readerPageCover(args, name),
                    showPhotoThumb = true,
                    allowRemoteFetch = allowRemoteFetch,
                    onClick = { onJumpToPage(index + 1) },
                    onLongClick = { onJumpToPage(index + 1) },
                )
            }
        }
    }
}
