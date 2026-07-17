package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R

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
            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
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
