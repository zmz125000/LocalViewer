package com.hippo.ehviewer.library.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfContentKindTest {
    @Test
    fun letterScanAt200DpiIsFullPage() {
        assertTrue(isFullPageScan(1700, 2200, 612f, 792f))
    }

    @Test
    fun smallFigureIsNotFullPage() {
        assertFalse(isFullPageScan(320, 240, 612f, 792f))
    }

    @Test
    fun largeBitmapWithoutPageBoxStillCountsAsScan() {
        assertTrue(isFullPageScan(1600, 2400, 0f, 0f))
    }

    @Test
    fun comicFrontMatterIsImagePdf() {
        val sample = PdfFrontSample(
            scannedPages = 8,
            fullPageImages = 8,
            pagesWithoutImage = 0,
            declaredPageCount = 120,
        )
        assertEquals(PdfContentKind.Image, sample.toKind())
    }

    @Test
    fun singlePageScanIsImagePdf() {
        val sample = PdfFrontSample(
            scannedPages = 1,
            fullPageImages = 1,
            pagesWithoutImage = 0,
            declaredPageCount = 1,
        )
        assertEquals(PdfContentKind.Image, sample.toKind())
    }

    @Test
    fun coverThenTextIsVectorPdf() {
        val sample = PdfFrontSample(
            scannedPages = 8,
            fullPageImages = 1,
            pagesWithoutImage = 7,
            declaredPageCount = 40,
        )
        assertEquals(PdfContentKind.Vector, sample.toKind())
    }

    @Test
    fun incompletePrefixDoesNotGuessComic() {
        val sample = PdfFrontSample(
            scannedPages = 1,
            fullPageImages = 1,
            pagesWithoutImage = 0,
            declaredPageCount = 80,
        )
        assertEquals(PdfContentKind.Vector, sample.toKind())
    }
}
