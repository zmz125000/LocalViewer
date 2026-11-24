package com.hippo.ehviewer.ui.reader

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayscaleDitheringTest {
    @Test
    fun distributesSignedErrorToTheFourForwardNeighbors() {
        for (gray in listOf(8, 9)) {
            val pixels = intArrayOf(0x7f000000 or (gray * 0x010101))
            val current = IntArray(3)
            val next = IntArray(3)

            ditherGrayscaleRow(pixels, current, next)

            val sign = if (gray == 8) 1 else -1
            assertEquals(if (gray == 8) 0x7f000000 else 0x7f111111, pixels[0])
            assertArrayEquals(intArrayOf(0, 0, sign * 14336), current)
            assertArrayEquals(intArrayOf(sign * 6144, sign * 10240, sign * 2048), next)
        }
    }

    @Test
    fun flatFieldsRetainTheirAverageGrayLevel() {
        val width = 256
        val height = 256
        for (gray in listOf(0, 1, 8, 16, 50, 128, 254, 255)) {
            val source = (0xff shl 24) or (gray * 0x010101)
            val pixels = IntArray(width)
            var current = IntArray(width + 2)
            var next = IntArray(width + 2)
            var sum = 0
            repeat(height) {
                pixels.fill(source)
                ditherGrayscaleRow(pixels, current, next)
                for (pixel in pixels) {
                    val value = pixel and 255
                    assertEquals(0xff000000.toInt() or (value * 0x010101), pixel)
                    assertEquals(0, value % 17)
                    sum += value
                }
                val finished = current
                current = next
                next = finished
                next.fill(0)
            }
            val average = sum.toDouble() / (width * height)
            // A little error can leave the padded edges, especially near black.
            assertTrue("Input $gray, average output $average", abs(gray - average) < 0.1)
        }
    }
}
