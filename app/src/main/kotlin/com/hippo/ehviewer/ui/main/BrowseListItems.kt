package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.graphics.vector.ImageVector
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.coil.coverThumbRequest
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.CoverEnsureResult
import com.hippo.ehviewer.library.EmptyArchiveRegistry
import com.hippo.ehviewer.library.LocalLibrary
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
import okio.Path

/** Cover source for browse list rows (local path or lazy remote download). */
sealed class BrowseCover {
    data class Local(val path: Path) : BrowseCover()
    /** Local comic archive — first page extracted to [ArchiveCoverCache] (skips solid 7z). */
    data class LocalArchive(val archivePath: Path) : BrowseCover()
    data class Smb(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
    data class WebDav(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
    /** Remote archive first-page cover (ZIP/TAR stream or solid sequential page 0). */
    data class SmbArchive(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
    data class WebDavArchive(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
}

@Composable
fun BrowseDirectoryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(stringResource(R.string.browse_directory)) },
        leadingContent = {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
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
) {
    val resolvedCover = cover ?: coverPath?.let { BrowseCover.Local(it) }
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                when {
                    pageCountCapped -> stringResource(R.string.browse_folder_gallery_pages_many)
                    pageCount > 0 -> stringResource(R.string.browse_folder_gallery_pages, pageCount)
                    else -> stringResource(R.string.library_gallery_folder)
                },
            )
        },
        leadingContent = {
            BrowseCoverThumb(
                cover = resolvedCover,
                decodeSizePx = CoverThumb.listDecodePx(),
                retryKey = thumbRetryKey,
            )
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
fun BrowseArchiveGalleryRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    thumbRetryKey: Any? = null,
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(stringResource(R.string.library_gallery_archive)) },
        leadingContent = {
            BrowseCoverThumb(
                cover = cover,
                modifier = Modifier
                    .size(56.dp)
                    .clip(ShapeDefaults.Medium),
                placeholderSize = 32.dp,
                decodeSizePx = CoverThumb.listDecodePx(),
                retryKey = thumbRetryKey,
                placeholderIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

// --- Grid (3-column thumb mode) ---

@Composable
fun BrowseDirectoryGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
        modifier = modifier,
        thumb = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
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
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
        modifier = modifier,
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
                )
                if (pageCount > 0 || pageCountCapped) {
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

@Composable
fun BrowseArchiveGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cover: BrowseCover? = null,
    thumbRetryKey: Any? = null,
) {
    BrowseGridCell(
        name = name,
        onClick = onClick,
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
                placeholderIcon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
        },
    )
}

@Composable
private fun BrowseGridCell(
    name: String,
    onClick: () -> Unit,
    thumb: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same caption metrics as Library grid (GalleryGridDefaults).
    val nameHeight = GalleryGridDefaults.nameHeight()
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onClick,
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
    modifier: Modifier = Modifier.size(56.dp),
    placeholderSize: Dp = 24.dp,
    decodeSizePx: Int? = null,
    /**
     * Bumped by parent (e.g. SMB browse [refreshToken]) to clear sticky fail and re-fetch
     * **only when disk cache is missing**. Cache hits never re-download.
     */
    retryKey: Any? = null,
    placeholderIcon: ImageVector = Icons.Default.PhotoLibrary,
) {
    val resolvedDecodePx = decodeSizePx ?: CoverThumb.listDecodePx()
    val context = LocalContext.current
    val downloadRemoteThumbs by Settings.downloadRemoteThumbs.collectAsState()
    val downloadNetworkArchiveThumbs by Settings.downloadNetworkArchiveThumbs.collectAsState()
    // Stable keys: BrowseCover is a new instance per list paint; identity by fields.
    val remoteKey = when (cover) {
        is BrowseCover.Smb -> "smb\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.WebDav -> "dav\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.SmbArchive -> "smba\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.WebDavArchive -> "dava\u0000${cover.sourceId}\u0000${cover.remoteRelativeFile}"
        is BrowseCover.LocalArchive -> "arch\u0000${cover.archivePath}"
        is BrowseCover.Local -> "local\u0000${cover.path}"
        null -> null
    }
    // Local image paths set immediately; archive/remote filled by LaunchedEffect after IO.
    var localPath by remember(remoteKey) {
        mutableStateOf(
            when (cover) {
                is BrowseCover.Local -> cover.path
                is BrowseCover.LocalArchive -> {
                    val cache = ArchiveCoverCache.thumbPathFor(cover.archivePath.toString())
                    cache.takeIf { ArchiveCoverCache.isCached(it) }
                }
                is BrowseCover.SmbArchive -> {
                    val key = "smb:${cover.sourceId}:${cover.remoteRelativeFile}"
                    val cache = ArchiveCoverCache.thumbPathFor(key)
                    cache.takeIf { ArchiveCoverCache.isCached(it) }
                }
                is BrowseCover.WebDavArchive -> {
                    val key = "webdav:${cover.sourceId}:${cover.remoteRelativeFile}"
                    val cache = ArchiveCoverCache.thumbPathFor(key)
                    cache.takeIf { ArchiveCoverCache.isCached(it) }
                }
                is BrowseCover.Smb -> {
                    val cache = SmbCache.thumbCachePath(cover.sourceId, cover.remoteRelativeFile)
                    cache.takeIf { SmbCache.isCached(it) }
                }
                is BrowseCover.WebDav -> {
                    val cache = WebDavCache.thumbCachePath(cover.sourceId, cover.remoteRelativeFile)
                    cache.takeIf { WebDavCache.isCached(it) }
                }
                null -> null
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
            cover !is BrowseCover.WebDavArchive
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
    // Folder image covers use [downloadRemoteThumbs]; archive first-page uses [downloadNetworkArchiveThumbs].
    LaunchedEffect(remoteKey, retryKey, resumeEpoch, downloadRemoteThumbs, downloadNetworkArchiveThumbs) {
        when (cover) {
            is BrowseCover.LocalArchive -> {
                // ZIP/TAR mmap page 0; RAR/CBR/7z first-page (same open as local reader).
                when (val result = withIOContext { ArchiveCoverCache.ensureCover(cover.archivePath) }) {
                    is CoverEnsureResult.Hit -> {
                        localPath = result.path
                        fetchFailed = false
                    }
                    CoverEnsureResult.NoImages -> {
                        // Native "Found 0 images" — hide from library + folder browse.
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
                if (!downloadNetworkArchiveThumbs) return@LaunchedEffect
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
                            )
                        }
                    } else {
                        ArchiveCoverCache.ensureStreamCover(key) {
                            val source = SmbRepository.load(cover.sourceId)
                                ?: error("SMB source missing")
                            val password = SmbPasswordStore.get(cover.sourceId)
                            SmbArchiveByteSource(source, password, cover.remoteRelativeFile)
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
                if (!downloadNetworkArchiveThumbs) return@LaunchedEffect
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
                            )
                        }
                    } else {
                        ArchiveCoverCache.ensureStreamCover(key) {
                            val source = WebDavRepository.load(cover.sourceId)
                                ?: error("WebDAV source missing")
                            val password = WebDavPasswordStore.get(cover.sourceId)
                            WebDavArchiveByteSource(source, password, cover.remoteRelativeFile)
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
                if (!downloadRemoteThumbs) {
                    // No network; placeholder only when nothing cached.
                    fetchFailed = false
                    return@LaunchedEffect
                }
                fetchFailed = false
                var lastError: Throwable? = null
                repeat(3) { attempt ->
                    val result = runCatching {
                        val source = SmbRepository.load(cover.sourceId) ?: error("SMB source missing")
                        val password = SmbPasswordStore.get(cover.sourceId)
                        SmbCache.ensureBrowseThumb(cover.sourceId, cover.remoteRelativeFile) { out ->
                            SmbGateway.downloadFile(source, password, cover.remoteRelativeFile, out)
                        }
                    }
                    if (result.isSuccess) {
                        localPath = result.getOrNull()
                        fetchFailed = false
                        return@LaunchedEffect
                    }
                    lastError = result.exceptionOrNull()
                    if (attempt < 2) kotlinx.coroutines.delay(150L * (attempt + 1))
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
                if (!downloadRemoteThumbs) {
                    fetchFailed = false
                    return@LaunchedEffect
                }
                fetchFailed = false
                var lastError: Throwable? = null
                repeat(3) { attempt ->
                    val result = runCatching {
                        val source = WebDavRepository.load(cover.sourceId) ?: error("WebDAV source missing")
                        val password = WebDavPasswordStore.get(cover.sourceId)
                        WebDavCache.ensureBrowseThumb(cover.sourceId, cover.remoteRelativeFile) { out ->
                            WebDavClient.downloadFile(source, password, cover.remoteRelativeFile, out)
                        }
                    }
                    if (result.isSuccess) {
                        localPath = result.getOrNull()
                        fetchFailed = false
                        return@LaunchedEffect
                    }
                    lastError = result.exceptionOrNull()
                    if (attempt < 2) kotlinx.coroutines.delay(150L * (attempt + 1))
                }
                lastError?.let { logcat(it) }
                fetchFailed = true
            }
            else -> return@LaunchedEffect
        }
    }

    // Disk thumbs are already ~512px JPEG; Coil size request is a light second pass.
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

@Composable
fun BrowseSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun BrowseEmptyHint(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}
