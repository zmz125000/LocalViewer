package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.Settings
import kotlinx.coroutines.launch

private val tabs = intArrayOf(
    R.string.pref_category_reading_mode,
    R.string.pref_category_general,
    R.string.custom_filter,
)

@Composable
fun SettingsPager(isWebtoon: Boolean, modifier: Modifier = Modifier) {
    val initialPage = Settings.readerSettingsTab.value.coerceIn(0, tabs.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialPage) { tabs.size }
    LaunchedEffect(Unit) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            Settings.readerSettingsTab.value = page
        }
    }
    val scope = rememberCoroutineScope()
    PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        containerColor = BottomSheetDefaults.ContainerColor,
    ) {
        tabs.forEachIndexed { index, res ->
            Tab(
                selected = pagerState.currentPage == index,
                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                text = { Text(text = stringResource(id = res)) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    HorizontalPager(
        modifier = modifier,
        state = pagerState,
        verticalAlignment = Alignment.Top,
    ) { page ->
        ProvideTextStyle(value = MaterialTheme.typography.labelLarge) {
            when (page) {
                0 -> ReaderModeSetting(isWebtoon)
                1 -> ReaderGeneralSetting()
                2 -> ColorFilterSetting()
            }
        }
    }
}
