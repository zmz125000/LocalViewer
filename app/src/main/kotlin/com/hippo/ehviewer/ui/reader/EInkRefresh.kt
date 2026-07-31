package com.hippo.ehviewer.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import kotlinx.coroutines.delay

/**
 * Full-screen flash after paged (gallery) page turns to reduce E-Ink ghosting.
 * Only composed for paged modes — not webtoon / continuous vertical.
 *
 * Behavior mirrors Venera-Next: count page changes, flash every N turns with black /
 * white / white-then-black for a configured duration.
 */
@Composable
fun EInkRefreshOverlay(
    pagerState: PagerState,
    modifier: Modifier = Modifier,
) {
    val enabled by Settings.eInkRefreshEnabled.collectAsState()
    val intervalPref by Settings.eInkRefreshInterval.collectAsState()
    val durationPref by Settings.eInkRefreshDuration.collectAsState()
    val stylePref by Settings.eInkRefreshStyle.collectAsState()

    val enabledState by rememberUpdatedState(enabled)
    val intervalState by rememberUpdatedState(intervalPref)
    val durationState by rememberUpdatedState(durationPref)
    val styleState by rememberUpdatedState(stylePref)

    var flashColor by remember { mutableStateOf<Color?>(null) }
    var pageChangeCount by remember { mutableIntStateOf(0) }
    var activeInterval by remember { mutableIntStateOf(-1) }
    var flashGeneration by remember { mutableIntStateOf(0) }
    var previousPage by remember { mutableIntStateOf(pagerState.currentPage) }

    LaunchedEffect(enabled) {
        if (!enabled) {
            pageChangeCount = 0
            activeInterval = -1
            flashColor = null
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page == previousPage) return@collect
            previousPage = page
            if (!enabledState) return@collect

            val interval = intervalState.coerceIn(1, 10)
            if (activeInterval != interval) {
                activeInterval = interval
                pageChangeCount = 0
            }
            val shouldRefresh = pageChangeCount % interval == 0
            pageChangeCount++
            if (!shouldRefresh) return@collect

            val duration = durationState.coerceIn(100, 1500).toLong()
            val style = styleState
            flashGeneration++
            val gen = flashGeneration
            when (style) {
                STYLE_WHITE -> {
                    flashColor = Color.White
                    delay(duration)
                    if (gen == flashGeneration) flashColor = null
                }
                STYLE_WHITE_THEN_BLACK -> {
                    val first = duration / 2
                    flashColor = Color.White
                    delay(first)
                    if (gen != flashGeneration) return@collect
                    flashColor = Color.Black
                    delay(duration - first)
                    if (gen == flashGeneration) flashColor = null
                }
                else -> {
                    flashColor = Color.Black
                    delay(duration)
                    if (gen == flashGeneration) flashColor = null
                }
            }
        }
    }

    val color = flashColor
    if (color != null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(color)
                // Block interaction for the flash window; no accessibility noise.
                .clearAndSetSemantics { }
                .pointerInput(Unit) {},
        )
    }
}

private const val STYLE_WHITE = 1
private const val STYLE_WHITE_THEN_BLACK = 2
