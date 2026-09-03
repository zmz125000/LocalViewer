package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.ElevatedCard
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.coil.coverThumbRequest
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.CoverEnsureResult
import com.hippo.ehviewer.library.EmptyArchiveRegistry
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.VideoThumbnail
import com.hippo.ehviewer.library.VideoThumbnailSource
import com.hippo.ehviewer.library.ZipMemberCover
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.smb.SmbArchiveByteSource
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavArchiveByteSource
import com.hippo.ehviewer.webdav.WebDavCache
import com.hippo.ehviewer.webdav.WebDavClient
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import okio.Path
import okio.Path.Companion.toPath

private const val BROWSE_LIST_SEP = " · "

/** Uppercase extension from a basename / relative path; `"FILE"` when missing. */
fun browseFileExtensionLabel(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot >= base.length - 1) return "FILE"
    return base.substring(dot + 1).uppercase(Locale.US)
}

/** Compact size for list meta (`340 KB`, `1.2 MB`); empty when unknown. */
fun browseListSizeLabel(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return ""
    return when {
        sizeBytes < 1024L -> "$sizeBytes B"
        sizeBytes < 1024L * 1024L -> {
            val kb = sizeBytes / 1024.0
            if (kb < 10.0) "%.1f KB".format(Locale.US, kb) else "${kb.toInt()} KB"
        }
        else -> {
            val mb = sizeBytes / (1024.0 * 1024.0)
            if (mb < 10.0) "%.1f MB".format(Locale.US, mb) else "%.0f MB".format(Locale.US, mb)
        }
    }
}

/** `12P` / `∞P` for folder galleries; empty when unknown. */
fun browseListPagesLabel(pageCount: Int, pageCountCapped: Boolean = false): String = when {
    pageCountCapped -> "∞P"
    pageCount > 0 -> "${pageCount}P"
    else -> ""
}

/**
 * List-row date: `Today 3:04 PM` / `Yesterday 3:04 PM` (localized day word + short time),
 * or locale short **date** (no time) when older. Empty when [lastModifiedMs] ≤ 0.
 */
@Composable
fun browseListDateLabel(lastModifiedMs: Long): String {
    if (lastModifiedMs <= 0L) return ""
    val time = remember(lastModifiedMs) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastModifiedMs))
    }
    val dayStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    return when {
        lastModifiedMs >= dayStart -> "${stringResource(R.string.today)} $time"
        lastModifiedMs >= dayStart - 24L * 60L * 60L * 1000L ->
            "${stringResource(R.string.yesterday)} $time"
        else -> remember(lastModifiedMs) {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(lastModifiedMs))
        }
    }
}

/**
 * Folder list second line: `ext|Dir|Folder|SMB|WebDAV` · size|xxP · date
 * (segments with empty values are omitted).
 */
@Composable
fun browseListSupportingLine(
    typeLabel: String,
    sizeBytes: Long = 0L,
    pageCount: Int = 0,
    pageCountCapped: Boolean = false,
    lastModifiedMs: Long = 0L,
): String {
    val mid = if (pageCount > 0 || pageCountCapped) {
        browseListPagesLabel(pageCount, pageCountCapped)
    } else {
        browseListSizeLabel(sizeBytes)
    }
    val date = browseListDateLabel(lastModifiedMs)
    return buildList {
        add(typeLabel)
        if (mid.isNotEmpty()) add(mid)
        if (date.isNotEmpty()) add(date)
    }.joinToString(BROWSE_LIST_SEP)
}

/**
 * List-row supporting content: optional type icon (same idea as favourite / history
 * grid caption badges) then the [browseListSupportingLine] text.
 */
@Composable
fun BrowseListSupportingContent(
    text: String,
    typeIcon: ImageVector? = null,
) {
    if (typeIcon == null) {
        Text(text)
        return
    }
    val iconSize = with(LocalDensity.current) {
        MaterialTheme.typography.bodyMedium.fontSize.toDp()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = typeIcon,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text)
    }
}

/** Cover source for browse list rows (local path or lazy remote download). */
sealed class BrowseCover {
    data class Local(val path: Path) : BrowseCover()

    /** Local archive or document — first page via [ArchiveCoverCache] (ZIP/TAR, solid RAR/7z, PDF/EPUB). */
    data class LocalArchive(val archivePath: Path) : BrowseCover()
    data class Smb(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
    data class WebDav(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()

    /** Remote archive/document cover (ZIP/TAR stream, solid sequential page 0, or PDF/EPUB extract). */
    data class SmbArchive(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
    data class WebDavArchive(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()

    /** Named member inside a remote ZIP/CBZ (zip-as-dir folder thumb). */
    data class SmbZipMember(
        val sourceId: Long,
        val zipRelativeFile: String,
        val memberRel: String,
    ) : BrowseCover()

    data class WebDavZipMember(
        val sourceId: Long,
        val zipRelativeFile: String,
        val memberRel: String,
    ) : BrowseCover()
}

@Composable
fun BrowseDirectoryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    cover: BrowseCover? = null,
    showFolderThumb: Boolean = false,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
    lastModifiedMs: Long = 0L,
) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                browseListSupportingLine(
                    typeLabel = "Dir",
                    lastModifiedMs = lastModifiedMs,
                ),
            )
        },
        // Same 56dp leading slot as [BrowseFolderGalleryRow] (icon placeholder when no thumb).
        leadingContent = {
            BrowseCoverThumb(
                cover = cover.takeIf { showFolderThumb },
                decodeSizePx = CoverThumb.listDecodePx(),
                retryKey = thumbRetryKey,
                allowRemoteFetch = allowRemoteFetch,
                placeholderIcon = Icons.Default.Folder,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    )
}

@Composable
fun BrowseFolderGalleryRow(
    name: String,
    pageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    /** @deprecated Prefer [cover]. */
    coverPath: Path? = null,
    pageCountCapped: Boolean = false,
    /** See [BrowseCoverThumb.retryKey] (SMB pull-to-refresh / parent bump). */
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
    showPages: Boolean = true,
    /** Long-press → photo-grid virtual folder; null keeps click-only. */
    onLongClick: (() -> Unit)? = null,
    lastModifiedMs: Long = 0L,
) {
    val haptic = LocalHapticFeedback.current
    val resolvedCover = cover ?: coverPath?.let { BrowseCover.Local(it) }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                browseListSupportingLine(
                    typeLabel = "Folder",
                    pageCount = if (showPages) pageCount else 0,
                    pageCountCapped = showPages && pageCountCapped,
                    lastModifiedMs = lastModifiedMs,
                ),
            )
        },
        leadingContent = {
            BrowseCoverThumb(
                cover = resolvedCover,
                decodeSizePx = CoverThumb.listDecodePx(),
                retryKey = thumbRetryKey,
                allowRemoteFetch = allowRemoteFetch,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    )
}

@Composable
fun BrowseArchiveGalleryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
    /** e.g. PDF long-press → open in external app; null keeps click-only. */
    onLongClick: (() -> Unit)? = null,
    /** Real archive basename / relative path for extension meta (defaults to [name]). */
    fileName: String = name,
    sizeBytes: Long = 0L,
    lastModifiedMs: Long = 0L,
) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                browseListSupportingLine(
                    typeLabel = browseFileExtensionLabel(fileName),
                    sizeBytes = sizeBytes,
                    lastModifiedMs = lastModifiedMs,
                ),
            )
        },
        leadingContent = {
            BrowseCoverThumb(
                cover = cover,
                decodeSizePx = CoverThumb.listDecodePx(),
                retryKey = thumbRetryKey,
                allowRemoteFetch = allowRemoteFetch,
                placeholderIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    )
}

@Composable
fun BrowseVideoRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailSource: VideoThumbnailSource? = null,
    /**
     * When false, only show an on-disk JPEG (old/offline listing). No extract.
     */
    allowRemoteFetch: Boolean = true,
    /** Long-press → open in external app; null keeps click-only. */
    onLongClick: (() -> Unit)? = null,
    /** Real video basename / relative path for extension meta (defaults to [name]). */
    fileName: String = name,
    sizeBytes: Long = 0L,
    lastModifiedMs: Long = 0L,
) {
    val haptic = LocalHapticFeedback.current
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                browseListSupportingLine(
                    typeLabel = browseFileExtensionLabel(fileName),
                    sizeBytes = sizeBytes,
                    lastModifiedMs = lastModifiedMs,
                ),
            )
        },
        leadingContent = {
            // Same 56dp / 24dp icon metrics as [BrowseCoverThumb] list default.
            BrowseVideoThumbnail(
                source = thumbnailSource,
                modifier = Modifier.size(56.dp).clip(ShapeDefaults.Medium),
                iconSize = 24.dp,
                allowRemoteFetch = allowRemoteFetch,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    )
}

@Composable
fun BrowseFileRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press → system "Open with"; defaults to [onClick]. */
    onLongClick: (() -> Unit)? = null,
    /**
     * Image file in Folder mode: same [BrowseCover] key as photo grid; fetch gated by
     * [Settings.downloadNetworkPhotoGridThumb] / [Settings.saveThumbOriginalCache].
     */
    cover: BrowseCover? = null,
    showPhotoThumb: Boolean = false,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
    /** Real file basename / relative path for extension meta (defaults to [name]). */
    fileName: String = name,
    sizeBytes: Long = 0L,
    lastModifiedMs: Long = 0L,
) {
    val haptic = LocalHapticFeedback.current
    val longClick = onLongClick ?: onClick
    val usePhotoThumb = showPhotoThumb && cover != null
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                browseListSupportingLine(
                    typeLabel = browseFileExtensionLabel(fileName),
                    sizeBytes = sizeBytes,
                    lastModifiedMs = lastModifiedMs,
                ),
            )
        },
        // Same 56dp leading slot as [BrowseFolderGalleryRow] (file icon when no photo thumb).
        leadingContent = {
            BrowseCoverThumb(
                cover = cover.takeIf { usePhotoThumb },
                decodeSizePx = CoverThumb.listDecodePx(),
                retryKey = thumbRetryKey,
                allowRemoteFetch = allowRemoteFetch,
                photoGridThumb = usePhotoThumb,
                placeholderIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    longClick()
                },
            ),
    )
}

// --- Grid (3-column thumb mode) ---

@Composable
fun BrowseDirectoryGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /** Top-end star badge when this dir path is favourited (O(1) set lookup — no dir scan). */
    showFavoriteStar: Boolean = false,
    /**
     * Lazy-scan cover for folder thumbs. When [showFolderThumb] and [cover] is non-null,
     * cell uses library favourite-gallery style (full-bleed cover + bottom label scrim).
     * No cover always keeps the classic icon + caption layout.
     */
    cover: BrowseCover? = null,
    showFolderThumb: Boolean = false,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
) {
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    // Style is per-item: only folders with a real cover use the gallery-thumb layout.
    val useThumbStyle = showFolderThumb && cover != null
    ElevatedCard(
        onClick = onClick,
        onLongClick = onLongClick ?: onClick,
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        if (useThumbStyle) {
            // Same as Library [FavoriteSourceGridCell] gallery: cover fills cell; label on scrim.
            Box(Modifier.fillMaxSize().clip(ShapeDefaults.Medium)) {
                BrowseCoverThumb(
                    cover = cover,
                    modifier = Modifier.fillMaxSize(),
                    placeholderSize = 40.dp,
                    decodeSizePx = CoverThumb.gridDecodePx(
                        screenWidthDp = LocalConfiguration.current.screenWidthDp,
                        columns = GalleryGridDefaults.columnCount(),
                        margin = GalleryGridDefaults.margin(),
                        gutter = GalleryGridDefaults.gutter(),
                    ),
                    retryKey = thumbRetryKey,
                    allowRemoteFetch = allowRemoteFetch,
                    placeholderIcon = Icons.Default.Folder,
                )
                if (showFavoriteStar) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(horizontal = namePadH)
                        .padding(top = 4.dp, bottom = namePadBottom),
                )
            }
        } else {
            // Classic icon + caption (no cover, or folder thumbs off).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(ShapeDefaults.Medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                if (showFavoriteStar) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = namePadH)
                    .padding(bottom = namePadBottom),
            )
        }
    }
}

@Composable
fun BrowseFolderGalleryGridItem(
    name: String,
    pageCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    pageCountCapped: Boolean = false,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
    showPages: Boolean = true,
    /** Long-press → photo-grid virtual folder; defaults to [onClick] when null. */
    onLongClick: (() -> Unit)? = null,
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        thumb = {
            Box(Modifier.fillMaxSize()) {
                BrowseCoverThumb(
                    cover = cover,
                    modifier = Modifier.fillMaxSize().clip(ShapeDefaults.Medium),
                    placeholderSize = 40.dp,
                    decodeSizePx = CoverThumb.gridDecodePx(
                        screenWidthDp = LocalConfiguration.current.screenWidthDp,
                        columns = GalleryGridDefaults.columnCount(),
                        margin = GalleryGridDefaults.margin(),
                        gutter = GalleryGridDefaults.gutter(),
                    ),
                    retryKey = thumbRetryKey,
                    allowRemoteFetch = allowRemoteFetch,
                )
                if (showPages && (pageCount > 0 || pageCountCapped)) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .widthIn(min = 32.dp)
                            .height(24.dp),
                    ) {
                        Text(
                            text = when {
                                pageCountCapped -> "∞"
                                else -> "$pageCount"
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * Image cell for photo-grid virtual folder **and** Folder-mode image files.
 * Uses the same [BrowseCover] path keys and [photoGridThumb] download prefs as photo grid.
 * When [showPhotoThumb] and [cover] are set, shows a cover thumb; otherwise a file icon.
 */
@Composable
fun BrowsePhotoGridImageItem(
    name: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    showPhotoThumb: Boolean = true,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
) {
    if (showPhotoThumb && cover != null) {
        BrowseGridCell(
            name = name,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
            thumb = {
                BrowseCoverThumb(
                    cover = cover,
                    modifier = Modifier.fillMaxSize().clip(ShapeDefaults.Medium),
                    placeholderSize = 40.dp,
                    decodeSizePx = CoverThumb.gridDecodePx(
                        screenWidthDp = LocalConfiguration.current.screenWidthDp,
                        columns = GalleryGridDefaults.columnCount(),
                        margin = GalleryGridDefaults.margin(),
                        gutter = GalleryGridDefaults.gutter(),
                    ),
                    retryKey = thumbRetryKey,
                    allowRemoteFetch = allowRemoteFetch,
                    photoGridThumb = true,
                    placeholderIcon = Icons.Default.PhotoLibrary,
                )
            },
        )
    } else {
        BrowseFileGridItem(
            name = name,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier,
        )
    }
}

@Composable
fun BrowseArchiveGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    thumbRetryKey: Any? = null,
    allowRemoteFetch: Boolean = true,
    /** e.g. PDF long-press → open in external app. */
    onLongClick: (() -> Unit)? = null,
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick ?: onClick,
        thumb = {
            BrowseCoverThumb(
                cover = cover,
                modifier = Modifier.fillMaxSize().clip(ShapeDefaults.Medium),
                placeholderSize = 40.dp,
                decodeSizePx = CoverThumb.gridDecodePx(
                    screenWidthDp = LocalConfiguration.current.screenWidthDp,
                    columns = GalleryGridDefaults.columnCount(),
                    margin = GalleryGridDefaults.margin(),
                    gutter = GalleryGridDefaults.gutter(),
                ),
                retryKey = thumbRetryKey,
                allowRemoteFetch = allowRemoteFetch,
                placeholderIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
        },
    )
}

@Composable
fun BrowseVideoGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailSource: VideoThumbnailSource? = null,
    /**
     * When false, only show an on-disk JPEG (old/offline listing). No extract.
     */
    allowRemoteFetch: Boolean = true,
    /** Long-press → open in external app; defaults to [onClick]. */
    onLongClick: (() -> Unit)? = null,
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
        onLongClick = onLongClick ?: onClick,
        modifier = modifier,
        thumb = {
            BrowseVideoThumbnail(
                thumbnailSource,
                Modifier.fillMaxSize(),
                48.dp,
                allowRemoteFetch,
            )
        },
    )
}

@Composable
private fun BrowseVideoThumbnail(
    source: VideoThumbnailSource?,
    modifier: Modifier,
    iconSize: Dp,
    allowRemoteFetch: Boolean = true,
) {
    val context = LocalContext.current
    val downloadNetworkVideoThumbs by Settings.downloadNetworkVideoThumbs.collectAsState()
    var thumbnail by remember(source) { mutableStateOf<java.io.File?>(null) }
    // Disk first (same as gallery covers). Extract only on a live listing when
    // network video thumbs are enabled.
    LaunchedEffect(source, downloadNetworkVideoThumbs, allowRemoteFetch) {
        val src = source ?: run {
            thumbnail = null
            return@LaunchedEffect
        }
        thumbnail = withIOContext {
            VideoThumbnail.cachedJpegIfPresent(src)
                ?: if (allowRemoteFetch) {
                    VideoThumbnail.getOrCreate(context, src)
                } else {
                    null
                }
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Movie,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            // Match [BrowseCoverThumb] list placeholder tint.
            tint = MaterialTheme.colorScheme.secondary,
        )
        thumbnail?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
fun BrowseFileGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Long-press → system "Open with"; defaults to [onClick]. */
    onLongClick: (() -> Unit)? = null,
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
        onLongClick = onLongClick ?: onClick,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun BrowseGridCell(
    name: String,
    onClick: () -> Unit,
    thumb: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val longClick = onLongClick ?: onClick
    // Same caption metrics as Library grid (GalleryGridDefaults).
    val nameHeight = GalleryGridDefaults.nameHeight()
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = longClick,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(ShapeDefaults.Medium),
            ) {
                thumb()
            }
            // Fixed height so 1-line and 2-line names share the same cell size;
            // text sits on the bottom of the band.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(nameHeight)
                    .padding(horizontal = namePadH),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = namePadBottom),
                )
            }
        }
    }
}

@Composable
fun BrowseCoverThumb(
    cover: BrowseCover?,
    modifier: Modifier = Modifier
        .size(56.dp)
        .clip(ShapeDefaults.Medium),
    placeholderSize: Dp = 24.dp,
    decodeSizePx: Int? = null,
    /**
     * Bumped by parent (e.g. SMB browse [refreshToken]) to clear sticky fail and re-fetch
     * **only when disk cache is missing**. Cache hits never re-download.
     */
    retryKey: Any? = null,
    /**
     * When false, only probe local disk for network/archive covers (old index-cache listing).
     * Skips remote download / stream extract so offline cached rows stay quiet.
     */
    allowRemoteFetch: Boolean = true,
    /**
     * Photo image cells (photo-grid virtual folder **or** Folder-mode image files):
     * gate network fetch with [Settings.downloadNetworkPhotoGridThumb]. Original page-cache
     * write uses [Settings.saveThumbOriginalCache] for these cells **and** gallery covers.
     * Thumbs always land in `*_thumb_cache` under the same path key as photo grid.
     */
    photoGridThumb: Boolean = false,
    placeholderIcon: ImageVector = Icons.Default.PhotoLibrary,
) {
    val resolvedDecodePx = decodeSizePx ?: CoverThumb.listDecodePx()
    val context = LocalContext.current
    val downloadRemoteThumbs by Settings.downloadRemoteThumbs.collectAsState()
    val downloadNetworkArchiveThumbs by Settings.downloadNetworkArchiveThumbs.collectAsState()
    val downloadNetworkPhotoGridThumb by Settings.downloadNetworkPhotoGridThumb.collectAsState()
    val saveThumbOriginalCache by Settings.saveThumbOriginalCache.collectAsState()
    val allowNetworkImageDownload = if (photoGridThumb) downloadNetworkPhotoGridThumb else downloadRemoteThumbs
    val cacheThumbOriginal = saveThumbOriginalCache
    // Stable keys: BrowseCover is a new instance per list paint; identity by fields.
    val remoteKey = when (cover) {
        is BrowseCover.Smb -> "smb\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.WebDav -> "dav\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.SmbArchive -> "smba\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.WebDavArchive -> "dava\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.SmbZipMember ->
            "smbz\u0000${cover.sourceId}\u0000${cover.zipRelativeFile}\u0000${cover.memberRel}"
        is BrowseCover.WebDavZipMember ->
            "davz\u0000${cover.sourceId}\u0000${cover.zipRelativeFile}\u0000${cover.memberRel}"
        is BrowseCover.LocalArchive -> "arch\u0000${cover.archivePath}"
        is BrowseCover.Local -> "local\u0000${cover.path}"
        null -> null
    }
    // Local image paths set immediately. Archive/network thumbs: never trust main-thread
    // [knownPresent] alone (file may be trimmed) — LaunchedEffect probes disk / re-extracts.
    var localPath by remember(remoteKey) {
        mutableStateOf(
            when (cover) {
                is BrowseCover.Local -> cover.path
                is BrowseCover.LocalArchive,
                is BrowseCover.SmbArchive,
                is BrowseCover.WebDavArchive,
                is BrowseCover.SmbZipMember,
                is BrowseCover.WebDavZipMember,
                is BrowseCover.Smb,
                is BrowseCover.WebDav,
                null,
                -> null
            },
        )
    }
    var fetchFailed by remember(remoteKey) { mutableStateOf(false) }
    // Internal resume counter + external retryKey both re-run the download effect.
    var resumeEpoch by remember(remoteKey) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, remoteKey) {
        if (cover !is BrowseCover.Smb && cover !is BrowseCover.WebDav &&
            cover !is BrowseCover.LocalArchive && cover !is BrowseCover.SmbArchive &&
            cover !is BrowseCover.WebDavArchive &&
            cover !is BrowseCover.SmbZipMember &&
            cover !is BrowseCover.WebDavZipMember
        ) {
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Disk probe / re-download is owned by LaunchedEffect (IO).
                fetchFailed = false
                resumeEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Lazy: only runs when this row is composed (in LazyColumn viewport).
    // Always probe disk on IO first so cached thumbs show even when download is off.
    // Folder image covers use [downloadRemoteThumbs] (or photo-grid prefs);
    // original page-cache write uses [saveThumbOriginalCache] for gallery covers too;
    // archive first-page uses [downloadNetworkArchiveThumbs].
    LaunchedEffect(
        remoteKey,
        retryKey,
        resumeEpoch,
        allowNetworkImageDownload,
        cacheThumbOriginal,
        downloadNetworkArchiveThumbs,
        allowRemoteFetch,
    ) {
        when (cover) {
            is BrowseCover.LocalArchive -> {
                // ZIP/TAR mmap page 0; RAR/CBR/7z first-page (same open as local reader).
                when (val result = withIOContext { ArchiveCoverCache.ensureCover(cover.archivePath) }) {
                    is CoverEnsureResult.Hit -> {
                        localPath = result.path
                        fetchFailed = false
                    }
                    CoverEnsureResult.NoImages -> {
                        // Native "Found 0 images" — library row removed; browse demotes to file.
                        withIOContext {
                            LocalLibrary.hideEmptyArchive(cover.archivePath.toString())
                        }
                    }
                    CoverEnsureResult.Skip -> {
                        // Leave placeholder; ON_RESUME retries (ArchiveAccess busy while reader open).
                        fetchFailed = localPath == null
                    }
                }
            }
            is BrowseCover.SmbArchive -> {
                val key = "smb:${cover.sourceId}:${cover.remoteRelativeFile}"
                val name = cover.remoteRelativeFile.substringAfterLast('/')
                    .substringAfterLast('\\')
                val solid = isSolidArchiveFileName(name)
                // Always probe disk (thumb JPEG or solid extract page 0) even if download is off.
                val diskOnly = withIOContext { ArchiveCoverCache.tryDiskCover(key) }
                if (diskOnly != null) {
                    localPath = diskOnly
                    fetchFailed = false
                    return@LaunchedEffect
                }
                if (!allowRemoteFetch || !downloadNetworkArchiveThumbs) return@LaunchedEffect
                val result = withIOContext {
                    if (solid) {
                        ArchiveCoverCache.ensureSolidStreamCover(key) {
                            val source = SmbRepository.load(cover.sourceId)
                                ?: error("SMB source missing")
                            val password = SmbPasswordStore.get(cover.sourceId)
                            SmbArchiveByteSource(
                                source,
                                password,
                                cover.remoteRelativeFile,
                                preferSequential = true,
                                pipeline = false,
                                sequentialWindow =
                                com.hippo.ehviewer.library.ReadAheadArchiveByteSource.COVER_WINDOW,
                                yieldable = true,
                            )
                        }
                    } else {
                        ArchiveCoverCache.ensureStreamCover(key) {
                            val source = SmbRepository.load(cover.sourceId)
                                ?: error("SMB source missing")
                            val password = SmbPasswordStore.get(cover.sourceId)
                            SmbArchiveByteSource(
                                source,
                                password,
                                cover.remoteRelativeFile,
                                pipeline = !isDocumentFileName(name),
                                yieldable = true,
                            )
                        }
                    }
                }
                when (result) {
                    is CoverEnsureResult.Hit -> {
                        localPath = result.path
                        fetchFailed = false
                    }
                    CoverEnsureResult.NoImages -> EmptyArchiveRegistry.mark(key)
                    CoverEnsureResult.Skip -> Unit
                }
            }
            is BrowseCover.WebDavArchive -> {
                val key = "webdav:${cover.sourceId}:${cover.remoteRelativeFile}"
                val name = cover.remoteRelativeFile.substringAfterLast('/')
                    .substringAfterLast('\\')
                val solid = isSolidArchiveFileName(name)
                val diskOnly = withIOContext { ArchiveCoverCache.tryDiskCover(key) }
                if (diskOnly != null) {
                    localPath = diskOnly
                    fetchFailed = false
                    return@LaunchedEffect
                }
                if (!allowRemoteFetch || !downloadNetworkArchiveThumbs) return@LaunchedEffect
                val result = withIOContext {
                    if (solid) {
                        ArchiveCoverCache.ensureSolidStreamCover(key) {
                            val source = WebDavRepository.load(cover.sourceId)
                                ?: error("WebDAV source missing")
                            val password = WebDavPasswordStore.get(cover.sourceId)
                            WebDavArchiveByteSource(
                                source,
                                password,
                                cover.remoteRelativeFile,
                                preferSequential = true,
                                pipeline = false,
                                sequentialWindow =
                                com.hippo.ehviewer.library.ReadAheadArchiveByteSource.COVER_WINDOW,
                            )
                        }
                    } else {
                        ArchiveCoverCache.ensureStreamCover(key) {
                            val source = WebDavRepository.load(cover.sourceId)
                                ?: error("WebDAV source missing")
                            val password = WebDavPasswordStore.get(cover.sourceId)
                            WebDavArchiveByteSource(
                                source,
                                password,
                                cover.remoteRelativeFile,
                                pipeline = !isDocumentFileName(name),
                            )
                        }
                    }
                }
                when (result) {
                    is CoverEnsureResult.Hit -> {
                        localPath = result.path
                        fetchFailed = false
                    }
                    CoverEnsureResult.NoImages -> EmptyArchiveRegistry.mark(key)
                    CoverEnsureResult.Skip -> Unit
                }
            }
            is BrowseCover.Smb -> {
                val cache = SmbCache.thumbCachePath(cover.sourceId, cover.remoteRelativeFile)
                val onDisk = withIOContext { SmbCache.isCachedOnDisk(cache) }
                if (onDisk) {
                    withIOContext { SmbCache.touch(cache) }
                    localPath = cache
                    fetchFailed = false
                    return@LaunchedEffect
                }
                if (!allowRemoteFetch || !allowNetworkImageDownload) {
                    // No network; placeholder only when nothing cached.
                    fetchFailed = false
                    return@LaunchedEffect
                }
                fetchFailed = false
                var lastError: Throwable? = null
                repeat(3) { attempt ->
                    try {
                        val source = SmbRepository.load(cover.sourceId) ?: error("SMB source missing")
                        val password = SmbPasswordStore.get(cover.sourceId)
                        localPath = SmbCache.ensureBrowseThumb(
                            cover.sourceId,
                            cover.remoteRelativeFile,
                            cacheOriginal = cacheThumbOriginal,
                        ) { out ->
                            SmbGateway.downloadFile(
                                source,
                                password,
                                cover.remoteRelativeFile,
                                out,
                                yieldable = true,
                            )
                        }
                        fetchFailed = false
                        return@LaunchedEffect
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                        if (attempt < 2) kotlinx.coroutines.delay(150L * (attempt + 1))
                    }
                }
                lastError?.let { logcat(it) }
                fetchFailed = true
            }
            is BrowseCover.WebDav -> {
                val cache = WebDavCache.thumbCachePath(cover.sourceId, cover.remoteRelativeFile)
                val onDisk = withIOContext { WebDavCache.isCachedOnDisk(cache) }
                if (onDisk) {
                    withIOContext { WebDavCache.touch(cache) }
                    localPath = cache
                    fetchFailed = false
                    return@LaunchedEffect
                }
                if (!allowRemoteFetch || !allowNetworkImageDownload) {
                    fetchFailed = false
                    return@LaunchedEffect
                }
                fetchFailed = false
                var lastError: Throwable? = null
                repeat(3) { attempt ->
                    try {
                        val source = WebDavRepository.load(cover.sourceId) ?: error("WebDAV source missing")
                        val password = WebDavPasswordStore.get(cover.sourceId)
                        localPath = WebDavCache.ensureBrowseThumb(
                            cover.sourceId,
                            cover.remoteRelativeFile,
                            cacheOriginal = cacheThumbOriginal,
                        ) { out ->
                            WebDavClient.downloadFile(source, password, cover.remoteRelativeFile, out)
                        }
                        fetchFailed = false
                        return@LaunchedEffect
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = e
                        if (attempt < 2) kotlinx.coroutines.delay(150L * (attempt + 1))
                    }
                }
                lastError?.let { logcat(it) }
                fetchFailed = true
            }
            is BrowseCover.SmbZipMember -> {
                val key = "smb:${cover.sourceId}:${cover.zipRelativeFile}"
                val disk = withIOContext {
                    ZipMemberCover.destFile(key, cover.memberRel).let { f ->
                        if (f.isFile && f.length() > 0L) f.absolutePath.toPath() else null
                    }
                }
                if (disk != null) {
                    localPath = disk
                    fetchFailed = false
                    return@LaunchedEffect
                }
                if (!allowRemoteFetch || !downloadNetworkArchiveThumbs) return@LaunchedEffect
                val extracted = withIOContext {
                    val source = SmbRepository.load(cover.sourceId) ?: return@withIOContext null
                    val password = SmbPasswordStore.get(cover.sourceId)
                    ZipMemberCover.ensure(key, cover.memberRel, notifyTooLarge = false) {
                        SmbArchiveByteSource(
                            source,
                            password,
                            cover.zipRelativeFile,
                            pipeline = false,
                            yieldable = true,
                        )
                    }
                }
                if (extracted != null) {
                    localPath = extracted
                    fetchFailed = false
                } else {
                    fetchFailed = localPath == null
                }
            }
            is BrowseCover.WebDavZipMember -> {
                val key = "webdav:${cover.sourceId}:${cover.zipRelativeFile}"
                val disk = withIOContext {
                    ZipMemberCover.destFile(key, cover.memberRel).let { f ->
                        if (f.isFile && f.length() > 0L) f.absolutePath.toPath() else null
                    }
                }
                if (disk != null) {
                    localPath = disk
                    fetchFailed = false
                    return@LaunchedEffect
                }
                if (!allowRemoteFetch || !downloadNetworkArchiveThumbs) return@LaunchedEffect
                val extracted = withIOContext {
                    val source = WebDavRepository.load(cover.sourceId) ?: return@withIOContext null
                    val password = WebDavPasswordStore.get(cover.sourceId)
                    ZipMemberCover.ensure(key, cover.memberRel, notifyTooLarge = false) {
                        WebDavArchiveByteSource(
                            source,
                            password,
                            cover.zipRelativeFile,
                            pipeline = false,
                        )
                    }
                }
                if (extracted != null) {
                    localPath = extracted
                    fetchFailed = false
                } else {
                    fetchFailed = localPath == null
                }
            }
            else -> return@LaunchedEffect
        }
    }

    // Disk thumbs are already ~768px JPEG (OriginDiskCache.THUMB_EDGE); Coil is a light second pass.
    val request = remember(cover, localPath, resolvedDecodePx) {
        localPath?.let { path ->
            val cacheKey = when (cover) {
                is BrowseCover.Smb ->
                    "smb-thumb:${cover.sourceId}:${cover.remoteRelativeFile}@${SmbCache.THUMB_DISK_EDGE}"
                is BrowseCover.WebDav ->
                    "dav-thumb:${cover.sourceId}:${cover.remoteRelativeFile}@${WebDavCache.THUMB_DISK_EDGE}"
                is BrowseCover.SmbArchive ->
                    "smba-thumb:${cover.sourceId}:${cover.remoteRelativeFile}@${ArchiveCoverCache.THUMB_EDGE}"
                is BrowseCover.WebDavArchive ->
                    "dava-thumb:${cover.sourceId}:${cover.remoteRelativeFile}@${ArchiveCoverCache.THUMB_EDGE}"
                is BrowseCover.LocalArchive ->
                    "arch-thumb:${cover.archivePath}@${ArchiveCoverCache.THUMB_EDGE}"
                is BrowseCover.SmbZipMember ->
                    "smbz-thumb:${cover.sourceId}:${cover.zipRelativeFile}!${cover.memberRel}"
                is BrowseCover.WebDavZipMember ->
                    "davz-thumb:${cover.sourceId}:${cover.zipRelativeFile}!${cover.memberRel}"
                is BrowseCover.Local -> cover.path.toString()
                null -> path.toString()
            }
            with(context) {
                coverThumbRequest(
                    path = path.toString(),
                    sizePx = resolvedDecodePx,
                    memoryKey = cacheKey,
                )
            }
        }
    }

    // Icon under AsyncImage: first load shows placeholder; cache hits paint immediately
    // without Success-only gating (which flashed on every LazyList recycle).
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            placeholderIcon,
            contentDescription = null,
            modifier = Modifier.size(placeholderSize),
            tint = MaterialTheme.colorScheme.secondary,
        )
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * Section label for folder browse lists (Directories / Galleries / …).
 * Optional [onClick] (e.g. collapse) uses **no ripple** (`indication = null`).
 * When clickable, the hit target is the full header row (text + trailing space),
 * same in list and grid — not only the label glyphs.
 */
@Composable
fun BrowseSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .then(
                if (onClick != null) {
                    // fillMaxWidth so list matches grid: tap anywhere on the header band.
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** In-memory collapse keys for folder-view section headers (not disk-persisted). */
enum class BrowseFolderSection {
    Directories,
    Galleries,
    Videos,
    Files,
}

/**
 * Section collapse for **one folder** ([folderKey] = path / SMB-WebDAV dir key).
 * Hide Videos here does not collapse Videos in other directories. Process memory only
 * (return to the same folder restores; process death clears).
 */
@Composable
fun rememberBrowseSectionCollapse(
    folderKey: Any? = null,
): Pair<Set<BrowseFolderSection>, (BrowseFolderSection) -> Unit> {
    val key = folderKey?.toString().orEmpty()
    var collapsed by remember(key) {
        mutableStateOf(
            BrowseSession.collapsedBrowseSections(key).mapNotNull { name ->
                runCatching { BrowseFolderSection.valueOf(name) }.getOrNull()
            }.toSet(),
        )
    }
    val toggle: (BrowseFolderSection) -> Unit = { section ->
        val next = if (section in collapsed) collapsed - section else collapsed + section
        collapsed = next
        BrowseSession.setCollapsedBrowseSections(key, next.map { it.name }.toSet())
    }
    return collapsed to toggle
}

@Composable
fun BrowseEmptyHint(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
