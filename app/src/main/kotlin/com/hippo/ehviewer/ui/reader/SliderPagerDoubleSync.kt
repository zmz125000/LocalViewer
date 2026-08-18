package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.hippo.ehviewer.gallery.NavigationKind
import com.hippo.ehviewer.gallery.ReaderNavigation
import com.hippo.ehviewer.gallery.ReaderSession
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

@Stable
class SliderPagerDoubleSync(
    private val lazyListState: LazyListState,
    private val pagerState: PagerState,
    private val pageLoader: ReaderSession,
) {
    private var sliderFollowPager by mutableStateOf(true)
    var sliderValue by mutableIntStateOf(pageLoader.startPage + 1)
        private set

    fun sliderScrollTo(index: Int) {
        sliderFollowPager = false
        // Always real page indices (1-based).
        sliderValue = index.coerceIn(1, pageLoader.size.coerceAtLeast(1))
    }

    fun reset() {
        sliderFollowPager = true
    }

    /**
     * @param webtoon continuous / webtoon list
     * @param pagerDual true when pager uses spread indices (LTR/RTL/Vertical dual)
     * @param webtoonHorizontal landscape dual webtoon (LazyRow reverseLayout RTL)
     */
    fun currentPageFlow(
        webtoon: Boolean,
        pagerDual: Boolean,
        webtoonHorizontal: Boolean,
    ) = if (webtoon) {
        snapshotFlow {
            lazyListState.layoutInfo.webtoonReadingIndex(horizontal = webtoonHorizontal)
        }.filterNotNull()
    } else if (pagerDual) {
        // Pager page = spread; expose first real page of the spread.
        snapshotFlow { dualFirstPageIndex(pagerState.currentPage) }
    } else {
        snapshotFlow { pagerState.currentPage }
    }

    @Composable
    fun Sync(
        webtoon: Boolean,
        pagerDual: Boolean = false,
        webtoonHorizontal: Boolean = false,
        onPageSelected: () -> Unit,
    ) {
        // Drag on the list/pager reclaims follow (volume keys / fling after seek).
        val listDragged by lazyListState.interactionSource.collectIsDraggedAsState()
        val pagerDragged by pagerState.interactionSource.collectIsDraggedAsState()
        if (listDragged || pagerDragged) {
            sliderFollowPager = true
        }

        val currentIndexFlow = remember(webtoon, pagerDual, webtoonHorizontal, lazyListState, pagerState) {
            currentPageFlow(webtoon, pagerDual, webtoonHorizontal)
        }
        if (sliderFollowPager) {
            LaunchedEffect(currentIndexFlow, pageLoader) {
                currentIndexFlow.distinctUntilChanged().drop(1).collect { index ->
                    // Always store real page index.
                    sliderValue = index + 1
                    pageLoader.startPage = index
                    onPageSelected()
                }
            }
        } else {
            LaunchedEffect(webtoon, pagerDual, pageLoader) {
                snapshotFlow { sliderValue - 1 }.collectLatest { index ->
                    val safe = index.coerceIn(0, (pageLoader.size - 1).coerceAtLeast(0))
                    val visible = if (pagerDual) {
                        val first = dualFirstPageIndex(dualSpreadIndex(safe))
                        first..minOf(first + 1, pageLoader.size - 1)
                    } else {
                        safe..safe
                    }
                    // Every seekbar tick is a real latest-wins jump. Demand is published
                    // before UI scrolling so source/decode work can preempt stale windows.
                    pageLoader.navigate(
                        ReaderNavigation(
                            anchor = visible.first,
                            visiblePages = visible,
                            kind = NavigationKind.Jump,
                        ),
                    )
                    if (webtoon) {
                        lazyListState.scrollToItem(safe)
                    } else if (pagerDual) {
                        pagerState.animateScrollToPage(dualSpreadIndex(safe))
                    } else {
                        pagerState.animateScrollToPage(safe)
                    }
                    pageLoader.startPage = safe
                    // Resume follow only after the jump lands. onValueChangeFinished
                    // runs in the same frame as a tap and would cancel this scroll.
                    sliderFollowPager = true
                }
            }
        }
    }
}

/**
 * Index that drives the seek bar / progress for webtoon.
 *
 * Vertical: historical "last item still in the upper portion" heuristic.
 *
 * Horizontal dual (reverseLayout RTL from e4682de): item offsets are often negative,
 * so the vertical predicate matches nothing and the slider freezes. Use the item
 * closest to the viewport center instead.
 */
internal fun LazyListLayoutInfo.webtoonReadingIndex(horizontal: Boolean): Int? {
    val items = visibleItemsInfo
    if (items.isEmpty()) return null
    if (!horizontal) {
        return items.lastOrNull {
            it.offset <= maxOf(viewportStartOffset, viewportEndOffset - it.size)
        }?.index
    }
    val viewCenter = (viewportStartOffset + viewportEndOffset) / 2
    return items.minByOrNull { item ->
        abs(item.offset + item.size / 2 - viewCenter)
    }?.index
}

@Stable
@Composable
fun rememberSliderPagerDoubleSyncState(
    lazyListState: LazyListState,
    pagerState: PagerState,
    pageLoader: ReaderSession,
): SliderPagerDoubleSync = remember(lazyListState, pagerState, pageLoader) {
    SliderPagerDoubleSync(lazyListState, pagerState, pageLoader)
}
