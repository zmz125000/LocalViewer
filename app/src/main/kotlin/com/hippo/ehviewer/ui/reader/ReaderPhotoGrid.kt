package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val names = remember(args, pageLoader, pageCount) {
        List(pageCount) { index -> readerPageFileName(args, pageLoader, index) }
    }
    val gridSpacing = GalleryGridDefaults.spacedBy()
    FastScrollLazyVerticalGrid(
        columns = GalleryGridDefaults.columns(),
        state = gridState,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = GalleryGridDefaults.contentPadding(),
        horizontalArrangement = gridSpacing,
        verticalArrangement = gridSpacing,
    ) {
        items(count = pageCount, key = { it }) { index ->
            val name = names[index]
            BrowsePhotoGridImageItem(
                name = name,
                cover = readerPageCover(args, name),
                showPhotoThumb = true,
                onClick = { onJumpToPage(index + 1) },
                onLongClick = { onJumpToPage(index + 1) },
            )
        }
    }
}
