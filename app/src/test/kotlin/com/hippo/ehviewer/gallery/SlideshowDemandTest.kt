package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlideshowDemandTest {
    private val policy = ReaderLoadPolicy(sourceAhead = 4, decodeAhead = 1)

    @Test
    fun manualBackStepStillDecodesOnlyTheNextForwardPage() {
        val planner = ReaderDemandPlanner()
        planner.plan(ReaderNavigation(10, 10..10, NavigationKind.Settled), 20, policy, forceForward = true)
        val demand = planner.plan(ReaderNavigation(9, 9..9, NavigationKind.Settled), 20, policy, forceForward = true)
        assertEquals(ReadingDirection.Forward, demand.direction)
        assertEquals(listOf(10), demand.decodeAhead)
        assertEquals(setOf(9, 10), demand.decodedPages)
        assertFalse(8 in demand.sourcePages)
    }

    @Test
    fun nextViewportDemandDropsThePageThatWasPassed() {
        val planner = ReaderDemandPlanner()
        val first = planner.plan(ReaderNavigation(5, 5..5, NavigationKind.Settled), 20, policy, forceForward = true)
        val next = planner.plan(ReaderNavigation(6, 6..6, NavigationKind.Settled), 20, policy, forceForward = true)
        assertEquals(setOf(5, 6), first.decodedPages)
        assertEquals(setOf(6, 7), next.decodedPages)
        assertFalse(5 in next.decodedPages)
    }

    @Test
    fun shufflePredecodesOnlyItsNextDestination() {
        val planner = ReaderDemandPlanner()
        val shuffle = SlideshowShuffle()
        val demand = shuffle.plan(
            planner.plan(ReaderNavigation(5, 5..5, NavigationKind.Settled), 20, policy, forceForward = true),
            20,
            policy,
        )
        assertEquals(1, demand.decodeAhead.size)
        assertEquals(shuffle.next(5, 20), demand.decodeAhead.single())
        val nextPage = demand.decodeAhead.single()
        val next = shuffle.plan(
            planner.plan(ReaderNavigation(nextPage, nextPage..nextPage, NavigationKind.Settled), 20, policy, forceForward = true),
            20,
            policy,
        )
        assertEquals(1, next.decodeAhead.size)
        assertFalse(5 in next.decodedPages)
    }

    @Test
    fun pendingSlideshowTransitionReplacesRatherThanAddsAnotherDecodeAhead() {
        val demand = ReaderDemandPlanner().plan(
            ReaderNavigation(5, 5..5, NavigationKind.Settled),
            20,
            policy,
            forceForward = true,
        )
        val preparing = demand.withTransitionPages(setOf(5, 15), replaceLookahead = true)
        assertEquals(listOf(15), preparing.decodeAhead)
        assertEquals(setOf(5, 15), preparing.decodedPages)
        assertFalse(6 in preparing.decodedPages)
        assertEquals(listOf(6), demand.withTransitionPages(emptySet()).decodeAhead)
    }

    @Test
    fun ordinaryReaderStillSupportsBackwardDecode() {
        val planner = ReaderDemandPlanner()
        planner.plan(ReaderNavigation(10, 10..10, NavigationKind.Settled), 20, policy)
        val demand = planner.plan(ReaderNavigation(9, 9..9, NavigationKind.Settled), 20, policy)
        assertEquals(ReadingDirection.Backward, demand.direction)
        assertEquals(listOf(8), demand.decodeAhead)
    }
}
