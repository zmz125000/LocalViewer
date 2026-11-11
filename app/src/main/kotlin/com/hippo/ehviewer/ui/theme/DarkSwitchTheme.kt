package com.hippo.ehviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** Tint the library preference switch's checked state without changing its layout or off colors. */
@Composable
fun DarkSwitchTheme(content: @Composable () -> Unit) {
    if (isSystemInDarkTheme()) {
        val scheme = MaterialTheme.colorScheme
        MaterialTheme(
            colorScheme = scheme.copy(
                primary = scheme.secondary,
                onPrimary = scheme.surfaceContainerLow,
            ),
            content = content,
        )
    } else {
        content()
    }
}
