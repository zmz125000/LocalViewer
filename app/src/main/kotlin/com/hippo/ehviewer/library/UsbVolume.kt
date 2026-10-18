package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.LibraryRootEntity
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A mounted removable volume. [uuid] is the filesystem id Android puts in the
 * SAF document id (`ABCD-1234:`), not the bus address from [android.hardware.usb.UsbDevice].
 * The same stick keeps this id across plugs, so the library root id and folder
 * index cache stay on that source.
 */
data class UsbVolume(
    val uuid: String,
    val label: String,
)

/** `primary` / `home` are internal shared storage. Anything else is a public volume id. */
fun externalStorageVolumeId(documentId: String): String? {
    val colon = documentId.indexOf(':')
    if (colon <= 0) return null
    val volume = documentId.substring(0, colon)
    if (volume.equals("primary", ignoreCase = true) || volume.equals("home", ignoreCase = true)) {
        return null
    }
    return volume
}

/** True only for the emulated shared-storage tree (`primary:…`). */
fun isPrimaryExternalDocument(documentId: String): Boolean {
    val colon = documentId.indexOf(':')
    if (colon <= 0) return false
    return documentId.substring(0, colon).equals("primary", ignoreCase = true)
}

/** Document id inside a SAF tree URI, or null when the URI has no `/tree/` segment. */
fun safTreeDocumentId(treeUri: String): String? {
    val marker = "/tree/"
    val start = treeUri.indexOf(marker)
    if (start < 0) return null
    var rest = treeUri.substring(start + marker.length)
    val slash = rest.indexOf('/')
    if (slash >= 0) rest = rest.substring(0, slash)
    val query = rest.indexOf('?')
    if (query >= 0) rest = rest.substring(0, query)
    if (rest.isEmpty()) return null
    return URLDecoder.decode(rest, StandardCharsets.UTF_8)
}

/**
 * Saved root for this volume. Prefer the tree that is the volume root
 * (`UUID:`) over a granted subfolder, so the source id stays the one the
 * folder index was written under.
 */
fun matchUsbRoot(roots: List<LibraryRootEntity>, uuid: String): LibraryRootEntity? {
    var best: LibraryRootEntity? = null
    var bestRank = Int.MAX_VALUE
    for (root in roots) {
        val doc = safTreeDocumentId(root.treeUri) ?: continue
        val volume = externalStorageVolumeId(doc) ?: continue
        if (!volume.equals(uuid, ignoreCase = true)) continue
        val rank = doc.substringAfter(':').length
        if (rank < bestRank) {
            best = root
            bestRank = rank
        }
    }
    return best
}

/**
 * Mounted drives go first in the Library favourites strip. A drive that is
 * already a whole-source favourite is not listed twice.
 */
fun libraryFavoritesWithUsb(
    favorites: List<FavoriteBrowseSource>,
    roots: List<LibraryRootEntity>,
    volumes: List<UsbVolume>,
): List<FavoriteBrowseSource> {
    if (volumes.isEmpty()) return favorites
    val matchedIds = volumes.mapNotNull { matchUsbRoot(roots, it.uuid)?.id }.toSet()
    val rest = favorites.filterNot { fav ->
        fav is FavoriteBrowseSource.Local && fav.root.id in matchedIds
    }
    val head = volumes.map { volume ->
        FavoriteBrowseSource.Usb(volume, matchUsbRoot(roots, volume.uuid))
    }
    return head + rest
}
