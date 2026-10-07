package com.hippo.ehviewer.gallery

import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.image.hdr.isRawStillExtension
import com.hippo.ehviewer.smb.SmbCache
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.webdav.WebDavCache
import java.io.File
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * While reader network cache is off, camera RAW can still be written during preload.
 * Only the newest [KEEP] files stay. Older ones are deleted here, without waiting
 * for the shared origin-cache LRU.
 */
object RawNetworkPageCache {
    const val KEEP = 10

    private val hold = RawPageHold(KEEP) { path ->
        File(path).delete()
        val okio = File(path).toOkioPath()
        SmbCache.markAbsent(okio)
        WebDavCache.markAbsent(okio)
    }

    fun wantsDisk(fileNameOrExt: String?): Boolean {
        if (!Settings.disableReaderNetworkCache.value) return false
        if (!Settings.readerAllowNetworkCacheRaw.value) return false
        // Preview mode already range-reads a small embedded JPEG into RAM.
        if (!Settings.readerCameraRaw.value) return false
        val ext = FileUtils.getExtensionFromFilename(fileNameOrExt) ?: fileNameOrExt
        return isRawStillExtension(ext)
    }

    /** Call from a background thread. [primary] is the page-cache path before UHDR rename. */
    fun note(fileNameOrExt: String?, primary: Path) {
        if (!wantsDisk(fileNameOrExt)) return
        val resolved = HdrConvertCache.resolvePagePath(primary)
        val chosen = when {
            File(resolved.toString()).isFile -> resolved
            File(primary.toString()).isFile -> primary
            else -> return
        }
        hold.remember(chosen.toString())
    }
}

/**
 * Access-ordered set of at most [keep] paths. Remembering one more deletes the oldest.
 */
internal class RawPageHold(
    private val keep: Int,
    private val delete: (String) -> Unit,
) {
    private val recent = LinkedHashMap<String, Unit>(16, 0.75f, true)

    fun remember(path: String) {
        if (path.isEmpty() || keep <= 0) return
        val dropped = ArrayList<String>()
        synchronized(recent) {
            recent[path] = Unit
            while (recent.size > keep) {
                val eldest = recent.entries.iterator().next().key
                recent.remove(eldest)
                dropped += eldest
            }
        }
        dropped.forEach(delete)
    }
}
