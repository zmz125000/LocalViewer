package com.hippo.ehviewer.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize

/**
 * True when the image’s long side is not aligned with the screen’s long side
 * (e.g. landscape image on a portrait phone). Caller should rotate 90° CW.
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

/** Content size after optional 90° CW (width/height swapped). */
fun fitDisplaySize(image: Size, rotate: Boolean): Size =
    if (rotate) Size(image.height, image.width) else image

fun fitDisplaySize(image: IntSize, rotate: Boolean): Size =
    fitDisplaySize(Size(image.width.toFloat(), image.height.toFloat()), rotate)
