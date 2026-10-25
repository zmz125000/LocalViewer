package com.hippo.ehviewer.gallery

/** Loader-owned slide order. Each entry is a real page or a complete dual-page spread. */
internal class SlideshowShuffle(
    private val pagesPerSlide: Int = 1,
    private val firstPageAlone: Boolean = false,
) {
    private val remaining = mutableListOf<IntRange>()
    private var pageCount = -1
    private val visited = mutableSetOf<Int>()

    fun next(current: Int, count: Int, repeat: Boolean = false): Int? {
        observe(current, count)
        if (remaining.isEmpty() && repeat) reset(current, count)
        return remaining.firstOrNull()?.first
    }

    private fun slides(count: Int): List<IntRange> = buildList {
        var first = 0
        while (first < count) {
            val end = if (first == 0 && firstPageAlone) 0 else minOf(first + pagesPerSlide - 1, count - 1)
            add(first..end)
            first = end + 1
        }
    }

    fun reset(current: Int, count: Int) {
        pageCount = count
        visited.clear()
        val slides = slides(count)
        slides.firstOrNull { current in it }?.let { visited += it.first }
        remaining.clear()
        remaining.addAll(slides.filterNot { it.first in visited }.shuffled())
    }

    private fun updateCount(count: Int) {
        if (count == pageCount) return
        if (pageCount < 0 || count < pageCount) {
            pageCount = count
            visited.clear()
            remaining.clear()
            remaining.addAll(slides(count).shuffled())
            return
        }
        // Progressive archives reveal pages during reading. Preserve the already
        // prefetched next slide and visited history; only append newly discovered slides.
        val slides = slides(count).associateBy { it.first }
        // If a previously solo last page gains a mate, its expanded spread contains
        // a new image and must be offered again unless it is currently on screen.
        slides(pageCount).lastOrNull()?.let { previous ->
            if ((slides[previous.first]?.last ?: previous.last) > previous.last) visited.remove(previous.first)
        }
        val pending = remaining.map { it.first }.toSet()
        val updated = remaining.mapNotNull { slides[it.first] }
        val added = slides.values.filterNot { it.first in visited || it.first in pending }.shuffled()
        remaining.clear()
        remaining.addAll(updated)
        remaining.addAll(added)
        pageCount = count
    }

    private fun observe(current: Int, count: Int) {
        updateCount(count)
        remaining.firstOrNull { current in it }?.let { visited += it.first }
        remaining.removeAll { current in it }
    }

    fun plan(demand: ReaderDemand, count: Int, policy: ReaderLoadPolicy): ReaderDemand {
        // Animated jumps can briefly cross other slides; only the destination is visited.
        updateCount(count)
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
