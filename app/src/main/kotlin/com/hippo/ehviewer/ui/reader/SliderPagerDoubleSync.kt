package com.hippo.ehviewer.ui.reader

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
import com.hippo.ehviewer.gallery.PageLoader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull

@Stable
class SliderPagerDoubleSync(
    private val lazyListState: LazyListState,
    private val pagerState: PagerState,
    private val pageLoader: PageLoader,
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
     */
    fun currentPageFlow(webtoon: Boolean, pagerDual: Boolean) = if (webtoon) {
        snapshotFlow {
            with(lazyListState.layoutInfo) {
                visibleItemsInfo.lastOrNull {
                    it.offset <= maxOf(viewportStartOffset, viewportEndOffset - it.size)
                }?.index
            }
        }.filterNotNull()
    } else if (pagerDual) {
        // Pager page = spread; expose first real page of the spread.
        snapshotFlow { dualFirstPageIndex(pagerState.currentPage) }
    } else {
        snapshotFlow { pagerState.currentPage }
    }

    @Composable
    fun Sync(webtoon: Boolean, pagerDual: Boolean = false, onPageSelected: () -> Unit) {
        val currentIndexFlow = remember(webtoon, pagerDual) {
            val initialIndex = sliderValue - 1
            sliderFollowPager = if (webtoon) {
                lazyListState.firstVisibleItemIndex == initialIndex
            } else if (pagerDual) {
                dualFirstPageIndex(pagerState.currentPage) == initialIndex ||
                    dualSpreadIndex(initialIndex) == pagerState.currentPage
            } else {
                pagerState.currentPage == initialIndex
            }
            currentPageFlow(webtoon, pagerDual)
        }
        if (sliderFollowPager) {
            LaunchedEffect(currentIndexFlow) {
                currentIndexFlow.drop(1).collect { index ->
                    // Always store real page index.
                    sliderValue = index + 1
                    pageLoader.startPage = index
                    onPageSelected()
                }
            }
        } else {
            LaunchedEffect(webtoon, pagerDual) {
                snapshotFlow { sliderValue - 1 }.collectLatest { index ->
                    if (webtoon) {
                        lazyListState.scrollToItem(index)
                    } else if (pagerDual) {
                        pagerState.animateScrollToPage(dualSpreadIndex(index))
                    } else {
                        pagerState.animateScrollToPage(index)
                    }
                    pageLoader.startPage = index
                }
            }
        }
    }
}

@Stable
@Composable
fun rememberSliderPagerDoubleSyncState(
    lazyListState: LazyListState,
    pagerState: PagerState,
    pageLoader: PageLoader,
): SliderPagerDoubleSync = remember {
    SliderPagerDoubleSync(lazyListState, pagerState, pageLoader)
}
