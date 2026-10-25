package com.hippo.ehviewer.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderTransitionDemandTest {
    @Test
    fun transitionRetainsViewportAndPrioritizesDistantDestination() {
        val demand = ReaderDemandPlanner().plan(
            ReaderNavigation(0, 0..1, NavigationKind.Settled),
            100,
            ReaderLoadPolicy(sourceAhead = 4, decodeAhead = 1),
        )
        val prepared = demand.withTransitionPages(setOf(80, 81, 0, 1))
        assertEquals(listOf(0, 1), prepared.visibleDecode)
        assertEquals(listOf(80, 81, 2), prepared.decodeAhead)
        assertEquals(setOf(0, 1, 2, 80, 81), prepared.decodedPages)
        assertEquals(demand.navigation, prepared.navigation)
        assertFalse(prepared.sourceOnly.any { it in prepared.decodedPages })
        assertEquals(demand, demand.withTransitionPages(emptySet()))
    }

    @Test
    fun transitionPromotesPrefetchedSourceWithoutDuplicatingIt() {
        val demand = ReaderDemandPlanner().plan(
            ReaderNavigation(0, 0..0, NavigationKind.Settled),
            10,
            ReaderLoadPolicy(sourceAhead = 4, decodeAhead = 0),
        )
        val prepared = demand.withTransitionPages(setOf(3))
        assertEquals(listOf(3), prepared.decodeAhead)
        assertEquals(listOf(1, 2, 4), prepared.sourceOnly)
        assertEquals(setOf(0, 1, 2, 3, 4), prepared.sourcePages)
    }
}
