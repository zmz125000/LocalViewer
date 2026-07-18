package com.hippo.ehviewer.ui.main

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.ElevatedCard
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ktbuilder.imageRequest
import com.hippo.ehviewer.library.LocalHistory
import okio.Path.Companion.toPath

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
private fun coverRequest(coverPath: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(coverPath) {
        coverPath?.let { path ->
            with(context) {
                imageRequest {
                    data(path.toPath().toUri())
                    memoryCacheKey(path)
                    diskCacheKey(path)
                }
            }
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
    Row {
        Box(
            // Square cover (matchHeight so fixed list row height defines the square size)
            modifier = Modifier
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(ShapeDefaults.Medium),
            contentAlignment = Alignment.Center,
        ) {
            val request = coverRequest(gallery.coverPath)
            if (request != null) {
                Image(
                    painter = rememberAsyncImagePainter(model = request),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                        Icons.Default.Inventory2
                    } else {
                        Icons.Default.Folder
                    },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
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
        LocalHistory.KindLabel.Unknown -> stringResource(R.string.history)
    }
    val placeholderIcon: ImageVector = when (kind) {
        LocalHistory.KindLabel.Archive -> Icons.Default.Inventory2
        LocalHistory.KindLabel.Smb -> Icons.Default.Lan
        LocalHistory.KindLabel.Library -> Icons.AutoMirrored.Filled.InsertDriveFile
        else -> Icons.Default.Folder
    }
    Row {
        Box(
            modifier = Modifier
                .aspectRatio(1f, matchHeightConstraintsFirst = true)
                .clip(ShapeDefaults.Medium),
            contentAlignment = Alignment.Center,
        ) {
            val request = coverRequest(info.thumbKey)
            if (request != null) {
                Image(
                    painter = rememberAsyncImagePainter(model = request),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = placeholderIcon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Column(modifier = Modifier.padding(start = 8.dp, top = 2.dp, end = 4.dp, bottom = 4.dp)) {
            Text(
                text = info.title.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.weight(1f))
            val pageLabel = if (
                showPages &&
                info.pages > 0 &&
                (kind == LocalHistory.KindLabel.Library || kind == LocalHistory.KindLabel.Archive)
            ) {
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

/** Match browse grid: fixed 2-line caption under square cover. */
private val LocalGridNameHeight = 44.dp

@Composable
fun LocalGalleryGridItem(
    gallery: LocalGalleryEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
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
                val request = coverRequest(gallery.coverPath)
                if (request != null) {
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (gallery.kind == LOCAL_GALLERY_KIND_ARCHIVE) {
                                Icons.Default.Inventory2
                            } else {
                                Icons.Default.Folder
                            },
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
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
                    .height(LocalGridNameHeight)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = gallery.title,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
        }
    }
}
