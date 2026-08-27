package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.ui.component.ElevatedCard
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.coil.coverThumbRequest
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.CoverEnsureResult
import com.hippo.ehviewer.library.HistoryThumbKey
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalHistoryTarget
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.SMB_BROWSE_TOKEN
import com.hippo.ehviewer.library.WEBDAV_BROWSE_TOKEN
import com.hippo.ehviewer.library.isVideoFileName
import okio.Path.Companion.toPath

/** Prefer stored [GalleryInfo.thumbKey]; for network archives / videos derive the logical cover key. */
private fun historyCoverKey(info: GalleryInfo): String? {
    info.thumbKey?.takeIf { it.isNotBlank() }?.let { return it }
    return when (val target = LocalHistory.parse(info)) {
        is LocalHistoryTarget.SmbStreamArchive ->
            HistoryThumbKey.smbArchive(target.sourceId, target.remotePath)
        is LocalHistoryTarget.WebDavStreamArchive ->
            HistoryThumbKey.webdavArchive(target.sourceId, target.remotePath)
        is LocalHistoryTarget.LocalFile ->
            target.path.takeIf { isVideoFileName(it) }?.let { HistoryThumbKey.videoLocal(it) }
        is LocalHistoryTarget.SmbFile ->
            target.remotePath.takeIf { isVideoFileName(it) }
                ?.let { HistoryThumbKey.videoSmb(target.sourceId, it) }
        is LocalHistoryTarget.WebDavFile ->
            target.remotePath.takeIf { isVideoFileName(it) }
                ?.let { HistoryThumbKey.videoWebdav(target.sourceId, it) }
        else -> null
    }
}

@Composable
private fun coverRequest(coverPath: String?, sizePx: Int): ImageRequest? {
    val context = LocalContext.current
    // Pass path string only — CoverPathFetcher resolves MediaStore/SAF URI off-main.
    // Do not call path.toUri() here (sync ContentResolver.query freezes tab switches).
    return remember(coverPath, sizePx) {
        coverPath?.let { path ->
            with(context) {
                coverThumbRequest(
                    path = path,
                    sizePx = sizePx,
                    memoryKey = path,
                )
            }
        }
    }
}

@Composable
internal fun CoverImage(
    coverPath: String?,
    sizePx: Int,
    placeholder: ImageVector,
    modifier: Modifier = Modifier,
    /**
     * When set, extract first page if [coverPath] is empty **or** points at a missing
     * file (evicted `archive_thumb` after cache trim / clear).
     */
    archiveContentPath: String? = null,
) {
    // Do not paint a DB/history path until IO verifies it still exists — stale
    // archive_thumb keys cause CoverPathFetcher ENOENT spam. Logical HistoryThumbKey
    // values (smb-thumb: / dav-thumb:) resolve to smb/webdav_thumb_cache only on hit.
    var resolvedCover by remember(coverPath, archiveContentPath) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(coverPath, archiveContentPath) {
        val stored = coverPath?.takeIf { it.isNotBlank() }
        if (stored != null) {
            val resolved = withIOContext { HistoryThumbKey.resolveReadablePath(stored) }
            if (resolved != null) {
                resolvedCover = resolved
                return@LaunchedEffect
            }
            // Evicted / deleted thumb — drop optimistic paint; re-extract if archive.
            resolvedCover = null
        }
        val arch = archiveContentPath ?: return@LaunchedEffect
        when (val result = withIOContext { ArchiveCoverCache.ensureCover(arch.toPath()) }) {
            is CoverEnsureResult.Hit -> {
                val pathStr = result.path.toString()
                resolvedCover = pathStr
                withIOContext {
                    LocalLibrary.updateGalleryPageAndCoverByContentPath(
                        arch,
                        0,
                        pathStr,
                    )
                }
            }
            CoverEnsureResult.NoImages -> {
                // Confirmed empty (native "Found 0 images") — drop from library listing.
                withIOContext { LocalLibrary.hideEmptyArchive(arch) }
            }
            CoverEnsureResult.Skip -> Unit
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = placeholder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        val request = coverRequest(resolvedCover, sizePx)
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

/** Same leading thumb size as browse folder [ListItem] rows. */
private val LibraryListLeadSize = 56.dp

@Composable
fun LocalGalleryListItem(
    gallery: LocalGalleryEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    @Suppress("UNUSED_PARAMETER") showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val listDecodePx = CoverThumb.listDecodePx()
    val isArchive = gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE
    // Best-effort local size for archive rows (folder list uses listing size).
    val archiveSizeBytes = remember(gallery.contentPath, isArchive) {
        if (!isArchive) {
            0L
        } else {
            runCatching {
                java.io.File(gallery.contentPath).takeIf { it.isFile }?.length() ?: 0L
            }.getOrDefault(0L)
        }
    }
    val metaLine = browseListSupportingLine(
        typeLabel = if (isArchive) {
            browseFileExtensionLabel(gallery.contentPath)
        } else {
            "Folder"
        },
        sizeBytes = archiveSizeBytes,
        pageCount = when {
            !showPages -> 0
            isArchive && archiveSizeBytes > 0L -> 0 // prefer byte size when known
            else -> gallery.pageCount
        },
        lastModifiedMs = gallery.mtime,
    )
    ListItem(
        headlineContent = { Text(gallery.title) },
        supportingContent = { Text(metaLine) },
        leadingContent = {
            CoverImage(
                coverPath = gallery.coverPath,
                sizePx = listDecodePx,
                placeholder = if (isArchive) {
                    Icons.Default.Inventory2
                } else {
                    Icons.Default.Folder
                },
                archiveContentPath = gallery.contentPath.takeIf { isArchive },
                modifier = Modifier
                    .size(LibraryListLeadSize)
                    .clip(ShapeDefaults.Medium),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    )
}

/**
 * History list row — same [ListItem] + 56dp leading layout as browse folder list.
 * Does not use EH thumb CDN / shared-element transitions.
 */
@Composable
fun HistoryListItem(
    info: GalleryInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    @Suppress("UNUSED_PARAMETER") showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val kind = LocalHistory.kindLabelKey(info)
    val placeholderIcon: ImageVector = when (kind) {
        LocalHistory.KindLabel.Archive -> Icons.Default.Inventory2
        LocalHistory.KindLabel.Smb -> Icons.Default.Lan
        LocalHistory.KindLabel.WebDav -> Icons.Default.Cloud
        LocalHistory.KindLabel.Library, LocalHistory.KindLabel.File ->
            Icons.AutoMirrored.Filled.InsertDriveFile
        LocalHistory.KindLabel.Video -> Icons.Default.Movie
        else -> Icons.Default.Folder
    }
    val historyFileName = remember(info.uploader, info.title) {
        val path = info.uploader.orEmpty()
        path.substringAfterLast('/').substringAfterLast('\\').ifEmpty {
            info.title.orEmpty()
        }
    }
    val typeLabel = when {
        LocalHistory.isBrowseDirectory(info) -> "Dir"
        kind == LocalHistory.KindLabel.Archive ||
            kind == LocalHistory.KindLabel.Video ||
            kind == LocalHistory.KindLabel.File -> browseFileExtensionLabel(historyFileName)
        kind == LocalHistory.KindLabel.Folder ||
            kind == LocalHistory.KindLabel.Library -> "Folder"
        kind == LocalHistory.KindLabel.Smb -> "SMB"
        kind == LocalHistory.KindLabel.WebDav -> "WebDAV"
        else -> "File"
    }
    val metaLine = browseListSupportingLine(
        typeLabel = typeLabel,
        pageCount = if (showPages && LocalHistory.showsPageProgress(info)) info.pages else 0,
    )
    val listDecodePx = CoverThumb.listDecodePx()
    val coverKey = remember(info.gid, info.thumbKey, info.token, info.uploader) {
        historyCoverKey(info)
    }
    ListItem(
        headlineContent = { Text(info.title.orEmpty()) },
        supportingContent = {
            // Same type icon as [HistoryGridItem] caption row.
            BrowseListSupportingContent(text = metaLine, typeIcon = placeholderIcon)
        },
        leadingContent = {
            CoverImage(
                coverPath = coverKey,
                sizePx = listDecodePx,
                placeholder = placeholderIcon,
                modifier = Modifier
                    .size(LibraryListLeadSize)
                    .clip(ShapeDefaults.Medium),
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    )
}

/** History grid cell — same layout as [LocalGalleryGridItem], covers library + browse path rows. */
@Composable
fun HistoryGridItem(
    info: GalleryInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val kind = LocalHistory.kindLabelKey(info)
    val placeholderIcon: ImageVector = when (kind) {
        LocalHistory.KindLabel.Archive -> Icons.Default.Inventory2
        LocalHistory.KindLabel.Smb -> Icons.Default.Lan
        LocalHistory.KindLabel.WebDav -> Icons.Default.Cloud
        LocalHistory.KindLabel.Library, LocalHistory.KindLabel.File ->
            Icons.AutoMirrored.Filled.InsertDriveFile
        LocalHistory.KindLabel.Video -> Icons.Default.Movie
        else -> Icons.Default.Folder
    }
    val nameHeight = GalleryGridDefaults.nameHeight()
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    val gridDecodePx = CoverThumb.gridDecodePx(
        screenWidthDp = LocalConfiguration.current.screenWidthDp,
        columns = GalleryGridDefaults.columnCount(),
        margin = GalleryGridDefaults.margin(),
        gutter = GalleryGridDefaults.gutter(),
    )
    val coverKey = remember(info.gid, info.thumbKey, info.token, info.uploader) {
        historyCoverKey(info)
    }
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(ShapeDefaults.Medium),
            ) {
                CoverImage(
                    coverPath = coverKey,
                    sizePx = gridDecodePx,
                    placeholder = placeholderIcon,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showPages && LocalHistory.showsPageProgress(info)) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .widthIn(min = 32.dp)
                            .height(24.dp),
                    ) {
                        val readProgress = if (showProgress) {
                            remember(info.gid) { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0).value
                        } else {
                            0
                        }
                        Text(
                            text = if (readProgress > 0) {
                                "${readProgress + 1}/${info.pages}"
                            } else {
                                "${info.pages}"
                            },
                        )
                    }
                }
            }
            // Fixed name band (same as before): caption sits on the bottom.
            // Icon is CenterVertically with the text only — not the whole name box.
            val labelIconSize = with(LocalDensity.current) {
                MaterialTheme.typography.labelMedium.fontSize.toDp()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(nameHeight)
                    .padding(horizontal = namePadH),
                contentAlignment = Alignment.BottomStart,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = namePadBottom),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = placeholderIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(labelIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = info.title.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * History **Directories** section grid cell — same square layout as Library
 * [FavoriteSourceGridCell]: full-bleed cover + bottom scrim when a thumb is cached;
 * otherwise 48.dp folder icon + caption (Lan/Cloud badge for network browse pins).
 */
@Composable
fun HistoryDirectoryGridItem(
    info: GalleryInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    modifier: Modifier = Modifier,
) {
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    val coverKey = remember(info.gid, info.thumbKey, info.token, info.uploader) {
        historyCoverKey(info)
    }
    var resolvedThumb by remember(coverKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(coverKey) {
        resolvedThumb = withIOContext { HistoryThumbKey.resolveReadablePath(coverKey) }
    }
    val useThumbStyle = resolvedThumb != null
    val networkBadge = when (info.token) {
        SMB_BROWSE_TOKEN -> Icons.Default.Lan
        WEBDAV_BROWSE_TOKEN -> Icons.Default.Cloud
        else -> null
    }
    ElevatedCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        if (useThumbStyle) {
            Box(Modifier.fillMaxSize().clip(ShapeDefaults.Medium)) {
                CoverImage(
                    coverPath = coverKey,
                    sizePx = CoverThumb.gridDecodePx(
                        screenWidthDp = LocalConfiguration.current.screenWidthDp,
                        columns = GalleryGridDefaults.columnCount(),
                        margin = GalleryGridDefaults.margin(),
                        gutter = GalleryGridDefaults.gutter(),
                    ),
                    placeholder = Icons.Default.Folder,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = info.title.orEmpty(),
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
            val labelIconSize = with(LocalDensity.current) {
                MaterialTheme.typography.labelMedium.fontSize.toDp()
            }
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
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = namePadH)
                    .padding(bottom = namePadBottom),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (networkBadge != null) {
                    Icon(
                        networkBadge,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(labelIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = info.title.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun LocalGalleryGridItem(
    gallery: LocalGalleryEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    val nameHeight = GalleryGridDefaults.nameHeight()
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    val gridDecodePx = CoverThumb.gridDecodePx(
        screenWidthDp = LocalConfiguration.current.screenWidthDp,
        columns = GalleryGridDefaults.columnCount(),
        margin = GalleryGridDefaults.margin(),
        gutter = GalleryGridDefaults.gutter(),
    )
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(ShapeDefaults.Medium),
            ) {
                CoverImage(
                    coverPath = gallery.coverPath,
                    sizePx = gridDecodePx,
                    archiveContentPath = gallery.contentPath.takeIf {
                        gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE
                    },
                    placeholder = if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        Icons.Default.Inventory2
                    } else {
                        Icons.Default.Folder
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (showPages && gallery.pageCount > 0) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .widthIn(min = 32.dp)
                            .height(24.dp),
                    ) {
                        val readProgress = if (showProgress) {
                            remember(gallery.id) { EhDB.getReadProgressFlow(gallery.id) }.collectAsState(0).value
                        } else {
                            0
                        }
                        Text(
                            text = if (readProgress > 0) {
                                "${readProgress + 1}/${gallery.pageCount}"
                            } else {
                                "${gallery.pageCount}"
                            },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(nameHeight)
                    .padding(horizontal = namePadH),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = gallery.title,
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
