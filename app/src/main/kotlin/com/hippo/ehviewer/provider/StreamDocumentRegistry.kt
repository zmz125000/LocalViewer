package com.hippo.ehviewer.provider

import com.hippo.ehviewer.library.ArchiveByteSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tokens for [StreamDocumentProvider] URIs.
 *
 * External apps receive a short-lived `content://…/streamdoc/{token}` grant; the
 * provider opens [ArchiveByteSource] only when they read, so SMB/WebDAV PDFs can
 * stream via range I/O instead of a full download.
 */
object StreamDocumentRegistry {
    data class Entry(
        val displayName: String,
        val mimeType: String,
        /** Known size when probed at register time; -1 if unknown. Helps viewers avoid over-read. */
        val sizeBytes: Long = -1L,
        val openSource: () -> ArchiveByteSource,
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

    fun get(token: String): Entry? = entries[token]

    fun remove(token: String) {
        entries.remove(token)
    }
}
