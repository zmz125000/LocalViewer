package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/**
 * Pref values for [com.hippo.ehviewer.Settings.autoRotateMode]:
 * 0 = off, 1 = CW 90°, 2 = CCW 90°.
 */
const val AUTO_ROTATE_OFF = 0
const val AUTO_ROTATE_CW = 1
const val AUTO_ROTATE_CCW = 2

/**
 * True when the image’s long side is not aligned with the screen’s long side
 * (e.g. landscape image on a portrait phone).
 */
fun needsFitRotation(
    imageWidth: Float,
    imageHeight: Float,
    screenWidth: Float,
    screenHeight: Float,
): Boolean {
    if (imageWidth <= 0f || imageHeight <= 0f || screenWidth <= 0f || screenHeight <= 0f) {
        return false
    }
    if (imageWidth == imageHeight || screenWidth == screenHeight) {
        return false
    }
    val imageLandscape = imageWidth > imageHeight
    val screenLandscape = screenWidth > screenHeight
    return imageLandscape != screenLandscape
}

fun needsFitRotation(image: IntSize, screen: Size): Boolean = needsFitRotation(
    imageWidth = image.width.toFloat(),
    imageHeight = image.height.toFloat(),
    screenWidth = screen.width,
    screenHeight = screen.height,
)

fun needsFitRotation(image: Size, screen: Size): Boolean = needsFitRotation(
    imageWidth = image.width,
    imageHeight = image.height,
    screenWidth = screen.width,
    screenHeight = screen.height,
)

/**
 * Single source of truth for auto-rotate: mode on + viewport known + long sides disagree.
 * Used by both [PagerItem] (draw) and [PagerViewer] (telephoto contentLocation) so they stay in lockstep.
 */
fun shouldAutoRotate(image: Size, viewport: Size, autoRotateMode: Int): Boolean =
    autoRotateMode != AUTO_ROTATE_OFF &&
        viewport != Size.Zero &&
        needsFitRotation(image, viewport)

fun shouldAutoRotate(image: IntSize, viewport: Size, autoRotateMode: Int): Boolean =
    shouldAutoRotate(
        image = Size(image.width.toFloat(), image.height.toFloat()),
        viewport = viewport,
        autoRotateMode = autoRotateMode,
    )

/** True when auto-rotate applies a +90° (CW) transform; false for −90° (CCW). */
fun isAutoRotateClockwise(autoRotateMode: Int): Boolean = autoRotateMode != AUTO_ROTATE_CCW

/** Content size after optional 90° rotation (width/height swapped). */
fun fitDisplaySize(image: Size, rotate: Boolean): Size =
    if (rotate) Size(image.height, image.width) else image

fun fitDisplaySize(image: IntSize, rotate: Boolean): Size =
    fitDisplaySize(Size(image.width.toFloat(), image.height.toFloat()), rotate)
