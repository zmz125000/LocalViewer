package com.hippo.ehviewer.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OtgLiveUpdatePauseTest {
    private val usb = UsbVolume("ABCD-1234", "USB", ExternalStorageKind.UsbDrive)
    private val sd = UsbVolume("5678-90AB", "SD", ExternalStorageKind.SdCard)

    @Test
    fun `app start with existing drives keeps live updates enabled`() {
        val state = OtgLiveUpdatePause()
        state.start(listOf(usb, sd))
        state.update(listOf(usb, sd))
        assertTrue(state.pausedIds.isEmpty())
        assertTrue(state.noticeIds.isEmpty())
    }

    @Test
    fun `SD insertion and removal never pause or announce live updates`() {
        val state = OtgLiveUpdatePause()
        state.start(emptyList())
        state.update(listOf(sd))
        assertTrue(state.pausedIds.isEmpty())
        assertTrue(state.noticeIds.isEmpty())
        state.update(emptyList())
        assertTrue(state.pausedIds.isEmpty())
        assertTrue(state.noticeIds.isEmpty())
    }

    @Test
    fun `Done dismisses OTG notice without resuming until refresh`() {
        val state = OtgLiveUpdatePause()
        state.start(listOf(sd))
        state.update(listOf(sd, usb))
        assertEquals(setOf("abcd-1234"), state.pausedIds)
        assertEquals(state.pausedIds, state.noticeIds)
        state.acknowledge()
        assertTrue(state.noticeIds.isEmpty())
        assertEquals(setOf("abcd-1234"), state.pausedIds)
        state.update(listOf(usb)) // An unrelated SD removal cannot show the notice again.
        assertTrue(state.noticeIds.isEmpty())
        assertEquals(setOf("abcd-1234"), state.pausedIds)
    }

    @Test
    fun `manual refresh resumes while OTG stays connected and callbacks do not re-pause`() {
        val state = OtgLiveUpdatePause()
        state.start(emptyList())
        state.update(listOf(usb))
        state.resume()
        state.update(listOf(usb.copy(label = "Renamed USB"), sd))
        assertTrue(state.pausedIds.isEmpty())
        assertTrue(state.noticeIds.isEmpty())
    }

    @Test
    fun `removing paused OTG clears pause and notice even with SD still mounted`() {
        val state = OtgLiveUpdatePause()
        state.start(listOf(sd))
        state.update(listOf(usb, sd))
        state.update(listOf(sd))
        assertTrue(state.pausedIds.isEmpty())
        assertTrue(state.noticeIds.isEmpty())
    }

    @Test
    fun `a new OTG connection pauses again after a manual resume`() {
        val state = OtgLiveUpdatePause()
        state.start(emptyList())
        state.update(listOf(usb))
        state.resume()
        val other = UsbVolume("1111-2222", "Second USB")
        state.update(listOf(usb, other))
        assertEquals(setOf("1111-2222"), state.pausedIds)
        state.update(listOf(usb))
        assertTrue(state.pausedIds.isEmpty())
        state.update(listOf(usb, other))
        assertEquals(setOf("1111-2222"), state.pausedIds)
    }

    @Test
    fun `a new app session clears an unacknowledged connection pause`() {
        val state = OtgLiveUpdatePause()
        state.start(emptyList())
        state.update(listOf(usb))
        state.start(listOf(usb))
        assertTrue(state.pausedIds.isEmpty())
        assertTrue(state.noticeIds.isEmpty())
    }
}
