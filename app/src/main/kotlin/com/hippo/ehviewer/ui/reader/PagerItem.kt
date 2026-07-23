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
import androidx.compose.runtime.DisposableEffect
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
import kotlin.math.roundToInt
import coil3.BitmapImage
import coil3.DrawableImage
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.unreachable
import com.google.accompanist.drawablepainter.DrawablePainter
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.progressObserved
import com.hippo.ehviewer.gallery.statusObserved
import com.hippo.ehviewer.image.Image
import com.hippo.ehviewer.util.AdsPlaceholderFile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop

@Composable
fun PagerItem(
    page: Page,
    pageLoader: PageLoader,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    viewportSize: Size = Size.Zero,
) {
    LaunchedEffect(Unit) {
        pageLoader.request(page.index)
        // In case page loader restart
        page.statusFlow.drop(1).collect {
            if (page.statusFlow.value == PageStatus.Queued) {
                pageLoader.request(page.index)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            pageLoader.cancelRequest(page.index)
        }
    }
    val defaultError = stringResource(id = R.string.decode_image_error)
    when (val state = page.statusObserved) {
        is PageStatus.Queued, is PageStatus.Loading -> {
            Box(
                modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT),
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
            var painter by remember { mutableStateOf<Painter?>(null) }
            LaunchedEffect(image) {
                if (image.pin()) {
                    painter = image.toPainter()
                    try {
                        awaitCancellation()
                    } finally {
                        if (image.unpin()) {
                            pageLoader.notifyPageWait(page.index)
                        }
                    }
                }
            }
            painter?.let { painter ->
                val drawable = (painter as? DrawablePainter)?.drawable
                val grayScale by Settings.grayScale.collectAsState()
                val invert by Settings.invertedColors.collectAsState()
                val autoRotate by Settings.autoRotateToFit.collectAsState()
                val clockwise by Settings.autoRotateClockwise.collectAsState()
                val imgSize = image.intrinsicSize
                val rotate = autoRotate &&
                    viewportSize != Size.Zero &&
                    needsFitRotation(imgSize, viewportSize)
                val colorFilter = when {
                    grayScale && invert -> grayScaleAndInvertFilter
                    grayScale -> grayScaleFilter
                    invert -> invertFilter
                    else -> null
                }
                FitPageImage(
                    painter = remember(painter) { painter },
                    rotate = rotate,
                    clockwise = clockwise,
                    contentScale = contentScale,
                    colorFilter = colorFilter,
                    modifier = Modifier.thenIf(drawable is Animatable) {
                        onVisibilityChanged(minDurationMs = 33, minFractionVisible = 0.5f) {
                            drawable!!.setVisible(it, false)
                        }
                    }.then(modifier),
                    contentModifier = contentModifier,
                )
            } ?: Spacer(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT))
        }
        is PageStatus.Blocked -> {
            AdsPlaceholder(
                modifier = modifier.fillMaxSize(),
                contentScale = if (contentScale == ContentScale.Inside) ContentScale.Fit else contentScale,
            )
        }
        is PageStatus.Error -> {
            Box(modifier = modifier.fillMaxWidth().aspectRatio(DEFAULT_ASPECT)) {
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
 * Layout reports the **post-rotation** size (aspect swapped) so webtoon row height is correct.
 * The image is measured at the pre-rotation size with matching aspect and drawn with Fit,
 * then rotated CW or CCW — no stretch.
 */
@Composable
private fun FitPageImage(
    painter: Painter,
    rotate: Boolean,
    clockwise: Boolean,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    modifier: Modifier,
    contentModifier: Modifier,
) {
    if (!rotate) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = modifier.then(contentModifier).fillMaxSize(),
            contentScale = contentScale,
            colorFilter = colorFilter,
        )
        return
    }

    val src = painter.intrinsicSize
    val imageAspect = if (src.width > 0f && src.height > 0f) {
        src.width / src.height
    } else {
        1f / DEFAULT_ASPECT
    }
    // After ±90° the laid-out box has aspect (width/height) = origH/origW = 1/imageAspect
    val displayAspect = 1f / imageAspect
    val degrees = if (clockwise) 90f else -90f

    Image(
        painter = painter,
        contentDescription = null,
        // Same outer order as non-rotate (zoomable outside); layout reports post-rotation
        // size, child is measured pre-rotation with matching aspect → uniform scale only.
        modifier = modifier
            .then(contentModifier)
            .fillMaxWidth()
            .rotate90FitLayout(displayAspect = displayAspect)
            .graphicsLayer { rotationZ = degrees },
        // Preserve aspect inside fixed pre-rotation constraints (matches bitmap aspect).
        contentScale = ContentScale.Fit,
        colorFilter = colorFilter,
    )
}

/**
 * Measures the child in the **pre-rotation** frame (aspect = 1/[displayAspect]), then
 * reports the **post-rotation** size (aspect = [displayAspect]) to the parent.
 *
 * Bounding box is the same for CW and CCW (±90° both swap width/height).
 */
private fun Modifier.rotate90FitLayout(displayAspect: Float): Modifier = layout { measurable, constraints ->
    val maxW = constraints.maxWidth
    val maxH = constraints.maxHeight
    val hasBoundedH = maxH != Constraints.Infinity

    // Largest display rect (post-rotation) that fits in parent constraints.
    val displayW: Int
    val displayH: Int
    if (!hasBoundedH || maxW.toFloat() / maxH <= displayAspect) {
        displayW = maxW.coerceAtLeast(1)
        displayH = (displayW / displayAspect).roundToInt().coerceAtLeast(1)
    } else {
        displayH = maxH.coerceAtLeast(1)
        displayW = (displayH * displayAspect).roundToInt().coerceAtLeast(1)
    }

    // Pre-rotation child size: swap of display → same aspect as the original bitmap.
    val preW = displayH
    val preH = displayW
    val placeable = measurable.measure(Constraints.fixed(preW, preH))

    layout(displayW, displayH) {
        placeable.place(
            x = (displayW - preW) / 2,
            y = (displayH - preH) / 2,
        )
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
