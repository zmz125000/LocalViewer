package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

    /** Deep peek/classify budget after shallow paint; keep shallow on expiry. */
    private const val DEEP_CLASSIFY_TIMEOUT_MS = 180_000L

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
        // Not session-current: sync path does not persist to NetworkFolderIndexCache.
        // Leaving current=false lets folder UI listDirectory hydrate/save + quick-scan.
        BrowseSession.putLocalListing(key, remote, sessionCurrent = false)
        return materializeLocalEntries(effective, remote)
    }

    /**
     * List a zip/cbz virtual directory (zip-as-dir). RAM + disk folder index keyed by
     * [ZipAsDirListing.virtualRelativeDir] — same relativeDir as a normal folder.
     *
     * @return classified rows, or null if the zip CD cannot be read (cache miss).
     */
    suspend fun listZipVirtualDirectory(
        rootId: Long,
        rootPath: Path?,
        zipPath: Path,
        zipRel: String,
        inner: String,
        currentDirName: String,
        preferMediaStore: Boolean = true,
        useCache: Boolean = true,
        photoGrid: Boolean = false,
        onCached: ((List<BrowseEntry>) -> Unit)? = null,
    ): List<BrowseEntry>? = withContext(Dispatchers.IO) {
        val zipAbs = zipPath.toString()
        val virtualDir = ZipAsDirListing.virtualRelativeDir(zipRel, inner)
        val ramKey = BrowseSession.localZipListingKey(rootId, virtualDir)
        val configKey = rootPath?.let { rootConfigKey(it, preferMediaStore) }

        fun materialize(remote: List<BrowseEntryRemote>): List<BrowseEntry> {
            if (photoGrid) {
                val names = FolderGalleryIndex.namesFromListing("", remote, "") ?: emptyList()
                return names.map { name ->
                    BrowseEntry.RegularFile(
                        name = name,
                        path = ZipPaths.encodePath(
                            zipAbs,
                            ZipAsDirListing.joinPrefix(inner, name),
                        ),
                    )
                }
            }
            return ZipAsDirListing.materializeLocal(zipAbs, inner, remote)
        }

        if (useCache) {
            val cached = BrowseSession.getLocalCachedListing(ramKey)
                ?: configKey?.let { key ->
                    NetworkFolderIndexCache.loadLocal(rootId, key, virtualDir)?.let { entries ->
                        BrowseSession.putLocalListing(ramKey, entries, sessionCurrent = false)
                        BrowseSession.CachedLocalListing(entries = entries, sessionCurrent = false)
                    }
                }
            if (cached != null) {
                val materialized = materialize(cached.entries)
                onCached?.invoke(materialized)
                return@withContext materialized
            }
        } else {
            BrowseSession.invalidateLocalListing(ramKey)
        }

        val remote = withLocalZipCentralDirectory(zipPath) { cd ->
            ZipAsDirListing.classifyAt(cd, inner, currentDirName)
        } ?: return@withContext null
        val stored = if (configKey != null) {
            NetworkFolderIndexCache.saveLocal(rootId, configKey, virtualDir, remote)
        } else {
            remote
        }
        BrowseSession.putLocalListing(ramKey, stored, sessionCurrent = true)
        val materialized = materialize(stored)
        onCached?.invoke(materialized)
        materialized
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
                // Shallow stubs must upgrade via full peeks, not slim.
                if (isShallowIncompleteListing(cached.entries)) {
                    // Fall through to cold shallow→deep path below (invalidate so we do not
                    // re-hit this branch with the same stub).
                    BrowseSession.invalidateLocalListing(pathKey)
                } else {
                    return@withContext try {
                        val refresh = listDirectorySlim(effective, preferMediaStore, cached.entries)
                        val toKeep = if (refresh.entries != cached.entries ||
                            refresh.removedDirectoryNames.isNotEmpty()
                        ) {
                            NetworkFolderIndexCache.saveLocal(
                                rootId,
                                configKey,
                                relativeDir,
                                refresh.entries,
                                refresh.removedDirectoryNames,
                            )
                        } else {
                            refresh.entries
                        }
                        BrowseSession.putLocalListing(
                            pathKey,
                            toKeep,
                            sessionCurrent = true,
                        )
                        materializeLocalEntries(effective, toKeep)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logcat("FolderIndex") {
                            "Local slim refresh failed for root=$rootId dir=$relativeDir " +
                                "(${e.message}); keeping cache"
                        }
                        materialized
                    }
                }
            }
        } else {
            BrowseSession.invalidateLocalListing(pathKey)
        }

        BrowseSession.getLocalListing(pathKey)?.let { return@withContext it }
        // Cold miss: shallow-first (one list → paint), then deferred peeks.
        val previous = BrowseSession.getLocalCachedListing(pathKey)?.entries
        val t0 = System.nanoTime()
        val children = listChildrenRemote(effective, preferMediaStore)
        val dirName = effective.name.ifEmpty { "Gallery" }
        val shallow = ZipAsDirListing.applyZipAsDirPreferenceLocal(
            classifyChildren(effective, dirName, children, emptyMap(), emptyMap()),
            effective,
        )
        val shallowMerged =
            if (previous != null) preferCompleteFolderGalleries(previous, shallow) else shallow
        // RAM-only until deep succeeds (avoid slim treating Empty shells as final).
        BrowseSession.putLocalListing(pathKey, shallowMerged, sessionCurrent = false)
        val shallowMaterialized = materializeLocalEntries(effective, shallowMerged)
        logcat("FolderIndex") {
            "Local shallow list root=$rootId dir=$relativeDir " +
                "children=${children.size} entries=${shallowMerged.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}"
        }
        onCached?.invoke(shallowMaterialized)

        BrowseSession.getLocalCachedListing(pathKey)?.let { cached ->
            if (cached.sessionCurrent) {
                return@withContext materializeLocalEntries(effective, cached.entries)
            }
        }

        return@withContext try {
            withTimeout(DEEP_CLASSIFY_TIMEOUT_MS) {
                coroutineContext.ensureActive()
                val t1 = System.nanoTime()
                val zipInteriors = ConcurrentHashMap<String, List<BrowseEntryRemote>>()
                val deep = classifyDirectoryChildren(
                    effective,
                    preferMediaStore,
                    children,
                    zipInteriors,
                )
                val fromRam = preferCompleteFolderGalleries(shallowMerged, deep)
                val stored =
                    NetworkFolderIndexCache.saveLocal(rootId, configKey, relativeDir, fromRam)
                BrowseSession.putLocalListing(pathKey, stored, sessionCurrent = true)
                ZipAsDirListing.persistFolderIndexes(
                    parentRelativeDir = relativeDir,
                    interiors = zipInteriors,
                    save = { dir, entries ->
                        NetworkFolderIndexCache.saveLocal(rootId, configKey, dir, entries)
                    },
                    putRam = { dir, entries ->
                        BrowseSession.putLocalListing(
                            BrowseSession.localZipListingKey(rootId, dir),
                            entries,
                            sessionCurrent = true,
                        )
                    },
                )
                logcat("FolderIndex") {
                    "Local deep classify root=$rootId dir=$relativeDir " +
                        "entries=${stored.size} ms=${(System.nanoTime() - t1) / 1_000_000}"
                }
                materializeLocalEntries(effective, stored)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            logcat("FolderIndex") {
                "Local deep classify timed out root=$rootId dir=$relativeDir; keeping shallow"
            }
            shallowMaterialized
        } catch (e: Throwable) {
            logcat("FolderIndex") {
                "Local deep classify failed root=$rootId dir=$relativeDir " +
                    "(${e.message}); keeping shallow"
            }
            shallowMaterialized
        }
    }

    fun listDirectoryUncachedRemote(
        dir: Path,
        preferMediaStore: Boolean,
    ): List<BrowseEntryRemote> {
        val children = listChildrenRemote(dir, preferMediaStore)
        return classifyDirectoryChildren(dir, preferMediaStore, children)
    }

    fun listDirectorySlim(
        dir: Path,
        preferMediaStore: Boolean,
        cached: List<BrowseEntryRemote>,
    ): SlimRefresh {
        val children = listChildrenRemote(dir, preferMediaStore)
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        val zipFileNames = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFileNames(children)
        } else {
            emptySet()
        }
        val deepHidden = if (Settings.browseShowHiddenFiles.value) {
            hiddenDirectoriesNeedingDeepScan(cached, children)
        } else {
            emptyList()
        }
        val deepNames = deepHidden.mapTo(HashSet()) { it.name }
        val cachedZipAsDir = if (zipFileNames.isEmpty()) {
            emptySet()
        } else {
            ZipAsDirListing.cachedDirectZipAsDirNames(cached)
        }
        val newZips = if (zipFileNames.isEmpty()) {
            emptyList()
        } else {
            children.filter { it.name in zipFileNames && it.name !in cachedZipAsDir }
        }
        val toClassify = (plan.addedDirectories + deepHidden + newZips).distinctBy { it.name }
        val dirName = dir.name.ifEmpty { "Gallery" }
        val liveForFiles = if (zipFileNames.isEmpty()) {
            children
        } else {
            children.filterNot { it.name in zipFileNames }
        }
        val zipAdjustedRemoved = plan.removedDirectoryNames - zipFileNames
        val dirsUnchanged = plan.addedDirectories.isEmpty() && zipAdjustedRemoved.isEmpty()
        if (dirsUnchanged && deepHidden.isEmpty() && newZips.isEmpty()) {
            // Dirs same — still patch surviving file size/mtime; add/drop direct files.
            // Zip-as-dir files are excluded so slim does not re-add ArchiveGallery rows.
            return SlimRefresh(
                entries = replaceSlimDirectFilesFromLive(cached, liveForFiles, dirName),
                removedDirectoryNames = emptySet(),
            )
        }
        // Drop shallow hidden shells (via removedDirectoryNames) then re-add full classify.
        // Keep cached zip-as-dir Directory/FolderGallery rows: live listing still has the file.
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = zipAdjustedRemoved + deepNames,
        )
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(dir, preferMediaStore, toClassify)
        }
        val merged = replaceSlimDirectFilesFromLive(
            mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries),
            liveForFiles,
            dirName,
        )
        zipAdjustedRemoved.forEach { name ->
            BrowseSession.invalidateLocalRawChildren(BrowseSession.pathKey(dir / name))
        }
        return SlimRefresh(
            entries = merged,
            removedDirectoryNames = zipAdjustedRemoved,
        )
    }

    private fun classifyDirectoryChildren(
        dir: Path,
        preferMediaStore: Boolean,
        children: List<RemoteChild>,
        zipInteriors: MutableMap<String, List<BrowseEntryRemote>>? = null,
    ): List<BrowseEntryRemote> {
        val deepScanHidden = Settings.browseShowHiddenFiles.value
        // Dot folders: always tag-only (never peek). `.nomedia` dirs peek only when Hidden on.
        val dirsToPeek = children.filter { c ->
            c.isDirectory &&
                !isProtectedSystemName(c.name) &&
                !isDotHiddenName(c.name) &&
                (deepScanHidden || !c.hidden)
        }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        if (dirsToPeek.isNotEmpty()) {
            runParallel(dirsToPeek) { c ->
                peeks[c.name] = listChildrenRemote(
                    dir / c.name,
                    preferMediaStore,
                    includeSafRemainder = !shouldSkipSafPeek(dir / c.name, preferMediaStore),
                )
            }
        }

        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>()
        for ((subName, peek) in peeks) {
            // First peek already ran (needed for `.nomedia` detection). Skip grandchild
            // scans into hidden dirs when Hidden files is off.
            if (!deepScanHidden && peekIndicatesHiddenDir(subName, peek)) continue
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
                val leafDir = dir / subName / leafName
                grandPeeks[leafRel] = listChildrenRemote(
                    leafDir,
                    preferMediaStore,
                    includeSafRemainder = !shouldSkipSafPeek(leafDir, preferMediaStore),
                )
            }
        }

        val dirName = humanizePathName(dir.name).ifEmpty { "Gallery" }
        val classified = classifyChildren(dir, dirName, children, peeks, grandPeeks, zipInteriors)
        // Cache/toggle fallback: leftover zip ArchiveGallery rows (unreadable CD, old cache).
        return ZipAsDirListing.applyZipAsDirPreferenceLocal(classified, dir)
    }

    /**
     * Feed zip EOCD listings as fake folders into [classifyRemoteListingWithPeeks]
     * **before** DirectoryListing sees zip files as archives.
     */
    private fun classifyChildren(
        dir: Path,
        dirName: String,
        children: List<RemoteChild>,
        childPeeks: Map<String, List<RemoteChild>>,
        grandPeeks: Map<String, List<RemoteChild>>,
        zipInteriors: MutableMap<String, List<BrowseEntryRemote>>? = null,
    ): List<BrowseEntryRemote> {
        val zipListings = zipRootListings(dir, children, zipInteriors)
        return ZipAsDirListing.classifyListingWithZipAsDirs(
            currentDirName = dirName,
            children = children,
            childPeeks = childPeeks,
            grandPeeks = grandPeeks,
        ) { zipListings[it] }
    }

    private fun zipRootListings(
        dir: Path,
        children: List<RemoteChild>,
        zipInteriors: MutableMap<String, List<BrowseEntryRemote>>? = null,
    ): Map<String, ZipAsDirListing.ZipRootListing> {
        if (!Settings.browseZipAsDir.value) return emptyMap()
        val zips = children.filter { !it.isDirectory && isZipArchiveFileName(it.name) }
        if (zips.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, ZipAsDirListing.ZipRootListing>()
        runParallel(zips) { child ->
            withLocalZipCentralDirectory(dir / child.name) { cd ->
                out[child.name] = ZipAsDirListing.zipRootListingFromCd(cd)
                zipInteriors?.put(child.name, ZipAsDirListing.classifyAt(cd, "", child.name))
            }
        }
        return out
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

    private fun listChildrenRemote(
        dir: Path,
        preferMediaStore: Boolean,
        includeSafRemainder: Boolean = true,
    ): List<RemoteChild> {
        val path = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
        if (!includeSafRemainder) {
            return mediaStoreRemoteChildren(path)
        }
        return BrowseSession.rememberLocalRawChildren(BrowseSession.pathKey(path)) {
            // Raw list: `.nomedia` dirs are tagged after child peeks (same as SMB),
            // so we do not SAF-list every subdirectory twice.
            path.listBrowseChildrenRaw().map { it.toRemoteChild() }
        }
    }

    /**
     * MediaStore-only peek for a SAF child that the index already listed. Not written
     * to [BrowseSession] raw-children cache — entering the folder still SAF-lists
     * archives.
     */
    private fun mediaStoreRemoteChildren(path: Path): List<RemoteChild> {
        val ms = when {
            path.isMediaStorePath() -> path
            else -> tryConvertSafPathToMediaStore(path) ?: return emptyList()
        }
        return MediaStoreFs.listChildren(ms).map { child ->
            RemoteChild(
                name = child.name,
                isDirectory = child.isDirectory,
                path = child.name,
                size = child.size,
                lastModifiedMs = child.lastModifiedMs,
                hidden = isDotHiddenName(child.name),
                readOnly = false,
            )
        }
    }

    private fun shouldSkipSafPeek(dir: Path, preferMediaStore: Boolean): Boolean {
        if (preferMediaStore) return false
        return dir.mediaStoreOverlayNonEmpty()
    }

    private fun BrowseChild.toRemoteChild() = RemoteChild(
        name = name,
        isDirectory = isDirectory,
        path = name,
        size = size,
        lastModifiedMs = lastModifiedMs,
        hidden = hidden,
        readOnly = readOnly,
    )

    fun rootConfigKey(rootPath: Path, preferMediaStore: Boolean): String {
        val effective = resolveBrowsePath(rootPath, preferMediaStore = preferMediaStore)
        return "local|$effective|ms=$preferMediaStore"
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
): List<BrowseEntry> {
    // Re-apply zip-as-dir preference so RAM/disk cache respects the current toggle.
    val entries = ZipAsDirListing.applyZipAsDirPreferenceLocal(remote, baseDir)
    return entries.map { entry ->
        when (entry) {
            is BrowseEntryRemote.Directory -> {
                val zipSeg = if (Settings.browseZipAsDir.value) {
                    ZipAsDirListing.zipFileSegment(entry.relativeName, entry.name)
                } else {
                    null
                }
                val path = when {
                    entry.relativeName.isEmpty() -> baseDir
                    zipSeg != null -> baseDir.resolveRelative(zipSeg)
                    else -> baseDir.resolveRelative(entry.relativeName)
                }
                val cover = entry.coverFileName?.let { coverRel ->
                    if (zipSeg != null) {
                        val inner = ZipAsDirListing.zipInnerPrefix(entry.relativeName)
                        ZipPaths.encodePath(
                            path.toString(),
                            ZipAsDirListing.joinPrefix(inner, coverRel),
                        )
                    } else {
                        path.resolveRelative(coverRel)
                    }
                }
                BrowseEntry.Directory(
                    name = entry.name,
                    path = path,
                    relativeName = entry.relativeName,
                    hasVideo = entry.hasVideo,
                    hasGallery = entry.hasGallery,
                    presence = entry.presence,
                    coverPath = cover,
                    lastModifiedMs = entry.lastModifiedMs,
                    hidden = entry.hidden,
                    virtual = entry.virtual,
                )
            }
            is BrowseEntryRemote.FolderGallery -> {
                val zipSeg = if (Settings.browseZipAsDir.value) {
                    ZipAsDirListing.zipFileSegment(entry.relativeName, entry.name)
                } else {
                    null
                }
                val path = when {
                    entry.relativeName.isEmpty() -> baseDir
                    zipSeg != null -> baseDir.resolveRelative(zipSeg)
                    else -> baseDir.resolveRelative(entry.relativeName)
                }
                val cover = entry.coverFileName?.let { coverRel ->
                    if (zipSeg != null) {
                        val inner = ZipAsDirListing.zipInnerPrefix(entry.relativeName)
                        ZipPaths.encodePath(
                            path.toString(),
                            ZipAsDirListing.joinPrefix(inner, coverRel),
                        )
                    } else {
                        path.resolveRelative(coverRel)
                    }
                }
                BrowseEntry.FolderGallery(
                    name = entry.name,
                    path = path,
                    relativeName = entry.relativeName,
                    pageCount = entry.pageCount,
                    pageCountCapped = entry.pageCountCapped,
                    coverPath = cover,
                    hidden = entry.hidden,
                    virtual = entry.virtual,
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
                    BrowseEntry.RegularFile(
                        name = entry.name,
                        path = path,
                        size = entry.size,
                        lastModifiedMs = entry.lastModifiedMs,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                } else {
                    BrowseEntry.ArchiveGallery(
                        name = entry.name,
                        path = path,
                        size = entry.size,
                        lastModifiedMs = entry.lastModifiedMs,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
            }
            is BrowseEntryRemote.VideoFile ->
                BrowseEntry.VideoFile(
                    name = entry.name,
                    path = baseDir.resolveRelative(entry.fileName),
                    size = entry.size,
                    lastModifiedMs = entry.lastModifiedMs,
                    hidden = entry.hidden,
                    virtual = entry.virtual,
                )
            is BrowseEntryRemote.RegularFile ->
                BrowseEntry.RegularFile(
                    name = entry.name,
                    path = baseDir.resolveRelative(entry.fileName),
                    size = entry.size,
                    lastModifiedMs = entry.lastModifiedMs,
                    hidden = entry.hidden,
                    virtual = entry.virtual,
                )
        }
    }
}
