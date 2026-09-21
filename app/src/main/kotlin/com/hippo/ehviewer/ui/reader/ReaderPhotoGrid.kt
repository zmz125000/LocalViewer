package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import com.ehviewer.core.ui.component.FastScrollLazyVerticalGrid
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.library.FolderSearch
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.isPdfFileName
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowsePhotoGridImageItem
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import kotlinx.coroutines.delay
import okio.Path.Companion.toPath

/** Shared cap for reader settings / small photo-grid sheets (skip partial expand). */
const val READER_SHEET_HEIGHT_FRACTION = 0.7f

/**
 * Absolute sheet height from the screen, not [fillMaxHeight]. ModalBottomSheet
 * first-measures with unbounded max height; a fractional fill is a no-op there
 * and a [fillMaxSize] LazyGrid then composes every cell (100 full-res thumbs).
 */
fun readerSheetHeightDp(screenHeightDp: Int, capHeight: Boolean): Dp {
    val screen = screenHeightDp.coerceAtLeast(1).dp
    return if (capHeight) screen * READER_SHEET_HEIGHT_FRACTION else screen
}

@Composable
fun Modifier.readerSheetBox(capHeight: Boolean): Modifier {
    val height = readerSheetHeightDp(LocalConfiguration.current.screenHeightDp, capHeight)
    return fillMaxWidth().height(height)
}

/** Photo grid uses the capped box below this page count; larger galleries fill the screen. */
const val READER_PHOTO_GRID_FULL_EXPAND_MIN = 40

fun readerPhotoGridHalfScreen(pageCount: Int, capHeight: Boolean): Boolean = capHeight && pageCount < READER_PHOTO_GRID_FULL_EXPAND_MIN

/**
 * Material3 sheets default to 640dp. Phone already fills that; tablet landscape
 * is ~⅓ of the screen, so the shared grid column count makes cells tiny.
 * Tablets (sw ≥ 600dp) use most of the current width in both orientations.
 */
const val READER_PHOTO_GRID_TABLET_WIDTH_FRACTION = 0.9f

fun readerPhotoGridSheetMaxWidth(smallestWidthDp: Int, screenWidthDp: Int): Dp {
    val tablet = smallestWidthDp >= WIDTH_DP_MEDIUM_LOWER_BOUND
    if (!tablet) return BottomSheetDefaults.SheetMaxWidth
    val target = (screenWidthDp.coerceAtLeast(0) * READER_PHOTO_GRID_TABLET_WIDTH_FRACTION).dp
    return maxOf(target, BottomSheetDefaults.SheetMaxWidth)
}

@Composable
fun readerPhotoGridSheetMaxWidth(): Dp {
    val configuration = LocalConfiguration.current
    return remember(configuration.smallestScreenWidthDp, configuration.screenWidthDp) {
        readerPhotoGridSheetMaxWidth(
            smallestWidthDp = configuration.smallestScreenWidthDp,
            screenWidthDp = configuration.screenWidthDp,
        )
    }
}

/**
 * Folder galleries, ZIP/CBZ, and PDF can open a reader photo grid.
 * RAR / 7z / EPUB / TAR keep the decode-size chrome button.
 */
fun readerGallerySupportsPhotoGrid(args: ReaderScreenArgs): Boolean = when (args) {
    is ReaderScreenArgs.LocalFolder,
    is ReaderScreenArgs.LocalZipFolder,
    is ReaderScreenArgs.SmbFolder,
    is ReaderScreenArgs.WebDavFolder,
    -> true
    is ReaderScreenArgs.Archive ->
        isZipArchiveFileName(args.path.substringAfterLast('/').substringAfterLast('\\')) ||
            isPdfFileName(args.path)
    is ReaderScreenArgs.SmbStreamArchive ->
        isZipArchiveFileName(args.remotePath.substringAfterLast('/').substringAfterLast('\\')) ||
            isPdfFileName(args.remotePath)
    is ReaderScreenArgs.WebDavStreamArchive ->
        isZipArchiveFileName(args.remotePath.substringAfterLast('/').substringAfterLast('\\')) ||
            isPdfFileName(args.remotePath)
}

/** Document-extract cache key used by the PDF reader page loader, or null if not a PDF. */
fun readerPdfCacheKey(args: ReaderScreenArgs): String? = when (args) {
    is ReaderScreenArgs.Archive -> args.path.takeIf { isPdfFileName(it) }
    is ReaderScreenArgs.SmbStreamArchive ->
        "smb:${args.sourceId}:${args.remotePath}".takeIf { isPdfFileName(args.remotePath) }
    is ReaderScreenArgs.WebDavStreamArchive ->
        "webdav:${args.sourceId}:${args.remotePath}".takeIf { isPdfFileName(args.remotePath) }
    else -> null
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

fun readerPageCover(args: ReaderScreenArgs, fileName: String, index: Int = 0): BrowseCover? {
    readerPdfCacheKey(args)?.let { cacheKey ->
        return BrowseCover.DocumentPage(cacheKey, index)
    }
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
    // First frame is placeholders only so the sheet can paint immediately; local
    // Coil decode and SMB/WebDAV thumb IO start on the next frame.
    var allowRemoteFetch by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { allowRemoteFetch = true }
    val pdfCacheKey = remember(args) { readerPdfCacheKey(args) }
    val gridSpacing = GalleryGridDefaults.spacedBy()
    Box(
        Modifier.readerSheetBox(
            readerPhotoGridHalfScreen(pageCount, GalleryGridDefaults.capReaderSheet()),
        ),
    ) {
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
                if (pdfCacheKey != null && allowRemoteFetch) {
                    LaunchedEffect(index) {
                        while (true) {
                            pageLoader.requestPageSource(index)
                            delay(500)
                        }
                    }
                }
                BrowsePhotoGridImageItem(
                    name = name,
                    cover = readerPageCover(args, name, index),
                    showPhotoThumb = true,
                    allowRemoteFetch = allowRemoteFetch,
                    onClick = { onJumpToPage(index + 1) },
                    onLongClick = { onJumpToPage(index + 1) },
                )
            }
        }
    }
}
