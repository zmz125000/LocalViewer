package com.hippo.ehviewer.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.library.BrowseEntry
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.isImageFileName
import com.hippo.ehviewer.library.isVideoFileName
import com.hippo.ehviewer.library.joinRemoteArchivePath
import com.hippo.ehviewer.library.libraryImageFileId
import com.hippo.ehviewer.library.libraryVideoFileId
import com.hippo.ehviewer.library.libraryVideoFolderId
import com.hippo.ehviewer.library.naturalCompare
import com.hippo.ehviewer.library.stableGalleryId

/**
 * Folder currently listed, for matching HISTORY gids of its direct items.
 * [zipInnerRel] null means a real directory; non-null (including "") is zip-as-dir browse.
 */
data class LocalBrowseRecentContext(
    val rootId: Long,
    val relativePath: String,
    val zipInnerRel: String?,
    val zipAsDir: Boolean,
)

/** Items in [entries] that have a history time, newest first. Does not remove them from [entries]. */
fun <T> recentBrowseEntries(
    entries: List<T>,
    historyTimeByGid: Map<Long, Long>,
    gidsOf: (T) -> List<Long>,
    nameOf: (T) -> String,
): List<T> {
    if (entries.isEmpty() || historyTimeByGid.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        val time = gidsOf(entry).maxOfOrNull { historyTimeByGid[it] ?: 0L } ?: 0L
        if (time > 0L) entry to time else null
    }.sortedWith { a, b ->
        val byTime = b.second.compareTo(a.second)
        if (byTime != 0) byTime else naturalCompare(nameOf(a.first), nameOf(b.first))
    }.map { it.first }
}

fun localBrowseHistoryGids(entry: BrowseEntry, ctx: LocalBrowseRecentContext): List<Long> {
    val rootId = ctx.rootId
    return when (entry) {
        is BrowseEntry.Directory -> localDirectoryHistoryGids(entry, ctx)
        is BrowseEntry.FolderGallery -> localFolderGalleryHistoryGids(entry, ctx)
        is BrowseEntry.ArchiveGallery -> {
            val abs = entry.path.toString()
            val rel = localChildRel(ctx, entry.name)
            listOf(
                stableGalleryId(rootId, rel.ifEmpty { entry.name }),
                stableGalleryId(0L, "local-archive:$abs"),
                stableGalleryId(0L, "local-file:$abs"),
            )
        }
        is BrowseEntry.VideoFile -> localMediaFileHistoryGids(ctx, entry.path.toString(), video = true)
        is BrowseEntry.RegularFile ->
            localMediaFileHistoryGids(ctx, entry.path.toString(), video = isVideoFileName(entry.name))
    }.distinct()
}

fun remoteBrowseHistoryGids(
    entry: BrowseEntryRemote,
    sourceId: Long,
    relativeDir: String,
    smb: Boolean,
): List<Long> {
    val browse = if (smb) "smb-browse:" else "webdav-browse:"
    val gallery = if (smb) "smb:" else "webdav:"
    val archive = if (smb) "smba:" else "dava:"
    val file = if (smb) "smbf:" else "davf:"
    fun gid(key: String) = stableGalleryId(sourceId, key)
    fun rel(child: String) = browseHistoryRel(joinBrowseRel(relativeDir, child))
    return when (entry) {
        is BrowseEntryRemote.Directory -> {
            val r = rel(entry.relativeName.ifEmpty { entry.name })
            listOf(gid(browse + r), gid(gallery + r))
        }
        is BrowseEntryRemote.FolderGallery -> {
            val r = if (entry.relativeName.isEmpty()) {
                browseHistoryRel(relativeDir)
            } else {
                rel(entry.relativeName)
            }
            listOf(gid(gallery + r), gid(browse + r))
        }
        is BrowseEntryRemote.ArchiveGallery -> {
            val r = browseHistoryRel(
                joinRemoteArchivePath(relativeDir, entry.parentRelativeName, entry.fileName),
            )
            listOf(gid(archive + r), gid(file + r))
        }
        is BrowseEntryRemote.VideoFile -> listOf(gid(file + rel(entry.fileName)))
        is BrowseEntryRemote.RegularFile -> listOf(gid(file + rel(entry.fileName)))
    }
}

/**
 * History times, or null until the first read finishes.
 * Folder lists wait on this so the Recent section does not insert after scroll.
 */
@Composable
fun rememberHistoryTimeByGid(): Map<Long, Long>? {
    val times by produceState<Map<Long, Long>?>(initialValue = null) {
        EhDB.historyTimeListFlow.collect { rows ->
            value = rows.associate { it.gid to it.time }
        }
    }
    return times
}

private fun localDirectoryHistoryGids(
    entry: BrowseEntry.Directory,
    ctx: LocalBrowseRecentContext,
): List<Long> {
    val child = entry.relativeName.ifEmpty { entry.name }
    if (ctx.zipInnerRel != null) {
        val inner = ZipAsDirListing.joinPrefix(ctx.zipInnerRel, child)
        val browseRel = browseHistoryRel(ZipAsDirListing.virtualRelativeDir(ctx.relativePath, inner))
        val galleryRel = ZipAsDirListing.historyGalleryRelative(ctx.relativePath, inner)
        return listOf(
            stableGalleryId(ctx.rootId, "browse:$browseRel"),
            stableGalleryId(ctx.rootId, "zip:$galleryRel"),
            stableGalleryId(ctx.rootId, browseRel.ifEmpty { "." }),
        )
    }
    val split = if (ctx.zipAsDir) ZipAsDirListing.splitZipBrowsePath(child) else null
    if (split != null) {
        val (zipRel, inner) = split
        val fullZip = browseHistoryRel(joinBrowseRel(ctx.relativePath, zipRel))
        val browseRel = browseHistoryRel(ZipAsDirListing.virtualRelativeDir(fullZip, inner))
        val galleryRel = ZipAsDirListing.historyGalleryRelative(fullZip, inner)
        return listOf(
            stableGalleryId(ctx.rootId, "browse:$browseRel"),
            stableGalleryId(ctx.rootId, "zip:$galleryRel"),
        )
    }
    val rel = browseHistoryRel(joinBrowseRel(ctx.relativePath, child))
    val folderKey = rel.ifEmpty { "." }
    return listOf(
        stableGalleryId(ctx.rootId, "browse:$rel"),
        stableGalleryId(ctx.rootId, folderKey),
        libraryVideoFolderId(ctx.rootId, folderKey),
    )
}

private fun localFolderGalleryHistoryGids(
    entry: BrowseEntry.FolderGallery,
    ctx: LocalBrowseRecentContext,
): List<Long> {
    if (ctx.zipInnerRel != null) {
        val inner = entry.relativeName.replace('\\', '/').trim('/')
        val histRel = ZipAsDirListing.historyGalleryRelative(ctx.relativePath, inner)
        val browseRel = browseHistoryRel(ZipAsDirListing.virtualRelativeDir(ctx.relativePath, inner))
        return listOf(
            stableGalleryId(ctx.rootId, "zip:$histRel"),
            stableGalleryId(ctx.rootId, "browse:$browseRel"),
            LocalHistory.folderGalleryGid(ctx.rootId, histRel),
        )
    }
    val child = entry.relativeName.replace('\\', '/').trim('/')
    val rel = browseHistoryRel(joinBrowseRel(ctx.relativePath, child))
    val gids = mutableListOf(
        stableGalleryId(ctx.rootId, rel.ifEmpty { "." }),
        stableGalleryId(ctx.rootId, "browse:$rel"),
        libraryVideoFolderId(ctx.rootId, rel.ifEmpty { "." }),
    )
    if (ctx.zipAsDir) {
        val zipSeg = ZipAsDirListing.zipFileSegment(entry.relativeName, entry.path.name)
        if (zipSeg != null) {
            val inner = ZipAsDirListing.zipInnerPrefix(entry.relativeName)
            val zipRel = browseHistoryRel(joinBrowseRel(ctx.relativePath, zipSeg))
            val histRel = ZipAsDirListing.historyGalleryRelative(zipRel, inner)
            gids += stableGalleryId(ctx.rootId, "zip:$histRel")
            gids += LocalHistory.folderGalleryGid(ctx.rootId, histRel)
        }
    }
    return gids
}

private fun localMediaFileHistoryGids(
    ctx: LocalBrowseRecentContext,
    absolutePath: String,
    video: Boolean,
): List<Long> {
    val name = absolutePath.replace('\\', '/').trimEnd('/').substringAfterLast('/')
    val rel = localChildRel(ctx, name)
    return buildList {
        add(stableGalleryId(0L, "local-file:$absolutePath"))
        if (video || isVideoFileName(name)) add(libraryVideoFileId(ctx.rootId, rel))
        if (isImageFileName(name)) add(libraryImageFileId(ctx.rootId, rel))
    }
}

private fun localChildRel(ctx: LocalBrowseRecentContext, child: String): String {
    if (ctx.zipInnerRel != null) {
        val inner = ZipAsDirListing.joinPrefix(ctx.zipInnerRel, child)
        return browseHistoryRel(ZipAsDirListing.virtualRelativeDir(ctx.relativePath, inner))
    }
    return browseHistoryRel(joinBrowseRel(ctx.relativePath, child))
}

private fun joinBrowseRel(parent: String, child: String): String {
    val p = parent.replace('\\', '/').trim('/')
    val c = child.replace('\\', '/').trim('/')
    return when {
        c.isEmpty() -> p
        p.isEmpty() -> c
        else -> "$p/$c"
    }
}

/** Same trim as [LocalHistory] browse relative paths (`""` / `"."` → empty). */
private fun browseHistoryRel(path: String): String = path.replace('\\', '/').trim('/').let { if (it == "." || it.isEmpty()) "" else it }
