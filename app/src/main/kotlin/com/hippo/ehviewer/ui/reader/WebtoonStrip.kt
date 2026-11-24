package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.getScrollViewportLength
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.indexForKey
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.scrollToIndex
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Edge of an anchor item held fixed across dynamic item-size changes. */
internal enum class WebtoonAnchorEdge {
    Start,
    End,
}

/** Semantic reading direction of the last scroll gesture (increasing vs decreasing index). */
internal enum class WebtoonScrollDirection {
    Forward,
    Backward,
}

/**
 * Viewport anchor in logical reading-axis coordinates (`0` = start edge, `viewportMainSize` = end edge).
 *
 * Keeping a bidirectional anchor prevents viewport jumps when an item above (or before) the current
 * reading position finishes decoding and changes height/width: backward scroll pins the trailing
 * edge or the already-stable page below, so newly decoded pages expand away from the reader.
 */
internal data class WebtoonAnchor(
    val index: Int = 0,
    val edge: WebtoonAnchorEdge = WebtoonAnchorEdge.Start,
    val offset: Float = 0f,
)

@Immutable
data class WebtoonStripItem(
    val index: Int,
    /** Logical start offset along the reading axis (`0` = top for vertical, right edge for RTL horizontal). */
    val offset: Int,
    /** Measured main-axis size in pixels. */
    val size: Int,
    /** Visual start coordinate in viewport space (`y` for vertical, `x` for horizontal RTL). */
    val screenStart: Int,
) {
    val screenEnd: Int
        get() = screenStart + size
}

@Immutable
data class WebtoonStripLayoutInfo(
    val visibleItemsInfo: List<WebtoonStripItem> = emptyList(),
    val viewportSize: IntSize = IntSize.Zero,
    val viewportMainSize: Int = 0,
    val totalItemsCount: Int = 0,
    val canScrollForward: Boolean = false,
    val canScrollBackward: Boolean = false,
)

internal data class WebtoonMeasureResult(
    val visibleItems: List<WebtoonStripItem>,
    /** Visible items plus keep-around cache neighbors that stay composed and measured. */
    val composedItems: List<WebtoonStripItem>,
    val anchor: WebtoonAnchor,
    val direction: WebtoonScrollDirection,
    val pinAnchorUntilStable: Boolean,
    val consumedDelta: Float,
    val canScrollForward: Boolean,
    val canScrollBackward: Boolean,
)

private data class MeasuredSlot(
    val index: Int,
    var start: Int,
    val size: Int,
) {
    val end: Int
        get() = start + size
}

/**
 * Pure 1D strip layout & anchoring algorithm used by [WebtoonStrip].
 *
 * Logical axis `u` runs from `0` (reading start) to `viewportMainSize` (reading end).
 * Positive [scrollDelta] moves forward (increasing page indices, decreasing `u`).
 */
internal fun measureWebtoonStrip(
    itemCount: Int,
    viewportMainSize: Int,
    gap: Int,
    horizontal: Boolean,
    cacheBufferPx: Int,
    maxCacheItems: Int,
    anchor: WebtoonAnchor,
    lastDirection: WebtoonScrollDirection,
    pinAnchorUntilStable: Boolean,
    scrollDelta: Float,
    isSizeStable: (Int) -> Boolean,
    measureItem: (Int) -> Int,
): WebtoonMeasureResult {
    if (itemCount <= 0 || viewportMainSize <= 0) {
        return WebtoonMeasureResult(
            visibleItems = emptyList(),
            composedItems = emptyList(),
            anchor = WebtoonAnchor(0, WebtoonAnchorEdge.Start, 0f),
            direction = lastDirection,
            pinAnchorUntilStable = false,
            consumedDelta = 0f,
            canScrollForward = false,
            canScrollBackward = false,
        )
    }

    val safeGap = gap.coerceAtLeast(0)
    val clampedAnchorIndex = anchor.index.coerceIn(0, itemCount - 1)
    val direction = when {
        scrollDelta > 0f -> WebtoonScrollDirection.Forward
        scrollDelta < 0f -> WebtoonScrollDirection.Backward
        else -> lastDirection
    }
    var keepPinned = pinAnchorUntilStable && scrollDelta == 0f && clampedAnchorIndex == anchor.index

    val sizeCache = HashMap<Int, Int>(8)
    fun sizeOf(index: Int): Int = sizeCache.getOrPut(index) { measureItem(index).coerceAtLeast(0) }

    val proposedOffset = anchor.offset - scrollDelta
    val anchorU = proposedOffset.roundToInt()
    val fractionalRemainder = proposedOffset - anchorU

    val anchorSize = sizeOf(clampedAnchorIndex)
    val anchorStart = when (anchor.edge) {
        WebtoonAnchorEdge.Start -> anchorU
        WebtoonAnchorEdge.End -> anchorU - anchorSize
    }

    val slots = ArrayDeque<MeasuredSlot>()
    slots.addLast(MeasuredSlot(clampedAnchorIndex, anchorStart, anchorSize))

    fun fillBackward() {
        while (slots.first().start > 0 && slots.first().index > 0) {
            val idx = slots.first().index - 1
            val size = sizeOf(idx)
            val end = slots.first().start - safeGap
            slots.addFirst(MeasuredSlot(idx, end - size, size))
        }
    }

    fun fillForward() {
        while (slots.last().end < viewportMainSize && slots.last().index < itemCount - 1) {
            val idx = slots.last().index + 1
            val size = sizeOf(idx)
            val start = slots.last().end + safeGap
            slots.addLast(MeasuredSlot(idx, start, size))
        }
    }

    fun shiftAll(delta: Int) {
        if (delta == 0) return
        for (slot in slots) {
            slot.start += delta
        }
    }

    fillBackward()
    fillForward()

    var totalShift = 0
    // Start boundary: no empty space before item 0.
    if (slots.first().index == 0 && slots.first().start > 0) {
        val shift = -slots.first().start
        shiftAll(shift)
        totalShift += shift
        fillForward()
    }
    // End boundary: no empty space after the last item unless the entire strip fits in viewport.
    if (slots.last().index == itemCount - 1 && slots.last().end < viewportMainSize) {
        val shiftDown = viewportMainSize - slots.last().end
        shiftAll(shiftDown)
        totalShift += shiftDown
        fillBackward()
        if (slots.first().index == 0 && slots.first().start > 0) {
            val shiftBack = -slots.first().start
            shiftAll(shiftBack)
            totalShift += shiftBack
        }
    }

    val consumedDelta = when {
        scrollDelta > 0f -> (scrollDelta - totalShift.toFloat()).coerceIn(0f, scrollDelta)
        scrollDelta < 0f -> (scrollDelta - totalShift.toFloat()).coerceIn(scrollDelta, 0f)
        else -> 0f
    }

    // Extract items intersecting [0, viewportMainSize).
    val visibleSlots = ArrayList<MeasuredSlot>(slots.size)
    for (slot in slots) {
        if (slot.end > 0 && slot.start < viewportMainSize) {
            visibleSlots.add(slot)
        }
    }
    if (visibleSlots.isEmpty()) {
        val fallback = slots.minByOrNull { slot ->
            when {
                slot.end <= 0 -> -slot.end
                slot.start >= viewportMainSize -> slot.start - viewportMainSize
                else -> 0
            }
        }
        if (fallback != null) visibleSlots.add(fallback)
    }

    // Build composed list = visible + keep-around cache neighbors (kept composed & measured, not placed).
    val composedSlots = ArrayDeque<MeasuredSlot>(visibleSlots.size + maxCacheItems * 2)
    visibleSlots.forEach { composedSlots.addLast(it) }
    if (composedSlots.isNotEmpty() && cacheBufferPx > 0 && maxCacheItems > 0) {
        var addedBefore = 0
        while (
            addedBefore < maxCacheItems &&
            composedSlots.first().index > 0 &&
            composedSlots.first().start > -cacheBufferPx
        ) {
            val idx = composedSlots.first().index - 1
            val size = sizeOf(idx)
            val end = composedSlots.first().start - safeGap
            composedSlots.addFirst(MeasuredSlot(idx, end - size, size))
            addedBefore++
        }
        var addedAfter = 0
        while (
            addedAfter < maxCacheItems &&
            composedSlots.last().index < itemCount - 1 &&
            composedSlots.last().end < viewportMainSize + cacheBufferPx
        ) {
            val idx = composedSlots.last().index + 1
            val size = sizeOf(idx)
            val start = composedSlots.last().end + safeGap
            composedSlots.addLast(MeasuredSlot(idx, start, size))
            addedAfter++
        }
    }

    fun MeasuredSlot.toStripItem(): WebtoonStripItem {
        val screenStart = if (horizontal) viewportMainSize - end else start
        return WebtoonStripItem(
            index = index,
            offset = start,
            size = size,
            screenStart = screenStart,
        )
    }

    val visibleItems = visibleSlots.map { it.toStripItem() }
    val composedItems = composedSlots.map { it.toStripItem() }

    val firstVisible = visibleSlots.first()
    val lastVisible = visibleSlots.last()
    val canScrollBackward = firstVisible.index > 0 || firstVisible.start < 0
    val canScrollForward = lastVisible.index < itemCount - 1 || lastVisible.end > viewportMainSize

    // Choose the next anchor. Preserve sub-pixel remainder only when not boundary-clamped.
    val carry = if (totalShift == 0) fractionalRemainder else 0f
    val pinnedSlot = if (keepPinned) visibleSlots.firstOrNull { it.index == clampedAnchorIndex } else null
    val nextAnchor = if (pinnedSlot != null) {
        if (isSizeStable(pinnedSlot.index)) keepPinned = false
        WebtoonAnchor(pinnedSlot.index, WebtoonAnchorEdge.Start, pinnedSlot.start.toFloat() + carry)
    } else {
        keepPinned = false
        when (direction) {
            WebtoonScrollDirection.Forward -> {
                val chosen = visibleSlots.firstOrNull { isSizeStable(it.index) } ?: firstVisible
                WebtoonAnchor(chosen.index, WebtoonAnchorEdge.Start, chosen.start.toFloat() + carry)
            }
            WebtoonScrollDirection.Backward -> {
                val chosen = visibleSlots.lastOrNull { isSizeStable(it.index) } ?: lastVisible
                WebtoonAnchor(chosen.index, WebtoonAnchorEdge.End, chosen.end.toFloat() + carry)
            }
        }
    }

    return WebtoonMeasureResult(
        visibleItems = visibleItems,
        composedItems = composedItems,
        anchor = nextAnchor,
        direction = direction,
        pinAnchorUntilStable = keepPinned,
        consumedDelta = consumedDelta,
        canScrollForward = canScrollForward,
        canScrollBackward = canScrollBackward,
    )
}

@Stable
class WebtoonStripState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
) : ScrollableState {
    internal var anchor: WebtoonAnchor = WebtoonAnchor(
        index = initialFirstVisibleItemIndex.coerceAtLeast(0),
        edge = WebtoonAnchorEdge.Start,
        offset = -initialFirstVisibleItemScrollOffset.coerceAtLeast(0).toFloat(),
    )
        private set

    internal var lastDirection: WebtoonScrollDirection = WebtoonScrollDirection.Forward
        private set

    internal var pinAnchorUntilStable: Boolean = true
        private set

    internal var pendingScrollDelta: Float = 0f
        private set

    internal var lastConsumedScrollDelta: Float = 0f
        private set

    internal var snapVersion by mutableIntStateOf(0)
        private set

    private var remeasurement: Remeasurement? = null

    internal val remeasurementModifier: RemeasurementModifier = object : RemeasurementModifier {
        override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
            this@WebtoonStripState.remeasurement = remeasurement
        }
    }

    private val layoutInfoState = mutableStateOf(WebtoonStripLayoutInfo())

    val layoutInfo: WebtoonStripLayoutInfo
        get() = layoutInfoState.value

    val interactionSource: MutableInteractionSource = MutableInteractionSource()

    val firstVisibleItemIndex: Int
        get() = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: anchor.index

    val firstVisibleItemScrollOffset: Int
        get() = layoutInfo.visibleItemsInfo.firstOrNull()?.let { (-it.offset).coerceAtLeast(0) } ?: 0

    internal val accessibilityScrollPosition: Float
        get() {
            val item = layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
            val fraction = if (item.size > 0) (-item.offset.toFloat() / item.size).coerceIn(0f, 1f) else 0f
            return item.index + fraction
        }

    override val canScrollForward: Boolean
        get() = layoutInfo.canScrollForward

    override val canScrollBackward: Boolean
        get() = layoutInfo.canScrollBackward

    private val scrollableState = ScrollableState { delta ->
        onScroll(delta)
    }

    override val isScrollInProgress: Boolean
        get() = scrollableState.isScrollInProgress

    override suspend fun scroll(
        scrollPriority: androidx.compose.foundation.MutatePriority,
        block: suspend androidx.compose.foundation.gestures.ScrollScope.() -> Unit,
    ) {
        scrollableState.scroll(scrollPriority, block)
    }

    override fun dispatchRawDelta(delta: Float): Float = scrollableState.dispatchRawDelta(delta)

    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) {
        scroll {
            snapToItemIndex(index, scrollOffset)
        }
    }

    internal fun snapToItemIndex(index: Int, scrollOffset: Int = 0) {
        anchor = WebtoonAnchor(
            index = index.coerceAtLeast(0),
            edge = WebtoonAnchorEdge.Start,
            offset = -scrollOffset.coerceAtLeast(0).toFloat(),
        )
        lastDirection = WebtoonScrollDirection.Forward
        pinAnchorUntilStable = true
        pendingScrollDelta = 0f
        lastConsumedScrollDelta = 0f
        snapVersion++
        remeasurement?.forceRemeasure()
    }

    private fun onScroll(delta: Float): Float {
        if (delta == 0f) return 0f
        val info = layoutInfoState.value
        if (info.viewportMainSize <= 0 || info.totalItemsCount <= 0) return 0f
        if ((delta > 0f && !info.canScrollForward) || (delta < 0f && !info.canScrollBackward)) {
            return 0f
        }
        val rem = remeasurement ?: return 0f
        pendingScrollDelta += delta
        lastConsumedScrollDelta = 0f
        rem.forceRemeasure()
        pendingScrollDelta = 0f
        return lastConsumedScrollDelta
    }

    internal fun applyMeasureResult(
        result: WebtoonMeasureResult,
        viewportSize: IntSize,
        viewportMainSize: Int,
        totalItemsCount: Int,
        isLookingAhead: Boolean,
    ) {
        // A speculative viewport can clamp the anchor differently. Keep the same input
        // for the actual measure, which alone owns the scroll delta and reading position.
        if (isLookingAhead) return
        pendingScrollDelta = 0f
        anchor = result.anchor
        lastDirection = result.direction
        pinAnchorUntilStable = result.pinAnchorUntilStable
        lastConsumedScrollDelta += result.consumedDelta
        layoutInfoState.value = WebtoonStripLayoutInfo(
            visibleItemsInfo = result.visibleItems,
            viewportSize = viewportSize,
            viewportMainSize = viewportMainSize,
            totalItemsCount = totalItemsCount,
            canScrollForward = result.canScrollForward,
            canScrollBackward = result.canScrollBackward,
        )
    }

    companion object {
        val Saver: Saver<WebtoonStripState, *> = listSaver(
            save = { listOf(it.firstVisibleItemIndex, it.firstVisibleItemScrollOffset) },
            restore = {
                WebtoonStripState(
                    initialFirstVisibleItemIndex = it[0],
                    initialFirstVisibleItemScrollOffset = it[1],
                )
            },
        )
    }
}

@Composable
fun rememberWebtoonStripState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): WebtoonStripState = rememberSaveable(saver = WebtoonStripState.Saver) {
    WebtoonStripState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
    )
}

/**
 * Bidirectional-anchored virtualized strip for webtoon / continuous reading.
 *
 * Vertical mode (`horizontal = false`) lays pages top-to-bottom.
 * Horizontal mode (`horizontal = true`) lays pages physically right-to-left (manga strip:
 * page 0 on the right, higher indices toward the left).
 */
@Composable
fun WebtoonStrip(
    state: WebtoonStripState,
    itemCount: Int,
    horizontal: Boolean,
    userScrollEnabled: Boolean,
    sidePadding: Dp = 0.dp,
    gap: Dp = 0.dp,
    cacheFraction: Float = SCROLL_FRACTION,
    maxCacheItems: Int = if (horizontal) 1 else 2,
    key: (index: Int) -> Any = { it },
    isSizeStable: (index: Int) -> Boolean,
    modifier: Modifier = Modifier,
    itemContent: @Composable (index: Int) -> Unit,
) {
    val orientation = if (horizontal) Orientation.Horizontal else Orientation.Vertical
    // Vertical: dragging finger UP (dy < 0) scrolls forward (delta > 0) -> reverseDirection = true.
    // Horizontal physical RTL: dragging finger RIGHT (dx > 0) pulls left pages in (delta > 0) -> reverseDirection = false.
    val reverseDirection = !horizontal
    val overscrollEffect = rememberOverscrollEffect()
    val scope = rememberCoroutineScope()
    val currentItemContent = rememberUpdatedState(itemContent)
    // Subcompose compares content identity even when the slot key is unchanged.
    val slotContent = remember { HashMap<Int, @Composable () -> Unit>() }

    SubcomposeLayout(
        modifier = modifier
            .clipToBounds()
            .semantics {
                isTraversalGroup = true
                collectionInfo = if (horizontal) CollectionInfo(1, itemCount) else CollectionInfo(itemCount, 1)
                indexForKey { itemKey -> (0 until itemCount).firstOrNull { key(it) == itemKey } ?: -1 }
                getScrollViewportLength { state.layoutInfo.viewportMainSize.toFloat() }
                val range = ScrollAxisRange(
                    value = { state.accessibilityScrollPosition },
                    maxValue = {
                        if (state.canScrollForward) itemCount.toFloat() else state.accessibilityScrollPosition
                    },
                    reverseScrolling = horizontal,
                )
                if (horizontal) horizontalScrollAxisRange = range else verticalScrollAxisRange = range
                if (userScrollEnabled) {
                    scrollToIndex { index ->
                        if (index !in 0 until itemCount) {
                            false
                        } else {
                            scope.launch { state.scrollToItem(index) }
                            true
                        }
                    }
                }
            }
            .overscroll(overscrollEffect)
            .scrollable(
                state = state,
                orientation = orientation,
                overscrollEffect = overscrollEffect,
                enabled = userScrollEnabled,
                reverseDirection = reverseDirection,
                flingBehavior = ScrollableDefaults.flingBehavior(),
                interactionSource = state.interactionSource,
            )
            .then(state.remeasurementModifier),
    ) { constraints ->
        // Observe programmatic snap requests even if forceRemeasure ran before attach.
        state.snapVersion

        val viewportWidth = constraints.maxWidth
        val viewportHeight = constraints.maxHeight
        val viewportMainSize = if (horizontal) viewportWidth else viewportHeight
        val sidePaddingPx = sidePadding.roundToPx().coerceAtLeast(0)
        val gapPx = gap.roundToPx().coerceAtLeast(0)
        val crossSize = (
            (if (horizontal) viewportHeight else viewportWidth) - sidePaddingPx * 2
            ).coerceAtLeast(1)
        val childConstraints = if (horizontal) {
            Constraints(
                minWidth = 0,
                maxWidth = Constraints.Infinity,
                minHeight = crossSize,
                maxHeight = crossSize,
            )
        } else {
            Constraints(
                minWidth = crossSize,
                maxWidth = crossSize,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
        }
        val cacheBufferPx = (viewportMainSize * cacheFraction.coerceAtLeast(0f)).roundToInt()

        val measuredPlaceables = HashMap<Int, List<Placeable>>(8)
        val result = measureWebtoonStrip(
            itemCount = itemCount,
            viewportMainSize = viewportMainSize,
            gap = gapPx,
            horizontal = horizontal,
            cacheBufferPx = cacheBufferPx,
            maxCacheItems = maxCacheItems,
            anchor = state.anchor,
            lastDirection = state.lastDirection,
            pinAnchorUntilStable = state.pinAnchorUntilStable,
            scrollDelta = state.pendingScrollDelta,
            isSizeStable = isSizeStable,
            measureItem = { index ->
                val placeables = measuredPlaceables.getOrPut(index) {
                    val content = slotContent.getOrPut(index) { @Composable { currentItemContent.value(index) } }
                    subcompose(key(index), content)
                        .map { it.measure(childConstraints) }
                }
                placeables.maxOfOrNull { if (horizontal) it.width else it.height } ?: 0
            },
        )

        state.applyMeasureResult(
            result = result,
            viewportSize = IntSize(viewportWidth, viewportHeight),
            viewportMainSize = viewportMainSize,
            totalItemsCount = itemCount,
            isLookingAhead = isLookingAhead,
        )
        if (!isLookingAhead) slotContent.keys.retainAll(measuredPlaceables.keys)

        layout(viewportWidth, viewportHeight) {
            // Only place items intersecting the viewport so offscreen cache neighbors stay
            // composed and measured without entering the draw pass (matches LazyLayoutCacheWindow).
            for (item in result.visibleItems) {
                val placeables = measuredPlaceables[item.index] ?: continue
                val x = if (horizontal) item.screenStart else sidePaddingPx
                val y = if (horizontal) sidePaddingPx else item.screenStart
                for (placeable in placeables) {
                    placeable.place(x = x, y = y)
                }
            }
        }
    }
}

/** Reading page index for [WebtoonStripLayoutInfo] (drives seekbar and progress). */
internal fun WebtoonStripLayoutInfo.webtoonReadingIndex(horizontal: Boolean): Int? {
    val items = visibleItemsInfo
    if (items.isEmpty()) return null
    if (!horizontal) {
        return items.lastOrNull {
            it.offset <= maxOf(0, viewportMainSize - it.size)
        }?.index ?: items.first().index
    }
    val viewCenter = viewportMainSize / 2
    return items.minByOrNull { item ->
        abs(item.offset + item.size / 2 - viewCenter)
    }?.index
}
