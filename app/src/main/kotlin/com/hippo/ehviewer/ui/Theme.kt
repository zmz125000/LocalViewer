package com.hippo.ehviewer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import com.ehviewer.core.ui.util.ProvideVectorPainterCache
import com.hippo.ehviewer.ui.destinations.BrowseScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.LibraryScreenDestination
import com.hippo.ehviewer.ui.destinations.SettingsScreenDestination
import com.hippo.ehviewer.ui.theme.EhTheme
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.LocalGlobalDialogState
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import me.zhanghai.compose.preference.ProvidePreferenceTheme
import me.zhanghai.compose.preference.preferenceTheme
import soup.compose.material.motion.animation.materialSharedAxisXIn
import soup.compose.material.motion.animation.materialSharedAxisXOut
import soup.compose.material.motion.animation.rememberSlideDistance

inline fun ComponentActivity.setMD3Content(crossinline content: @Composable DialogState.() -> Unit) = setContent {
    EhTheme(useDarkTheme = isSystemInDarkTheme()) {
        val theme = preferenceTheme(
            iconColor = MaterialTheme.colorScheme.primary,
            titleTextStyle = MaterialTheme.typography.titleMedium,
        )
        ProvidePreferenceTheme(theme) {
            ProvideVectorPainterCache {
                val dialogState = remember { DialogState() }
                CompositionLocalProvider(LocalGlobalDialogState provides dialogState) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content(dialogState)
                        dialogState.Place()
                    }
                }
            }
        }
    }
}

private typealias Ty = AnimatedContentTransitionScope<NavBackStackEntry>

/** Main bar/rail order: Library → Browse → History → Settings. */
private val mainTabRoutes = listOf(
    LibraryScreenDestination.route,
    BrowseScreenDestination.route,
    HistoryScreenDestination.route,
    SettingsScreenDestination.route,
)

private fun NavBackStackEntry.mainTabIndex(): Int? {
    val route = destination.route ?: return null
    val index = mainTabRoutes.indexOfFirst { tab ->
        route == tab || route.startsWith("$tab?") || route.startsWith("$tab/")
    }
    return index.takeIf { it >= 0 }
}

/**
 * For main-tab ↔ main-tab moves, `true` = left→right (higher index), `false` = right→left.
 * `null` = not a pure main-tab switch (use default forward/back nav animation).
 */
private fun Ty.mainTabForward(): Boolean? {
    val from = initialState.mainTabIndex() ?: return null
    val to = targetState.mainTabIndex() ?: return null
    if (from == to) return null
    return to > from
}

@Composable
fun rememberEhNavAnim(): NavHostAnimatedDestinationStyle {
    val slideDistance = rememberSlideDistance()
    return remember(slideDistance) {
        object : NavHostAnimatedDestinationStyle() {
            // Main tabs: same fade+slide, direction from bar order.
            // Other screens: keep previous always-forward push / reverse pop.
            override val enterTransition: Ty.() -> EnterTransition = {
                materialSharedAxisXIn(mainTabForward() ?: true, slideDistance)
            }
            override val exitTransition: Ty.() -> ExitTransition = {
                materialSharedAxisXOut(mainTabForward() ?: true, slideDistance)
            }
            override val popEnterTransition: Ty.() -> EnterTransition = {
                materialSharedAxisXIn(mainTabForward() ?: false, slideDistance)
            }
            override val popExitTransition: Ty.() -> ExitTransition = {
                materialSharedAxisXOut(mainTabForward() ?: false, slideDistance)
            }
        }
    }
}
