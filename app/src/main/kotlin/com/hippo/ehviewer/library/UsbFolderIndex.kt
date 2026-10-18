package com.hippo.ehviewer.library

import com.ehviewer.core.files.mediaStoreVirtualPath
import java.io.File

/**
 * Folder-index identity for a mounted USB filesystem.
 *
 * Browse listings are stored as `local_{sourceId}`. A library root's Room id
 * changes if the row is recreated, and a USB volume is not a Room row at all.
 * [StorageVolume.getUuid] (filesystem id: FAT `XXXX-XXXX` or a GUID) is stable
 * across replug. [StorageVolume.getStorageUuid] is null for portable sticks
 * that are not adopted, so it is not the cache key.
 *
 * The filesystem id is the cache key. Two partitions on one stick do not share
 * a listing, and a reformat should not reuse the old listing. A serial is only
 * a fallback when the volume has no filesystem id. Discovery does not read sysfs.
 */
object UsbFolderIndex {
    fun folderIndexIdentity(fsUuid: String?, deviceSerial: String?): String? {
        val fs = fsUuid?.trim()?.takeIf { it.isNotEmpty() }
        if (fs != null) return fs.lowercase()
        val serial = deviceSerial?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "usb-serial:$serial"
    }

    /** Negative so it cannot collide with a Room autoincrement root id. */
    fun cacheSourceId(identity: String): Long {
        var hash = FNV_OFFSET
        for (c in identity) {
            hash = hash xor c.code.toLong()
            hash *= FNV_PRIME
        }
        val positive = (hash and Long.MAX_VALUE).let { if (it == 0L) 1L else it }
        return -positive
    }

    /**
     * When [configKey] is exactly a mounted USB volume root, listings share
     * [UsbIndexVolume.cacheSourceId]. A subfolder root keeps its own id:
     * relative paths would mean different folders.
     */
    fun aliasSourceId(rootId: Long, configKey: String, volumes: List<UsbIndexVolume>): Long {
        val path = configKeyRootPath(configKey) ?: return rootId
        val hit = volumes.firstOrNull { isVolumeRootPath(path, it.directory, it.fsUuid) }
            ?: return rootId
        return hit.cacheSourceId
    }

    fun configKeyRootPath(configKey: String): String? {
        if (!configKey.startsWith("local|")) return null
        val rest = configKey.removePrefix("local|")
        val ms = rest.lastIndexOf("|ms=")
        if (ms <= 0) return null
        return rest.substring(0, ms)
    }

    fun isVolumeRootPath(rootPath: String, directory: String, fsUuid: String): Boolean {
        val path = rootPath.trim().trimEnd('/')
        val dir = directory.trim().trimEnd('/')
        if (dir.isNotEmpty() && path.equals(dir, ignoreCase = true)) return true
        val uuid = fsUuid.trim()
        if (uuid.isEmpty()) return false
        if (path.equals(mediaStoreVirtualPath(uuid.lowercase(), ""), ignoreCase = true)) return true
        val suffixes = arrayOf(
            "/$uuid:",
            "/$uuid%3A",
            "/$uuid%3a",
            "/document/$uuid:",
            "/document/$uuid%3A",
            "/document/$uuid%3a",
        )
        return suffixes.any { path.endsWith(it, ignoreCase = true) }
    }

    fun matchesBrowseFrame(cacheSourceId: Long, directory: String, fsUuid: String, rootId: Long, path: String): Boolean {
        if (rootId == cacheSourceId) return true
        return isVolumeRootPath(path, directory, fsUuid)
    }

    /** Rename [from] onto [to] only when the guid directory is not already there. */
    fun moveIndexDirIfAbsent(from: File, to: File): Boolean {
        if (from == to) return false
        if (to.exists()) return false
        if (!from.isDirectory) return false
        to.parentFile?.mkdirs()
        return from.renameTo(to)
    }

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L
}

data class UsbIndexVolume(
    val fsUuid: String,
    val directory: String,
    val cacheSourceId: Long,
)
