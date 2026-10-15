package com.hippo.ehviewer.ui.screen

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ExplorerSidePanelSizeTest {
    @Test
    fun openFoldIsThreeFifthsOfTheScreen() {
        val (width, height) = explorerSidePanelSize(
            screenWidth = 900.dp,
            screenHeight = 840.dp,
            foldOpen = true,
            phonePortrait = false,
            phoneLandscape = false,
            listColumns = 3,
            widthCap = 860.dp,
            heightCap = 760.dp,
        )
        assertEquals(540.dp, width)
        assertEquals(504.dp, height)
    }

    @Test
    fun closedFoldKeepsThePhonePortraitPanel() {
        val (width, height) = explorerSidePanelSize(
            screenWidth = 360.dp,
            screenHeight = 800.dp,
            foldOpen = false,
            phonePortrait = true,
            phoneLandscape = false,
            listColumns = 1,
            widthCap = 360.dp,
            heightCap = 700.dp,
        )
        assertEquals(306.dp, width)
        assertEquals(480.dp, height)
    }

    @Test
    fun tabletWithoutAFoldKeepsTheLandscapePanel() {
        val (width, height) = explorerSidePanelSize(
            screenWidth = 1000.dp,
            screenHeight = 800.dp,
            foldOpen = false,
            phonePortrait = false,
            phoneLandscape = false,
            listColumns = 3,
            widthCap = 960.dp,
            heightCap = 720.dp,
        )
        assertEquals(400.dp, width)
        assertEquals(800.dp * 2f / 3f, height)
    }
}
