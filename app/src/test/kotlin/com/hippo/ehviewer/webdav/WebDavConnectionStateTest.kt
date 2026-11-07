package com.hippo.ehviewer.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConnectionStateTest {
    @Test
    fun startsUnconfirmedAndRecoversAfterTransportFailure() {
        val state = WebDavConnectionState()
        assertTrue(state.sources.value.isEmpty())
        state.response(state.begin("server"), 207)
        assertTrue("server" in state.sources.value)
        state.failed(state.begin("server"))
        assertFalse("server" in state.sources.value)
        state.response(state.begin("server"), 200)
        assertTrue("server" in state.sources.value)
    }

    @Test
    fun authenticationAndServerFailuresGreyOnlyTheAffectedSource() {
        for (status in listOf(401, 403, 407, 429, 500, 502, 503)) {
            val state = WebDavConnectionState()
            state.response(state.begin("other"), 207)
            state.response(state.begin("server"), 207)
            state.response(state.begin("server"), status)
            assertEquals("HTTP $status", setOf("other"), state.sources.value)
        }
    }

    @Test
    fun missingFilesRangeLimitationsAndConflictsStillProveReachability() {
        val state = WebDavConnectionState()
        for (status in listOf(200, 201, 204, 206, 207, 404, 405, 409, 412, 416)) {
            state.response(state.begin("server"), status)
            assertTrue("HTTP $status", "server" in state.sources.value)
        }
    }

    @Test
    fun networkChangeClearsAllSourcesAndIgnoresOldResponses() {
        val state = WebDavConnectionState()
        val old = state.begin("server")
        state.response(old, 207)
        state.response(state.begin("other"), 200)
        state.invalidate()
        state.response(old, 207)
        assertTrue(state.sources.value.isEmpty())
        state.response(state.begin("server"), 207)
        state.failed(old)
        assertEquals(setOf("server"), state.sources.value)
    }

    @Test
    fun credentialChangeInvalidatesTheSourceAndItsPendingRequests() {
        val state = WebDavConnectionState()
        val old = state.begin("server")
        state.response(old, 207)
        state.response(state.begin("other"), 207)
        state.invalidate("server")
        state.response(old, 200)
        assertEquals(setOf("other"), state.sources.value)
        state.response(state.begin("server"), 207)
        assertEquals(setOf("server", "other"), state.sources.value)
    }

    @Test
    fun repeatedRequestsDoNotReplaceOrPublishUnchangedConnectionSets() {
        val state = WebDavConnectionState()
        state.response(state.begin("server"), 207)
        val connected = state.sources.value
        repeat(100) { state.response(state.begin("server"), 206) }
        assertSame(connected, state.sources.value)
    }
}
