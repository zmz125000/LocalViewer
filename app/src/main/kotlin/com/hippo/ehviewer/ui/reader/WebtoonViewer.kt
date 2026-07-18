package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
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
    pageLoader: PageLoader,
    navigator: () -> NavigationRegions,
    onSelectPage: (Page) -> Unit,
    onMenuRegionClick: () -> Unit,
    onPrevFolder: () -> Unit = {},
    onNextFolder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val items = pageLoader.pages
    val zoomableState = rememberZoomableState(zoomSpec = WebtoonZoomSpec)
    val density = LocalDensity.current
    val paddingPercent by Settings.webtoonSidePadding.collectAsState()
    val sidePadding by remember(density) {
        snapshotFlow {
            with(density) {
                (lazyListState.layoutInfo.viewportSize.width * paddingPercent / 100f).toDp()
            }
        }
    }.collectAsState(0.dp)
    val doubleTap = remember(navigator, onPrevFolder, onNextFolder) {
        doubleTapAction(
            isRtl = false,
            getViewportSize = {
                lazyListState.layoutInfo.viewportSize.toSize().takeIf { it != Size.Zero } ?: Size.Zero
            },
            getNavigator = navigator,
            onPrevFolder = onPrevFolder,
            onNextFolder = onNextFolder,
        )
    }

    // At 1×: pinch only — one-finger drag belongs to LazyColumn.
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

    LazyColumn(
        modifier = modifier
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
                            val (w, h) = layoutInfo.viewportSize
                            val (x, y) = offset
                            when (navigator().getAction(Offset(x / w, y / h))) {
                                NavigationRegion.MENU -> onMenuRegionClick()
                                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
                            }
                        }
                    }
                },
                onLongClick = { ofs ->
                    val info = lazyListState.layoutInfo.visibleItemsInfo.find { info ->
                        info.offset <= ofs.y && info.offset + info.size > ofs.y
                    }
                    if (info != null) {
                        onSelectPage(items[info.index])
                    }
                },
                onDoubleClick = doubleTap,
            ),
        state = lazyListState,
        userScrollEnabled = !multiTouch,
        contentPadding = PaddingValues(horizontal = sidePadding),
        verticalArrangement = Arrangement.spacedBy(if (withGaps) 15.dp else 0.dp),
    ) {
        items(items, key = { it.index }) { page ->
            PagerItem(
                page = page,
                pageLoader = pageLoader,
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private val WebtoonZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 3f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)
