package com.hippo.ehviewer.provider

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * In-memory tokens for [StreamDocumentProvider] URIs.
 *
 * External apps receive a short-lived `content://…/streamdoc/{token}` grant.
 *
 * - **Network** ([register]): provider opens [ArchiveByteSource] on demand (SMB/WebDAV
 *   range I/O + block cache via proxy FD). [retain]/[release] track live proxy FDs and
 *   drive [StreamKeepAliveService]. Tokens are **not** removed on last FD close so a
 *   player can reopen the same URI after buffering / seek / resume; [pruneStale] ages them out.
 * - **Local/SAF** ([registerDirect]): provider returns a real seekable
 *   [ParcelFileDescriptor] (no FUSE proxy). Tokens age out via [MAX_AGE_MS] / cap
 *   (no close hook on the kernel FD).
 */
object StreamDocumentRegistry {
    data class Entry(
        val displayName: String,
        val mimeType: String,
        /** Known size when probed at register time; -1 if unknown. Helps viewers avoid over-read. */
        val sizeBytes: Long = -1L,
        /** Network / stream path: open random-access source for proxy FD. */
        val openSource: (() -> ArchiveByteSource)? = null,
        /**
         * When true, [openSource] may be invoked twice for independent sticky sessions
         * (video dual-lane prefetch). SMB/WebDAV sticky opens each own a TCP session.
         */
        val parallelPrefetch: Boolean = false,
        /** Local/SAF path: hand through a real descriptor (preferred when available). */
        val openFileDescriptor: (() -> ParcelFileDescriptor)? = null,
        /** ElapsedRealtime ms when registered / last touched (for stale prune). */
        @Volatile var lastAccessMs: Long = SystemClock.elapsedRealtime(),
        /** Live [StreamDocumentProvider.openFile] proxy FDs for this token. */
        val openCount: AtomicInteger = AtomicInteger(0),
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes global FD accounting with delayed service stop/start transitions. */
    private val keepAliveLock = Any()
    private var totalNetworkOpen = 0
    private var stopKeepAliveJob: Job? = null

    /** Live network proxy FDs across all tokens (for keep-alive re-promote after FGS timeout). */
    fun networkOpenCount(): Int = synchronized(keepAliveLock) { totalNetworkOpen }

    /** Soft cap so repeated long-press PDF does not retain unbounded lambdas. */
    private const val MAX_ENTRIES = 24

    /**
     * Age out idle tokens (no live proxy FD). Long enough for multi-hour movies that
     * briefly close/reopen the content URI between seeks or after buffer drain.
     */
    private const val MAX_AGE_MS = 6L * 60L * 60L * 1000L

    /**
     * Keep FGS a bit after the last proxy FD closes so players that close/reopen the
     * content URI (seek, rebuffer, resume) do not hit background-start restrictions.
     */
    private const val KEEP_ALIVE_STOP_DELAY_MS = 3L * 60L * 1000L

    fun register(
        displayName: String,
        mimeType: String = "application/pdf",
        sizeBytes: Long = -1L,
        parallelPrefetch: Boolean = false,
        openSource: () -> ArchiveByteSource,
    ): String {
        pruneStale()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            openSource = openSource,
            parallelPrefetch = parallelPrefetch,
        )
        return token
    }

    fun registerDirect(
        displayName: String,
        mimeType: String = "application/pdf",
        sizeBytes: Long = -1L,
        openFileDescriptor: () -> ParcelFileDescriptor,
    ): String {
        pruneStale()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            openFileDescriptor = openFileDescriptor,
        )
        return token
    }

    fun get(token: String): Entry? {
        val entry = entries[token] ?: return null
        entry.lastAccessMs = SystemClock.elapsedRealtime()
        return entry
    }

    fun remove(token: String) {
        entries.remove(token)
    }

    /** Call when a network proxy FD is successfully opened for [token]. */
    fun retain(token: String, context: Context = appCtx) {
        val entry = entries[token] ?: return
        entry.openCount.incrementAndGet()
        entry.lastAccessMs = SystemClock.elapsedRealtime()
        synchronized(keepAliveLock) {
            totalNetworkOpen++
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = null
            // Refresh on every open. This recovers if the OS or user stopped the previous
            // service, and startForegroundService() is idempotent while it is already alive.
            StreamKeepAliveService.start(context)
        }
    }

    /**
     * Call from proxy [android.os.ProxyFileDescriptorCallback.onRelease].
     * Keeps the token so external players can reopen the same URI (resume / rebuffer).
     * [pruneStale] eventually drops idle grants.
     */
    fun release(token: String, context: Context = appCtx) {
        val entry = entries[token] ?: return
        // Floor at 0 — duplicate release must not go negative.
        var left: Int
        do {
            left = entry.openCount.get()
            if (left <= 0) return
        } while (!entry.openCount.compareAndSet(left, left - 1))
        entry.lastAccessMs = SystemClock.elapsedRealtime()
        synchronized(keepAliveLock) {
            totalNetworkOpen = (totalNetworkOpen - 1).coerceAtLeast(0)
            if (totalNetworkOpen != 0) return
            stopKeepAliveJob?.cancel()
            stopKeepAliveJob = scope.launch {
                delay(KEEP_ALIVE_STOP_DELAY_MS)
                synchronized(keepAliveLock) {
                    if (totalNetworkOpen == 0) {
                        StreamKeepAliveService.stop(context)
                        stopKeepAliveJob = null
                    }
                }
            }
        }
    }

    /**
     * Drop tokens idle longer than [maxAgeMs], and if still over [MAX_ENTRIES] drop the
     * oldest by last access (skips entries with live proxy FDs).
     */
    fun pruneStale(
        nowMs: Long = SystemClock.elapsedRealtime(),
        maxAgeMs: Long = MAX_AGE_MS,
    ) {
        if (entries.isEmpty()) return
        for ((token, entry) in entries) {
            if (entry.openCount.get() > 0) continue
            if (nowMs - entry.lastAccessMs > maxAgeMs) {
                entries.remove(token, entry)
            }
        }
        if (entries.size <= MAX_ENTRIES) return
        val overflow = entries.size - MAX_ENTRIES
        entries.entries
            .filter { it.value.openCount.get() == 0 }
            .sortedBy { it.value.lastAccessMs }
            .take(overflow)
            .forEach { entries.remove(it.key, it.value) }
    }
}
