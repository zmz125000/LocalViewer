package com.hippo.ehviewer.ui.reader

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness5
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.alpha
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.RollingNumber
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.ui.settings.Preference

@Composable
fun ColorFilterSetting(
    showImageScaler: Boolean = true,
    showCameraRaw: Boolean = false,
) = Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
    if (showCameraRaw) {
        val cameraRaw = Settings.readerCameraRaw.asMutableState()
        AnimatedVisibility(visible = cameraRaw.value) {
            CameraRawSetting()
        }
    }
    if (showImageScaler) ImageScalerChoice()
    val customBrightness = Settings.customBrightness.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_custom_brightness),
        field = customBrightness,
    )
    AnimatedVisibility(visible = customBrightness.value) {
        val brightness = Settings.customBrightnessValue.asMutableState()
        SliderChoice(
            startSlot = { Icon(imageVector = Icons.Default.Brightness5, contentDescription = null) },
            endSlot = { RollingNumber(number = brightness.value, length = 3) },
            range = -75..100,
            field = brightness,
        )
    }
    val colorFilter = Settings.colorFilter.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_custom_color_filter),
        field = colorFilter,
    )
    AnimatedVisibility(visible = colorFilter.value) {
        var color by Settings.colorFilterValue.asMutableState()
        val rf = remember { mutableIntStateOf(color.red) }
        val r by rf
        val gf = remember { mutableIntStateOf(color.green) }
        val g by gf
        val bf = remember { mutableIntStateOf(color.blue) }
        val b by bf
        val af = remember { mutableIntStateOf(color.alpha) }
        val a by af
        color = (a shl 24) or (r shl 16) or (g shl 8) or b
        Column {
            SliderChoice(
                startSlot = { Text(text = "R") },
                endSlot = { RollingNumber(number = r, length = 3) },
                range = 0..255,
                field = rf,
            )
            SliderChoice(
                startSlot = { Text(text = "G") },
                endSlot = { RollingNumber(number = g, length = 3) },
                range = 0..255,
                field = gf,
            )
            SliderChoice(
                startSlot = { Text(text = "B") },
                endSlot = { RollingNumber(number = b, length = 3) },
                range = 0..255,
                field = bf,
            )
            SliderChoice(
                startSlot = { Text(text = "A") },
                endSlot = { RollingNumber(number = a, length = 3) },
                range = 0..255,
                field = af,
            )
        }
    }
    SpinnerChoice(
        title = stringResource(id = R.string.pref_color_filter_mode),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.color_filter_modes),
        values = listOf(0, 1, 2, 3, 4, 5),
        field = Settings.colorFilterMode.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_grayscale),
        field = Settings.grayScale.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_inverted_colors),
        field = Settings.invertedColors.asMutableState(),
    )
}

@Composable
private fun CameraRawSetting() = Column {
    Spacer(modifier = Modifier.size(16.dp))
    Text(
        text = stringResource(id = R.string.pref_reader_camera_raw),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val whiteBalance = Settings.readerRawWhiteBalance.asMutableState()
    SpinnerChoice(
        title = stringResource(id = R.string.pref_reader_raw_white_balance),
        entries = arrayOf(
            stringResource(id = R.string.pref_reader_raw_wb_camera),
            stringResource(id = R.string.pref_reader_raw_wb_auto),
            stringResource(id = R.string.pref_reader_raw_wb_daylight),
            stringResource(id = R.string.pref_reader_raw_wb_cloudy),
            stringResource(id = R.string.pref_reader_raw_wb_shade),
            stringResource(id = R.string.pref_reader_raw_wb_tungsten),
            stringResource(id = R.string.pref_reader_raw_wb_fluorescent),
            stringResource(id = R.string.pref_reader_raw_wb_flash),
            stringResource(id = R.string.pref_reader_raw_wb_kelvin),
        ),
        values = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8),
        field = whiteBalance,
    )
    AnimatedVisibility(visible = whiteBalance.value == 8) {
        CommitSliderChoice(
            title = stringResource(id = R.string.pref_reader_raw_kelvin),
            summary = stringResource(id = R.string.pref_reader_raw_kelvin_summary),
            range = 2000..12000,
            field = Settings.readerRawKelvin.asMutableState(),
            valueText = { "$it K" },
            steps = 99,
        )
    }
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_raw_camera_look),
        summary = stringResource(id = R.string.pref_reader_raw_camera_look_summary),
        field = Settings.readerRawCameraLook.asMutableState(),
    )
    val hdrLinear = Settings.readerRawHdrLinear.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_reader_raw_hdr_linear),
        summary = stringResource(id = R.string.pref_reader_raw_hdr_linear_summary),
        field = hdrLinear,
    )
    AnimatedVisibility(visible = hdrLinear.value) {
        CommitSliderChoice(
            title = stringResource(id = R.string.pref_reader_raw_shoulder),
            range = 0..100,
            field = Settings.readerRawShoulder.asMutableState(),
            valueText = { "$it" },
        )
    }
    CommitSliderChoice(
        title = stringResource(id = R.string.pref_reader_raw_exposure),
        range = -30..30,
        field = Settings.readerRawExposure.asMutableState(),
        valueText = { "%+.1f".format(it / 10f) },
    )
    CommitSliderChoice(
        title = stringResource(id = R.string.pref_reader_raw_shadows),
        range = -100..100,
        field = Settings.readerRawShadows.asMutableState(),
        valueText = { "%+d".format(it) },
    )
    CommitSliderChoice(
        title = stringResource(id = R.string.pref_reader_raw_midtones),
        range = -100..100,
        field = Settings.readerRawMidtones.asMutableState(),
        valueText = { "%+d".format(it) },
    )
    CommitSliderChoice(
        title = stringResource(id = R.string.pref_reader_raw_highlights),
        range = -100..100,
        field = Settings.readerRawHighlights.asMutableState(),
        valueText = { "%+d".format(it) },
    )
    CommitSliderChoice(
        title = stringResource(id = R.string.pref_reader_raw_highlight),
        summary = stringResource(id = R.string.pref_reader_raw_highlight_summary),
        range = 0..30,
        field = Settings.readerRawHighlight.asMutableState(),
        valueText = { "%.1f".format(it / 10f) },
    )
    Preference(
        title = stringResource(id = R.string.pref_reader_raw_reset),
        onClick = { Settings.resetReaderRaw() },
    )
}

@Composable
private fun ImageScalerChoice() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val kernels = arrayOf(
        stringResource(id = R.string.pref_image_scaler_default),
        stringResource(id = R.string.pref_image_scaler_nearest),
        stringResource(id = R.string.pref_image_scaler_bilinear),
        stringResource(id = R.string.pref_image_scaler_bspline),
        stringResource(id = R.string.pref_image_scaler_catmull),
        stringResource(id = R.string.pref_image_scaler_mitchell),
        stringResource(id = R.string.pref_image_scaler_lanczos),
    )
    val values = listOf(0, 1, 2, 3, 4, 5, 6)
    SpinnerChoice(
        title = stringResource(id = R.string.pref_image_scaler_down),
        entries = kernels,
        values = values,
        field = Settings.readerDownscaleFilter.asMutableState(),
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_image_scaler_up),
        entries = kernels,
        values = values,
        field = Settings.readerUpscaleFilter.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_image_scaler_up_limit),
        field = Settings.readerUpscaleLimit.asMutableState(),
    )
}
