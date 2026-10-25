package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowShuffleTest {
    @Test
    fun visitsEveryOtherPageOnceThenEnds() {
        val shuffle = SlideshowShuffle()
        val visited = mutableListOf(2)
        repeat(9) {
            visited += requireNotNull(shuffle.next(visited.last(), 10))
        }
        assertEquals((0 until 10).toSet(), visited.toSet())
        assertNull(shuffle.next(visited.last(), 10))
        assertFalse(shuffle.next(visited.last(), 10, repeat = true) == visited.last())
    }

    @Test
    fun handlesSinglePageAndChangingPageCount() {
        val shuffle = SlideshowShuffle()
        assertNull(shuffle.next(0, 1))
        assertEquals(1, shuffle.next(0, 2))
        assertNull(shuffle.next(1, 2))
        assertNull(shuffle.next(0, 0))
    }

    @Test
    fun loaderLookaheadMatchesNextSlidesAndStaysStableAcrossReplans() {
        val shuffle = SlideshowShuffle()
        val planner = ReaderDemandPlanner()
        val policy = ReaderLoadPolicy(sourceAhead = 4, decodeAhead = 2)
        val navigation = ReaderNavigation(3, 3..3, NavigationKind.Settled)
        val sequential = planner.plan(navigation, 10, policy)
        val demand = shuffle.plan(sequential, 10, policy)
        assertEquals(listOf(3), demand.visibleDecode)
        assertEquals(demand.decodeAhead.first(), shuffle.next(3, 10))
        assertEquals(demand, shuffle.plan(sequential, 10, policy))
        val expected = demand.decodeAhead + demand.sourceOnly
        var current = 3
        val actual = expected.map {
            requireNotNull(shuffle.next(current, 10)).also { current = it }
        }
        assertEquals(expected, actual)
        assertFalse(3 in demand.sourcePages - demand.visibleDecode.toSet())
    }

    @Test
    fun crossingPagesDuringAnimatedJumpDoesNotConsumeThem() {
        val shuffle = SlideshowShuffle()
        val policy = ReaderLoadPolicy(sourceAhead = 3, decodeAhead = 2)
        val planner = ReaderDemandPlanner()
        val initial = shuffle.plan(planner.plan(ReaderNavigation(0, 0..0, NavigationKind.Settled), 8, policy), 8, policy)
        val crossed = initial.sourceOnly.first()
        shuffle.plan(planner.plan(ReaderNavigation(crossed, crossed..crossed, NavigationKind.Scroll), 8, policy), 8, policy)
        val after = shuffle.plan(planner.plan(ReaderNavigation(0, 0..0, NavigationKind.Settled), 8, policy), 8, policy)
        assertEquals(initial.decodeAhead, after.decodeAhead)
        assertEquals(initial.sourceOnly, after.sourceOnly)
    }

    @Test
    fun dualPageShuffleKeepsSpreadsTogetherAndCoverAlone() {
        val shuffle = SlideshowShuffle(pagesPerSlide = 2, firstPageAlone = true)
        val policy = ReaderLoadPolicy(sourceAhead = 6, decodeAhead = 4)
        val sequential = ReaderDemandPlanner().plan(ReaderNavigation(0, 0..0, NavigationKind.Settled), 7, policy)
        val demand = shuffle.plan(sequential, 7, policy)
        val ahead = demand.decodeAhead + demand.sourceOnly
        assertEquals((1..6).toSet(), ahead.toSet())
        ahead.chunked(2).forEach { pair ->
            assertEquals(pair.first() + 1, pair.last())
            assertTrue(pair.first() % 2 == 1)
        }
        assertEquals(ahead.first(), shuffle.next(0, 7))
    }

    @Test
    fun progressiveCatalogGrowthPreservesVisitedPagesAndPrefetchedNextSlide() {
        val shuffle = SlideshowShuffle()
        val visited = mutableSetOf(0)
        var current = requireNotNull(shuffle.next(0, 5))
        visited += current
        val prefetched = requireNotNull(shuffle.next(current, 5))
        assertEquals(prefetched, shuffle.next(current, 8))
        val remaining = mutableListOf<Int>()
        while (true) {
            val next = shuffle.next(current, 8) ?: break
            remaining += next
            current = next
        }
        assertEquals((0 until 8).toSet() - visited, remaining.toSet())
        assertEquals(remaining.size, remaining.toSet().size)
    }

    @Test
    fun newMateOfPreviouslySoloSpreadIsNotSkippedWhenCatalogGrows() {
        val shuffle = SlideshowShuffle(pagesPerSlide = 2)
        assertEquals(2, shuffle.next(0, 3))
        assertNull(shuffle.next(2, 3))
        assertEquals(2, shuffle.next(0, 4))
    }
}
