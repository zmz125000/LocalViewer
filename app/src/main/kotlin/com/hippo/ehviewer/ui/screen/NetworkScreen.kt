package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.LibrarySettingsScreenDestination
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * SMB management lives in [LibrarySettingsScreen] (browse sources).
 * Keep this destination as a redirect for any remaining deep links.
 */
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.NetworkScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    LaunchedEffect(Unit) {
        navigator.navigate(LibrarySettingsScreenDestination) {
            launchSingleTop = true
        }
        navigator.popBackStack(LibrarySettingsScreenDestination, inclusive = false)
    }
}
