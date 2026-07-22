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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import coil3.compose.AsyncImage
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.ElevatedCard
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.coil.CoverThumb
import com.hippo.ehviewer.coil.coverThumbRequest
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import okio.Path

/** Cover source for browse list rows (local path or lazy SMB download). */
sealed class BrowseCover {
    data class Local(val path: Path) : BrowseCover()
    data class Smb(val sourceId: Long, val remoteRelativeFile: String) : BrowseCover()
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
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(stringResource(R.string.library_gallery_archive)) },
        leadingContent = {
            Icon(
                Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
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
                    Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
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
) {
    val resolvedDecodePx = decodeSizePx ?: CoverThumb.listDecodePx()
    val context = LocalContext.current
    var localPath by remember(cover) {
        mutableStateOf(
            when (cover) {
                is BrowseCover.Local -> cover.path
                is BrowseCover.Smb -> {
                    val cache = SmbCache.cachePathForRemoteFile(cover.sourceId, cover.remoteRelativeFile)
                    cache.takeIf { SmbCache.isCached(it) }
                }
                null -> null
            },
        )
    }
    var fetchFailed by remember(cover) { mutableStateOf(false) }

    // Lazy: only runs when this row is composed (in LazyColumn viewport).
    // Retry a few times — a single broken-pipe on a pooled connection must not
    // permanently blank gallery thumbs while scrolling the browse list.
    LaunchedEffect(cover) {
        val smb = cover as? BrowseCover.Smb ?: return@LaunchedEffect
        if (localPath != null || fetchFailed) return@LaunchedEffect
        val cache = SmbCache.cachePathForRemoteFile(smb.sourceId, smb.remoteRelativeFile)
        if (SmbCache.isCached(cache)) {
            localPath = cache
            return@LaunchedEffect
        }
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching {
                val source = SmbRepository.load(smb.sourceId) ?: error("SMB source missing")
                val password = SmbPasswordStore.get(smb.sourceId)
                SmbCache.downloadIfNeeded(cache) { out ->
                    SmbGateway.downloadFile(source, password, smb.remoteRelativeFile, out)
                }
                cache
            }
            if (result.isSuccess) {
                localPath = result.getOrNull()
                return@LaunchedEffect
            }
            lastError = result.exceptionOrNull()
            if (attempt < 2) {
                kotlinx.coroutines.delay(150L * (attempt + 1))
            }
        }
        lastError?.let { logcat(it) }
        fetchFailed = true
    }

    // Memory/disk keys include remote identity + decode size so list/grid recycle
    // never paints another comic's 001.jpg at full resolution.
    val request = remember(cover, localPath, resolvedDecodePx) {
        localPath?.let { path ->
            val cacheKey = when (cover) {
                is BrowseCover.Smb -> "smb:${cover.sourceId}:${cover.remoteRelativeFile}"
                is BrowseCover.Local -> cover.path.toString()
                null -> path.toString()
            }
            with(context) {
                // Path string only — MediaStore/SAF URI resolve happens in CoverPathFetcher (off-main).
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
            Icons.Default.PhotoLibrary,
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
