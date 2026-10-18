package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShapeDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ehviewer.core.ui.component.ElevatedCard
import com.hippo.ehviewer.library.UsbVolume
import com.hippo.ehviewer.ui.main.BrowseGridPlaceholderIconSize
import com.hippo.ehviewer.ui.main.BrowseListLeadingIconSize
import com.hippo.ehviewer.ui.main.GalleryGridDefaults

@Composable
internal fun UsbFavoriteListRow(
    volume: UsbVolume,
    onClick: () -> Unit,
) {
    val leadSize = 56.dp
    ListItem(
        headlineContent = {
            Text(volume.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text("USB", style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Box(modifier = Modifier.size(leadSize), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Usb,
                    contentDescription = null,
                    modifier = Modifier.size(BrowseListLeadingIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onClick),
    )
}

@Composable
internal fun UsbFavoriteGridCell(
    volume: UsbVolume,
    onClick: () -> Unit,
    iconSize: Dp = BrowseGridPlaceholderIconSize,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
) {
    val namePadH = GalleryGridDefaults.namePaddingH()
    val namePadBottom = GalleryGridDefaults.namePaddingBottom()
    val labelIconSize = with(LocalDensity.current) { labelStyle.fontSize.toDp() }
    ElevatedCard(
        onClick = onClick,
        onLongClick = onClick,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        colors = GalleryGridDefaults.cardColors(),
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(ShapeDefaults.Medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Usb,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
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
            Icon(
                Icons.Filled.Usb,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp).size(labelIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = volume.label,
                style = labelStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
