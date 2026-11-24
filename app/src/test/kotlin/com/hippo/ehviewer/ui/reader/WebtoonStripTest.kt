package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebtoonStripTest {
    private class StripHarness(
        val itemCount: Int,
        val viewportMainSize: Int = 1000,
        val gap: Int = 0,
        val horizontal: Boolean = false,
        val cacheBufferPx: Int = 750,
        val maxCacheItems: Int = 2,
        initialIndex: Int = 0,
        initialOffset: Int = 0,
    ) {
        val sizes = IntArray(itemCount) { 1412 }
        val stable = BooleanArray(itemCount)
        var anchor = WebtoonAnchor(initialIndex, WebtoonAnchorEdge.Start, -initialOffset.toFloat())
        var direction = WebtoonScrollDirection.Forward
        var pinAnchorUntilStable = true
        var lastMeasureCounts = HashMap<Int, Int>()

        fun markLoaded(index: Int, actualSize: Int) {
            sizes[index] = actualSize
            stable[index] = true
        }

        fun layout(scrollDelta: Float = 0f): WebtoonMeasureResult {
            lastMeasureCounts.clear()
            val result = measureWebtoonStrip(
                itemCount = itemCount,
                viewportMainSize = viewportMainSize,
                gap = gap,
                horizontal = horizontal,
                cacheBufferPx = cacheBufferPx,
                maxCacheItems = maxCacheItems,
                anchor = anchor,
                lastDirection = direction,
                pinAnchorUntilStable = pinAnchorUntilStable,
                scrollDelta = scrollDelta,
                isSizeStable = { stable[it] },
                measureItem = { idx ->
                    lastMeasureCounts[idx] = (lastMeasureCounts[idx] ?: 0) + 1
                    sizes[idx]
                },
            )
            anchor = result.anchor
            direction = result.direction
            pinAnchorUntilStable = result.pinAnchorUntilStable
            return result
        }
    }

    @Test
    fun forwardScrollRemainsStableWhenFollowingImageResizes() {
        val h = StripHarness(itemCount = 20, viewportMainSize = 1000, gap = 10, initialIndex = 5)
        h.markLoaded(5, 800)
        val first = h.layout()
        assertEquals(0, first.visibleItems.first { it.index == 5 }.offset)
        assertEquals(810, first.visibleItems.first { it.index == 6 }.offset)

        // Page 6 loads as a tall webtoon panel (3500px) — page 5 and page 6 start stay fixed.
        h.markLoaded(6, 3500)
        val afterGrow = h.layout()
        assertEquals(0, afterGrow.visibleItems.first { it.index == 5 }.offset)
        assertEquals(810, afterGrow.visibleItems.first { it.index == 6 }.offset)

        // Page 6 shrinks to a short landscape panel (300px) — page 5 still stays fixed.
        h.markLoaded(6, 300)
        val afterShrink = h.layout()
        assertEquals(0, afterShrink.visibleItems.first { it.index == 5 }.offset)
        assertEquals(810, afterShrink.visibleItems.first { it.index == 6 }.offset)
    }

    @Test
    fun backwardScrollDoesNotJumpWhenPreviousImageGrows() {
        val h = StripHarness(itemCount = 20, viewportMainSize = 1000, gap = 15, initialIndex = 10)
        h.markLoaded(10, 1400)
        h.layout()

        // Scroll backward by 250px so unloaded placeholder page 9 peeks in above page 10.
        val peek = h.layout(scrollDelta = -250f)
        assertEquals(listOf(9, 10), peek.visibleItems.map { it.index })
        assertEquals(250, peek.visibleItems.first { it.index == 10 }.offset)
        assertEquals(250 - 15 - 1412, peek.visibleItems.first { it.index == 9 }.offset)

        // Page 9 finishes decoding at 4000px tall — page 10 MUST stay at y = 250!
        h.markLoaded(9, 4000)
        val afterDecode = h.layout()
        assertEquals(listOf(9, 10), afterDecode.visibleItems.map { it.index })
        assertEquals(250, afterDecode.visibleItems.first { it.index == 10 }.offset)
        assertEquals(250 - 15 - 4000, afterDecode.visibleItems.first { it.index == 9 }.offset)
    }

    @Test
    fun backwardScrollDoesNotJumpWhenPreviousImageShrinksAndBackfillsAbove() {
        val h = StripHarness(itemCount = 20, viewportMainSize = 1000, gap = 10, initialIndex = 10)
        h.markLoaded(10, 1200)
        h.layout()

        // Scroll backward by 400px: page 10 sits at y = 400, placeholder page 9 covers [0, 390].
        val peek = h.layout(scrollDelta = -400f)
        assertEquals(400, peek.visibleItems.first { it.index == 10 }.offset)

        // Page 9 finishes decoding as a short 200px image — page 10 stays at y = 400,
        // page 9 sits at [190, 390], and page 8 automatically fills above [..., 180].
        h.markLoaded(9, 200)
        val afterDecode = h.layout()
        assertEquals(listOf(8, 9, 10), afterDecode.visibleItems.map { it.index })
        assertEquals(400, afterDecode.visibleItems.first { it.index == 10 }.offset)
        assertEquals(190, afterDecode.visibleItems.first { it.index == 9 }.offset)
        assertEquals(180 - 1412, afterDecode.visibleItems.first { it.index == 8 }.offset)
    }

    @Test
    fun backwardFlingAcrossMultipleUnloadedPagesKeepsFirstDecodedImagePinned() {
        val h = StripHarness(itemCount = 25, viewportMainSize = 1000, gap = 0, initialIndex = 20)
        h.markLoaded(20, 1400)
        h.layout()

        // Fling backward 3000px into unloaded pages 17 & 18.
        val landed = h.layout(scrollDelta = -3000f)
        assertEquals(listOf(17, 18), landed.visibleItems.map { it.index })
        val page18StartBefore = landed.visibleItems.first { it.index == 18 }.offset
        val page18EndBefore = page18StartBefore + 1412

        // Page 17 decodes first (3000px) — seam between 17 and 18 stays pinned!
        h.markLoaded(17, 3000)
        val after17 = h.layout()
        assertEquals(page18StartBefore, after17.visibleItems.first { it.index == 18 }.offset)
        assertEquals(page18StartBefore - 3000, after17.visibleItems.first { it.index == 17 }.offset)

        // Page 18 decodes next (500px) — page 17 (already visible & decoded!) stays frozen!
        val page17Offset = after17.visibleItems.first { it.index == 17 }.offset
        h.markLoaded(18, 500)
        val after18 = h.layout()
        assertEquals(page17Offset, after18.visibleItems.first { it.index == 17 }.offset)
        assertEquals(page18StartBefore, after18.visibleItems.first { it.index == 18 }.offset)
        assertEquals(page18EndBefore - 912, after18.visibleItems.first { it.index == 18 }.offset + 500)
    }

    @Test
    fun backwardScrollCrossingSeamDuringDecodeStaysContinuous() {
        val h = StripHarness(itemCount = 20, viewportMainSize = 1000, gap = 0, initialIndex = 10)
        h.markLoaded(10, 1400)
        h.layout()

        // Scroll back 950px: page 10 is still peeking at bottom [950..2350].
        val nearSeam = h.layout(scrollDelta = -950f)
        assertEquals(950, nearSeam.visibleItems.first { it.index == 10 }.offset)

        // In the exact same frame page 9 decodes at 4000px, user scrolls another -100px
        // (pushing page 10 just offscreen to y = 1050). Page 9's bottom must land at y = 1050.
        h.markLoaded(9, 4000)
        val crossed = h.layout(scrollDelta = -100f)
        assertEquals(listOf(9), crossed.visibleItems.map { it.index })
        val item9 = crossed.visibleItems.single()
        assertEquals(1050 - 4000, item9.offset)
        assertEquals(1050, item9.offset + item9.size)
    }

    @Test
    fun freshJumpKeepsTargetTopPinnedEvenIfNextPageDecodesFirst() {
        val h = StripHarness(itemCount = 20, viewportMainSize = 2000, gap = 0, initialIndex = 10)
        val initial = h.layout()
        assertEquals(listOf(10, 11), initial.visibleItems.map { it.index })
        assertEquals(0, initial.visibleItems.first { it.index == 10 }.offset)

        // Page 11 decodes before page 10.
        h.markLoaded(11, 800)
        val after11 = h.layout()
        assertEquals(0, after11.visibleItems.first { it.index == 10 }.offset)

        // Page 10 then decodes at 500px — page 10 stays pinned at y = 0!
        h.markLoaded(10, 500)
        val after10 = h.layout()
        assertEquals(0, after10.visibleItems.first { it.index == 10 }.offset)
        assertEquals(500, after10.visibleItems.first { it.index == 11 }.offset)
    }

    @Test
    fun boundariesClampAndReportExactConsumedDelta() {
        val h = StripHarness(itemCount = 2, viewportMainSize = 1000, gap = 0, initialIndex = 0)
        h.markLoaded(0, 600)
        h.markLoaded(1, 600)
        val start = h.layout()
        assertFalse(start.canScrollBackward)
        assertTrue(start.canScrollForward)

        // Total height is 1200 -> max forward scroll is 200.
        val overForward = h.layout(scrollDelta = 350f)
        assertEquals(200f, overForward.consumedDelta, 0.01f)
        assertFalse(overForward.canScrollForward)
        assertTrue(overForward.canScrollBackward)
        assertEquals(-200, overForward.visibleItems.first { it.index == 0 }.offset)

        // Scroll backward past top by 350 -> only -200 consumed.
        val overBackward = h.layout(scrollDelta = -350f)
        assertEquals(-200f, overBackward.consumedDelta, 0.01f)
        assertFalse(overBackward.canScrollBackward)
        assertTrue(overBackward.canScrollForward)
        assertEquals(0, overBackward.visibleItems.first { it.index == 0 }.offset)
    }

    @Test
    fun contentShorterThanViewportStaysPinnedToStart() {
        val h = StripHarness(itemCount = 1, viewportMainSize = 1000, gap = 0, initialIndex = 0)
        h.markLoaded(0, 400)
        val r = h.layout(scrollDelta = 150f)
        assertEquals(0f, r.consumedDelta, 0.01f)
        assertEquals(0, r.visibleItems.single().offset)
        assertFalse(r.canScrollForward)
        assertFalse(r.canScrollBackward)
    }

    @Test
    fun horizontalRtlComputesPhysicalScreenCoordinatesAndStaysStableOnBackwardDecode() {
        val h = StripHarness(
            itemCount = 10,
            viewportMainSize = 1000,
            gap = 10,
            horizontal = true,
            initialIndex = 5,
        )
        h.markLoaded(5, 600)
        val initial = h.layout()
        val item5 = initial.visibleItems.first { it.index == 5 }
        // Page 5 sits flush on the right edge: [400, 1000].
        assertEquals(400, item5.screenStart)
        assertEquals(1000, item5.screenEnd)

        // Scroll backward by 200px so unloaded page 4 enters from the right.
        val peek = h.layout(scrollDelta = -200f)
        val item5Peek = peek.visibleItems.first { it.index == 5 }
        assertEquals(200, item5Peek.offset)
        assertEquals(200, item5Peek.screenStart)
        assertEquals(800, item5Peek.screenEnd)

        // Page 4 decodes at 900px wide — page 5 stays at exact screen [200, 800]!
        h.markLoaded(4, 900)
        val afterDecode = h.layout()
        val item5After = afterDecode.visibleItems.first { it.index == 5 }
        val item4After = afterDecode.visibleItems.first { it.index == 4 }
        assertEquals(200, item5After.screenStart)
        assertEquals(800, item5After.screenEnd)
        assertEquals(810, item4After.screenStart)
        assertEquals(1710, item4After.screenEnd)
    }

    @Test
    fun subPixelScrollAccumulatesWithoutDriftAndMeasuresEachIndexAtMostOnce() {
        val h = StripHarness(itemCount = 10, viewportMainSize = 1000, gap = 0, initialIndex = 0)
        for (i in 0 until 10) h.markLoaded(i, 1000)

        val atRest = h.layout()
        // At rest on page 0 ([0, 1000]): page 1 stays composed in the cache window without entering visibleItems.
        assertEquals(listOf(0), atRest.visibleItems.map { it.index })
        assertEquals(listOf(0, 1), atRest.composedItems.map { it.index })

        var res = atRest
        repeat(10) {
            res = h.layout(scrollDelta = 0.4f)
            for ((idx, count) in h.lastMeasureCounts) {
                assertEquals("Index $idx measured more than once", 1, count)
            }
        }
        // 10 * 0.4f = 4px forward.
        assertEquals(-4, res.visibleItems.first { it.index == 0 }.offset)
        assertEquals(listOf(0, 1), res.visibleItems.map { it.index })

        // Approaching page 2 within cacheBufferPx (750px) brings page 2 into composedItems only.
        val nearPage2 = h.layout(scrollDelta = 300f)
        assertEquals(listOf(0, 1), nearPage2.visibleItems.map { it.index })
        assertEquals(listOf(0, 1, 2), nearPage2.composedItems.map { it.index })
    }

    @Test
    fun lookaheadFollowedByMainMeasurePreservesConsumedScrollDelta() {
        val state = WebtoonStripState(initialFirstVisibleItemIndex = 0)
        val sizes = IntArray(5) { 1000 }
        val viewport = IntSize(1000, 1000)

        fun runMeasurePass() {
            val result = measureWebtoonStrip(
                itemCount = sizes.size,
                viewportMainSize = viewport.height,
                gap = 0,
                horizontal = false,
                cacheBufferPx = 750,
                maxCacheItems = 2,
                anchor = state.anchor,
                lastDirection = state.lastDirection,
                pinAnchorUntilStable = state.pinAnchorUntilStable,
                scrollDelta = state.consumePendingScrollDelta(),
                isSizeStable = { true },
                measureItem = { sizes[it] },
            )
            state.applyMeasureResult(
                result = result,
                viewportSize = viewport,
                viewportMainSize = viewport.height,
                totalItemsCount = sizes.size,
            )
        }

        // Simulate SharedTransitionLayout / LookaheadScope: forceRemeasure executes a lookahead
        // measure pass immediately followed by a main measure pass.
        state.remeasurementModifier.onRemeasurementAvailable(
            object : Remeasurement {
                override fun forceRemeasure() {
                    runMeasurePass()
                    runMeasurePass()
                }
            },
        )
        runMeasurePass()
        runMeasurePass()

        val consumedForward = state.dispatchRawDelta(250f)
        assertEquals(250f, consumedForward, 0.01f)
        assertEquals(-250, state.layoutInfo.visibleItemsInfo.first { it.index == 0 }.offset)

        val consumedBackward = state.dispatchRawDelta(-100f)
        assertEquals(-100f, consumedBackward, 0.01f)
        assertEquals(-150, state.layoutInfo.visibleItemsInfo.first { it.index == 0 }.offset)
    }
}
