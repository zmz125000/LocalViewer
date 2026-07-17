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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.ktbuilder.imageRequest
import okio.Path

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
    coverPath: Path? = null,
) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                if (pageCount > 0) {
                    stringResource(R.string.browse_folder_gallery_pages, pageCount)
                } else {
                    stringResource(R.string.library_gallery_folder)
                },
            )
        },
        leadingContent = {
            BrowseCoverThumb(coverPath = coverPath)
        },
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun BrowseCoverThumb(coverPath: Path?) {
    val context = LocalContext.current
    val request = remember(coverPath) {
        coverPath?.let { path ->
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
            // Compose only when this row is in LazyColumn viewport → lazy cover fetch
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
