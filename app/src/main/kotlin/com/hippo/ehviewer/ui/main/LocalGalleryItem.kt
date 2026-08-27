package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.ui.component.CrystalCard
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
import com.hippo.ehviewer.ui.screen.collectListThumbSizeAsState
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

/** Kind / page-count chip — text on secondaryContainer, used on list cards. */
@Composable
private fun LocalGalleryMetaChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .clip(ShapeDefaults.Small)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(vertical = 2.dp, horizontal = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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

@Composable
fun LocalGalleryListItem(
    gallery: LocalGalleryEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) = CrystalCard(
    modifier = modifier,
    onClick = onClick,
    onLongClick = onLongClick,
) {
    val cardHeight by collectListThumbSizeAsState()
    val listDecodePx = CoverThumb.libraryListDecodePx(cardHeight)
    Row {
        CoverImage(
            coverPath = gallery.coverPath,
            sizePx = listDecodePx,
            placeholder = if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                Icons.Default.Inventory2
            } else {
                Icons.Default.Folder
            },
            archiveContentPath = gallery.contentPath.takeIf {
                gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE
            },
            modifier = Modifier
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(ShapeDefaults.Medium),
        )
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = gallery.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            // Push kind + page chips to bottom-left of the card body.
            Spacer(modifier = Modifier.weight(1f))
            val kindLabel = if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                stringResource(R.string.library_gallery_archive)
            } else {
                stringResource(R.string.library_gallery_folder)
            }
            val pageLabel = if (showPages && gallery.pageCount > 0) {
                val readProgress = if (showProgress) {
                    remember(gallery.id) { EhDB.getReadProgressFlow(gallery.id) }.collectAsState(0).value
                } else {
                    0
                }
                if (readProgress > 0) {
                    "${readProgress + 1}/${gallery.pageCount}P"
                } else {
                    "${gallery.pageCount}P"
                }
            } else {
                null
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocalGalleryMetaChip(text = kindLabel)
                if (pageLabel != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = pageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * History list row for library galleries and browse folder path links.
 * Does not use EH thumb CDN / shared-element transitions.
 */
@Composable
fun HistoryListItem(
    info: GalleryInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) = CrystalCard(
    modifier = modifier,
    onClick = onClick,
    onLongClick = onLongClick,
) {
    val kind = LocalHistory.kindLabelKey(info)
    val kindLabel = when (kind) {
        LocalHistory.KindLabel.Library -> stringResource(R.string.library)
        LocalHistory.KindLabel.Archive -> stringResource(R.string.library_gallery_archive)
        LocalHistory.KindLabel.Folder -> stringResource(R.string.folder)
        LocalHistory.KindLabel.Smb -> stringResource(R.string.network)
        LocalHistory.KindLabel.WebDav -> stringResource(R.string.webdav)
        LocalHistory.KindLabel.Video -> stringResource(R.string.browse_videos)
        LocalHistory.KindLabel.File -> stringResource(R.string.browse_files)
        LocalHistory.KindLabel.Unknown -> stringResource(R.string.history)
    }
    val placeholderIcon: ImageVector = when (kind) {
        LocalHistory.KindLabel.Archive -> Icons.Default.Inventory2
        LocalHistory.KindLabel.Smb -> Icons.Default.Lan
        LocalHistory.KindLabel.WebDav -> Icons.Default.Cloud
        LocalHistory.KindLabel.Library, LocalHistory.KindLabel.File ->
            Icons.AutoMirrored.Filled.InsertDriveFile
        LocalHistory.KindLabel.Video -> Icons.Default.Movie
        else -> Icons.Default.Folder
    }
    val cardHeight by collectListThumbSizeAsState()
    val listDecodePx = CoverThumb.libraryListDecodePx(cardHeight)
    val coverKey = remember(info.gid, info.thumbKey, info.token, info.uploader) {
        historyCoverKey(info)
    }
    Row {
        CoverImage(
            coverPath = coverKey,
            sizePx = listDecodePx,
            placeholder = placeholderIcon,
            modifier = Modifier
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(ShapeDefaults.Medium),
        )
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = info.title.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            val pageLabel = if (showPages && LocalHistory.showsPageProgress(info)) {
                val readProgress = if (showProgress) {
                    remember(info.gid) { EhDB.getReadProgressFlow(info.gid) }.collectAsState(0).value
                } else {
                    0
                }
                if (readProgress > 0) {
                    "${readProgress + 1}/${info.pages}P"
                } else {
                    "${info.pages}P"
                }
            } else {
                null
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LocalGalleryMetaChip(text = kindLabel)
                if (pageLabel != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = pageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
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
