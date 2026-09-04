package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState

@Composable
fun ReaderGeneralSetting() = Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
    SpinnerChoice(
        title = stringResource(id = R.string.pref_reader_theme),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.reader_themes),
        values = integerArrayResource(id = com.hippo.ehviewer.R.array.reader_themes_values).toList(),
        field = Settings.readerTheme.asMutableState(),
    )
    val hdrDisplay = Settings.readerHdrDisplay.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_hdr_display),
        field = hdrDisplay,
    )
    AnimatedVisibility(visible = hdrDisplay.value) {
        SwitchChoice(
            title = stringResource(id = R.string.pref_reader_oppo_proxdr),
            field = Settings.readerOppoProxdr.asMutableState(),
        )
    }
    val advancedColor = Settings.readerAdvancedColor.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_advanced_color),
        field = advancedColor,
    )
    AnimatedVisibility(visible = advancedColor.value) {
        SwitchChoice(
            title = stringResource(id = R.string.pref_reader_platform_high_depth),
            field = Settings.readerPlatformHighDepth.asMutableState(),
        )
    }
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_lib_direct_bitmap),
        summary = stringResource(id = R.string.pref_reader_lib_direct_bitmap_summary),
        field = Settings.readerLibDirectBitmap.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_hardware_bitmap),
        summary = stringResource(id = R.string.pref_reader_hardware_bitmap_summary),
        field = Settings.readerHardwareBitmap.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_show_page_number),
        field = Settings.showPageNumber.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_hide_top_bar),
        field = Settings.readerHideTopBar.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_show_reader_seekbar),
        field = Settings.showReaderSeekbar.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_double_tap_to_zoom),
        field = Settings.doubleTapToZoom.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.settings_advanced_disable_reader_network_cache),
        field = Settings.disableReaderNetworkCache.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_smb_reader_safe_concurrency),
        field = Settings.smbReaderSafeConcurrency.asMutableState(),
    )
    val fullscreen = Settings.fullscreen.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_fullscreen),
        field = fullscreen,
    )
    val view = LocalView.current
    val hasDisplayCutout = remember(view) { view.rootWindowInsets.displayCutout != null }
    if (hasDisplayCutout) {
        AnimatedVisibility(visible = fullscreen.value) {
            SwitchChoice(
                title = stringResource(id = R.string.pref_cutout_short),
                field = Settings.cutoutShort.asMutableState(),
            )
        }
    }
    SwitchChoice(
        title = stringResource(id = R.string.pref_keep_screen_on),
        field = Settings.keepScreenOn.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_read_with_long_tap),
        field = Settings.readerLongTapAction.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_page_transitions),
        field = Settings.pageTransitions.asMutableState(),
    )
    val volume = Settings.readWithVolumeKeys.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.settings_read_volume_page),
        field = volume,
    )
    AnimatedVisibility(visible = volume.value) {
        SwitchChoice(
            title = stringResource(id = R.string.settings_read_reverse_volume),
            field = Settings.readWithVolumeKeysInverted.asMutableState(),
        )
    }
}
