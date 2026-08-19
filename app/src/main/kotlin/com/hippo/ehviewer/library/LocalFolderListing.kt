package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath

/**
 * Local folder browse listing aligned with SMB/WebDAV lazy scan:
 * full child peeks + ≤[SMB_PROMOTE_MAX_LEAVES] grand peeks via
 * [classifyRemoteListingWithPeeks], optional disk index + slim quick scan.
 */
object LocalFolderListing {
    private val peekPool = Executors.newFixedThreadPool(8) { r ->
        Thread(r, "local-browse-peek-${peekThreadSeq.getAndIncrement()}").apply { isDaemon = true }
    }
    private val peekThreadSeq = AtomicInteger(0)

    data class SlimRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
    )

    /**
     * RAM / sync path used by sibling navigation and callers that only need a listing.
     * Prefer [listDirectory] from the folder UI when index cache + quick scan matter.
     */
    fun listDirectorySync(
        dir: Path,
        useCache: Boolean = true,
        preferMediaStore: Boolean = true,
    ): List<BrowseEntry> {
        val effective = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
        val key = BrowseSession.pathKey(effective)
        if (useCache) {
            BrowseSession.getLocalListing(key)?.let { return it }
        }
        val remote = listDirectoryUncachedRemote(effective, preferMediaStore)
        BrowseSession.putLocalListing(key, remote, sessionCurrent = true)
        return materializeLocalEntries(effective, remote)
    }

    /**
     * Folder-browser path: session + disk index, with optional slim quick scan on stale hits.
     *
     * @param rootId library/folder root id (disk index source id)
     * @param rootPath absolute root path (config key + materialize base for relativeDir="")
     * @param relativeDir path under the root (same idea as SMB relativeDir)
     * @param listedPath absolute path of the directory being listed (usually root/relativeDir)
     */
    suspend fun listDirectory(
        rootId: Long,
        rootPath: Path,
        relativeDir: String,
        listedPath: Path,
        preferMediaStore: Boolean = true,
        useCache: Boolean = true,
        onCached: ((List<BrowseEntry>) -> Unit)? = null,
    ): List<BrowseEntry> = withContext(Dispatchers.IO) {
        val effective = resolveBrowsePath(listedPath, preferMediaStore = preferMediaStore)
        val pathKey = BrowseSession.pathKey(effective)
        val configKey = rootConfigKey(rootPath, preferMediaStore)

        if (useCache) {
            val cached = BrowseSession.getLocalCachedListing(pathKey)
                ?: NetworkFolderIndexCache.loadLocal(rootId, configKey, relativeDir)?.let { entries ->
                    BrowseSession.putLocalListing(pathKey, entries, sessionCurrent = false)
                    BrowseSession.CachedLocalListing(entries = entries, sessionCurrent = false)
                }
            if (cached != null) {
                val materialized = materializeLocalEntries(effective, cached.entries)
                onCached?.invoke(materialized)
                val shouldQuickScan =
                    Settings.networkFolderIndexQuickScan.value && !cached.sessionCurrent
                if (!shouldQuickScan) return@withContext materialized
                return@withContext try {
                    val refresh = listDirectorySlim(effective, preferMediaStore, cached.entries)
                    BrowseSession.putLocalListing(
                        pathKey,
                        refresh.entries,
                        sessionCurrent = true,
                    )
                    if (refresh.entries != cached.entries ||
                        refresh.removedDirectoryNames.isNotEmpty()
                    ) {
                        NetworkFolderIndexCache.saveLocal(
                            rootId,
                            configKey,
                            relativeDir,
                            refresh.entries,
                            refresh.removedDirectoryNames,
                        )
                    }
                    materializeLocalEntries(effective, refresh.entries)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat("FolderIndex") {
                        "Local slim refresh failed for root=$rootId dir=$relativeDir " +
                            "(${e.message}); keeping cache"
                    }
                    materialized
                }
            }
        } else {
            BrowseSession.invalidateLocalListing(pathKey)
        }

        BrowseSession.getLocalListing(pathKey)?.let { return@withContext it }
        val remote = listDirectoryUncachedRemote(effective, preferMediaStore)
        BrowseSession.putLocalListing(pathKey, remote, sessionCurrent = true)
        NetworkFolderIndexCache.saveLocal(rootId, configKey, relativeDir, remote)
        materializeLocalEntries(effective, remote)
    }

    fun listDirectoryUncachedRemote(
        dir: Path,
        preferMediaStore: Boolean,
    ): List<BrowseEntryRemote> {
        val children = listChildrenRemote(dir, preferMediaStore)
            .filterNot { isProtectedSystemName(it.name) }
        return classifyDirectoryChildren(dir, preferMediaStore, children)
    }

    fun listDirectorySlim(
        dir: Path,
        preferMediaStore: Boolean,
        cached: List<BrowseEntryRemote>,
    ): SlimRefresh {
        val children = listChildrenRemote(dir, preferMediaStore)
            .filterNot { isProtectedSystemName(it.name) }
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        val deepHidden = if (Settings.browseShowHiddenFiles.value) {
            hiddenDirectoriesNeedingDeepScan(cached, children)
        } else {
            emptyList()
        }
        val deepNames = deepHidden.mapTo(HashSet()) { it.name }
        val toClassify = (plan.addedDirectories + deepHidden).distinctBy { it.name }
        if (plan.isUnchanged && deepHidden.isEmpty()) {
            return SlimRefresh(cached, emptySet())
        }
        // Drop shallow hidden shells (via removedDirectoryNames) then re-add full classify.
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = plan.removedDirectoryNames + deepNames,
        )
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(dir, preferMediaStore, toClassify)
        }
        var merged = mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries)
        merged = replaceDirectFilesFromLive(merged, children, dir.name)
        return SlimRefresh(
            entries = merged,
            removedDirectoryNames = plan.removedDirectoryNames,
        )
    }

    /**
     * After folder add/remove merge, refresh direct (non-promoted) files/galleries from the
     * live parent listing so slim picks up new loose images/archives/videos without a full
     * re-peek of existing folders.
     */
    private fun replaceDirectFilesFromLive(
        merged: List<BrowseEntryRemote>,
        liveChildren: List<RemoteChild>,
        currentDirName: String,
    ): List<BrowseEntryRemote> {
        val liveDirect = classifyRemoteListingWithPeeks(
            currentDirName = currentDirName.ifEmpty { "Gallery" },
            entries = liveChildren.filter { !it.isDirectory },
            childPeeks = emptyMap(),
            grandPeeks = emptyMap(),
        )
        val keptFolders = merged.filter {
            when (it) {
                is BrowseEntryRemote.Directory -> true
                is BrowseEntryRemote.FolderGallery -> {
                    val rel = it.relativeName.replace('\\', '/').trim('/')
                    // Keep promoted/child galleries; replace synthetic "" current-dir gallery.
                    rel.isNotEmpty()
                }
                is BrowseEntryRemote.ArchiveGallery -> it.parentRelativeName.isNotEmpty()
                is BrowseEntryRemote.VideoFile -> '/' in it.fileName.replace('\\', '/')
                is BrowseEntryRemote.RegularFile -> '/' in it.fileName.replace('\\', '/')
            }
        }
        return buildList(keptFolders.size + liveDirect.size) {
            addAll(keptFolders.filterIsInstance<BrowseEntryRemote.Directory>())
            addAll(keptFolders.filterIsInstance<BrowseEntryRemote.FolderGallery>())
            addAll(liveDirect.filterIsInstance<BrowseEntryRemote.FolderGallery>())
            addAll(keptFolders.filterIsInstance<BrowseEntryRemote.ArchiveGallery>())
            addAll(liveDirect.filterIsInstance<BrowseEntryRemote.ArchiveGallery>())
            addAll(keptFolders.filterIsInstance<BrowseEntryRemote.VideoFile>())
            addAll(liveDirect.filterIsInstance<BrowseEntryRemote.VideoFile>())
            addAll(keptFolders.filterIsInstance<BrowseEntryRemote.RegularFile>())
            addAll(liveDirect.filterIsInstance<BrowseEntryRemote.RegularFile>())
        }
    }

    private fun classifyDirectoryChildren(
        dir: Path,
        preferMediaStore: Boolean,
        children: List<RemoteChild>,
    ): List<BrowseEntryRemote> {
        val deepScanHidden = Settings.browseShowHiddenFiles.value
        // Skip deep peek into hidden (dot / .nomedia) dirs unless Hidden files is on.
        val dirsToPeek = children.filter { c ->
            c.isDirectory &&
                !isProtectedSystemName(c.name) &&
                (deepScanHidden || !c.hidden)
        }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        if (dirsToPeek.isNotEmpty()) {
            runParallel(dirsToPeek) { c ->
                peeks[c.name] = listChildrenRemote(dir / c.name, preferMediaStore)
            }
        }

        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>()
        for ((subName, peek) in peeks) {
            val leaves = peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
            if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                for (leaf in leaves) {
                    leavesToPeek += subName to leaf.name
                }
            } else if (leaves.isNotEmpty()) {
                leavesToPeek += subName to leaves.first().name
            }
        }
        if (leavesToPeek.isNotEmpty()) {
            runParallel(leavesToPeek) { (subName, leafName) ->
                val leafRel = "$subName/$leafName"
                grandPeeks[leafRel] = listChildrenRemote(dir / subName / leafName, preferMediaStore)
            }
        }

        val dirName = humanizePathName(dir.name).ifEmpty { "Gallery" }
        // Re-tag with peek-based .nomedia detection after child peeks exist.
        val tagged = children.withHiddenFlags(peeks)
        return classifyRemoteListingWithPeeks(dirName, tagged, peeks, grandPeeks)
    }

    private fun <T> runParallel(items: List<T>, block: (T) -> Unit) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            block(items[0])
            return
        }
        val futures = items.map { item ->
            peekPool.submit(Callable { block(item) })
        }
        futures.forEach { it.get() }
    }

    private fun listChildrenRemote(dir: Path, preferMediaStore: Boolean): List<RemoteChild> {
        val path = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
        // listBrowseChildren applies .nomedia directory tagging.
        return path.listBrowseChildren().map { child ->
            RemoteChild(
                name = child.name,
                isDirectory = child.isDirectory,
                path = child.name,
                size = child.size,
                lastModifiedMs = child.lastModifiedMs,
                hidden = child.hidden,
                readOnly = child.readOnly,
            )
        }
    }

    private fun rootConfigKey(rootPath: Path, preferMediaStore: Boolean): String {
        val effective = resolveBrowsePath(rootPath, preferMediaStore = preferMediaStore)
        return "local|${effective}|ms=$preferMediaStore"
    }
}

/** Join relative segments onto [base] (accepts `/` or `\`). */
fun Path.resolveRelative(relative: String): Path {
    var p = this
    for (seg in relative.replace('\\', '/').trim('/').split('/')) {
        if (seg.isNotEmpty()) p /= seg
    }
    return p
}

/**
 * Turn classifier output (relative names) into Path-based [BrowseEntry] rows for the UI.
 */
fun materializeLocalEntries(
    baseDir: Path,
    remote: List<BrowseEntryRemote>,
): List<BrowseEntry> = remote.map { entry ->
    when (entry) {
        is BrowseEntryRemote.Directory -> {
            val path = if (entry.relativeName.isEmpty()) {
                baseDir
            } else {
                baseDir.resolveRelative(entry.relativeName)
            }
            val cover = entry.coverFileName?.let { path.resolveRelative(it) }
            BrowseEntry.Directory(
                name = entry.name,
                path = path,
                hasVideo = entry.hasVideo,
                hasGallery = entry.hasGallery,
                presence = entry.presence,
                coverPath = cover,
                hidden = entry.hidden,
            )
        }
        is BrowseEntryRemote.FolderGallery -> {
            val path = if (entry.relativeName.isEmpty()) {
                baseDir
            } else {
                baseDir.resolveRelative(entry.relativeName)
            }
            val cover = entry.coverFileName?.let { path.resolveRelative(it) }
            BrowseEntry.FolderGallery(
                name = entry.name,
                path = path,
                pageCount = entry.pageCount,
                pageCountCapped = entry.pageCountCapped,
                coverPath = cover,
                hidden = entry.hidden,
            )
        }
        is BrowseEntryRemote.ArchiveGallery -> {
            val parent = if (entry.parentRelativeName.isEmpty()) {
                baseDir
            } else {
                baseDir.resolveRelative(entry.parentRelativeName)
            }
            val path = parent / entry.fileName
            if (EmptyArchiveRegistry.isMarked(path.toString())) {
                BrowseEntry.RegularFile(name = entry.name, path = path, hidden = entry.hidden)
            } else {
                BrowseEntry.ArchiveGallery(name = entry.name, path = path, hidden = entry.hidden)
            }
        }
        is BrowseEntryRemote.VideoFile ->
            BrowseEntry.VideoFile(
                name = entry.name,
                path = baseDir.resolveRelative(entry.fileName),
                hidden = entry.hidden,
            )
        is BrowseEntryRemote.RegularFile ->
            BrowseEntry.RegularFile(
                name = entry.name,
                path = baseDir.resolveRelative(entry.fileName),
                hidden = entry.hidden,
            )
    }
}
