package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.UsbVolume
import com.hippo.ehviewer.ui.main.BrowseListLeadingIconSize

/** First row of a USB volume root. Hidden when this app cannot eject the volume. */
fun LazyGridScope.usbEjectBrowseItem(volume: UsbVolume?, onEject: () -> Unit) {
    if (volume == null || !volume.ejectSupported) return
    item(key = "usb-eject", span = { GridItemSpan(maxLineSpan) }) {
        UsbEjectListRow(onEject)
    }
}

@Composable
private fun UsbEjectListRow(onEject: () -> Unit) {
    val leadSize = 56.dp
    ListItem(
        headlineContent = { Text(stringResource(R.string.usb_eject)) },
        leadingContent = {
            Box(modifier = Modifier.size(leadSize), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Eject,
                    contentDescription = null,
                    modifier = Modifier.size(BrowseListLeadingIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEject),
    )
}
