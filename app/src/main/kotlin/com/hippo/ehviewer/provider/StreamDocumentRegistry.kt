package com.hippo.ehviewer.provider

import android.os.ParcelFileDescriptor
import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tokens for [StreamDocumentProvider] URIs.
 *
 * External apps receive a short-lived `content://…/streamdoc/{token}` grant.
 *
 * - **Network** ([register]): provider opens [ArchiveByteSource] on demand (SMB/WebDAV
 *   range I/O + block cache via proxy FD).
 * - **Local/SAF** ([registerDirect]): provider returns a real seekable
 *   [ParcelFileDescriptor] (no FUSE proxy) — same kernel path as a file manager.
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
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(
        displayName: String,
        mimeType: String = "application/pdf",
        sizeBytes: Long = -1L,
        openSource: () -> ArchiveByteSource,
    ): String {
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
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            openFileDescriptor = openFileDescriptor,
        )
        return token
    }

    fun get(token: String): Entry? = entries[token]

    fun remove(token: String) {
        entries.remove(token)
    }
}
