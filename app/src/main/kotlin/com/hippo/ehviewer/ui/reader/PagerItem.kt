package com.hippo.ehviewer.ui.reader

import android.graphics.drawable.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.unreachable
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.gallery.progressObserved
import com.hippo.ehviewer.gallery.statusObserved
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.ui.tools.DrawablePainter
import com.hippo.ehviewer.util.AdsPlaceholderFile
import kotlin.math.roundToInt
import kotlinx.coroutines.awaitCancellation

@Composable
fun PagerItem(
    page: Page,
    pageLoader: ReaderSession,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    viewportSize: Size = Size.Zero,
    /** Landscape dual webtoon: height-driven width in a LazyRow. */
    horizontalStrip: Boolean = false,
) {
    // Scheduling is driven by one ReaderNavigation from the viewport. This item only renders
    // status and owns a display pin; Compose retention no longer determines decode-ahead.
    val defaultError = stringResource(id = R.string.decode_image_error)
    val aspect = page.layoutAspect.takeIf { it > 0f } ?: DEFAULT_ASPECT
    val placeholderMod = if (horizontalStrip) {
        modifier.fillMaxHeight().aspectRatio(aspect, matchHeightConstraintsFirst = true)
    } else {
        modifier.fillMaxWidth().aspectRatio(aspect)
    }
    when (val state = page.statusObserved) {
        is PageStatus.Queued, is PageStatus.Loading -> {
            Box(
                modifier = placeholderMod,
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = state is PageStatus.Loading,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "progressState",
                ) { determinate ->
                    val animatedProgress by animateFloatAsState(
                        targetValue = state.progressObserved,
                        animationSpec = WavyProgressIndicatorDefaults.ProgressAnimationSpec,
                        label = "progress",
                    )
                    if (determinate) {
                        CircularWavyProgressIndicator(progress = { animatedProgress })
                    } else {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
        is PageStatus.Ready -> {
            val image = state.image
            var painter by remember(image) { mutableStateOf<Painter?>(null) }
            LaunchedEffect(image) {
                if (!image.pin()) {
                    // Recycled / dead image still marked Ready — force a clean reload.
                    painter = null
                    pageLoader.retryPage(page.index)
                    return@LaunchedEffect
                }
                // Reuse the same painter for this Image. A new DrawablePainter on every
                // effect start raced with the old onForgotten(stop) after scroll.
                if (painter == null) painter = image.toPainter()
                try {
                    awaitCancellation()
                } finally {
                    // Drop display pin only. Do not notifyPageWait — that turned visible
                    // pages into forever-Queued when the cache also released its pin.
                    image.unpin()
                }
            }
            painter?.let { painter ->
                val drawable = (painter as? DrawablePainter)?.drawable
                val grayScale by Settings.grayScale.collectAsState()
                val invert by Settings.invertedColors.collectAsState()
                val autoRotateMode by Settings.autoRotateMode.collectAsState()
                val imgSize = image.intrinsicSize
                val rotate = shouldAutoRotate(imgSize, viewportSize, autoRotateMode)
                val clockwise = isAutoRotateClockwise(autoRotateMode)
                // Color matrices crush Ultra HDR gain-map boost — leave filters off for HDR pages.
                val colorFilter = when {
                    image.hasGainmap -> null
                    grayScale && invert -> grayScaleAndInvertFilter
                    grayScale -> grayScaleFilter
                    invert -> invertFilter
                    else -> null
                }
                FitPageImage(
                    painter = painter,
                    rotate = rotate,
                    clockwise = clockwise,
                    contentScale = contentScale,
                    colorFilter = colorFilter,
                    horizontalStrip = horizontalStrip,
                    modifier = Modifier.thenIf(drawable is Animatable) {
                        // Any on-screen pixel is enough. 0.5f froze webtoon/pager WebP that
                        // GIF/APNG still resumed via AnimatedImageDrawable.setVisible.
                        onVisibilityChanged(minDurationMs = 33, minFractionVisible = 0f) { visible ->
                            drawable!!.setVisible(visible, false)
                            if (visible) (drawable as Animatable).start()
                        }
                    }.then(modifier),
                    contentModifier = contentModifier,
                )
            } ?: Spacer(modifier = placeholderMod)
        }
        is PageStatus.Blocked -> {
            AdsPlaceholder(
                modifier = if (horizontalStrip) {
                    modifier.fillMaxHeight().aspectRatio(DEFAULT_ASPECT, matchHeightConstraintsFirst = true)
                } else {
                    modifier.fillMaxSize()
                },
                contentScale = if (contentScale == ContentScale.Inside) ContentScale.Fit else contentScale,
            )
        }
        is PageStatus.Error -> {
            Box(modifier = placeholderMod) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.message ?: defaultError,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { pageLoader.retryPage(page.index) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Text(text = stringResource(id = R.string.action_retry))
                    }
                }
            }
        }
    }
}

/**
 * Draws [painter] optionally rotated ±90° so the image long side matches the screen.
 *
 * Telephoto uses [ZoomableContentLocation.scaledInsideAndCenterAligned] (ContentScale.**Inside**)
 * for unscaled bounds, then [ZoomableState.contentScale] (e.g. Fit) as base zoom. Non-rotated
 * pages draw with ContentScale.Inside so the bitmap matches that model.
 *
 * **Pager:** draw the rotated image at **Inside** size of the swapped aspect (never pre-upscale).
 * Pre-fitting with Fit upscaled small pages (1220×889 → scale > 1), then telephoto Fit
 * upscaled again → crop. Large pages only downscaled once so they looked fine.
 *
 * **Webtoon (vertical):** no per-page telephoto fit — width-driven post-rotation row height.
 * **Webtoon (horizontal strip):** height-driven post-rotation item width.
 *
 * Whether to rotate must match [shouldAutoRotate] used in [PagerViewer] for contentLocation.
 */
@Composable
private fun FitPageImage(
    painter: Painter,
    rotate: Boolean,
    clockwise: Boolean,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    horizontalStrip: Boolean,
    modifier: Modifier,
    contentModifier: Modifier,
) {
    if (!rotate) {
        if (horizontalStrip) {
            val src = painter.intrinsicSize
            val aspect = (src.width / src.height.coerceAtLeast(1f)).coerceAtLeast(0.01f)
            Image(
                painter = painter,
                contentDescription = null,
                modifier = modifier
                    .then(contentModifier)
                    .fillMaxHeight()
                    .aspectRatio(aspect, matchHeightConstraintsFirst = true),
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
            )
        } else {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = modifier.then(contentModifier).fillMaxSize(),
                contentScale = contentScale,
                colorFilter = colorFilter,
            )
        }
        return
    }

    val src = painter.intrinsicSize
    val srcW = src.width.roundToInt().coerceAtLeast(1)
    val srcH = src.height.roundToInt().coerceAtLeast(1)
    val degrees = if (clockwise) 90f else -90f

    Image(
        painter = painter,
        contentDescription = null,
        modifier = modifier
            .then(contentModifier)
            .then(if (horizontalStrip) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
            .rotate90FitLayout(bitmapW = srcW, bitmapH = srcH, horizontalStrip = horizontalStrip)
            .graphicsLayer {
                rotationZ = degrees
                clip = false
            },
        // Pre-rotation box matches drawn aspect → FillBounds is uniform 1:1 in that box.
        // (contentScale param is for the non-rotate path; telephoto owns Fit/Crop/Original.)
        contentScale = ContentScale.FillBounds,
        colorFilter = colorFilter,
    )
}

/**
 * - **Pager (bounded H):** measure at ContentScale.**Inside** of post-rotation size (no upscale),
 *   report full viewport so telephoto can Fit-upscale / zoom.
 * - **Webtoon vertical (unbounded H):** width-driven post-rotation row height from bitmap aspect.
 * - **Webtoon horizontal (unbounded W):** height-driven post-rotation item width.
 */
private fun Modifier.rotate90FitLayout(
    bitmapW: Int,
    bitmapH: Int,
    horizontalStrip: Boolean = false,
): Modifier = layout { measurable, constraints ->
    val maxW = constraints.maxWidth
    val maxH = constraints.maxHeight
    val hasBoundedH = maxH != Constraints.Infinity
    val hasBoundedW = maxW != Constraints.Infinity
    // Post-rotation aspect (width/height) = origH/origW
    val displayAspect = bitmapH.toFloat().coerceAtLeast(1f) / bitmapW.toFloat().coerceAtLeast(1f)

    if (horizontalStrip && hasBoundedH && !hasBoundedW) {
        // Horizontal continuous: height-driven strip; fit rotated image to max height.
        val displayH = maxH.coerceAtLeast(1)
        val displayW = (displayH * displayAspect).roundToInt().coerceAtLeast(1)
        val preW = displayH
        val preH = displayW
        val placeable = measurable.measure(Constraints.fixed(preW, preH))
        layout(displayW, displayH) {
            placeable.place(
                x = (displayW - preW) / 2,
                y = (displayH - preH) / 2,
            )
        }
    } else if (!hasBoundedH) {
        // Vertical webtoon: width-driven strip; fit rotated image to max width.
        val displayW = maxW.coerceAtLeast(1)
        val displayH = (displayW / displayAspect).roundToInt().coerceAtLeast(1)
        val preW = displayH
        val preH = displayW
        val placeable = measurable.measure(Constraints.fixed(preW, preH))
        layout(displayW, displayH) {
            placeable.place(
                x = (displayW - preW) / 2,
                y = (displayH - preH) / 2,
            )
        }
    } else {
        // Post-rotation logical size = swap of bitmap (matches fitDisplaySize / contentLocation).
        val logicalW = bitmapH.toFloat().coerceAtLeast(1f)
        val logicalH = bitmapW.toFloat().coerceAtLeast(1f)
        val viewport = Size(
            maxW.coerceAtLeast(1).toFloat(),
            maxH.coerceAtLeast(1).toFloat(),
        )
        // Same as telephoto scaledInsideAndCenterAligned: Inside never upscales.
        val inside = ContentScale.Inside.computeScaleFactor(
            srcSize = Size(logicalW, logicalH),
            dstSize = viewport,
        )
        val displayW = (logicalW * inside.scaleX).roundToInt().coerceAtLeast(1)
        val displayH = (logicalH * inside.scaleY).roundToInt().coerceAtLeast(1)
        // Pre-rotation child = swap of display box.
        // May place with negative x when bitmap width > viewport (no clip; graphicsLayer clip=false).
        val preW = displayH
        val preH = displayW
        val placeable = measurable.measure(Constraints.fixed(preW, preH))
        val layoutW = maxW
        val layoutH = maxH.coerceAtLeast(1)
        layout(layoutW, layoutH) {
            placeable.place(
                x = (layoutW - preW) / 2,
                y = (layoutH - preH) / 2,
            )
        }
    }
}

private fun Image.toPainter() = when (val image = innerImage) {
    is BitmapImage -> BitmapPainter(image.bitmap, intrinsicSize.toSize())
    is DrawableImage -> DrawablePainter(image.drawable)
    else -> unreachable()
}

private const val DEFAULT_ASPECT = 1 / 1.4125f

private val invertMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)
private val grayScaleMatrix = ColorMatrix().apply { setToSaturation(0f) }
private val grayScaleAndInvertMatrix = ColorMatrix().also { mtx ->
    mtx.setToSaturation(0f)
    mtx *= invertMatrix
}

private val grayScaleFilter = ColorFilter.colorMatrix(grayScaleMatrix)
private val invertFilter = ColorFilter.colorMatrix(invertMatrix)
private val grayScaleAndInvertFilter = ColorFilter.colorMatrix(grayScaleAndInvertMatrix)

@Composable
fun AdsPlaceholder(
    modifier: Modifier = Modifier,
    contentScale: ContentScale,
) = SubcomposeAsyncImage(
    model = AdsPlaceholderFile,
    contentDescription = null,
    modifier = modifier,
    contentScale = contentScale,
) {
    val placeholderState by painter.state.collectAsState()
    if (placeholderState is AsyncImagePainter.State.Success) {
        SubcomposeAsyncImageContent()
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
            contentAlignment = Alignment.Center,
        ) {
            if (placeholderState is AsyncImagePainter.State.Error) {
                Text(text = stringResource(id = R.string.blocked_image))
            }
        }
    }
}
