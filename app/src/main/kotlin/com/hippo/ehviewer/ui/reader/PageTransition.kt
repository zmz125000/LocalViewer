package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.gallery.status
import eu.kanade.tachiyomi.ui.reader.setting.PageTransitionType
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Shared by taps, volume keys, mouse wheel, seek bar, and slideshow page advances. */
internal object PagerTransitionHandlers {
    private val handlers = mutableMapOf<PagerState, suspend (Int) -> Unit>()
    private val active = mutableStateMapOf<PagerState, Boolean>()
    private val targets = mutableMapOf<PagerState, Int>()

    // currentPage changes halfway through a slide, and only after fade-out for fades.
    // Relative input must follow the requested destination throughout either animation.
    fun navigationPage(pager: PagerState): Int = targets[pager] ?: pager.currentPage
    fun setTarget(pager: PagerState, target: Int) {
        targets[pager] = target
    }
    fun isRunning(pager: PagerState): Boolean = active[pager] == true
    fun setRunning(pager: PagerState, running: Boolean) {
        if (running) {
            active[pager] = true
        } else {
            active.remove(pager)
            targets.remove(pager)
        }
    }
    operator fun get(pager: PagerState) = handlers[pager]
    operator fun set(pager: PagerState, handler: suspend (Int) -> Unit) {
        handlers[pager] = handler
    }
    fun remove(pager: PagerState) {
        handlers.remove(pager)
        active.remove(pager)
        targets.remove(pager)
    }
}

/** Errors and blocked images take the normal error/blocked path, rather than waiting forever. */
internal suspend fun awaitTransitionImages(pages: List<Page>): Boolean {
    if (pages.isEmpty()) return false
    return combine(pages.map { it.statusFlow }) { statuses ->
        when {
            statuses.any { it is PageStatus.Error || it is PageStatus.Blocked } -> false
            statuses.all { it is PageStatus.Ready } -> true
            else -> null
        }
    }.filterNotNull().first()
}

private suspend fun awaitTransitionDisplay(pages: List<Page>): Boolean = combine(
    pages.map { page ->
        combine(page.statusFlow, page.displayReadyFlow) { status, displayed ->
            when (status) {
                is PageStatus.Error, is PageStatus.Blocked -> false
                is PageStatus.Ready -> true.takeIf { displayed }
                else -> null
            }
        }
    },
) { states ->
    when {
        states.any { it == false } -> false
        states.all { it == true } -> true
        else -> null
    }
}.filterNotNull().first()

/** Fast initial motion with zero speed at the final frame. */
internal val PageTransitionEaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Preserve the system animator scale, including disabled animations, while slowing slideshow turns. */
internal class SlideshowMotionDurationScale(private val parent: MotionDurationScale?) : MotionDurationScale {
    override val scaleFactor: Float get() = (parent?.scaleFactor ?: 1f) * 3f
}

private suspend fun <T> withReaderTransitionDuration(slideshow: Boolean, block: suspend () -> T): T {
    val parentScale = currentCoroutineContext()[MotionDurationScale]
    val scale = if (slideshow) SlideshowMotionDurationScale(parentScale) else EmptyCoroutineContext
    return withContext(scale) { block() }
}

internal data class PagerTransitionState(
    val type: Int,
    val modifier: Modifier,
    val outgoingPage: Int?,
    val outgoingModifier: Modifier,
)

@Composable
internal fun pagerTransitionModifier(
    pager: PagerState,
    loader: ReaderSession,
    dualPage: Boolean,
    landscapeCover: Boolean,
): PagerTransitionState {
    val readingMode by Settings.readingMode.collectAsState()
    val general by Settings.pageTransition.collectAsState()
    val override by Settings.slideshowPageTransition.collectAsState()
    val slideshow = readingMode == ReadingModeType.SLIDESHOW.prefValue
    val transition = PageTransitionType.resolve(general, override, slideshow)
    val alpha = remember(pager, loader, transition, readingMode, dualPage, landscapeCover) { Animatable(1f) }
    val outgoingAlpha = remember(pager, loader, transition, readingMode, dualPage, landscapeCover) { Animatable(1f) }
    val outgoingPage = remember(pager, loader, transition, readingMode, dualPage, landscapeCover) { mutableStateOf<Int?>(null) }
    LaunchedEffect(loader, transition) { loader.replan() }
    DisposableEffect(pager, loader, transition, readingMode, dualPage, landscapeCover) {
        val mutex = MutatorMutex()
        var running: Job? = null
        var disposed = false
        fun realPages(slot: Int): List<Page> {
            val indices = if (dualPage) {
                dualFirstPageIndex(slot, landscapeCover)..dualLastPageIndex(slot, loader.size, landscapeCover)
            } else {
                slot..slot
            }
            return indices.mapNotNull { loader.pages.getOrNull(it) }
        }
        suspend fun awaitPaint(pages: List<Page>): Boolean {
            val displayed = awaitTransitionDisplay(pages)
            withFrameNanos { }
            withFrameNanos { }
            return displayed
        }
        PagerTransitionHandlers[pager] = { target ->
            withReaderTransitionDuration(slideshow) {
                mutex.mutate {
                    running = currentCoroutineContext()[Job]
                    PagerTransitionHandlers.setRunning(pager, true)
                    PagerTransitionHandlers.setTarget(pager, target)
                    try {
                        if (target in 0 until pager.pageCount && target != pager.currentPage) {
                            val destination = realPages(target)
                            val current = realPages(pager.currentPage)
                            // Actual viewport demand must remain independent of a requested seek.
                            loader.setTransitionPages((destination + current).map { it.index }.toSet())
                            val waitForReady = transition.needsPageReady(slideshow)
                            val ready = if (waitForReady) {
                                awaitTransitionImages(destination)
                            } else {
                                true
                            }
                            val currentReady = current.isNotEmpty() && current.all {
                                it.status is PageStatus.Ready && it.displayReadyFlow.value
                            }
                            when (transition) {
                                PageTransitionType.DEFAULT -> {
                                    if (ready) pager.animateScrollToPage(target) else pager.scrollToPage(target)
                                }
                                PageTransitionType.SLIDE -> {
                                    if (ready) {
                                        pager.animateScrollToPage(target, animationSpec = tween(450, easing = PageTransitionEaseOut))
                                    } else {
                                        pager.scrollToPage(target)
                                    }
                                }
                                PageTransitionType.FADE -> {
                                    if (ready) {
                                        if (currentReady) {
                                            alpha.animateTo(0f, tween(250, easing = PageTransitionEaseOut))
                                        } else {
                                            alpha.snapTo(0f)
                                        }
                                    }
                                    pager.scrollToPage(target)
                                    if (ready && awaitPaint(destination)) {
                                        alpha.animateTo(1f, tween(350, easing = PageTransitionEaseOut))
                                    }
                                }
                                PageTransitionType.FADE_BLEND -> {
                                    if (ready && currentReady) {
                                        outgoingAlpha.snapTo(1f)
                                        outgoingPage.value = pager.currentPage
                                        // Move the existing page content above the pager, keeping its
                                        // zoom, HDR painter and display pin while the new page appears.
                                        awaitPaint(current)
                                        pager.scrollToPage(target)
                                        if (awaitPaint(destination)) {
                                            // Source-over on the fully opaque destination gives exact
                                            // complementary image weights, with no dark midpoint.
                                            outgoingAlpha.animateTo(0f, tween(500, easing = PageTransitionEaseOut))
                                        }
                                    } else {
                                        if (ready) alpha.snapTo(0f)
                                        pager.scrollToPage(target)
                                        if (ready && awaitPaint(destination)) {
                                            alpha.animateTo(1f, tween(350, easing = PageTransitionEaseOut))
                                        }
                                    }
                                }
                                PageTransitionType.NONE -> pager.scrollToPage(target)
                            }
                            if (ready && waitForReady && !transition.isFade) awaitPaint(destination)
                        }
                    } finally {
                        if (!disposed) {
                            withContext(NonCancellable) {
                                outgoingPage.value = null
                                alpha.snapTo(1f)
                                loader.setTransitionPages(emptySet())
                                PagerTransitionHandlers.setRunning(pager, false)
                            }
                        }
                        running = null
                    }
                }
            }
        }
        onDispose {
            disposed = true
            PagerTransitionHandlers.remove(pager)
            running?.cancel()
            outgoingPage.value = null
            loader.setTransitionPages(emptySet())
        }
    }
    return PagerTransitionState(
        type = transition.prefValue,
        modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
        outgoingPage = outgoingPage.value,
        outgoingModifier = Modifier.graphicsLayer {
            this.alpha = outgoingAlpha.value
            clip = true
        },
    )
}
