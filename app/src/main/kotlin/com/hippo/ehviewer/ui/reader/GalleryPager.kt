package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.ReaderSession
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType.CONTINUOUS_VERTICAL
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType.RIGHT_TO_LEFT
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType.VERTICAL
import eu.kanade.tachiyomi.ui.reader.setting.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation

@Composable
fun GalleryPager(
    type: ReadingModeType,
    pagerState: PagerState,
    lazyListState: LazyListState,
    pageLoader: ReaderSession,
    showNavigationOverlay: Boolean,
    onNavigationModeChange: () -> Unit,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    onPrevFolder: () -> Unit = {},
    onNextFolder: () -> Unit = {},
    onBack: () -> Unit = {},
    /** Landscape dual pref active (pref + landscape). */
    dualActive: Boolean = false,
    /** First page is a persisted landscape cover (solo slot). */
    landscapeCover: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val isPagerType = !ReadingModeType.isWebtoon(type)
    val pagerDual = isPagerDual(dualActive, type)
    val webtoonHorizontal = isWebtoonHorizontal(dualActive, type)
    val pagerNavigation by Settings.readerPagerNav.collectAsState()
    val pagerInvertMode by Settings.readerPagerNavInverted.collectAsState()
    val webtoonNavigation by Settings.readerWebtoonNav.collectAsState()
    val webtoonInvertMode by Settings.readerWebtoonNavInverted.collectAsState()
    val navigationType = if (isPagerType) pagerNavigation else webtoonNavigation
    val navigation = remember(navigationType, type) {
        ViewerNavigation.fromPreference(navigationType, ReadingModeType.isVertical(type))
    }
    val invertMode = if (isPagerType) pagerInvertMode else webtoonInvertMode
    val regions = remember(navigation, invertMode) {
        navigation.regions(TappingInvertMode.entries[invertMode])
    }
    val navigator by rememberUpdatedState(regions)
    // Tap-zone hint: only when viewer-nav / invert prefs change — not when reading mode
    // alone rebuilds [navigation] (LTR ↔ vertical ↔ webtoon was flashing the overlay).
    val onNavModeChange by rememberUpdatedState(onNavigationModeChange)
    var skipPagerNavHint by remember { mutableStateOf(true) }
    var skipWebtoonNavHint by remember { mutableStateOf(true) }
    LaunchedEffect(pagerNavigation, pagerInvertMode) {
        if (skipPagerNavHint) {
            skipPagerNavHint = false
            return@LaunchedEffect
        }
        if (isPagerType) onNavModeChange()
    }
    LaunchedEffect(webtoonNavigation, webtoonInvertMode) {
        if (skipWebtoonNavHint) {
            skipWebtoonNavHint = false
            return@LaunchedEffect
        }
        if (!isPagerType) onNavModeChange()
    }
    if (isPagerType) {
        PagerViewer(
            pagerState = pagerState,
            isRtl = type == RIGHT_TO_LEFT,
            isVertical = type == VERTICAL,
            pageLoader = pageLoader,
            navigator = { navigator },
            onSelectPage = onSelectPage,
            onMenuRegionClick = onMenuRegionClick,
            onPrevFolder = onPrevFolder,
            onNextFolder = onNextFolder,
            onBack = onBack,
            dualPage = pagerDual,
            landscapeCover = landscapeCover,
            modifier = modifier.readerMouseWheelPages { forward ->
                if (forward) pagerState.moveToNext() else pagerState.moveToPrevious()
            },
        )
    } else {
        WebtoonViewer(
            lazyListState = lazyListState,
            withGaps = type == CONTINUOUS_VERTICAL,
            pageLoader = pageLoader,
            navigator = { navigator },
            onSelectPage = onSelectPage,
            onMenuRegionClick = onMenuRegionClick,
            onPrevFolder = onPrevFolder,
            onNextFolder = onNextFolder,
            onBack = onBack,
            horizontal = webtoonHorizontal,
            modifier = modifier.readerMouseWheelList(lazyListState),
        )
    }
    NavigationOverlay(showNavigationOverlay, regions, modifier = Modifier.fillMaxSize())
    // Paged modes only (LTR / RTL / vertical page) — not webtoon / continuous.
    // Drawn above content + nav hint so the flash fully covers the reader surface.
    if (isPagerType) {
        EInkRefreshOverlay(pagerState = pagerState)
    }
}
