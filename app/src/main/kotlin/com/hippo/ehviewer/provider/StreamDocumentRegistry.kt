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
        val openSource: () -> ArchiveByteSource,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(
        displayName: String,
        mimeType: String = "application/pdf",
        openSource: () -> ArchiveByteSource,
    ): String {
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(displayName = displayName, mimeType = mimeType, openSource = openSource)
        return token
    }

    fun get(token: String): Entry? = entries[token]

    fun remove(token: String) {
        entries.remove(token)
    }
}
