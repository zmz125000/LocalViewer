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

    /** Keeps token expiry accurate even when no later registration calls [pruneStale]. */
    private val pruneLock = Any()
    private var pruneJob: Job? = null

    /** Live network proxy FDs across all tokens (for keep-alive re-promote after FGS timeout). */
    fun networkOpenCount(): Int = synchronized(keepAliveLock) { totalNetworkOpen }

    /** Streamdoc tokens still registered (may be idle grants). */
    fun tokenCount(): Int = entries.size

    /** Network streamdoc grants (idle or open). Local/SAF hand-throughs do not hold FGS. */
    fun networkTokenCount(): Int = entries.values.count { it.openSource != null }

    /**
     * Drop all tokens and FGS stop timers. Process exit / Recents swipe only —
     * external viewers will get grant failures on the next open.
     */
    fun clearAll(reason: String = "clear") {
        synchronized(keepAliveLock) {
            totalNetworkOpen = 0
            entries.clear()
        }
        synchronized(pruneLock) {
            pruneJob?.cancel()
            pruneJob = null
        }
    }

    /** Soft cap so repeated long-press PDF does not retain unbounded lambdas. */
    private const val MAX_ENTRIES = 24

    // Idle token age: see [StreamKeepAlivePolicy] (20 min limited / never unlimited).

    fun register(
        displayName: String,
        mimeType: String = "application/pdf",
        sizeBytes: Long = -1L,
        openSource: () -> ArchiveByteSource,
    ): String {
        pruneStale()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            openSource = openSource,
        )
        schedulePrune()
        StreamKeepAlivePolicy.reconcileFgs()
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
        schedulePrune()
        return token
    }

    fun get(token: String): Entry? {
        val entry = entries[token] ?: return null
        entry.lastAccessMs = SystemClock.elapsedRealtime()
        schedulePrune()
        return entry
    }

    /** Optional: in-app video backend evicts when its token is dropped. */
    @Volatile
    var onTokenRemoved: ((String) -> Unit)? = null

    fun remove(token: String) {
        val removed = synchronized(keepAliveLock) {
            entries.remove(token)?.also { entry ->
                totalNetworkOpen = (totalNetworkOpen - entry.openCount.getAndSet(0)).coerceAtLeast(0)
            }
        }
        if (removed != null) onTokenRemoved?.invoke(token)
        schedulePrune()
        StreamKeepAlivePolicy.reconcileFgs()
    }

    private fun removeIdle(token: String, entry: Entry): Boolean {
        val removed = synchronized(keepAliveLock) {
            entry.openCount.get() == 0 && entries.remove(token, entry)
        }
        if (removed) onTokenRemoved?.invoke(token)
        return removed
    }

    /** Call when a network proxy FD is successfully opened for [token]. */
    fun retain(token: String, context: Context = appCtx) {
        val entry = synchronized(keepAliveLock) {
            val current = entries[token] ?: return
            current.openCount.incrementAndGet()
            totalNetworkOpen++
            current
        }
        entry.lastAccessMs = SystemClock.elapsedRealtime()
        schedulePrune()
        StreamKeepAlivePolicy.reconcileFgs(context)
    }

    /**
     * Call from proxy [android.os.ProxyFileDescriptorCallback.onRelease].
     * Keeps the token so external players can reopen the same URI (resume / rebuffer).
     * [pruneStale] eventually drops idle grants.
     */
    fun release(token: String, context: Context = appCtx) {
        val entry = synchronized(keepAliveLock) {
            val current = entries[token] ?: return
            if (current.openCount.get() <= 0) return
            current.openCount.decrementAndGet()
            totalNetworkOpen = (totalNetworkOpen - 1).coerceAtLeast(0)
            current
        }
        entry.lastAccessMs = SystemClock.elapsedRealtime()
        StreamKeepAlivePolicy.reconcileFgs(context)
        schedulePrune()
    }

    /**
     * Drop tokens idle longer than [maxAgeMs], and if still over [MAX_ENTRIES] drop the
     * oldest by last access (skips entries with live proxy FDs).
     */
    fun pruneStale(
        nowMs: Long = SystemClock.elapsedRealtime(),
        maxAgeMs: Long? = StreamKeepAlivePolicy.tokenMaxAgeMs(),
    ) {
        if (entries.isEmpty()) return
        if (maxAgeMs != null) {
            for ((token, entry) in entries) {
                if (entry.openCount.get() > 0) continue
                if (nowMs - entry.lastAccessMs >= maxAgeMs) {
                    removeIdle(token, entry)
                }
            }
        }
        if (entries.size > MAX_ENTRIES) {
            val overflow = entries.size - MAX_ENTRIES
            entries.entries
                .filter { it.value.openCount.get() == 0 }
                .sortedBy { it.value.lastAccessMs }
                .take(overflow)
                .forEach { removeIdle(it.key, it.value) }
        }
        StreamKeepAlivePolicy.reconcileFgs()
    }

    /** Arm one timer for the earliest idle token; touches and reopen events rebase it. */
    private fun schedulePrune(nowMs: Long = SystemClock.elapsedRealtime()) {
        val maxAgeMs = StreamKeepAlivePolicy.tokenMaxAgeMs()
        val nextDelayMs = if (maxAgeMs == null) {
            null
        } else {
            entries.values
                .asSequence()
                .filter { it.openCount.get() == 0 }
                .map { maxAgeMs - (nowMs - it.lastAccessMs) }
                .minOrNull()
                ?.coerceAtLeast(1L)
        }
        synchronized(pruneLock) {
            pruneJob?.cancel()
            pruneJob = nextDelayMs?.let { delayMs ->
                scope.launch {
                    delay(delayMs)
                    pruneStale()
                    schedulePrune()
                }
            }
        }
    }
}
