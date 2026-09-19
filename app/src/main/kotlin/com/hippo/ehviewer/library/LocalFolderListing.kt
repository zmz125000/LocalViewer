package com.hippo.ehviewer.library

import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    /** Same idea as SMB/WebDAV peek gates: cap fan-out on [Dispatchers.IO], no extra pool. */
    private const val PEEK_PARALLELISM = 8

    /** Deep peek/classify budget after shallow paint; keep shallow on expiry. */
    private const val DEEP_CLASSIFY_TIMEOUT_MS = 180_000L

    /**
     * Drop in-flight slim/deep jobs for [rootId] (or every local root).
     * Access-mode change / source delete must not let a stale job rewrite the index.
     */
    fun cancelListingJobs(rootId: Long? = null) {
        LocalListingJobs.cancelAll(rootId)
    }

    /**
     * Folder-bar submit search. Walk the local tree ourselves and abort on
     * coroutine cancel ([ensureActive] between directories).
     */
    suspend fun searchDirectory(
        listedPath: Path,
        relativeDir: String,
        query: String,
        includeHidden: Boolean = false,
        preferMediaStore: Boolean = true,
        zipInnerRel: String? = null,
        onHits: suspend (List<BrowseEntry>) -> Unit = {},
    ): List<BrowseEntry> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        suspend fun emitZip(inner: String, remote: List<BrowseEntryRemote>): List<BrowseEntry> {
            val local = ZipAsDirListing.materializeLocal(listedPath.toString(), inner, remote)
            onHits(local)
            return local
        }
        if (zipInnerRel != null) {
            val remote = searchLocalZip(listedPath, zipInnerRel, q, includeHidden) { r ->
                emitZip(zipInnerRel, r)
            }
            return@withContext ZipAsDirListing.materializeLocal(
                listedPath.toString(),
                zipInnerRel,
                remote,
            )
        }
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (_, inner) ->
                val remote = searchLocalZip(listedPath, inner, q, includeHidden) { r ->
                    emitZip(inner, r)
                }
                return@withContext ZipAsDirListing.materializeLocal(
                    listedPath.toString(),
                    inner,
                    remote,
                )
            }
        }
        val zipAsDir = Settings.browseZipAsDir.value
        val remote = FolderSearch.deepSearch(
            searchRoot = "",
            query = q,
            includeHidden = includeHidden,
            zipAsDir = zipAsDir,
            parallelism = PEEK_PARALLELISM,
            listChildren = { dir ->
                val path = if (dir.isEmpty()) listedPath else listedPath.resolveRelative(dir)
                try {
                    listChildrenRemote(path, preferMediaStore)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logcat { "LocalFolderListing: search skip dir=$dir ${e.message}" }
                    emptyList()
                }
            },
            onHits = { r -> onHits(materializeLocalEntries(listedPath, r)) },
        )
        materializeLocalEntries(listedPath, remote)
    }

    data class SlimRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
        val persist: Boolean = true,
        val zipInteriors: Map<String, List<BrowseEntryRemote>> = emptyMap(),
    )

    /**
     * RAM / cache path used by sibling navigation and callers that only need a listing.
     * Prefer [listDirectory] from the folder UI when index cache + quick scan matter.
     */
    suspend fun listDirectorySync(
        dir: Path,
        useCache: Boolean = true,
        preferMediaStore: Boolean = true,
    ): List<BrowseEntry> = withContext(Dispatchers.IO) {
        val effective = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
        val key = BrowseSession.pathKey(effective)
        if (useCache) {
            BrowseSession.getLocalListing(key)?.let { return@withContext it }
        }
        val remote = listDirectoryUncachedRemote(effective, preferMediaStore)
        // Not session-current: this path does not persist to NetworkFolderIndexCache.
        // Leaving current=false lets folder UI listDirectory hydrate/save + quick-scan.
        BrowseSession.putLocalListing(key, remote, sessionCurrent = false)
        materializeLocalEntries(effective, remote)
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

        val zipName = zipRel.substringAfterLast('/').substringAfterLast('\\')
        val parentEntries = parentListingForZip(rootId, rootPath, configKey, zipRel)
        val stale = ZipAsDirListing.isZipAsDirStale(parentEntries, zipName)

        if (useCache && !stale) {
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
        } else if (!useCache) {
            BrowseSession.invalidateLocalListing(ramKey)
        }
        if (stale) {
            BrowseSession.invalidateLocalZipListingsUnder(rootId, zipRel)
            NetworkFolderIndexCache.removeLocalUnder(rootId, zipRel)
        }

        val tree = withLocalZipCentralDirectory(zipPath, ZipCdParse.Enter) { cd ->
            ZipAsDirListing.virtualFolderTree(cd, zipName)
        } ?: return@withContext null
        val treeKey = ZipAsDirListing.virtualRelativeDir(zipName, inner)
        val clearedParent = if (stale && parentEntries != null) {
            ZipAsDirListing.clearZipAsDirStale(parentEntries, zipName)
        } else {
            null
        }
        if (configKey != null) {
            persistZipVirtualFolderTree(
                rootId,
                configKey,
                zipRel,
                tree,
                parentEntries = clearedParent?.takeIf { it !== parentEntries },
            )
        } else {
            BrowseSession.putLocalListing(ramKey, tree[treeKey].orEmpty(), sessionCurrent = true)
        }
        if (clearedParent != null && clearedParent !== parentEntries && rootPath != null) {
            val parent = ZipAsDirListing.parentRelative(zipRel)
            val parentPath = if (parent.isEmpty()) rootPath else rootPath.resolveRelative(parent)
            BrowseSession.putLocalFolderListing(
                rootId,
                parent,
                clearedParent,
                sessionCurrent = true,
                pathAlias = parentPath,
            )
        }
        val remote = BrowseSession.getLocalCachedListing(ramKey)?.entries
            ?: tree[treeKey].orEmpty()
        val materialized = materialize(remote)
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
        if (Settings.browseZipAsDir.value) {
            val split = ZipAsDirListing.splitZipBrowsePath(relativeDir)
            if (split != null) {
                val (zipRel, inner) = split
                val zipPath = rootPath.resolveRelative(zipRel)
                return@withContext listZipVirtualDirectory(
                    rootId = rootId,
                    rootPath = rootPath,
                    zipPath = zipPath,
                    zipRel = zipRel,
                    inner = inner,
                    currentDirName = inner.substringAfterLast('/').ifEmpty {
                        zipPath.name
                    },
                    preferMediaStore = preferMediaStore,
                    useCache = useCache,
                    onCached = onCached,
                ).orEmpty()
            }
        }
        val effective = resolveBrowsePath(listedPath, preferMediaStore = preferMediaStore)
        val dirKey = BrowseSession.normalizeLocalRelativeDir(relativeDir)
        val configKey = rootConfigKey(rootPath, preferMediaStore)
        val jobKey = BrowseSession.localFolderListingKey(rootId, dirKey)

        if (useCache && LocalListingJobs.isActive(jobKey)) {
            BrowseSession.getLocalFolderCachedListing(rootId, dirKey)?.let { ram ->
                onCached?.invoke(materializeLocalEntries(effective, ram.entries))
            }
            return@withContext LocalListingJobs.await(jobKey) {
                error("joined in-flight local list job")
            }
        }

        if (useCache) {
            val ram = BrowseSession.getLocalFolderCachedListing(rootId, dirKey)
            val needDisk = ram == null ||
                !ram.sessionCurrent ||
                isShallowIncompleteListing(ram.entries)
            val disk = if (needDisk) {
                NetworkFolderIndexCache.loadLocal(rootId, configKey, dirKey)
            } else {
                null
            }
            val selected = selectCachedFolderListing(
                ramEntries = ram?.entries,
                ramSessionCurrent = ram?.sessionCurrent == true,
                diskEntries = disk,
            )
            val cached = selected?.let { (entries, sessionCurrent) ->
                if (ram == null || ram.entries !== entries || ram.sessionCurrent != sessionCurrent) {
                    BrowseSession.putLocalFolderListing(
                        rootId,
                        dirKey,
                        entries,
                        sessionCurrent = sessionCurrent,
                        pathAlias = effective,
                    )
                }
                BrowseSession.CachedLocalListing(entries = entries, sessionCurrent = sessionCurrent)
            }
            if (cached != null) {
                val filledRemote = if (cached.sessionCurrent) {
                    cached.entries
                } else {
                    withLocalArchivePageCounts(effective, cached.entries)
                }
                if (filledRemote !== cached.entries) {
                    NetworkFolderIndexCache.saveLocal(
                        rootId,
                        configKey,
                        dirKey,
                        filledRemote,
                    )
                    BrowseSession.putLocalFolderListing(
                        rootId,
                        dirKey,
                        filledRemote,
                        sessionCurrent = cached.sessionCurrent,
                        pathAlias = effective,
                    )
                }
                val materialized = materializeLocalEntries(effective, filledRemote)
                onCached?.invoke(materialized)
                val shouldQuickScan =
                    Settings.networkFolderIndexQuickScan.value && !cached.sessionCurrent
                if (!shouldQuickScan) return@withContext materialized
                if (!isShallowIncompleteListing(filledRemote)) {
                    return@withContext LocalListingJobs.await(jobKey) {
                        try {
                            runLocalSlimAndPersist(
                                effective,
                                preferMediaStore,
                                filledRemote,
                                rootId,
                                configKey,
                                dirKey,
                                materialized,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            logcat("FolderIndex") {
                                "Local slim refresh failed for root=$rootId dir=$dirKey " +
                                    "(${e.message}); keeping cache"
                            }
                            materialized
                        }
                    }
                }
            }
        } else {
            LocalListingJobs.cancel(jobKey)
            BrowseSession.invalidateLocalFolderListing(rootId, dirKey, effective)
        }

        BrowseSession.getLocalFolderCachedListing(rootId, dirKey)?.let { listed ->
            if (listed.sessionCurrent) {
                return@withContext materializeLocalEntries(effective, listed.entries)
            }
        }
        // Cold miss: shallow-first paint, then deep persist. Both run on a process
        // job so enter/back cannot cancel the index write.
        return@withContext LocalListingJobs.await(jobKey) {
            val previous = BrowseSession.getLocalFolderCachedListing(rootId, dirKey)?.entries
            val t0 = System.nanoTime()
            val children = listChildrenRemote(effective, preferMediaStore)
            val dirName = effective.name.ifEmpty { "Gallery" }
            val shallow = ZipAsDirListing.applyZipAsDirPreferenceLocal(
                classifyChildren(effective, dirName, children, emptyMap(), emptyMap()),
                effective,
            )
            val shallowMerged =
                if (previous != null) preferCompleteFolderGalleries(previous, shallow) else shallow
            BrowseSession.putLocalFolderListing(
                rootId,
                dirKey,
                shallowMerged,
                sessionCurrent = false,
                pathAlias = effective,
            )
            val shallowMaterialized = materializeLocalEntries(effective, shallowMerged)
            logcat("FolderIndex") {
                "Local shallow list root=$rootId dir=$dirKey " +
                    "children=${children.size} entries=${shallowMerged.size} " +
                    "ms=${(System.nanoTime() - t0) / 1_000_000}"
            }
            onCached?.invoke(shallowMaterialized)
            val alreadyCurrent = BrowseSession.getLocalFolderCachedListing(rootId, dirKey)
            if (alreadyCurrent?.sessionCurrent == true) {
                return@await materializeLocalEntries(effective, alreadyCurrent.entries)
            }
            runLocalDeepClassifyAndPersist(
                effective,
                preferMediaStore,
                children,
                shallowMerged,
                rootId,
                configKey,
                dirKey,
                shallowMaterialized,
            )
        }
    }

    suspend fun listDirectoryUncachedRemote(
        dir: Path,
        preferMediaStore: Boolean,
    ): List<BrowseEntryRemote> {
        val children = listChildrenRemote(dir, preferMediaStore)
        return classifyDirectoryChildren(dir, preferMediaStore, children)
    }

    suspend fun listDirectorySlim(
        dir: Path,
        preferMediaStore: Boolean,
        cached: List<BrowseEntryRemote>,
        rootId: Long,
        configKey: String,
        relativeDir: String,
    ): SlimRefresh {
        val children = listChildrenRemote(dir, preferMediaStore)
        if (isUntrustedSlimLiveListing(cached, children)) {
            return SlimRefresh(cached, emptySet(), persist = false)
        }
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
        val zipAdjustedUnreachable = plan.unreachableDirectoryNames - zipFileNames
        val recovered = plan.recoveredDirectoryNames
        val dirsUnchanged = plan.addedDirectories.isEmpty() &&
            zipAdjustedUnreachable.isEmpty() &&
            recovered.isEmpty()
        if (dirsUnchanged && deepHidden.isEmpty() && newZips.isEmpty()) {
            // Dirs same — still patch surviving file size/mtime; add/drop direct files.
            // Cached zip-as-dir dirs consume the live zip file (size/mtime + stale mark).
            return SlimRefresh(
                entries = withLocalArchivePageCounts(
                    dir,
                    replaceSlimDirectFilesFromLive(cached, children, dirName),
                ),
                removedDirectoryNames = emptySet(),
            )
        }
        // Drop shallow hidden shells (via removedDirectoryNames) then re-add full classify.
        // Missing live dirs are marked unreachable — descendant index keys stay.
        // Keep cached zip-as-dir Directory/FolderGallery rows: live listing still has the file.
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = deepNames,
            unreachableDirectoryNames = zipAdjustedUnreachable,
            recoveredDirectoryNames = recovered,
        )
        val zipInteriors = ConcurrentHashMap<String, List<BrowseEntryRemote>>()
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(dir, preferMediaStore, toClassify, zipInteriors)
        }
        val merged = replaceSlimDirectFilesFromLive(
            mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries),
            children,
            dirName,
        )
        return SlimRefresh(
            entries = withLocalArchivePageCounts(dir, merged),
            removedDirectoryNames = emptySet(),
            zipInteriors = zipInteriors,
        )
    }

    private suspend fun classifyDirectoryChildren(
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
                // MediaStore never indexes archives. SAF-mode peeks must always take the
                // DocumentsContract remainder, or a dir with images+zips is tagged as an
                // image leaf and the folder (and its archives) disappear from Folder view.
                peeks[c.name] = listChildrenRemote(
                    dir / c.name,
                    preferMediaStore,
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
                )
            }
        }

        val dirName = humanizePathName(dir.name).ifEmpty { "Gallery" }
        val classified = classifyChildren(dir, dirName, children, peeks, grandPeeks, zipInteriors)
        // Cache/toggle fallback: leftover zip ArchiveGallery rows (unreadable CD, old cache).
        return withLocalArchivePageCounts(
            dir,
            ZipAsDirListing.applyZipAsDirPreferenceLocal(classified, dir),
        )
    }

    /**
     * Feed zip EOCD listings as fake folders into [classifyRemoteListingWithPeeks]
     * **before** DirectoryListing sees zip files as archives.
     */
    private suspend fun classifyChildren(
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

    private suspend fun zipRootListings(
        dir: Path,
        children: List<RemoteChild>,
        zipInteriors: MutableMap<String, List<BrowseEntryRemote>>? = null,
    ): Map<String, ZipAsDirListing.ZipRootListing> {
        if (!Settings.browseZipAsDir.value) return emptyMap()
        val zips = children.filter { !it.isDirectory && isZipArchiveFileName(it.name) }
        if (zips.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, ZipAsDirListing.ZipRootListing>()
        runParallel(zips) { child ->
            withLocalZipCentralDirectory(dir / child.name, ZipCdParse.Parent) { cd ->
                out[child.name] = ZipAsDirListing.zipRootListingFromCd(cd)
                zipInteriors?.putAll(ZipAsDirListing.parentListingInteriors(cd, child.name))
            }
        }
        return out
    }

    private suspend fun runLocalSlimAndPersist(
        effective: Path,
        preferMediaStore: Boolean,
        filledRemote: List<BrowseEntryRemote>,
        rootId: Long,
        configKey: String,
        dirKey: String,
        materialized: List<BrowseEntry>,
    ): List<BrowseEntry> {
        val refresh = listDirectorySlim(
            effective,
            preferMediaStore,
            filledRemote,
            rootId,
            configKey,
            dirKey,
        )
        if (!refresh.persist) {
            logcat("FolderIndex") {
                "Local slim ignored untrusted listing for root=$rootId " +
                    "dir=$dirKey; keeping cache"
            }
            return materialized
        }
        val toKeep = persistFinishedListing(
            rootId,
            configKey,
            dirKey,
            effective,
            refresh.entries,
            refresh.zipInteriors,
            persistEntries = refresh.entries != filledRemote || refresh.zipInteriors.isNotEmpty(),
        )
        return materializeLocalEntries(effective, toKeep)
    }

    private suspend fun runLocalDeepClassifyAndPersist(
        effective: Path,
        preferMediaStore: Boolean,
        children: List<RemoteChild>,
        shallowMerged: List<BrowseEntryRemote>,
        rootId: Long,
        configKey: String,
        dirKey: String,
        shallowMaterialized: List<BrowseEntry>,
    ): List<BrowseEntry> = try {
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
            val stored = persistFinishedListing(
                rootId,
                configKey,
                dirKey,
                effective,
                fromRam,
                zipInteriors,
                persistEntries = true,
            )
            logcat("FolderIndex") {
                "Local deep classify root=$rootId dir=$dirKey " +
                    "entries=${stored.size} ms=${(System.nanoTime() - t1) / 1_000_000}"
            }
            materializeLocalEntries(effective, stored)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TimeoutCancellationException) {
        logcat("FolderIndex") {
            "Local deep classify timed out root=$rootId dir=$dirKey; keeping shallow"
        }
        shallowMaterialized
    } catch (e: Throwable) {
        logcat("FolderIndex") {
            "Local deep classify failed root=$rootId dir=$dirKey " +
                "(${e.message}); keeping shallow"
        }
        shallowMaterialized
    }

    /**
     * Disk + RAM write after slim/deep. [NonCancellable] so a late UI cancel cannot
     * drop the listing we already classified.
     */
    private suspend fun persistFinishedListing(
        rootId: Long,
        configKey: String,
        dirKey: String,
        effective: Path,
        entries: List<BrowseEntryRemote>,
        zipInteriors: Map<String, List<BrowseEntryRemote>>,
        persistEntries: Boolean,
    ): List<BrowseEntryRemote> = withContext(NonCancellable) {
        val stored = if (persistEntries) {
            persistParentAndZipInteriors(
                rootId,
                configKey,
                dirKey,
                entries,
                zipInteriors,
            )
        } else {
            entries
        }
        BrowseSession.putLocalFolderListing(
            rootId,
            dirKey,
            stored,
            sessionCurrent = true,
            pathAlias = effective,
        )
        stored
    }

    private suspend fun persistZipVirtualFolderTree(
        rootId: Long,
        configKey: String,
        zipRel: String,
        interiors: Map<String, List<BrowseEntryRemote>>,
        parentEntries: List<BrowseEntryRemote>? = null,
    ) {
        persistZipVirtualInteriors(
            rootId,
            configKey,
            ZipAsDirListing.parentRelative(zipRel),
            interiors,
            parentEntries = parentEntries,
        )
    }

    private suspend fun persistZipVirtualInteriors(
        rootId: Long,
        configKey: String,
        parentRelativeDir: String,
        interiors: Map<String, List<BrowseEntryRemote>>,
        parentEntries: List<BrowseEntryRemote>? = null,
    ) {
        if (interiors.isEmpty() && parentEntries == null) return
        withContext(NonCancellable) {
            ZipAsDirListing.persistFolderIndexes(
                parentRelativeDir = parentRelativeDir,
                interiors = interiors,
                saveAll = { folders ->
                    NetworkFolderIndexCache.saveLocalAll(rootId, configKey, folders)
                },
                putRam = { dir, entries ->
                    BrowseSession.putLocalListing(
                        BrowseSession.localZipListingKey(rootId, dir),
                        entries,
                        sessionCurrent = true,
                    )
                },
                parentEntries = parentEntries,
            )
        }
    }

    private suspend fun persistParentAndZipInteriors(
        rootId: Long,
        configKey: String,
        relativeDir: String,
        parentEntries: List<BrowseEntryRemote>,
        interiors: Map<String, List<BrowseEntryRemote>>,
    ): List<BrowseEntryRemote> {
        if (interiors.isEmpty()) {
            return NetworkFolderIndexCache.saveLocal(rootId, configKey, relativeDir, parentEntries)
        }
        val stored = ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = relativeDir,
            interiors = interiors,
            saveAll = { folders ->
                NetworkFolderIndexCache.saveLocalAll(rootId, configKey, folders)
            },
            putRam = { dir, entries ->
                BrowseSession.putLocalListing(
                    BrowseSession.localZipListingKey(rootId, dir),
                    entries,
                    sessionCurrent = true,
                )
            },
            parentEntries = parentEntries,
        )
        return stored[relativeDir.replace('\\', '/').trim('/')] ?: parentEntries
    }

    private suspend fun parentListingForZip(
        rootId: Long,
        rootPath: Path?,
        configKey: String?,
        zipRel: String,
    ): List<BrowseEntryRemote>? {
        val parent = ZipAsDirListing.parentRelative(zipRel)
        BrowseSession.getLocalFolderCachedListing(rootId, parent)?.entries?.let { return it }
        if (rootPath != null) {
            val parentPath = if (parent.isEmpty()) rootPath else rootPath.resolveRelative(parent)
            BrowseSession.getLocalCachedListing(BrowseSession.pathKey(parentPath))?.entries?.let { return it }
        }
        return configKey?.let { NetworkFolderIndexCache.loadLocal(rootId, it, parent) }
    }

    private suspend fun <T> runParallel(items: List<T>, block: (T) -> Unit) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            coroutineContext.ensureActive()
            block(items[0])
            return
        }
        coroutineScope {
            val gate = Semaphore(PEEK_PARALLELISM)
            items.map { item ->
                async {
                    gate.withPermit {
                        coroutineContext.ensureActive()
                        block(item)
                    }
                }
            }.awaitAll()
        }
    }

    private fun listChildrenRemote(
        dir: Path,
        preferMediaStore: Boolean,
    ): List<RemoteChild> {
        val path = resolveBrowsePath(dir, preferMediaStore = preferMediaStore)
        return BrowseSession.rememberLocalRawChildren(BrowseSession.pathKey(path)) {
            // Raw list: `.nomedia` dirs are tagged after child peeks (same as SMB),
            // so we do not SAF-list every subdirectory twice.
            // Full SIZE/LAST_MODIFIED on SAF remainder so archive/PDF list cells
            // keep mtime/size when MediaStore overlay already listed images.
            path.listBrowseChildrenRaw(lightSafMeta = false).map { it.toRemoteChild() }
        }
    }

    private fun BrowseChild.toRemoteChild() = RemoteChild(
        name = name,
        isDirectory = isDirectory,
        path = name,
        size = size,
        lastModifiedMs = lastModifiedMs,
        hidden = hidden,
        readOnly = readOnly,
        mimeType = mimeType,
    )

    private suspend fun searchLocalZip(
        zipPath: Path,
        inner: String,
        query: String,
        includeHidden: Boolean,
        onHits: suspend (List<BrowseEntryRemote>) -> Unit,
    ): List<BrowseEntryRemote> {
        val src = openLocalArchiveByteSource(zipPath) ?: return emptyList()
        return try {
            val cd = ZipCentralDirectory.open(src, ZipCdParse.Enter) ?: return emptyList()
            FolderSearch.searchZipCentralDirectory(cd, inner, query, includeHidden, onHits)
        } finally {
            runCatching { src.close() }
        }
    }

    fun rootConfigKey(rootPath: Path, preferMediaStore: Boolean): String {
        val effective = resolveBrowsePath(rootPath, preferMediaStore = preferMediaStore)
        return "local|$effective|ms=$preferMediaStore"
    }
}

/**
 * Process-scoped local list jobs. UI [LaunchedEffect] cancel (enter/back) only
 * drops the await — slim/deep + index persist keep running.
 */
internal object LocalListingJobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Deferred<List<BrowseEntry>>>()

    fun isActive(key: String): Boolean = jobs[key]?.isActive == true

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }

    fun cancelAll(rootId: Long? = null) {
        if (rootId == null) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            return
        }
        val prefixes = arrayOf("local:$rootId|", "zipasdir:$rootId|")
        jobs.entries.toList().forEach { (key, job) ->
            if (prefixes.any { key.startsWith(it) }) {
                if (jobs.remove(key, job)) job.cancel()
            }
        }
    }

    suspend fun await(
        key: String,
        restart: Boolean = false,
        loader: suspend () -> List<BrowseEntry>,
    ): List<BrowseEntry> {
        val deferred = jobs.compute(key) { _, existing ->
            if (!restart && existing != null && existing.isActive) {
                existing
            } else {
                existing?.cancel()
                scope.async { loader() }.also { job ->
                    job.invokeOnCompletion { jobs.remove(key, job) }
                }
            }
        }!!
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // Caller left this folder. The process job is still running.
            coroutineContext.ensureActive()
            throw e
        }
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
    val unreachable = cachedUnreachableDirectoryNames(entries)
    return entries.mapNotNull { entry ->
        if (entry.isUnderUnreachableFolder(unreachable)) return@mapNotNull null
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
                    size = entry.size,
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
                    lastModifiedMs = entry.lastModifiedMs,
                    size = entry.size,
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
                        pageCount = entry.pageCount,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
            }
            is BrowseEntryRemote.VideoFile ->
                BrowseEntry.VideoFile(
                    name = entry.name,
                    path = ZipAsDirListing.materializeLocalFilePath(
                        baseDir,
                        entry.fileName,
                        Settings.browseZipAsDir.value,
                    ),
                    size = entry.size,
                    lastModifiedMs = entry.lastModifiedMs,
                    hidden = entry.hidden,
                    virtual = entry.virtual,
                )
            is BrowseEntryRemote.RegularFile ->
                BrowseEntry.RegularFile(
                    name = entry.name,
                    path = ZipAsDirListing.materializeLocalFilePath(
                        baseDir,
                        entry.fileName,
                        Settings.browseZipAsDir.value,
                    ),
                    size = entry.size,
                    lastModifiedMs = entry.lastModifiedMs,
                    hidden = entry.hidden,
                    virtual = entry.virtual,
                )
        }
    }
}
