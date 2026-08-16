package com.hippo.ehviewer.ui.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

/**
 * Landscape dual-page helpers.
 *
 * User-facing progress (slider, startPage, indicator) always uses **real page indices**.
 * HorizontalPager / VerticalPager use **spread** indices when [isPagerDual] is true.
 *
 * - LTR / Vertical: left = 2i, right = 2i+1; next slot = next two pages
 * - RTL: left = 2i+1, right = 2i (manga book order); reverseLayout handles direction
 * - Webtoon / continuous: not paired — landscape uses horizontal strip instead
 */

fun dualPageActive(dualPref: Boolean, isLandscape: Boolean): Boolean = dualPref && isLandscape

/**
 * Two-up spreads for paged readers: LTR, RTL, and Vertical.
 * Webtoon modes use [isWebtoonHorizontal] instead.
 */
fun isPagerDual(dualActive: Boolean, type: ReadingModeType): Boolean = dualActive && !ReadingModeType.isWebtoon(type)

/** Webtoon / continuous landscape: horizontal strip (no pairing). */
fun isWebtoonHorizontal(dualActive: Boolean, type: ReadingModeType): Boolean = dualActive && ReadingModeType.isWebtoon(type)

fun dualSpreadCount(pageCount: Int): Int = if (pageCount <= 0) 0 else (pageCount + 1) / 2

fun dualSpreadIndex(pageIndex: Int): Int = (pageIndex / 2).coerceAtLeast(0)

/** First real page index of a spread (always the lower index of the pair). */
fun dualFirstPageIndex(spreadIndex: Int): Int = (spreadIndex * 2).coerceAtLeast(0)

/**
 * Left/right real page indices for a spread.
 * LTR / Vertical: left = 2i, right = 2i+1
 * RTL: left = 2i+1, right = 2i (manga book order)
 */
fun dualLeftRight(spreadIndex: Int, pageCount: Int, isRtl: Boolean): Pair<Int?, Int?> {
    val a = spreadIndex * 2
    val b = a + 1
    val first = a.takeIf { it < pageCount }
    val second = b.takeIf { it < pageCount }
    return if (isRtl) second to first else first to second
}
