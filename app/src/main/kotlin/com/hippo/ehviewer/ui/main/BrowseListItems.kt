package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.ehviewer.core.files.mkdirs
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import java.io.File
import java.io.FileOutputStream
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
            BrowseCoverThumb(cover = resolvedCover)
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun BrowseCoverThumb(cover: BrowseCover?) {
    val context = LocalContext.current
    var localPath by remember(cover) {
        mutableStateOf(
            when (cover) {
                is BrowseCover.Local -> cover.path
                is BrowseCover.Smb -> {
                    val name = cover.remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\')
                    val parent = cover.remoteRelativeFile.substringBeforeLast('/', "")
                        .substringBeforeLast('\\', "")
                        .replace('\\', '/')
                    val cache = SmbCache.cachePath(cover.sourceId, parent, name)
                    cache.takeIf { SmbCache.isCached(it) }
                }
                null -> null
            },
        )
    }
    var fetchFailed by remember(cover) { mutableStateOf(false) }

    // Lazy: only runs when this row is composed (in LazyColumn viewport).
    LaunchedEffect(cover) {
        val smb = cover as? BrowseCover.Smb ?: return@LaunchedEffect
        if (localPath != null || fetchFailed) return@LaunchedEffect
        runCatching {
            val name = smb.remoteRelativeFile.substringAfterLast('/').substringAfterLast('\\')
            val parent = smb.remoteRelativeFile.substringBeforeLast('/', missingDelimiterValue = "")
                .substringBeforeLast('\\', missingDelimiterValue = "")
                .replace('\\', '/')
            val cache = SmbCache.cachePath(smb.sourceId, parent, name)
            if (SmbCache.isCached(cache)) {
                localPath = cache
                return@runCatching
            }
            val source = SmbRepository.load(smb.sourceId) ?: error("SMB source missing")
            val password = SmbPasswordStore.get(smb.sourceId)
            cache.parent?.mkdirs()
            val tmp = File(cache.toString() + ".tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    SmbGateway.downloadFile(source, password, smb.remoteRelativeFile, out)
                }
                check(tmp.renameTo(File(cache.toString()))) { "commit cover cache failed" }
                localPath = cache
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
        }.onFailure {
            logcat(it)
            fetchFailed = true
        }
    }

    val request = remember(localPath) {
        localPath?.let { path ->
            with(context) {
                imageRequest {
                    data(path.toUri())
                    memoryCacheKey(path.toString())
                    diskCacheKey(path.toString())
                }
            }
        }
    }

    Box(
        modifier = Modifier.size(56.dp).clip(ShapeDefaults.Small),
        contentAlignment = Alignment.Center,
    ) {
        if (request != null) {
            val painter = rememberAsyncImagePainter(model = request)
            val state by painter.state.collectAsState()
            if (state is AsyncImagePainter.State.Success) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
        } else {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
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
