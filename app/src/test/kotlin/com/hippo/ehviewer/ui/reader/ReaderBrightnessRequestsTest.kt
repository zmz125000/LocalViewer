package com.hippo.ehviewer.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBrightnessRequestsTest {
    @Test
    fun outgoingReaderCannotResetIncomingReaderBrightness() {
        val requests = ReaderBrightnessRequests()
        val outgoing = Any()
        val incoming = Any()
        assertEquals(35, requests.set(outgoing, 35))
        assertEquals(80, requests.set(incoming, 80))
        assertEquals(80, requests.set(outgoing, 10))
        assertEquals(80, requests.remove(outgoing))
        assertEquals(0, requests.remove(incoming))
        assertTrue(requests.isEmpty)
    }

    @Test
    fun disposingTopReaderRestoresUnderlyingReader() {
        val requests = ReaderBrightnessRequests()
        val underlying = Any()
        val top = Any()
        requests.set(underlying, -25)
        requests.set(top, 65)
        assertEquals(-25, requests.remove(top))
        assertEquals(0, requests.remove(underlying))
    }
}
