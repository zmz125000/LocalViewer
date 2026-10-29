package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState

/** Shared by General and Download settings so conversion preferences stay identical. */
@Composable
internal fun ImageConversionPreferences() {
    val conversionQuality = Settings.imageConversionQuality.asMutableState()
    SimpleMenuPreferenceInt(
        title = stringResource(id = R.string.image_conversion_quality),
        entry = com.hippo.ehviewer.R.array.image_conversion_quality_entries,
        entryValueRes = com.hippo.ehviewer.R.array.image_conversion_quality_values,
        state = conversionQuality,
    )
    SwitchPreference(
        title = stringResource(R.string.image_conversion_keep_exif),
        state = Settings.imageConversionKeepExif.asMutableState(),
    )
    SwitchPreference(
        title = stringResource(R.string.image_conversion_extended_srgb_bt2020),
        state = Settings.imageConversionExtendedSrgbBt2020.asMutableState(),
    )
    SwitchPreference(
        title = stringResource(R.string.image_conversion_jxl_jpeg_transcoding),
        state = Settings.imageConversionJxlJpegTranscoding.asMutableState(),
    )
    AnimatedVisibility(visible = conversionQuality.value == 4) {
        Column {
            val distance = Settings.imageConversionJxlDistance.asMutableState()
            val sliderDistance = remember { mutableIntStateOf(distance.value.coerceIn(0, 300)) }
            LaunchedEffect(distance.value) {
                if (distance.value !in 0..300) distance.value = distance.value.coerceIn(0, 300)
            }
            me.zhanghai.compose.preference.IntSliderPreference(
                state = distance,
                sliderState = sliderDistance,
                title = { Text(stringResource(R.string.image_conversion_jxl_distance)) },
                valueRange = 0..300,
                valueSteps = 59,
                valueText = { Text("%.2f".format(sliderDistance.intValue / 100f)) },
            )
            IntSliderPreference(
                title = stringResource(R.string.image_conversion_custom_codec_quality),
                minValue = 1,
                maxValue = 100,
                state = Settings.imageConversionCustomQuality.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(R.string.image_conversion_avif_12_bit),
                state = Settings.imageConversionAvif12Bit.asMutableState(),
            )
            SwitchPreference(
                title = stringResource(R.string.image_conversion_downsize_gain_map),
                state = Settings.imageConversionDownsizeGainMap.asMutableState(),
            )
        }
    }
    val rawHdrMode = Settings.rawHdrConversionMode.asMutableState()
    val rawHdrEntries = stringArrayResource(id = com.hippo.ehviewer.R.array.raw_hdr_conversion_entries)
    SimpleMenuPreferenceInt(
        title = stringResource(id = R.string.raw_hdr_conversion),
        summary = stringResource(id = R.string.raw_hdr_conversion_summary, rawHdrEntries[rawHdrMode.value.coerceIn(0, 2)]),
        entry = com.hippo.ehviewer.R.array.raw_hdr_conversion_entries,
        entryValueRes = com.hippo.ehviewer.R.array.raw_hdr_conversion_values,
        state = rawHdrMode,
    )
}
