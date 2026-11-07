package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.database.model.WebDavSourceEntity
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.webdav.WebDavRepository
import java.net.URI
import java.util.Locale

internal data class UploadPathIdentity(val kind: ExplorerWindows.Kind, val endpoint: String, val path: String) {
    fun contains(other: UploadPathIdentity): Boolean = kind == other.kind && endpoint == other.endpoint &&
        (path == other.path || path.isEmpty() || other.path.startsWith("$path/"))
}

internal fun smbUploadIdentity(source: SmbSourceEntity, path: String): UploadPathIdentity {
    val fixedShare = source.share.trim().trim('/')
    val full = if (fixedShare.isEmpty()) path else listOf(fixedShare, source.pathPrefix, path).joinToString("/")
    return UploadPathIdentity(
        ExplorerWindows.Kind.Smb,
        "${source.host.trim().lowercase(Locale.ROOT)}:${source.port}",
        full.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/").lowercase(Locale.ROOT),
    )
}

internal fun webDavUploadIdentity(source: WebDavSourceEntity, path: String): UploadPathIdentity {
    val base = URI(source.baseUrl.trim().let { if ("://" in it) it else "https://$it" })
    val scheme = base.scheme.lowercase(Locale.ROOT)
    val port = base.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
    val full = listOf(base.path.orEmpty(), source.pathPrefix, path).joinToString("/")
        .replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")
    return UploadPathIdentity(ExplorerWindows.Kind.WebDav, "$scheme://${base.host.lowercase(Locale.ROOT)}:$port", full)
}

/** Check the whole batch before mkdir/write, including folder copies into their own subtree. */
internal suspend fun uploadDestinationOverlapsSource(
    roots: List<TransferFile>,
    destination: TransferLocation,
    identity: suspend (TransferLocation) -> UploadPathIdentity,
): Boolean {
    val parent = identity(destination)
    for (root in roots) {
        val source = identity(root.source)
        if (source == identity(destination.child(root.relative))) return true
        if (root.directory && source.contains(parent)) return true
    }
    return false
}

internal suspend fun uploadDestinationOverlapsSource(roots: List<TransferFile>, destination: TransferLocation): Boolean {
    val smb = HashMap<Long, SmbSourceEntity>()
    val dav = HashMap<Long, WebDavSourceEntity>()
    suspend fun identity(location: TransferLocation): UploadPathIdentity = when (location.kind) {
        ExplorerWindows.Kind.Local -> UploadPathIdentity(location.kind, "local", BrowseMutationRefresh.canonical(location).path)
        ExplorerWindows.Kind.Smb -> smbUploadIdentity(smb.getOrPut(location.sourceId) { requireNotNull(SmbRepository.load(location.sourceId)) { "SMB source missing" } }, location.path)
        ExplorerWindows.Kind.WebDav -> webDavUploadIdentity(dav.getOrPut(location.sourceId) { requireNotNull(WebDavRepository.load(location.sourceId)) { "WebDAV source missing" } }, location.path)
    }
    if (uploadDestinationOverlapsSource(roots, destination, ::identity)) return true
    // Providers with opaque IDs need the existing document identity, not parent/name.
    if (destination.kind == ExplorerWindows.Kind.Local && destination.path.startsWith("content:") &&
        roots.any { it.source.kind == ExplorerWindows.Kind.Local && "com.android.externalstorage.documents" !in it.source.path }
    ) {
        val existing = TransferFiles.children(destination).associateBy { it.relative }
        for (root in roots) {
            val target = existing[root.relative] ?: continue
            if (identity(root.source) == identity(target.source)) return true
        }
    }
    return false
}
