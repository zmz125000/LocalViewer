package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

/**
 * Landscape dual-page helpers.
 *
 * User-facing progress (slider, startPage, indicator) always uses **real page indices**.
 * HorizontalPager / VerticalPager use **spread** indices when [isPagerDual] is true.
 *
 * - LTR / Vertical: left = 2i, right = 2i+1; next slot = next two pages
 * - RTL: left = 2i+1, right = 2i (manga book order); reverseLayout handles direction
 * - Webtoon / continuous: not paired — landscape uses a **right-to-left** horizontal strip
 */

fun dualPageActive(dualPref: Boolean, isLandscape: Boolean): Boolean = dualPref && isLandscape

/**
 * Two-up spreads for paged readers: LTR, RTL, and Vertical.
 * Webtoon modes use [isWebtoonHorizontal] instead.
 */
fun isPagerDual(dualActive: Boolean, type: ReadingModeType): Boolean = dualActive && !ReadingModeType.isWebtoon(type)

/**
 * Webtoon / continuous landscape: continuous horizontal strip (no pairing),
 * laid out right-to-left (page 0 on the right).
 */
fun isWebtoonHorizontal(dualActive: Boolean, type: ReadingModeType): Boolean = dualActive && ReadingModeType.isWebtoon(type)

/**
 * @param cover first page is a persisted landscape cover: slot 0 is that page alone,
 *   and pairing of the rest starts at page 1.
 */
fun dualSpreadCount(pageCount: Int, cover: Boolean = false): Int {
    if (pageCount <= 0) return 0
    if (!cover) return (pageCount + 1) / 2
    val rest = pageCount - 1
    return 1 + if (rest <= 0) 0 else (rest + 1) / 2
}

fun dualSpreadIndex(pageIndex: Int, cover: Boolean = false): Int {
    if (pageIndex <= 0) return 0
    if (!cover) return pageIndex / 2
    return 1 + (pageIndex - 1) / 2
}

/** First real page index of a spread (always the lower index of the pair). */
fun dualFirstPageIndex(spreadIndex: Int, cover: Boolean = false): Int {
    if (spreadIndex <= 0) return 0
    if (!cover) return spreadIndex * 2
    return 1 + (spreadIndex - 1) * 2
}

/** Last real page in [spreadIndex], inclusive. Cover slot 0 is page 0 only. */
fun dualLastPageIndex(spreadIndex: Int, pageCount: Int, cover: Boolean = false): Int {
    if (pageCount <= 0) return 0
    val first = dualFirstPageIndex(spreadIndex, cover).coerceIn(0, pageCount - 1)
    if (cover && spreadIndex <= 0) return first
    val second = first + 1
    return if (second < pageCount) second else first
}

/**
 * Left/right real page indices for a spread.
 * LTR / Vertical: left = 2i, right = 2i+1
 * RTL: left = 2i+1, right = 2i (manga book order)
 * [cover]: spread 0 is page 0 alone.
 */
fun dualLeftRight(
    spreadIndex: Int,
    pageCount: Int,
    isRtl: Boolean,
    cover: Boolean = false,
): Pair<Int?, Int?> {
    if (cover && spreadIndex <= 0) {
        val page = 0.takeIf { pageCount > 0 }
        return if (isRtl) null to page else page to null
    }
    val a = if (cover) dualFirstPageIndex(spreadIndex, cover = true) else spreadIndex * 2
    val b = a + 1
    val first = a.takeIf { it < pageCount }
    val second = b.takeIf { it < pageCount }
    return if (isRtl) second to first else first to second
}

/**
 * Size of a no-gap dual spread with [combinedAspect] (width/height) fitted into [viewport].
 * Same as ContentScale.Fit of that aspect: never exceeds pager constraints.
 */
fun fitSpreadSize(combinedAspect: Float, viewport: Size): Size {
    if (viewport.width <= 0f || viewport.height <= 0f) return Size.Zero
    val aspect = combinedAspect.coerceAtLeast(MIN_SPREAD_ASPECT)
    val widthIfFullHeight = viewport.height * aspect
    return if (widthIfFullHeight <= viewport.width) {
        Size(widthIfFullHeight, viewport.height)
    } else {
        Size(viewport.width, viewport.width / aspect)
    }
}

/**
 * Pixel size of a no-gap spread: pages share the taller decoded height, widths from aspect.
 * Telephoto uses this as contentLocation. Visual layout uses [insideSpreadSize] so small
 * pages stay glued; a viewport-fitted row letterboxed them to the left and right.
 * [left]/[right] are decoded sizes; a side with aspect 0 is absent.
 * [Size.Zero] if neither page has a decoded height yet.
 */
fun unscaledSpreadSize(
    left: Size?,
    right: Size?,
    leftAspect: Float,
    rightAspect: Float,
): Size {
    val h = maxOf(left?.height ?: 0f, right?.height ?: 0f)
    if (h <= 0f) return Size.Zero
    val lw = if (leftAspect > 0f) h * leftAspect else 0f
    val rw = if (rightAspect > 0f) h * rightAspect else 0f
    return Size((lw + rw).coerceAtLeast(1f), h)
}

/**
 * Visual size of a no-gap pair: [unscaled] if it fits, otherwise the same as [fitSpreadSize].
 * Never exceeds [viewport], so the row is not clipped before telephoto scales.
 */
fun insideSpreadSize(unscaled: Size, viewport: Size): Size {
    if (unscaled.width <= 0f || unscaled.height <= 0f) return Size.Zero
    if (viewport.width <= 0f || viewport.height <= 0f) return unscaled
    val scale = minOf(1f, viewport.width / unscaled.width, viewport.height / unscaled.height)
    return Size(unscaled.width * scale, unscaled.height * scale)
}

/** Screen-X of the gutter between left and right pages for a centered no-gap spread. */
fun spreadGutterX(leftAspect: Float, rightAspect: Float, viewport: Size): Float {
    val left = leftAspect.coerceAtLeast(MIN_SPREAD_ASPECT)
    val right = rightAspect.coerceAtLeast(MIN_SPREAD_ASPECT)
    val combined = left + right
    val fitted = fitSpreadSize(combined, viewport)
    val originX = (viewport.width - fitted.width) / 2f
    return originX + fitted.width * (left / combined)
}

private const val MIN_SPREAD_ASPECT = 0.01f
