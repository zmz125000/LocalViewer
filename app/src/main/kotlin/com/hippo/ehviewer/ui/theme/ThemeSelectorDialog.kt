package com.hippo.ehviewer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.main.TaskStyledDialog
import com.hippo.ehviewer.ui.settings.SimpleMenuPreferenceInt
import com.hippo.ehviewer.ui.settings.SwitchPreference

/** Shared request so General settings can return to Library and open its selector. */
object ThemeSelectorRequest {
    var open by mutableStateOf(false)
}

@Composable
fun ThemeSelectorDialog() {
    if (!ThemeSelectorRequest.open) return
    val colorfulDark = Settings.colorfulDarkTheme.asMutableState()
    val lightPalette = Settings.colorPalette.asMutableState()
    val darkPalette = Settings.darkColorPalette.asMutableState()
    val dark = isSystemInDarkTheme()
    val changeLightPalette = !dark || darkPalette.value == -1
    val choices = integerArrayResource(
        if (changeLightPalette) {
            com.hippo.ehviewer.R.array.color_theme_values
        } else {
            com.hippo.ehviewer.R.array.dark_color_theme_values
        },
    ).filter { it != -1 } // Follow theme is a menu choice, not a Prev/Next stop.
    val current = if (changeLightPalette) lightPalette.value else darkPalette.value
    val currentIndex = choices.indexOf(current).takeIf { it >= 0 } ?: 0
    fun move(delta: Int) {
        val next = choices[(currentIndex + delta + choices.size) % choices.size]
        if (changeLightPalette) lightPalette.value = next else darkPalette.value = next
    }

    TaskStyledDialog(onDismiss = { ThemeSelectorRequest.open = false }) { _ ->
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.theme_selector),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SwitchPreference(
                    title = stringResource(R.string.colorful_dark_theme),
                    state = colorfulDark,
                )
                SwitchPreference(
                    title = stringResource(R.string.grid_cards),
                    state = Settings.gridCards.asMutableState(),
                )
                SimpleMenuPreferenceInt(
                    title = stringResource(R.string.color_theme),
                    entry = com.hippo.ehviewer.R.array.color_theme_entries,
                    entryValueRes = com.hippo.ehviewer.R.array.color_theme_values,
                    state = lightPalette,
                )
                SimpleMenuPreferenceInt(
                    title = stringResource(R.string.color_theme_dark),
                    entry = com.hippo.ehviewer.R.array.dark_color_theme_entries,
                    entryValueRes = com.hippo.ehviewer.R.array.dark_color_theme_values,
                    state = darkPalette,
                )
                SimpleMenuPreferenceInt(
                    title = stringResource(R.string.dark_theme),
                    entry = com.hippo.ehviewer.R.array.night_mode_entries,
                    entryValueRes = com.hippo.ehviewer.R.array.night_mode_values,
                    state = Settings.theme.asMutableState(),
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { move(-1) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_zone_prev))
                }
                TextButton(onClick = { move(1) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.nav_zone_next))
                }
            }
        }
    }
}
