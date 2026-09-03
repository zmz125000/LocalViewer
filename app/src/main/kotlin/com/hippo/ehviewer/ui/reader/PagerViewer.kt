package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FixedScale
import androidx.compose.ui.layout.times
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import arrow.core.partially1
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.gallery.statusObserved
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegions
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.getAction
import kotlin.contracts.contract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.zoomable

@Composable
fun PagerViewer(
    pagerState: PagerState,
    isRtl: Boolean,
    isVertical: Boolean,
    pageLoader: ReaderSession,
    navigator: () -> NavigationRegions,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    onPrevFolder: () -> Unit = {},
    onNextFolder: () -> Unit = {},
    onBack: () -> Unit = {},
    chromeVisible: Boolean = false,
    /**
     * Landscape dual: each pager slot is a two-page spread.
     * Used for LTR, RTL, and Vertical (same pairing; Vertical scrolls up/down between spreads).
     */
    dualPage: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Real page count for content; pager pageCount (spread or page) comes from [pagerState].
    val realPageCount = pageLoader.size
    val items = pageLoader.pages
    val scaleType by Settings.imageScaleType.collectAsState()
    val landscapeZoom by Settings.landscapeZoom.collectAsState()
    val autoRotateMode by Settings.autoRotateMode.collectAsState()
    val zoomStart by Settings.zoomStart.collectAsState()
    val alignment = Alignment.fromPreferences(zoomStart, isRtl, isVertical)
    val layoutSize by remember(pagerState) {
        derivedStateOf {
            pagerState.layoutInfo.viewportSize.toSize()
        }
    }
    val doubleTap = remember(isRtl, navigator, onPrevFolder, onNextFolder, onBack) {
        doubleTapAction(
            isRtl = isRtl,
            getViewportSize = {
                // Prefer live pager viewport; fall back to last known layoutSize
                val live = pagerState.layoutInfo.viewportSize.toSize()
                if (live != Size.Zero) live else layoutSize
            },
            getNavigator = navigator,
            onPrevFolder = onPrevFolder,
            onNextFolder = onNextFolder,
            onBack = onBack,
        )
    }
    // Vertical is never RTL; dual spreads still use LTR left/right order.
    val dualRtl = isRtl && !isVertical
    val canScroll = realPageCount > 0

    @Composable
    fun SpreadOrPage(index: Int) {
        if (dualPage) {
            val (leftIdx, rightIdx) = dualLeftRight(index, realPageCount, dualRtl)
            val left = leftIdx?.let { items.getOrNull(it) }
            val right = rightIdx?.let { items.getOrNull(it) }
            if (left == null && right == null) return
            DualPageContainer(
                leftPage = left,
                rightPage = right,
                pageLoader = pageLoader,
                isRtl = dualRtl,
                layoutSize = layoutSize,
                navigator = navigator,
                pagerState = pagerState,
                onSelectPage = onSelectPage,
                onMenuRegionClick = onMenuRegionClick,
                onDoubleClick = doubleTap,
                chromeVisible = chromeVisible,
                scope = scope,
            )
        } else {
            val page = items.getOrNull(index) ?: return
            PageContainer(
                page = page,
                pageLoader = pageLoader,
                isRtl = if (isVertical) false else isRtl,
                scaleType = scaleType,
                landscapeZoom = landscapeZoom,
                autoRotateMode = autoRotateMode,
                alignment = alignment,
                layoutSize = layoutSize,
                navigator = navigator,
                pagerState = pagerState,
                onSelectPage = onSelectPage,
                onMenuRegionClick = onMenuRegionClick,
                onDoubleClick = doubleTap,
                chromeVisible = chromeVisible,
                scope = scope,
            )
        }
    }

    if (isVertical) {
        VerticalPager(
            state = pagerState,
            modifier = modifier,
            beyondViewportPageCount = 1,
            userScrollEnabled = canScroll,
            key = { it },
        ) { index ->
            SpreadOrPage(index)
        }
    } else {
        val isRtlLayout = LocalLayoutDirection.current == LayoutDirection.Rtl
        HorizontalPager(
            state = pagerState,
            modifier = modifier,
            beyondViewportPageCount = 1,
            reverseLayout = isRtl xor isRtlLayout,
            userScrollEnabled = canScroll,
            key = { it },
        ) { index ->
            SpreadOrPage(index)
        }
    }
}

/**
 * Two pages side-by-side (or one centered when the spread has a single page).
 * One zoom state for the whole spread; long-press picks left/right by x.
 */
@Composable
private fun DualPageContainer(
    leftPage: Page?,
    rightPage: Page?,
    pageLoader: ReaderSession,
    isRtl: Boolean,
    layoutSize: Size,
    navigator: () -> NavigationRegions,
    pagerState: PagerState,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    onDoubleClick: DoubleClickToZoomListener,
    chromeVisible: Boolean,
    scope: CoroutineScope,
) {
    @Suppress("NAME_SHADOWING")
    val isRtl by rememberUpdatedState(isRtl)
    @Suppress("NAME_SHADOWING")
    val chromeVisible by rememberUpdatedState(chromeVisible)
    @Suppress("NAME_SHADOWING")
    val onMenuRegionClick by rememberUpdatedState(onMenuRegionClick)
    val zoomableState = rememberZoomableState(zoomSpec = PagerZoomSpec)
    val solo = leftPage != null && rightPage == null || leftPage == null && rightPage != null
    val halfSize = if (layoutSize == Size.Zero) {
        Size.Zero
    } else {
        Size(layoutSize.width / 2f, layoutSize.height)
    }

    if (layoutSize != Size.Zero) {
        // Spread fills the viewport; telephoto zooms the pair as one unit.
        zoomableState.contentScale = ContentScale.Fit
        LaunchedEffect(layoutSize) {
            zoomableState.setContentLocation(
                ZoomableContentLocation.scaledInsideAndCenterAligned(layoutSize),
            )
            zoomableState.contentAlignment = Alignment.Center
        }
    }

    val onLongClick: (Offset) -> Unit = { offset ->
        val page = when {
            solo -> leftPage ?: rightPage
            offset.x < layoutSize.width / 2f -> leftPage
            else -> rightPage
        }
        if (page != null) onSelectPage(page)
    }
    val onTap: ZoomableState?.(Offset) -> Unit = tap@{ offset ->
        if (chromeVisible) {
            onMenuRegionClick()
            return@tap
        }
        scope.launch {
            with(pagerState) {
                val size = layoutInfo.viewportSize.toSize()
                val (w, h) = size
                val (x, y) = offset
                val distance = size.width
                val bounds = size.toRect()
                when (navigator().getAction(Offset(x / w, y / h))) {
                    NavigationRegion.MENU -> onMenuRegionClick()
                    NavigationRegion.NEXT -> {
                        val canPan = if (isRtl) panRight(distance, bounds) else panLeft(distance, bounds)
                        if (!canPan) moveToNext()
                    }
                    NavigationRegion.PREV -> {
                        val canPan = if (isRtl) panLeft(distance, bounds) else panRight(distance, bounds)
                        if (!canPan) moveToPrevious()
                    }
                    NavigationRegion.RIGHT -> {
                        if (!panLeft(distance, bounds)) {
                            if (isRtl) moveToPrevious() else moveToNext()
                        }
                    }
                    NavigationRegion.LEFT -> {
                        if (!panRight(distance, bounds)) {
                            if (isRtl) moveToNext() else moveToPrevious()
                        }
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val zoomMod = Modifier.zoomable(
            state = zoomableState,
            onClick = onTap.partially1(zoomableState),
            onLongClick = onLongClick,
            onDoubleClick = onDoubleClick,
        )
        if (solo) {
            val page = leftPage ?: rightPage!!
            // Odd last page: full-width single page (no half-column).
            PagerItem(
                page = page,
                pageLoader = pageLoader,
                contentScale = ContentScale.Inside,
                viewportSize = layoutSize,
                modifier = Modifier.pointerInput(onTap) {
                    detectTapGestures(onLongPress = onLongClick, onTap = onTap.partially1(null))
                },
                contentModifier = zoomMod,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(onTap) {
                        detectTapGestures(onLongPress = onLongClick, onTap = onTap.partially1(null))
                    }
                    .then(zoomMod),
            ) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (leftPage != null) {
                        PagerItem(
                            page = leftPage,
                            pageLoader = pageLoader,
                            contentScale = ContentScale.Fit,
                            viewportSize = halfSize,
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (rightPage != null) {
                        PagerItem(
                            page = rightPage,
                            pageLoader = pageLoader,
                            contentScale = ContentScale.Fit,
                            viewportSize = halfSize,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContainer(
    page: Page,
    pageLoader: ReaderSession,
    isRtl: Boolean,
    scaleType: Int,
    landscapeZoom: Boolean,
    autoRotateMode: Int,
    alignment: Alignment.Horizontal,
    layoutSize: Size,
    navigator: () -> NavigationRegions,
    pagerState: PagerState,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    onDoubleClick: DoubleClickToZoomListener,
    chromeVisible: Boolean,
    scope: CoroutineScope,
) {
    @Suppress("NAME_SHADOWING")
    val isRtl by rememberUpdatedState(isRtl)
    @Suppress("NAME_SHADOWING")
    val chromeVisible by rememberUpdatedState(chromeVisible)
    @Suppress("NAME_SHADOWING")
    val onMenuRegionClick by rememberUpdatedState(onMenuRegionClick)
    val zoomableState = rememberZoomableState(zoomSpec = PagerZoomSpec)
    val status = page.statusObserved
    if (status is PageStatus.Ready && layoutSize != Size.Zero) {
        val raw = status.image.intrinsicSize.toSize()
        // Must match PagerItem / FitPageImage (shouldAutoRotate) so draw + contentLocation lockstep.
        val rotate = shouldAutoRotate(raw, layoutSize, autoRotateMode)
        val size = fitDisplaySize(raw, rotate)
        val contentScale = ContentScale.fromPreferences(scaleType, size, layoutSize)
        zoomableState.contentScale = contentScale
        LaunchedEffect(size, contentScale, alignment) {
            val contentSize = if (contentScale is FixedScale) { // Original
                size
            } else {
                size * contentScale.computeScaleFactor(size, layoutSize)
            }
            val horizontalAlignment = if (contentSize.width > layoutSize.width) {
                alignment
            } else {
                Alignment.CenterHorizontally
            }
            val verticalAlignment = if (contentSize.height > layoutSize.height) {
                Alignment.Top
            } else {
                Alignment.CenterVertically
            }
            zoomableState.contentAlignment = horizontalAlignment + verticalAlignment
        }
        LaunchedEffect(size) {
            val contentLocation = ZoomableContentLocation.scaledInsideAndCenterAligned(size)
            zoomableState.setContentLocation(contentLocation)
        }
        // Skip landscape-zoom when this page was fit-rotated (would double-treat).
        if (landscapeZoom && !rotate && contentScale == ContentScale.Fit && raw.width > raw.height) {
            LaunchedEffect(alignment) {
                val zoomFraction = snapshotFlow { zoomableState.zoomFraction }.first { it != null }
                if (zoomFraction == 0f) {
                    delay(500)
                    val contentSize = with(zoomableState.coordinateSystem) {
                        unscaledContentBounds(false).sizeIn(CoordinateSpace.Viewport)
                    }
                    val scale = ContentScale.FillHeight.computeScaleFactor(contentSize, layoutSize)
                    val targetScale = scale.scaleX.coerceAtMost(zoomableState.zoomSpec.maximum.factor)
                    val offset = alignment.align(0, layoutSize.width.toInt(), LayoutDirection.Ltr)
                    zoomableState.zoomTo(targetScale, Offset(offset.toFloat(), 0f))
                }
            }
        }
    }
    val onLongClick = { _: Offset -> onSelectPage(page) }
    val onTap: ZoomableState?.(Offset) -> Unit = tap@{ offset ->
        if (chromeVisible) {
            onMenuRegionClick()
            return@tap
        }
        scope.launch {
            with(pagerState) {
                // Don't use `layoutSize` as it may capture outdated value
                val size = layoutInfo.viewportSize.toSize()
                val (w, h) = size
                val (x, y) = offset
                val distance = size.width
                val bounds = size.toRect()
                when (navigator().getAction(Offset(x / w, y / h))) {
                    NavigationRegion.MENU -> onMenuRegionClick()
                    NavigationRegion.NEXT -> {
                        val canPan = if (isRtl) panRight(distance, bounds) else panLeft(distance, bounds)
                        if (!canPan) {
                            moveToNext()
                        }
                    }
                    NavigationRegion.PREV -> {
                        val canPan = if (isRtl) panLeft(distance, bounds) else panRight(distance, bounds)
                        if (!canPan) {
                            moveToPrevious()
                        }
                    }
                    NavigationRegion.RIGHT -> {
                        if (!panLeft(distance, bounds)) {
                            if (isRtl) moveToPrevious() else moveToNext()
                        }
                    }
                    NavigationRegion.LEFT -> {
                        if (!panRight(distance, bounds)) {
                            if (isRtl) moveToNext() else moveToPrevious()
                        }
                    }
                }
            }
        }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PagerItem(
            page = page,
            pageLoader = pageLoader,
            contentScale = ContentScale.Inside,
            viewportSize = layoutSize,
            modifier = Modifier.pointerInput(onTap) {
                detectTapGestures(onLongPress = onLongClick, onTap = onTap.partially1(null))
            },
            contentModifier = Modifier.zoomable(
                state = zoomableState,
                onClick = onTap.partially1(zoomableState),
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            ),
        )
    }
}

private suspend fun ZoomableState?.panLeft(distance: Float, bounds: Rect): Boolean = if (canPan { it.right - bounds.right }) {
    panBy(Offset(-distance, 0f))
    true
} else {
    false
}

private suspend fun ZoomableState?.panRight(distance: Float, bounds: Rect): Boolean = if (canPan { bounds.left - it.left }) {
    panBy(Offset(distance, 0f))
    true
} else {
    false
}

private inline fun ZoomableState?.canPan(getRemaining: (Rect) -> Float): Boolean {
    // TODO: Remove when K2 mode in IDE is stable
    contract {
        returns(true) implies (this@canPan != null)
    }
    return this != null && Settings.navigateToPan.value &&
        getRemaining(with(coordinateSystem) { contentBounds(false).rectIn(CoordinateSpace.Viewport) }) > 1f
}

private val PagerZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 5f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)
