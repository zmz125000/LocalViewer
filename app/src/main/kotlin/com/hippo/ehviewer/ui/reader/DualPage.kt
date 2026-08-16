package com.hippo.ehviewer.ui.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType

/**
 * Landscape dual-page helpers.
 *
 * User-facing progress (slider, startPage, indicator) always uses **real page indices**.
 * HorizontalPager only uses spread indices when [isPagerDual] is true.
 */

fun dualPageActive(dualPref: Boolean, isLandscape: Boolean): Boolean = dualPref && isLandscape

/** LTR/RTL two-up spreads only (not Vertical, not webtoon). */
fun isPagerDual(dualActive: Boolean, type: ReadingModeType): Boolean =
    dualActive &&
        (type == ReadingModeType.LEFT_TO_RIGHT || type == ReadingModeType.RIGHT_TO_LEFT)

/** Webtoon / continuous horizontal strip (no pairing). */
fun isWebtoonHorizontal(dualActive: Boolean, type: ReadingModeType): Boolean =
    dualActive && ReadingModeType.isWebtoon(type)

fun dualSpreadCount(pageCount: Int): Int = if (pageCount <= 0) 0 else (pageCount + 1) / 2

fun dualSpreadIndex(pageIndex: Int): Int = (pageIndex / 2).coerceAtLeast(0)

/** First real page index of a spread (always the lower index of the pair). */
fun dualFirstPageIndex(spreadIndex: Int): Int = (spreadIndex * 2).coerceAtLeast(0)

/**
 * Left/right real page indices for a spread.
 * LTR: left = 2i, right = 2i+1
 * RTL: left = 2i+1, right = 2i (manga book order)
 */
fun dualLeftRight(spreadIndex: Int, pageCount: Int, isRtl: Boolean): Pair<Int?, Int?> {
    val a = spreadIndex * 2
    val b = a + 1
    val first = a.takeIf { it < pageCount }
    val second = b.takeIf { it < pageCount }
    return if (isRtl) second to first else first to second
}
