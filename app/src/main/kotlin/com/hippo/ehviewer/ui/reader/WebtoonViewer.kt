package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.ReaderSession
import eu.kanade.tachiyomi.ui.reader.viewer.NavigationRegions
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.getAction
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

@Composable
fun WebtoonViewer(
    lazyListState: LazyListState,
    withGaps: Boolean,
    pageLoader: ReaderSession,
    navigator: () -> NavigationRegions,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    onPrevFolder: () -> Unit = {},
    onNextFolder: () -> Unit = {},
    onBack: () -> Unit = {},
    /**
     * Landscape dual: continuous horizontal strip (no page pairing).
     * Always right-to-left (manga): page 0 on the right, next pages toward the left.
     */
    horizontal: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    @Suppress("NAME_SHADOWING")
    val onMenuRegionClick by rememberUpdatedState(onMenuRegionClick)
    // Snapshot size drives item count; do not capture a stale pages list length.
    val pageCount = pageLoader.size
    val items = pageLoader.pages
    val zoomableState = rememberZoomableState(zoomSpec = WebtoonZoomSpec)
    val density = LocalDensity.current
    // Horizontal dual webtoon reads RTL like a manga strip.
    val isRtl = horizontal
    val paddingPercent by Settings.webtoonSidePadding.collectAsState()
    // Viewport from onSizeChanged only (not layoutInfo). Reading layoutInfo inside Lazy
    // items recomposes every scroll frame; landscape dual shows many narrow pages so that
    // thrash is much worse than portrait webtoon (1–2 tall items).
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    val viewportSize = remember(viewportPx) {
        Size(viewportPx.width.toFloat(), viewportPx.height.toFloat())
    }
    val sidePadding = with(density) {
        val edge = if (horizontal) viewportPx.height else viewportPx.width
        (edge * paddingPercent / 100f).toDp()
    }
    val doubleTap = remember(navigator, onPrevFolder, onNextFolder, onBack, isRtl, viewportSize) {
        doubleTapAction(
            isRtl = isRtl,
            getViewportSize = {
                viewportSize.takeIf { it != Size.Zero } ?: Size.Zero
            },
            getNavigator = navigator,
            onPrevFolder = onPrevFolder,
            onNextFolder = onNextFolder,
            onBack = onBack,
        )
    }

    // At 1×: pinch only — one-finger drag belongs to LazyColumn/Row.
    // When zoomed: pan + pinch so content can be moved; edge pass-through via Telephoto canPan.
    val zoomFraction = zoomableState.zoomFraction ?: 0f
    val zoomedIn = zoomFraction > 0.01f
    val gestures = if (zoomedIn) {
        EnabledZoomGestures.ZoomAndPan
    } else {
        EnabledZoomGestures(zoom = true, pan = false)
    }

    // While 2+ fingers are down, stop list scroll so pinch can own the pointer stream.
    var multiTouch by remember { mutableStateOf(false) }

    val listModifier = modifier
        .onSizeChanged { size ->
            if (size != viewportPx) viewportPx = size
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    multiTouch = event.changes.count { it.pressed } >= 2
                }
            }
        }
        .zoomable(
            state = zoomableState,
            gestures = gestures,
            onClick = { offset ->
                scope.launch {
                    with(lazyListState) {
                        // Prefer stable size; fall back to layoutInfo only for the tap gesture.
                        val w = viewportPx.width.takeIf { it > 0 }
                            ?: layoutInfo.viewportSize.width
                        val h = viewportPx.height.takeIf { it > 0 }
                            ?: layoutInfo.viewportSize.height
                        if (w <= 0 || h <= 0) return@with
                        val (x, y) = offset
                        // Index++ = next page. With reverseLayout, that is still scrollRight().
                        // LEFT/RIGHT zones flip in RTL so the left side advances (manga).
                        when (navigator().getAction(Offset(x / w, y / h))) {
                            NavigationRegion.MENU -> onMenuRegionClick()
                            NavigationRegion.NEXT -> {
                                if (horizontal) scrollRight() else scrollDown()
                            }
                            NavigationRegion.PREV -> {
                                if (horizontal) scrollLeft() else scrollUp()
                            }
                            NavigationRegion.RIGHT -> {
                                when {
                                    !horizontal -> scrollDown()
                                    isRtl -> scrollLeft()
                                    else -> scrollRight()
                                }
                            }
                            NavigationRegion.LEFT -> {
                                when {
                                    !horizontal -> scrollUp()
                                    isRtl -> scrollRight()
                                    else -> scrollLeft()
                                }
                            }
                        }
                    }
                }
            },
            onLongClick = { ofs ->
                val info = if (horizontal) {
                    // reverseLayout may place items with negative offsets; hit-test both axes.
                    lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                        val start = info.offset.toFloat()
                        val end = start + info.size
                        ofs.x in start..end || ofs.x in end..start
                    }
                } else {
                    lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                        info.offset <= ofs.y && info.offset + info.size > ofs.y
                    }
                }
                if (info != null) {
                    onSelectPage(items[info.index])
                }
            },
            onDoubleClick = doubleTap,
        )

    val gap = if (withGaps) 15.dp else 0.dp
    val contentScale = if (horizontal) ContentScale.FillHeight else ContentScale.FillWidth

    // key axis so LazyColumn ↔ LazyRow swap does not reuse incompatible item measure.
    key(horizontal) {
        if (horizontal) {
            LazyRow(
                modifier = listModifier,
                state = lazyListState,
                reverseLayout = true,
                userScrollEnabled = !multiTouch,
                contentPadding = PaddingValues(vertical = sidePadding),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(
                    count = pageCount,
                    key = { index -> items.getOrNull(index)?.index ?: index },
                ) { index ->
                    val page = items.getOrNull(index) ?: return@items
                    PagerItem(
                        page = page,
                        pageLoader = pageLoader,
                        contentScale = contentScale,
                        viewportSize = viewportSize,
                        horizontalStrip = true,
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = listModifier,
                state = lazyListState,
                userScrollEnabled = !multiTouch,
                contentPadding = PaddingValues(horizontal = sidePadding),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                items(
                    count = pageCount,
                    key = { index -> items.getOrNull(index)?.index ?: index },
                ) { index ->
                    val page = items.getOrNull(index) ?: return@items
                    PagerItem(
                        page = page,
                        pageLoader = pageLoader,
                        contentScale = contentScale,
                        viewportSize = viewportSize,
                    )
                }
            }
        }
    }
}

private val WebtoonZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 3f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)
