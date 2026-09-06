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

    @Test
    fun concurrentFilesSpreadAcrossConnectionBudget() {
        // Fill-first (take any free multiplex slot) packed this as [3] on one TCP.
        assertEquals(listOf(1, 1, 1), smbSpreadDataOps(opCount = 3, maxConnections = 3, opsPerSession = 3))
        assertEquals(listOf(1), smbSpreadDataOps(opCount = 1, maxConnections = 3, opsPerSession = 3))
        assertEquals(listOf(1, 1), smbSpreadDataOps(opCount = 2, maxConnections = 3, opsPerSession = 3))
    }

    @Test
    fun leftoverOpsMultiplexOnlyAfterBudgetIsFull() {
        assertEquals(listOf(2, 2, 1), smbSpreadDataOps(opCount = 5, maxConnections = 3, opsPerSession = 3))
        assertEquals(listOf(1, 1, 1, 1, 1), smbSpreadDataOps(opCount = 5, maxConnections = 5, opsPerSession = 3))
    }

    @Test
    fun extraIdleSessionsAreUsedInsteadOfTheBusyFirstTcp() {
        // Pool already grew to 3; two finished. Next op must not stick to session 0.
        val place = smbPlaceDataOp(
            outstanding = intArrayOf(1, 0, 0),
            availableSlots = intArrayOf(2, 3, 3),
            maxConnections = 3,
        )
        assertEquals(SmbDataPlacement.Use(1), place)
        val underCap = smbPlaceDataOp(
            outstanding = intArrayOf(1, 0, 0),
            availableSlots = intArrayOf(2, 3, 3),
            maxConnections = 5,
        )
        assertEquals(SmbDataPlacement.Use(1), underCap)
    }

    @Test
    fun busySessionDoesNotAbsorbWorkWhileBudgetRemains() {
        assertEquals(
            SmbDataPlacement.Grow,
            smbPlaceDataOp(
                outstanding = intArrayOf(1),
                availableSlots = intArrayOf(2),
                maxConnections = 3,
            ),
        )
        assertEquals(
            SmbDataPlacement.Use(0),
            smbPlaceDataOp(
                outstanding = intArrayOf(1),
                availableSlots = intArrayOf(2),
                maxConnections = 1,
            ),
        )
    }

    @Test
    fun equalLoadPrefersSessionThatAlreadyHasTheShare() {
        assertEquals(
            SmbDataPlacement.Use(1),
            smbPlaceDataOp(
                outstanding = intArrayOf(1, 1),
                availableSlots = intArrayOf(2, 2),
                maxConnections = 2,
                hasShare = booleanArrayOf(false, true),
            ),
        )
    }

    @Test
    fun atCapWithNoSlotsWaits() {
        assertEquals(
            SmbDataPlacement.Wait,
            smbPlaceDataOp(
                outstanding = intArrayOf(3, 3, 3),
                availableSlots = intArrayOf(0, 0, 0),
                maxConnections = 3,
            ),
        )
    }
}
