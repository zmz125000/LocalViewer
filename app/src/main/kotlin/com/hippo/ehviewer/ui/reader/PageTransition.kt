package com.hippo.ehviewer.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.gallery.status
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
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
    operator fun get(pager: PagerState) = handlers[pager]
    operator fun set(pager: PagerState, handler: suspend (Int) -> Unit) {
        handlers[pager] = handler
    }
    fun remove(pager: PagerState) {
        handlers.remove(pager)
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

internal data class PagerTransitionState(val type: Int, val modifier: Modifier)

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
    val transition = if (slideshow && override in 0..2) override else general
    val alpha = remember(pager, loader, transition) { Animatable(1f) }
    LaunchedEffect(loader, transition) { loader.replan() }
    DisposableEffect(pager, loader, transition, slideshow, dualPage, landscapeCover) {
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
        PagerTransitionHandlers[pager] = { target ->
            mutex.mutate {
                running = currentCoroutineContext()[Job]
                try {
                    if (target in 0 until pager.pageCount && target != pager.currentPage) {
                        val destination = realPages(target)
                        val current = realPages(pager.currentPage)
                        // Fade always waits, and slideshow waits for every transition type.
                        // Hold the current image while the destination is being decoded.
                        val waitForReady = transition == 1 || slideshow
                        val ready = if (waitForReady) {
                            val preparing = if (transition == 1) destination + current else destination
                            loader.setTransitionPages(preparing.map { it.index }.toSet())
                            awaitTransitionImages(destination)
                        } else {
                            true
                        }
                        when (transition) {
                            0 -> {
                                if (ready) pager.animateScrollToPage(target) else pager.scrollToPage(target)
                            }
                            1 -> {
                                val currentReady = current.all {
                                    it.status is PageStatus.Ready && it.displayReadyFlow.value
                                }
                                if (ready) {
                                    if (currentReady) alpha.animateTo(0f, tween(150)) else alpha.snapTo(0f)
                                }
                                pager.scrollToPage(target)
                                if (ready) {
                                    // Never fade in a placeholder, even if the outgoing page
                                    // was itself still loading when navigation was requested.
                                    val displayed = awaitTransitionDisplay(destination)
                                    withFrameNanos { }
                                    withFrameNanos { }
                                    if (displayed) alpha.animateTo(1f, tween(200))
                                }
                            }
                            else -> pager.scrollToPage(target)
                        }
                    }
                } finally {
                    if (!disposed) {
                        withContext(NonCancellable) {
                            alpha.snapTo(1f)
                            loader.setTransitionPages(emptySet())
                        }
                    }
                    running = null
                }
            }
        }
        onDispose {
            disposed = true
            PagerTransitionHandlers.remove(pager)
            running?.cancel()
            loader.setTransitionPages(emptySet())
        }
    }
    return PagerTransitionState(transition, Modifier.graphicsLayer { this.alpha = alpha.value })
}
