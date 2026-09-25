package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.asMutableState
import com.hippo.ehviewer.library.document.EBOOK_FONT_SIZE_MAX
import com.hippo.ehviewer.library.document.EBOOK_FONT_SIZE_MIN
import eu.kanade.tachiyomi.ui.reader.setting.OrientationType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

@Composable
fun ReaderModeSetting(isWebtoon: Boolean, isDocument: Boolean = false) = Column(modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
    SpinnerChoice(
        title = stringResource(id = R.string.pref_category_reading_mode),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.viewers_selector),
        values = ReadingModeType.entries.map { it.prefValue },
        field = Settings.readingMode.asMutableState(),
    )
    val dualPageLandscape = Settings.dualPageLandscape.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_dual_page_landscape),
        field = dualPageLandscape,
    )
    if (!isWebtoon) {
        AnimatedVisibility(visible = dualPageLandscape.value) {
            Column {
                SwitchChoice(
                    title = stringResource(id = R.string.pref_dual_page_gap),
                    field = Settings.dualPageGap.asMutableState(),
                )
                SpinnerChoice(
                    title = stringResource(id = R.string.pref_landscape_cover),
                    entries = arrayOf(
                        stringResource(id = R.string.pref_landscape_cover_auto),
                        stringResource(id = R.string.pref_landscape_cover_on),
                        stringResource(id = R.string.pref_landscape_cover_off),
                    ),
                    values = listOf(
                        Settings.LANDSCAPE_COVER_AUTO,
                        Settings.LANDSCAPE_COVER_ON,
                        Settings.LANDSCAPE_COVER_OFF,
                    ),
                    field = Settings.landscapeCover.asMutableState(),
                )
            }
        }
    }
    SpinnerChoice(
        title = stringResource(id = R.string.rotation_type),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.rotation_type),
        values = OrientationType.entries.map { it.prefValue },
        field = Settings.orientationMode.asMutableState(),
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_auto_rotate_mode),
        entries = arrayOf(
            stringResource(id = R.string.pref_auto_rotate_off),
            stringResource(id = R.string.pref_auto_rotate_cw),
            stringResource(id = R.string.pref_auto_rotate_ccw),
        ),
        values = listOf(0, 1, 2),
        field = Settings.autoRotateMode.asMutableState(),
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_decode_size),
        entries = arrayOf(
            stringResource(id = R.string.pref_decode_size_1_5x),
            stringResource(id = R.string.pref_decode_size_2x),
            stringResource(id = R.string.pref_decode_size_2_5x),
            stringResource(id = R.string.pref_decode_size_3x),
            stringResource(id = R.string.pref_decode_size_origin),
        ),
        values = listOf(0, 1, 2, 3, 4),
        field = Settings.readerDecodeSize.asMutableState(),
    )
    if (isDocument) {
        Spacer(modifier = Modifier.size(8.dp))
        DocumentStyleSetting()
    }
    Spacer(modifier = Modifier.size(16.dp))
    Crossfade(targetState = isWebtoon, label = "Setting") { webtoon ->
        if (webtoon) {
            WebtoonSetting()
        } else {
            PagerSetting()
        }
    }
}

@Composable
private fun DocumentStyleSetting() = Column {
    SpinnerChoice(
        title = stringResource(id = R.string.pref_reader_theme),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.reader_themes),
        values = integerArrayResource(id = com.hippo.ehviewer.R.array.reader_themes_values).toList(),
        field = Settings.ebookTheme.asMutableState(),
    )
    Text(
        text = stringResource(id = R.string.pref_ebook_text),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_ebook_font),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.ebook_font),
        values = listOf(
            Settings.EBOOK_FONT_SERIF,
            Settings.EBOOK_FONT_SANS,
            Settings.EBOOK_FONT_SYSTEM,
        ),
        field = Settings.ebookFont.asMutableState(),
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_ebook_charset),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.ebook_charset),
        values = listOf(
            Settings.EBOOK_CHARSET_AUTO,
            Settings.EBOOK_CHARSET_AUTO_ZH,
            Settings.EBOOK_CHARSET_AUTO_JA,
            Settings.EBOOK_CHARSET_AUTO_KO,
            Settings.EBOOK_CHARSET_UTF8,
            Settings.EBOOK_CHARSET_UTF16LE,
            Settings.EBOOK_CHARSET_UTF16BE,
            Settings.EBOOK_CHARSET_GBK,
            Settings.EBOOK_CHARSET_GB18030,
            Settings.EBOOK_CHARSET_BIG5,
            Settings.EBOOK_CHARSET_SJIS,
            Settings.EBOOK_CHARSET_EUCKR,
            Settings.EBOOK_CHARSET_1252,
        ),
        field = Settings.ebookCharset.asMutableState(),
    )
    val fontSize = Settings.ebookFontSize.asMutableState()
    Text(
        text = stringResource(id = R.string.pref_ebook_font_size),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SliderChoice(
        startSlot = {},
        endSlot = { Text(text = "${fontSize.value}") },
        range = EBOOK_FONT_SIZE_MIN..EBOOK_FONT_SIZE_MAX,
        field = fontSize,
    )
    Text(
        text = stringResource(id = R.string.pref_ebook_paragraph),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_ebook_paragraph_mode),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.ebook_paragraph_mode),
        values = listOf(
            Settings.EBOOK_PARA_AUTO,
            Settings.EBOOK_PARA_HARD,
            Settings.EBOOK_PARA_SOFT,
        ),
        field = Settings.ebookParagraphMode.asMutableState(),
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_ebook_indent),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.ebook_indent),
        values = listOf(0, 1, 2),
        field = Settings.ebookIndent.asMutableState(),
    )
    SpinnerChoice(
        title = stringResource(id = R.string.pref_ebook_align),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.ebook_align),
        values = listOf(Settings.EBOOK_ALIGN_START, Settings.EBOOK_ALIGN_JUSTIFY),
        field = Settings.ebookAlign.asMutableState(),
    )
    val lineHeight = Settings.ebookLineHeight.asMutableState()
    Text(
        text = stringResource(id = R.string.pref_ebook_line_height),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SliderChoice(
        startSlot = {},
        endSlot = { Text(text = "%.2f em".format(lineHeight.value / 100f)) },
        range = 100..200,
        field = lineHeight,
    )
    val paragraph = Settings.ebookParagraphSpacing.asMutableState()
    Text(
        text = stringResource(id = R.string.pref_ebook_paragraph_spacing),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SliderChoice(
        startSlot = {},
        endSlot = { Text(text = "%.2f em".format(paragraph.value / 100f)) },
        range = 0..200,
        field = paragraph,
    )
    val margin = Settings.ebookMargin.asMutableState()
    Text(
        text = stringResource(id = R.string.pref_ebook_margin),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SliderChoice(
        startSlot = {},
        endSlot = { Text(text = "${margin.value}%") },
        range = 4..12,
        field = margin,
    )
}

@Composable
private fun PagerSetting() = Column {
    Text(
        text = stringResource(id = R.string.pager_viewer),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val pagerNav = Settings.readerPagerNav.asMutableState()
    SpinnerChoice(
        title = stringResource(id = R.string.pref_viewer_nav),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.pager_nav),
        values = listOf(0, 1, 2, 3, 4, 5),
        field = pagerNav,
    )
    AnimatedVisibility(visible = pagerNav.value != 5) {
        Column {
            SpinnerChoice(
                title = stringResource(id = R.string.pref_read_with_tapping_inverted),
                entries = stringArrayResource(id = com.hippo.ehviewer.R.array.invert_tapping_mode),
                values = listOf(0, 1, 2, 3),
                field = Settings.readerPagerNavInverted.asMutableState(),
            )
            SwitchChoice(
                title = stringResource(id = R.string.pref_navigate_pan),
                field = Settings.navigateToPan.asMutableState(),
            )
        }
    }
    val scaleType = Settings.imageScaleType.asMutableState()
    SpinnerChoice(
        title = stringResource(id = R.string.pref_image_scale_type),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.image_scale_type),
        values = listOf(1, 2, 3, 4, 5, 6),
        field = scaleType,
    )
    AnimatedVisibility(visible = scaleType.value == 1) {
        SwitchChoice(
            title = stringResource(id = R.string.pref_landscape_zoom),
            field = Settings.landscapeZoom.asMutableState(),
        )
    }
    SpinnerChoice(
        title = stringResource(id = R.string.pref_zoom_start),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.zoom_start),
        values = listOf(1, 2, 3, 4),
        field = Settings.zoomStart.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_crop_borders),
        field = Settings.cropBorder.asMutableState(),
    )
    val eInkRefresh = Settings.eInkRefreshEnabled.asMutableState()
    SwitchChoice(
        title = stringResource(id = R.string.pref_eink_refresh),
        summary = stringResource(id = R.string.pref_eink_refresh_summary),
        field = eInkRefresh,
    )
    AnimatedVisibility(visible = eInkRefresh.value) {
        Column {
            val duration = Settings.eInkRefreshDuration.asMutableState()
            Text(
                text = stringResource(id = R.string.pref_eink_refresh_duration),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 100–1500 ms in 100 ms steps (15 stops → 13 intermediate steps).
            SliderChoice(
                startSlot = {},
                endSlot = { Text(text = "${duration.value} ms") },
                range = 100..1500,
                steps = 13,
                field = duration,
            )
            val interval = Settings.eInkRefreshInterval.asMutableState()
            Text(
                text = stringResource(id = R.string.pref_eink_refresh_interval),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SliderChoice(
                startSlot = {},
                endSlot = { Text(text = "${interval.value}") },
                range = 1..10,
                field = interval,
            )
            SpinnerChoice(
                title = stringResource(id = R.string.pref_eink_refresh_style),
                entries = stringArrayResource(id = com.hippo.ehviewer.R.array.eink_refresh_style),
                values = listOf(0, 1, 2),
                field = Settings.eInkRefreshStyle.asMutableState(),
            )
        }
    }
}

@Composable
private fun WebtoonSetting() = Column {
    Text(
        text = stringResource(id = R.string.webtoon_viewer),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val webtoonNav = Settings.readerWebtoonNav.asMutableState()
    SpinnerChoice(
        title = stringResource(id = R.string.pref_viewer_nav),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.webtoon_nav),
        values = listOf(0, 1, 2, 3, 4, 5),
        field = webtoonNav,
    )
    AnimatedVisibility(visible = webtoonNav.value != 5) {
        SpinnerChoice(
            title = stringResource(id = R.string.pref_read_with_tapping_inverted),
            entries = stringArrayResource(id = com.hippo.ehviewer.R.array.invert_tapping_mode),
            values = listOf(0, 1, 2, 3),
            field = Settings.readerWebtoonNavInverted.asMutableState(),
        )
    }
    SpinnerChoice(
        title = stringResource(id = R.string.pref_webtoon_side_padding),
        entries = stringArrayResource(id = com.hippo.ehviewer.R.array.webtoon_side_padding),
        values = integerArrayResource(id = com.hippo.ehviewer.R.array.webtoon_side_padding_values).toList(),
        field = Settings.webtoonSidePadding.asMutableState(),
    )
    SwitchChoice(
        title = stringResource(id = R.string.pref_crop_borders),
        field = Settings.cropBorder.asMutableState(),
    )
}
