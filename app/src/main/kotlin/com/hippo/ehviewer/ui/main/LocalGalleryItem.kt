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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inventory2
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.ehviewer.core.database.model.LOCAL_GALLERY_KIND_ARCHIVE
import com.ehviewer.core.database.model.LocalGalleryEntity
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.CrystalCard
import com.ehviewer.core.ui.component.ElevatedCard
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.ktbuilder.imageRequest
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

@Composable
fun LocalGalleryGridItem(
    gallery: LocalGalleryEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick,
    showPages: Boolean,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) = ElevatedCard(
    modifier = modifier,
    onClick = onClick,
    onLongClick = onLongClick,
) {
    Box {
        val request = coverRequest(gallery.coverPath)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.aspectRatio(1f),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.aspectRatio(1f).fillMaxSize(),
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
                modifier = Modifier.align(Alignment.TopEnd).widthIn(min = 32.dp).height(24.dp),
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
}
