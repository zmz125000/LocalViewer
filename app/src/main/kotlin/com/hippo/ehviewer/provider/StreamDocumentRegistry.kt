package com.hippo.ehviewer.provider

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory tokens for [StreamDocumentProvider] URIs.
 *
 * External apps receive a short-lived `content://…/streamdoc/{token}` grant.
 *
 * - **Network** ([register]): provider opens [ArchiveByteSource] on demand (SMB/WebDAV
 *   range I/O + block cache via proxy FD). [retain]/[release] track live proxy FDs;
 *   token is removed when the last FD is released.
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

    /** Soft cap so repeated long-press PDF does not retain unbounded lambdas. */
    private const val MAX_ENTRIES = 24

    /** Local direct tokens have no close hook; age them out after one hour idle. */
    private const val MAX_AGE_MS = 60L * 60L * 1000L

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
    fun retain(token: String) {
        entries[token]?.let {
            it.openCount.incrementAndGet()
            it.lastAccessMs = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Call from proxy [android.os.ProxyFileDescriptorCallback.onRelease].
     * Removes the token when no proxy FDs remain.
     */
    fun release(token: String) {
        val entry = entries[token] ?: return
        val left = entry.openCount.decrementAndGet()
        if (left <= 0) {
            entries.remove(token, entry)
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
