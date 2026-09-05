package com.hippo.ehviewer.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbHostPoolPolicyTest {
    @Test
    fun disconnectedDataSessionDoesNotFillTheCap() {
        assertTrue(smbCountsTowardDataCap(retired = false, reservedForList = false, connected = true))
        assertFalse(smbCountsTowardDataCap(retired = false, reservedForList = false, connected = false))
        assertFalse(smbCountsTowardDataCap(retired = true, reservedForList = false, connected = true))
        assertFalse(smbCountsTowardDataCap(retired = false, reservedForList = true, connected = true))
        assertFalse(smbCountsTowardDataCap(retired = true, reservedForList = false, connected = false))
    }

    @Test
    fun threeDownSessionsLeaveRoomToGrow() {
        val counted = List(3) {
            smbCountsTowardDataCap(retired = false, reservedForList = false, connected = false)
        }.count { it }
        assertTrue("dead sockets must not occupy 3/3", counted < 3)
        assertTrue(
            smbCountsTowardDataCap(retired = false, reservedForList = false, connected = true),
        )
    }

    @Test
    fun backgroundAbortClearsUiConnectedFlag() {
        val liveAfterAbort = List(3) {
            smbCountsTowardDataCap(retired = false, reservedForList = false, connected = false)
        }.count { it }
        assertFalse(
            "folder ON_RESUME must see disconnected so it lists / probes a new TCP",
            smbUiHostConnected(liveAfterAbort),
        )
        assertTrue(smbUiHostConnected(1))
    }

    @Test
    fun reconnectProbeIsImmediateOnFirstAttempt() {
        assertEquals(0L, smbReconnectProbeDelayMs(0, 3_000L))
        assertEquals(3_000L, smbReconnectProbeDelayMs(1, 3_000L))
        assertEquals(3_000L, smbReconnectProbeDelayMs(2, 3_000L))
    }
}
