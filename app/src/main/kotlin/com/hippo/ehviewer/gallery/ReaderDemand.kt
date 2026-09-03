package com.hippo.ehviewer.gallery

import com.ehviewer.core.model.GalleryInfo
import okio.Path

/** Small reader-facing seam; source/decode callbacks remain private to its implementation. */
interface ReaderSession : AutoCloseable {
    val info: GalleryInfo?
    val title: String
    val size: Int
    val pages: List<Page>
    var startPage: Int

    fun navigate(navigation: ReaderNavigation)
    fun replan()
    fun restart()
    fun onForeground()

    /** Flush [startPage] to DB. Safe to call often; no-op without [info]. */
    fun persistProgress()
    fun retryPage(index: Int, orgImg: Boolean = false)
    fun getImageFilename(index: Int): String?
    fun save(index: Int, file: Path): Boolean
}

/** Why the reader's viewport changed. All indices are real image indices. */
enum class NavigationKind {
    Scroll,
    Settled,
    Jump,
}

/**
 * Complete loading input from the reader UI.
 *
 * [visiblePages] contains only pages intersecting the viewport. Compose cache / pager
 * beyond-viewport items must not be included, otherwise composition controls decode-ahead.
 */
data class ReaderNavigation(
    val anchor: Int,
    val visiblePages: IntRange,
    val kind: NavigationKind,
)

enum class ReadingDirection {
    Forward,
    Backward,
}

data class ReaderLoadPolicy(
    val sourceAhead: Int,
    val decodeAhead: Int,
) {
    init {
        require(sourceAhead >= 0)
        require(decodeAhead >= 0)
    }
}

/** A priority-ordered, non-overlapping loading plan. */
data class ReaderDemand(
    val navigation: ReaderNavigation,
    val direction: ReadingDirection,
    /** Live source lookahead used for this plan, including progressive catalog discovery. */
    val sourceAhead: Int,
    val visibleDecode: List<Int>,
    val decodeAhead: List<Int>,
    val sourceOnly: List<Int>,
) {
    val decodedPages: Set<Int> = (visibleDecode + decodeAhead).toSet()
    val sourcePages: Set<Int> = (visibleDecode + decodeAhead + sourceOnly).toSet()
}

/**
 * High-water target for sources whose catalog grows while reading (solid archives, TAR, PDF).
 *
 * [sourcePages] is intentionally clamped to the currently published catalog and is therefore
 * suitable only for acquisition. Discovery must cross that boundary or the catalog can never
 * reveal its next page. It always advances from the furthest visible page, independent of the
 * current reading direction; progressive catalogs can only discover toward increasing indices.
 */
@PublishedApi
internal fun ReaderDemand.progressiveDiscoveryTarget(lookahead: Int): Int {
    val visibleEnd = maxOf(navigation.anchor, navigation.visiblePages.last)
    return visibleEnd + lookahead.coerceAtLeast(1)
}

/**
 * Stateful direction tracker and pure window calculator.
 *
 * Direction is semantic page order: increasing indices are forward regardless of RTL or
 * scroll axis. A stationary update retains the last real movement direction.
 */
class ReaderDemandPlanner(
    initialDirection: ReadingDirection = ReadingDirection.Forward,
) {
    private var previousAnchor: Int? = null
    private var direction = initialDirection

    fun plan(
        navigation: ReaderNavigation,
        pageCount: Int,
        policy: ReaderLoadPolicy,
    ): ReaderDemand {
        if (pageCount <= 0) {
            return ReaderDemand(
                navigation = navigation,
                direction = direction,
                sourceAhead = policy.sourceAhead,
                visibleDecode = emptyList(),
                decodeAhead = emptyList(),
                sourceOnly = emptyList(),
            )
        }

        val lastPage = pageCount - 1
        val anchor = navigation.anchor.coerceIn(0, lastPage)
        previousAnchor?.let { previous ->
            direction = when {
                anchor > previous -> ReadingDirection.Forward
                anchor < previous -> ReadingDirection.Backward
                else -> direction
            }
        }
        previousAnchor = anchor

        val visibleStart = navigation.visiblePages.first.coerceIn(0, lastPage)
        val visibleEnd = navigation.visiblePages.last.coerceIn(visibleStart, lastPage)
        val visible = (visibleStart..visibleEnd).toList()
            .sortedBy { kotlin.math.abs(it - anchor) }

        fun ahead(count: Int): List<Int> {
            if (count <= 0) return emptyList()
            return when (direction) {
                ReadingDirection.Forward ->
                    ((visibleEnd + 1)..minOf(lastPage, visibleEnd + count)).toList()
                ReadingDirection.Backward ->
                    (visibleStart - 1 downTo maxOf(0, visibleStart - count)).toList()
            }
        }

        val decode = ahead(policy.decodeAhead)
        val decoded = (visible + decode).toHashSet()
        val sourceOnly = ahead(policy.sourceAhead).filterNot(decoded::contains)
        return ReaderDemand(
            navigation = navigation.copy(anchor = anchor, visiblePages = visibleStart..visibleEnd),
            direction = direction,
            sourceAhead = policy.sourceAhead,
            visibleDecode = visible,
            decodeAhead = decode,
            sourceOnly = sourceOnly,
        )
    }
}
