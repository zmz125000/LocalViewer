package com.hippo.ehviewer.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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

/** Image conversion controls in Download settings. */
@Composable
internal fun ColumnScope.ImageConversionPreferences() {
    val conversionQuality = Settings.imageConversionQuality.asMutableState()
    val srgbClamp = Settings.imageConversionSrgbClamp.asMutableState()
    SimpleMenuPreferenceInt(
        title = stringResource(id = R.string.image_conversion_quality),
        entry = com.hippo.ehviewer.R.array.image_conversion_quality_entries,
        entryValueRes = com.hippo.ehviewer.R.array.image_conversion_quality_values,
        state = conversionQuality,
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
            SwitchPreference(
                title = stringResource(R.string.image_conversion_float32),
                summary = stringResource(R.string.image_conversion_float32_summary),
                state = Settings.imageConversionFloat32.asMutableState(),
            )
        }
    }
    HdrToneMappingPreferences()
    val rawHdrMode = Settings.rawHdrConversionMode.asMutableState()
    val rawHdrEntries = stringArrayResource(id = com.hippo.ehviewer.R.array.raw_hdr_conversion_entries)
    SimpleMenuPreferenceInt(
        title = stringResource(id = R.string.raw_hdr_conversion),
        summary = stringResource(id = R.string.raw_hdr_conversion_summary, rawHdrEntries[rawHdrMode.value.coerceIn(0, 2)]),
        entry = com.hippo.ehviewer.R.array.raw_hdr_conversion_entries,
        entryValueRes = com.hippo.ehviewer.R.array.raw_hdr_conversion_values,
        state = rawHdrMode,
    )
    AnimatedVisibility(visible = rawHdrMode.value.coerceIn(0, 2) != 2) {
        SwitchPreference(
            title = stringResource(R.string.raw_hdr_auto_exposure),
            state = Settings.rawHdrConversionAutoExposure.asMutableState(),
        )
    }
    SwitchPreference(
        title = stringResource(R.string.raw_ae_protect_highlights),
        state = Settings.rawAutoExposureProtectHighlights.asMutableState(),
    )
    SwitchPreference(
        title = stringResource(R.string.image_conversion_keep_exif),
        state = Settings.imageConversionKeepExif.asMutableState(),
    )
    SwitchPreference(
        title = stringResource(R.string.image_conversion_srgb_clamp),
        state = srgbClamp,
    )
    AnimatedVisibility(visible = !srgbClamp.value) {
        SwitchPreference(
            title = stringResource(R.string.image_conversion_extended_srgb_bt2020),
            state = Settings.imageConversionExtendedSrgbBt2020.asMutableState(),
        )
    }
    SwitchPreference(
        title = stringResource(R.string.image_conversion_jxl_jpeg_transcoding),
        state = Settings.imageConversionJxlJpegTranscoding.asMutableState(),
    )
}

@Composable
private fun HdrToneMappingPreferences() {
    val mapper = Settings.imageConversionToneMapper.asMutableState()
    val shoulder = Settings.imageConversionToneShoulder.asMutableState()
    val sliderShoulder = remember { mutableIntStateOf(shoulder.value.coerceIn(0, 95)) }
    val range = Settings.imageConversionToneRange.asMutableState()
    LaunchedEffect(range.value) {
        val snapped = ((range.value.coerceIn(210, 10000) + 5) / 10) * 10
        if (range.value != snapped) range.value = snapped
    }
    SimpleMenuPreferenceInt(
        title = stringResource(R.string.image_conversion_tone_mapper),
        entry = com.hippo.ehviewer.R.array.sdr_tone_mapper_entries,
        entryValueRes = com.hippo.ehviewer.R.array.sdr_tone_mapper_values,
        state = mapper,
    )
    AnimatedVisibility(mapper.value == 2) {
        Column {
            me.zhanghai.compose.preference.IntSliderPreference(
                state = shoulder,
                sliderState = sliderShoulder,
                title = { Text(stringResource(R.string.image_conversion_tone_shoulder)) },
                summary = { Text(stringResource(R.string.image_conversion_tone_shoulder_summary, kotlin.math.round(sliderShoulder.intValue * 2.03f).toInt())) },
                valueRange = 0..95,
                valueSteps = 94,
                valueText = { Text("${sliderShoulder.intValue}%") },
            )
            IntSliderPreference(
                title = stringResource(R.string.image_conversion_tone_range),
                summary = stringResource(R.string.image_conversion_tone_range_summary, range.value),
                minValue = 210,
                maxValue = 10000,
                step = (10000 - 210) / 10 - 1,
                state = range,
            )
        }
    }
}
