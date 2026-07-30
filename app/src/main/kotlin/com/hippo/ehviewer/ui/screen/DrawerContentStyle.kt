package com.hippo.ehviewer.ui.screen

import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.ui.util.isMediumWidthOrWider

@Composable
fun selectedListItemColor() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
    trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

@Composable
fun listItemOnDrawerColor(selected: Boolean) = if (selected) {
    selectedListItemColor()
} else {
    ListItemDefaults.colors(
        containerColor = DrawerDefaults.modalContainerColor,
    )
}

@Composable
fun topBarOnDrawerColor() = TopAppBarDefaults.topAppBarColors(
    containerColor = DrawerDefaults.modalContainerColor,
    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)

/**
 * Top app bar colors that blend with main chrome.
 *
 * - **Navigation rail** (medium+ width): keep container color fixed to the rail’s surface so
 *   scroll doesn’t switch the bar to `surfaceContainer` and leave a seam beside the rail.
 * - **Bottom navigation** (compact): default M3 behavior — rest `surface`, scrolled
 *   `surfaceContainer`.
 */
@Composable
fun adaptiveTopAppBarColors(): TopAppBarColors {
    if (LocalWindowSizeClass.current.isMediumWidthOrWider) {
        val rail = NavigationRailDefaults.ContainerColor
        return TopAppBarDefaults.topAppBarColors(
            containerColor = rail,
            scrolledContainerColor = rail,
        )
    }
    return TopAppBarDefaults.topAppBarColors()
}
