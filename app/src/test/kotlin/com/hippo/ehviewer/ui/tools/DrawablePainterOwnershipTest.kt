package com.hippo.ehviewer.ui.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawablePainterOwnershipTest {
    @Test
    fun forgottenPainterMustNotStopIfAnotherPainterOwnsTheDrawable() {
        val oldCb = Any()
        val newCb = Any()
        assertTrue(drawablePainterOwnsCallback(oldCb, oldCb))
        assertFalse(drawablePainterOwnsCallback(newCb, oldCb))
        assertFalse(drawablePainterOwnsCallback(null, oldCb))
    }
}
