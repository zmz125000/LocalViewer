package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderDemandPlannerTest {
    @Test
    fun `source and decode windows are independent`() {
        val demand = ReaderDemandPlanner().plan(
            navigation = ReaderNavigation(10, 10..10, NavigationKind.Settled),
            pageCount = 30,
            policy = ReaderLoadPolicy(sourceAhead = 5, decodeAhead = 2),
        )

        assertEquals(listOf(10), demand.visibleDecode)
        assertEquals(listOf(11, 12), demand.decodeAhead)
        assertEquals(listOf(13, 14, 15), demand.sourceOnly)
        assertEquals(setOf(10, 11, 12, 13, 14, 15), demand.sourcePages)
    }

    @Test
    fun `decode can extend beyond source prefetch`() {
        val demand = ReaderDemandPlanner().plan(
            navigation = ReaderNavigation(4, 4..4, NavigationKind.Settled),
            pageCount = 20,
            policy = ReaderLoadPolicy(sourceAhead = 0, decodeAhead = 2),
        )

        assertEquals(listOf(5, 6), demand.decodeAhead)
        assertEquals(emptyList<Int>(), demand.sourceOnly)
    }

    @Test
    fun `direction reverses from real anchors`() {
        val planner = ReaderDemandPlanner()
        val policy = ReaderLoadPolicy(sourceAhead = 5, decodeAhead = 2)
        planner.plan(ReaderNavigation(10, 10..10, NavigationKind.Scroll), 30, policy)
        planner.plan(ReaderNavigation(12, 12..12, NavigationKind.Scroll), 30, policy)

        val backward = planner.plan(
            ReaderNavigation(11, 11..11, NavigationKind.Scroll),
            30,
            policy,
        )

        assertEquals(ReadingDirection.Backward, backward.direction)
        assertEquals(listOf(10, 9), backward.decodeAhead)
        assertEquals(listOf(8, 7, 6), backward.sourceOnly)
    }

    @Test
    fun `dual visible pages are demanded equally`() {
        val demand = ReaderDemandPlanner().plan(
            navigation = ReaderNavigation(8, 8..9, NavigationKind.Jump),
            pageCount = 20,
            policy = ReaderLoadPolicy(sourceAhead = 5, decodeAhead = 2),
        )

        assertEquals(listOf(8, 9), demand.visibleDecode)
        assertEquals(listOf(10, 11), demand.decodeAhead)
        assertEquals(listOf(12, 13, 14), demand.sourceOnly)
    }

    @Test
    fun `windows clamp at gallery ends`() {
        val demand = ReaderDemandPlanner().plan(
            navigation = ReaderNavigation(9, 9..9, NavigationKind.Jump),
            pageCount = 10,
            policy = ReaderLoadPolicy(sourceAhead = 5, decodeAhead = 2),
        )

        assertEquals(emptyList<Int>(), demand.decodeAhead)
        assertEquals(emptyList<Int>(), demand.sourceOnly)
    }

    @Test
    fun `stationary updates retain the last movement direction`() {
        val planner = ReaderDemandPlanner()
        val policy = ReaderLoadPolicy(sourceAhead = 3, decodeAhead = 1)
        planner.plan(ReaderNavigation(8, 8..8, NavigationKind.Scroll), 20, policy)
        planner.plan(ReaderNavigation(7, 7..7, NavigationKind.Scroll), 20, policy)

        val settled = planner.plan(
            ReaderNavigation(7, 7..7, NavigationKind.Settled),
            20,
            policy,
        )

        assertEquals(ReadingDirection.Backward, settled.direction)
        assertEquals(listOf(6), settled.decodeAhead)
        assertEquals(listOf(5, 4), settled.sourceOnly)
    }
}
