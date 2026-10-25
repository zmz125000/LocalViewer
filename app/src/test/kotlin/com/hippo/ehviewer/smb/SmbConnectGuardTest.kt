package com.hippo.ehviewer.smb

import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbConnectGuardTest {
    @Test
    fun rejectedLoginStopsQueuedAndFutureAttemptsUntilExplicitRetry() {
        var now = 0L
        val guard = SmbConnectGuard { now }
        guard.failed("host", "old password", authentication = true, transient = false)
        repeat(100) {
            now += 60_000L
            assertThrows(SmbConnectSuppressedException::class.java) {
                guard.checkAllowed("host", "old password")
            }
        }
        guard.checkAllowed("host", "new password")
        guard.connected("host")
        // Another successful login must not unblock the bad credentials.
        assertThrows(SmbConnectSuppressedException::class.java) {
            guard.checkAllowed("host", "old password")
        }
        guard.retryCredentials("old password")
        guard.checkAllowed("host", "old password")
    }

    @Test
    fun capacityAndTransportFailuresShareIncreasingHostBackoff() {
        var now = 0L
        val guard = SmbConnectGuard { now }
        for (delay in listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)) {
            guard.failed("host", "user", authentication = false, transient = true)
            guard.checkAllowed("other-host", "user")
            now += delay - 1
            assertTrue(guard.isCoolingDown("host"))
            assertThrows(SmbConnectSuppressedException::class.java) {
                guard.checkAllowed("host", "other-user")
            }
            now++
            guard.checkAllowed("host", "user")
        }
        guard.connected("host")
        guard.failed("host", "user", authentication = false, transient = true)
        now += 2_000
        assertFalse(guard.isCoolingDown("host"))
    }

    @Test
    fun fileErrorsDoNotBlockNewConnections() {
        val guard = SmbConnectGuard { 0L }
        guard.failed("host", "user", authentication = false, transient = false)
        guard.checkAllowed("host", "user")
    }

    @Test
    fun recognizesWrappedLoginAndAccountRejectionsWithoutTreatingFileAccessAsLoginFailure() {
        for (status in listOf(0xC000006DL, 0xC0000071L, 0xC0000234L)) {
            val error = SMBApiException(status, SMB2MessageCommandCode.SMB2_SESSION_SETUP, "login", null)
            assertTrue(isSmbAuthenticationFailure(IOException("wrapped", error)))
        }
        val denied = SMBApiException(0xC0000022L, SMB2MessageCommandCode.SMB2_CREATE, "file", null)
        assertFalse(isSmbAuthenticationFailure(denied))
    }
}
