package com.hippo.ehviewer.gallery

/** Loader-owned slide order. Each entry is a real page or a complete dual-page spread. */
internal class SlideshowShuffle(
    private val pagesPerSlide: Int = 1,
    private val firstPageAlone: Boolean = false,
) {
    private val remaining = mutableListOf<IntRange>()
    private var pageCount = -1

    fun next(current: Int, count: Int, repeat: Boolean = false): Int? {
        observe(current, count)
        if (remaining.isEmpty() && repeat) reset(current, count)
        return remaining.firstOrNull()?.first
    }

    fun reset(current: Int, count: Int) {
        pageCount = count
        remaining.clear()
        var first = 0
        while (first < count) {
            val end = if (first == 0 && firstPageAlone) 0 else minOf(first + pagesPerSlide - 1, count - 1)
            val slide = first..end
            if (current !in slide) remaining += slide
            first = end + 1
        }
        remaining.shuffle()
    }

    private fun observe(current: Int, count: Int) {
        if (count != pageCount) reset(current, count)
        remaining.removeAll { current in it }
    }

    fun plan(demand: ReaderDemand, count: Int, policy: ReaderLoadPolicy): ReaderDemand {
        // Animated jumps can briefly cross other slides; only the destination is visited.
        if (count != pageCount) reset(demand.navigation.anchor, count)
        if (demand.navigation.kind != NavigationKind.Scroll) observe(demand.navigation.anchor, count)
        val visible = demand.visibleDecode.toSet()
        val upcoming = remaining.asSequence().flatMap { it.asSequence() }
            .filterNot(visible::contains)
            .take(maxOf(policy.decodeAhead, policy.sourceAhead)).toList()
        val decode = upcoming.take(policy.decodeAhead)
        val decoded = decode.toSet()
        val sourceOnly = upcoming.take(policy.sourceAhead).filterNot(decoded::contains)
        return demand.copy(
            direction = ReadingDirection.Forward,
            decodeAhead = decode,
            sourceOnly = sourceOnly,
        )
    }
}
