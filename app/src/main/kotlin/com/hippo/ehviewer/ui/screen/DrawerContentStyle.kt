package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

/** Back arrow outside select mode, close icon inside. Crossfades and turns between the two. */
@Composable
fun FolderSelectNavIcon(selecting: Boolean) {
    val transition = updateTransition(targetState = selecting, label = "folderSelectNav")
    val rotation by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 200) },
        label = "rotation",
    ) { close -> if (close) 90f else 0f }
    val closeAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 200) },
        label = "closeAlpha",
    ) { close -> if (close) 1f else 0f }
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.graphicsLayer {
                alpha = 1f - closeAlpha
                rotationZ = rotation
            },
        )
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.graphicsLayer {
                alpha = closeAlpha
                rotationZ = rotation - 90f
            },
        )
    }
}
