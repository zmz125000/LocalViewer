package com.hippo.ehviewer.smb

import com.hierynomus.mssmb2.SMBApiException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class SmbConnectSuppressedException(message: String) : IOException(message)

/** Shared by every SMB connection path. Existing sessions remain usable during backoff. */
internal class SmbConnectGuard(
    private val nowMs: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    private data class Backoff(val failures: Int, val untilMs: Long)
    private val rejectedCredentials = HashSet<String>()
    private val hosts = HashMap<String, Backoff>()

    @Synchronized
    fun checkAllowed(host: String, credential: String) {
        if (credential in rejectedCredentials) {
            throw SmbConnectSuppressedException("SMB login rejected; update credentials or test the connection to retry")
        }
        if (isCoolingDown(host)) {
            throw SmbConnectSuppressedException("SMB connection recovering; retry later")
        }
    }

    @Synchronized
    fun isCoolingDown(host: String): Boolean = hosts[host]?.let { nowMs() < it.untilMs } == true

    @Synchronized
    fun failed(host: String, credential: String, authentication: Boolean, transient: Boolean) {
        if (authentication) rejectedCredentials.add(credential)
        if (transient && !authentication) {
            val failures = ((hosts[host]?.failures ?: 0) + 1).coerceAtMost(6)
            val delayMs = (2_000L shl (failures - 1)).coerceAtMost(60_000L)
            hosts[host] = Backoff(failures, nowMs() + delayMs)
        }
    }

    @Synchronized
    fun connected(host: String) {
        hosts.remove(host)
    }

    /** Explicit user connection test; automatic probes never clear a rejected login. */
    @Synchronized
    fun retryCredentials(credential: String) {
        rejectedCredentials.remove(credential)
    }
}

// Use wire codes: SMBJ's NtStatus enum does not include every account rejection.
private val smbAuthenticationStatuses = setOf(
    0xC0000064L, // NO_SUCH_USER
    0xC000006AL, // WRONG_PASSWORD
    0xC000006DL, // LOGON_FAILURE
    0xC000006EL, // ACCOUNT_RESTRICTION
    0xC000006FL, // INVALID_LOGON_HOURS
    0xC0000070L, // INVALID_WORKSTATION
    0xC0000071L, // PASSWORD_EXPIRED
    0xC0000072L, // ACCOUNT_DISABLED
    0xC000015BL, // LOGON_TYPE_NOT_GRANTED
    0xC0000193L, // ACCOUNT_EXPIRED
    0xC0000224L, // PASSWORD_MUST_CHANGE
    0xC0000234L, // ACCOUNT_LOCKED_OUT
)

internal fun isSmbAuthenticationFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
    (cause is SMBApiException && cause.statusCode and 0xFFFFFFFFL in smbAuthenticationStatuses) ||
        cause.message.orEmpty().contains("Authentication failed", ignoreCase = true)
}
