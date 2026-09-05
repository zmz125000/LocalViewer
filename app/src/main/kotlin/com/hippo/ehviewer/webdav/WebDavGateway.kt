package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.FolderGalleryIndex
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.RemoteDirectorySlimPlan
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipCentralDirectory
import com.hippo.ehviewer.library.classifyRemoteListing
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.hiddenDirectoriesNeedingDeepScan
import com.hippo.ehviewer.library.isDotHiddenName
import com.hippo.ehviewer.library.isPromotableLeafDirName
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.isShallowIncompleteListing
import com.hippo.ehviewer.library.isUntrustedSlimLiveListing
import com.hippo.ehviewer.library.isZipArchiveFileName
import com.hippo.ehviewer.library.mergeRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.peekIndicatesHiddenDir
import com.hippo.ehviewer.library.planRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.preferCompleteFolderGalleries
import com.hippo.ehviewer.library.replaceSlimDirectFilesFromLive
import com.hippo.ehviewer.library.selectCachedFolderListing
import com.hippo.ehviewer.library.withHiddenFlags
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Browse listing for WebDAV: PROPFIND + parallel peeks + same remote classify as SMB.
 * No TCP session pool — HTTP multiplexes; [WebDavClient] caps fan-out.
 */
object WebDavGateway {
    private val peekSlots = Semaphore(6)

    /** Deep peek/classify budget after shallow paint; keep shallow on expiry. */
    private const val DEEP_CLASSIFY_TIMEOUT_MS = 180_000L

    fun sourceConfigKey(source: WebDavSourceEntity): String = "${source.id}|${source.baseUrl}|${source.pathPrefix}|${source.username}"

    fun joinRelative(parent: String, child: String): String {
        val p = parent.replace('\\', '/').trim('/')
        val c = child.replace('\\', '/').trim('/')
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }

    suspend fun listDirectory(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        useCache: Boolean = true,
        onCached: ((List<BrowseEntryRemote>) -> Unit)? = null,
    ): List<BrowseEntryRemote> {
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
                return listZipVirtualDirectory(
                    source,
                    password,
                    relativeDir,
                    zipRel,
                    inner,
                    useCache,
                    onCached,
                )
            }
        }
        val configKey = sourceConfigKey(source)
        if (useCache) {
            val ram = BrowseSession.getWebDavCachedListing(source.id, relativeDir)
            val needDisk = ram == null || isShallowIncompleteListing(ram.entries)
            val disk = if (needDisk) {
                NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)
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
                    BrowseSession.putWebDavListing(
                        source.id,
                        relativeDir,
                        entries,
                        sessionCurrent = sessionCurrent,
                    )
                }
                BrowseSession.CachedRemoteListing(entries = entries, sessionCurrent = sessionCurrent)
            }
            if (cached != null) {
                val presented = presentListingForZipAsDirToggle(
                    source,
                    configKey,
                    relativeDir,
                    cached.entries,
                    cached.sessionCurrent,
                )
                onCached?.invoke(presented)
                val shouldQuickScan =
                    com.hippo.ehviewer.Settings.networkFolderIndexQuickScan.value &&
                        !cached.sessionCurrent
                if (!shouldQuickScan) return presented
                if (isShallowIncompleteListing(cached.entries)) {
                    return listDirectoryShallowThenDeep(
                        source,
                        password,
                        relativeDir,
                        configKey,
                        onCached,
                    )
                }
                return try {
                    val refresh = withIOContext {
                        listDirectorySlim(source, password, relativeDir, cached.entries, configKey)
                    }
                    if (!refresh.persist) {
                        logcat("FolderIndex") {
                            "WebDAV slim ignored untrusted listing for source=${source.id} " +
                                "dir=$relativeDir; keeping cache"
                        }
                        return presented
                    }
                    val toKeep = if (refresh.entries != cached.entries ||
                        refresh.removedDirectoryNames.isNotEmpty()
                    ) {
                        NetworkFolderIndexCache.saveWebDav(
                            source.id,
                            configKey,
                            relativeDir,
                            refresh.entries,
                            refresh.removedDirectoryNames,
                        )
                    } else {
                        refresh.entries
                    }
                    presentListingForZipAsDirToggle(
                        source,
                        configKey,
                        relativeDir,
                        toKeep,
                        sessionCurrent = true,
                        previousForZipNames = cached.entries,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Leave non-current so a later visit can retry quick scan.
                    presented
                }
            }
        } else {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        // Cold miss: shallow-first (one PROPFIND → paint), then deferred peeks.
        return listDirectoryShallowThenDeep(source, password, relativeDir, configKey, onCached)
    }

    /**
     * Cold list: publish name-only shallow rows immediately, then peek/classify.
     * Deep failure / timeout / cancel keeps shallow (`sessionCurrent=false`).
     */
    private suspend fun listDirectoryShallowThenDeep(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        configKey: String,
        onCached: ((List<BrowseEntryRemote>) -> Unit)?,
    ): List<BrowseEntryRemote> {
        val previous = BrowseSession.getWebDavListing(source.id, relativeDir)
        val t0 = System.nanoTime()
        val children = withIOContext {
            listChildrenForRelativeDir(source, password, relativeDir)
        }
        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        val shallowChildren = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFilesAsPendingDirectories(children)
        } else {
            children
        }
        val shallow = classifyRemoteListing(dirName, shallowChildren.withHiddenFlags())
        val shallowMerged = if (previous != null) {
            preferCompleteFolderGalleries(previous, shallow)
        } else {
            shallow
        }
        // RAM-only until deep succeeds (same reason as SMB — avoid slim false-complete).
        BrowseSession.putWebDavListing(
            source.id,
            relativeDir,
            shallowMerged,
            sessionCurrent = false,
        )
        logcat("FolderIndex") {
            "WebDAV shallow list source=${source.id} dir=$relativeDir " +
                "children=${children.size} entries=${shallowMerged.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}"
        }
        onCached?.invoke(shallowMerged)

        BrowseSession.getWebDavCachedListing(source.id, relativeDir)?.let { cached ->
            if (cached.sessionCurrent) return cached.entries
        }

        return try {
            withTimeout(DEEP_CLASSIFY_TIMEOUT_MS) {
                coroutineContext.ensureActive()
                val t1 = System.nanoTime()
                val deep = withIOContext {
                    classifyDirectoryChildren(
                        source,
                        password,
                        relativeDir,
                        children,
                        onPartial = { partial ->
                            val merged = preferCompleteFolderGalleries(shallowMerged, partial)
                            BrowseSession.putWebDavListing(
                                source.id,
                                relativeDir,
                                merged,
                                sessionCurrent = false,
                            )
                            onCached?.invoke(merged)
                        },
                    )
                }
                val fromRam = preferCompleteFolderGalleries(shallowMerged, deep)
                val stored = presentListingForZipAsDirToggle(
                    source,
                    configKey,
                    relativeDir,
                    fromRam,
                    sessionCurrent = true,
                    previousForZipNames = shallowMerged,
                    persist = true,
                )
                logcat("FolderIndex") {
                    "WebDAV deep classify source=${source.id} dir=$relativeDir " +
                        "entries=${stored.size} ms=${(System.nanoTime() - t1) / 1_000_000}"
                }
                stored
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutCancellationException) {
            logcat("FolderIndex") {
                "WebDAV deep classify timed out source=${source.id} dir=$relativeDir; keeping shallow"
            }
            shallowMerged
        } catch (e: Throwable) {
            logcat("FolderIndex") {
                "WebDAV deep classify failed source=${source.id} dir=$relativeDir " +
                    "(${e.message}); keeping shallow"
            }
            shallowMerged
        }
    }

    private data class SlimDirectoryRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
        val persist: Boolean = true,
    )

    private suspend fun listDirectoryUncached(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        return classifyDirectoryChildren(source, password, relativeDir, children)
    }

    /**
     * Flat non-directory child basenames (one PROPFIND, no classify / peeks).
     * Used by HTTP access-dir so folder playlists match the real remote directory.
     */
    suspend fun listChildFileNames(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> = withIOContext {
        if (ZipAsDirListing.splitZipBrowsePath(relativeDir) != null) return@withIOContext emptyList()
        WebDavClient.listChildren(source, password, relativeDir)
            .asSequence()
            .filterNot { it.isDirectory || it.name.startsWith('.') || isProtectedSystemName(it.name) }
            .map { it.name }
            .toList()
    }

    /**
     * Cache-hit refresh: one PROPFIND for the current directory. Only new child folders
     * run the existing child/leaf peek classifier. Direct files are reconciled
     * (drop stale / add new) from the live listing.
     */
    private suspend fun listDirectorySlim(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        cached: List<BrowseEntryRemote>,
        configKey: String,
    ): SlimDirectoryRefresh {
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        if (isUntrustedSlimLiveListing(cached, children)) {
            return SlimDirectoryRefresh(cached, emptySet(), persist = false)
        }
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        val zipFileNames = if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.zipFileNames(children)
        } else {
            emptySet()
        }
        val deepHidden = if (com.hippo.ehviewer.Settings.browseShowHiddenFiles.value) {
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
        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        val liveForFiles = if (zipFileNames.isEmpty()) {
            children
        } else {
            children.filterNot { it.name in zipFileNames }
        }
        val zipAdjustedRemoved = plan.removedDirectoryNames - zipFileNames
        val dirsUnchanged = plan.addedDirectories.isEmpty() && zipAdjustedRemoved.isEmpty()
        if (dirsUnchanged && deepHidden.isEmpty() && newZips.isEmpty()) {
            return SlimDirectoryRefresh(
                entries = replaceSlimDirectFilesFromLive(cached, liveForFiles, dirName),
                removedDirectoryNames = emptySet(),
            )
        }
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = zipAdjustedRemoved + deepNames,
        )
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(source, password, relativeDir, toClassify)
        }
        val merged = replaceSlimDirectFilesFromLive(
            mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries),
            liveForFiles,
            dirName,
        )
        return SlimDirectoryRefresh(
            entries = merged,
            removedDirectoryNames = zipAdjustedRemoved,
        ).also {
            zipAdjustedRemoved.forEach { name ->
                BrowseSession.invalidateWebDavRawChildren(source.id, joinRelative(relativeDir, name))
            }
        }
    }

    private suspend fun classifyDirectoryChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
        onPartial: (suspend (List<BrowseEntryRemote>) -> Unit)? = null,
    ): List<BrowseEntryRemote> {
        val deepScanHidden = com.hippo.ehviewer.Settings.browseShowHiddenFiles.value
        // Dot folders: always tag-only (never peek). `.nomedia` dirs peek only when Hidden on.
        val dirsToPeek = children.filter { c ->
            c.isDirectory &&
                !isProtectedSystemName(c.name) &&
                !isDotHiddenName(c.name) &&
                (deepScanHidden || !c.hidden)
        }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        if (dirsToPeek.isNotEmpty()) {
            coroutineScope {
                dirsToPeek.map { c ->
                    async {
                        peekSlots.withPermit {
                            val childRel = joinRelative(relativeDir, c.name)
                            peeks[c.name] = runCatching {
                                listChildrenForRelativeDir(source, password, childRel)
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll()
            }
        }

        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>()
        for ((subName, peek) in peeks) {
            // First peek already ran (needed for `.nomedia` detection). Skip grandchild
            // scans into hidden dirs when Hidden files is off.
            if (!deepScanHidden && peekIndicatesHiddenDir(subName, peek)) continue
            // sample/ does not count toward the 1..3 leaf budget or grand-peek work.
            val leaves = peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
            if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                for (leaf in leaves) {
                    leavesToPeek += subName to leaf.name
                }
            } else if (leaves.isNotEmpty()) {
                // Cover-only: first leaf when promote budget exceeded.
                leavesToPeek += subName to leaves.first().name
            }
        }
        if (leavesToPeek.isNotEmpty()) {
            coroutineScope {
                leavesToPeek.map { (subName, leafName) ->
                    async {
                        peekSlots.withPermit {
                            val leafRel = joinRelative(joinRelative(relativeDir, subName), leafName)
                            grandPeeks["$subName/$leafName"] = runCatching {
                                listChildrenForRelativeDir(source, password, leafRel)
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll()
            }
        }

        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        val zipListings = if (Settings.browseZipAsDir.value &&
            children.any { !it.isDirectory && isZipArchiveFileName(it.name) }
        ) {
            if (onPartial != null) {
                val nonZip = children.filter { it.isDirectory || !isZipArchiveFileName(it.name) }
                val partial = classifyRemoteListingWithPeeks(
                    dirName,
                    nonZip,
                    peeks,
                    grandPeeks,
                ) + ZipAsDirListing.pendingZipDirectoryRows(children)
                onPartial(partial)
            }
            zipRootListings(source, password, relativeDir, children)
        } else {
            emptyMap()
        }
        return ZipAsDirListing.classifyListingWithZipAsDirs(
            currentDirName = dirName,
            children = children,
            childPeeks = peeks,
            grandPeeks = grandPeeks,
        ) { zipListings[it] }
    }

    private suspend fun listZipVirtualDirectory(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        zipRel: String,
        inner: String,
        useCache: Boolean,
        onCached: ((List<BrowseEntryRemote>) -> Unit)?,
    ): List<BrowseEntryRemote> {
        val configKey = sourceConfigKey(source)
        if (useCache) {
            val cached = BrowseSession.getWebDavListing(source.id, relativeDir)
                ?: NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)
            if (cached != null) {
                // EOCD listings are complete; there is no slim scan of a virtual zip path.
                BrowseSession.putWebDavListing(source.id, relativeDir, cached, sessionCurrent = true)
                onCached?.invoke(cached)
                return cached
            }
        } else {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        val title = inner.substringAfterLast('/').ifEmpty {
            zipRel.substringAfterLast('/').ifEmpty { source.displayName }
        }
        val entries = withIOContext {
            try {
                WebDavArchiveByteSource(
                    source,
                    password,
                    zipRel,
                    pipeline = false,
                    readahead = false,
                ).use { src ->
                    val cd = ZipCentralDirectory.open(src) ?: return@use emptyList()
                    persistZipVirtualFolderTree(source, configKey, zipRel, inner, title, cd)
                    BrowseSession.getWebDavListing(source.id, relativeDir)
                        ?: ZipAsDirListing.classifyAt(cd, inner, title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
        onCached?.invoke(entries)
        return entries
    }

    private suspend fun zipRootListings(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
    ): Map<String, ZipAsDirListing.ZipRootListing> {
        if (!Settings.browseZipAsDir.value) return emptyMap()
        val zips = children.filter { !it.isDirectory && isZipArchiveFileName(it.name) }
        if (zips.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, ZipAsDirListing.ZipRootListing>()
        val t0 = System.nanoTime()
        coroutineScope {
            zips.map { child ->
                async {
                    peekSlots.withPermit {
                        val zipRel = joinRelative(relativeDir, child.name)
                        runCatching {
                            WebDavArchiveByteSource(
                                source,
                                password,
                                zipRel,
                                pipeline = false,
                                knownSize = child.size,
                                readahead = false,
                            ).use { src ->
                                val cd = ZipCentralDirectory.open(src) ?: return@use
                                out[child.name] = ZipAsDirListing.zipRootListingFromCd(cd)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        logcat("FolderIndex") {
            "WebDAV zip-as-dir EOCD source=${source.id} dir=$relativeDir " +
                "zips=${zips.size} ok=${out.size} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000}"
        }
        return out
    }

    private suspend fun persistZipVirtualFolderTree(
        source: WebDavSourceEntity,
        configKey: String,
        zipRel: String,
        inner: String,
        title: String,
        cd: ZipCentralDirectory,
    ) {
        val zipName = zipRel.substringAfterLast('/')
        val key = if (inner.isEmpty()) zipName else "$zipName/$inner"
        ZipAsDirListing.persistFolderIndexes(
            parentRelativeDir = ZipAsDirListing.parentRelative(zipRel),
            interiors = mapOf(key to ZipAsDirListing.classifyAt(cd, inner, title)),
            save = { dir, entries ->
                NetworkFolderIndexCache.saveWebDav(source.id, configKey, dir, entries)
            },
            putRam = { dir, entries ->
                BrowseSession.putWebDavListing(source.id, dir, entries, sessionCurrent = true)
            },
        )
    }

    /**
     * Shape a listing for the current zip-as-dir toggle and land it in RAM.
     *
     * On: keep zip Directory/FolderGallery rows. [persist] writes the parent index
     * (deep classify). Slim/cache hits only [BrowseSession.putWebDavListing] so
     * session-current can allow folder thumbs.
     *
     * Off: demote zip rows to ArchiveGallery, persist that, and drop interior keys.
     */
    private suspend fun presentListingForZipAsDirToggle(
        source: WebDavSourceEntity,
        configKey: String,
        relativeDir: String,
        entries: List<BrowseEntryRemote>,
        sessionCurrent: Boolean,
        previousForZipNames: List<BrowseEntryRemote>? = null,
        persist: Boolean = false,
    ): List<BrowseEntryRemote> {
        if (Settings.browseZipAsDir.value) {
            val stored = if (persist) {
                NetworkFolderIndexCache.saveWebDav(source.id, configKey, relativeDir, entries)
            } else {
                entries
            }
            BrowseSession.putWebDavListing(source.id, relativeDir, stored, sessionCurrent = sessionCurrent)
            return stored
        }
        var zips = ZipAsDirListing.cachedDirectZipAsDirNames(previousForZipNames ?: entries)
        if (zips.isEmpty()) {
            zips = ZipAsDirListing.cachedDirectZipAsDirNames(
                NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir).orEmpty(),
            )
        }
        val presented = ZipAsDirListing.demoteZipFoldersToArchives(entries)
        if (zips.isEmpty() && presented == entries && !persist) {
            BrowseSession.putWebDavListing(source.id, relativeDir, entries, sessionCurrent = sessionCurrent)
            return entries
        }
        val stored = NetworkFolderIndexCache.saveWebDav(
            source.id,
            configKey,
            relativeDir,
            presented,
            zips,
        )
        BrowseSession.putWebDavListing(source.id, relativeDir, stored, sessionCurrent = sessionCurrent)
        for (name in zips) {
            BrowseSession.invalidateWebDavListingsUnder(source.id, joinRelative(relativeDir, name))
        }
        return stored
    }

    /** One PROPFIND, reused when a parent peek already listed this relative path. */
    private suspend fun listChildrenForRelativeDir(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<RemoteChild> = BrowseSession.rememberWebDavRawChildren(source.id, relativeDir) {
        WebDavClient.listChildren(source, password, relativeDir)
    }

    suspend fun listImageFileNames(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<String> {
        // Complete index → no PROPFIND. Miss → live list like before (stateless HTTP).
        FolderGalleryIndex.loadWebDav(source.id, sourceConfigKey(source), relativeDir)?.let { return it }
        if (Settings.browseZipAsDir.value) {
            ZipAsDirListing.splitZipBrowsePath(relativeDir)?.let { (zipRel, inner) ->
                return withIOContext {
                    runCatching {
                        WebDavArchiveByteSource(source, password, zipRel, pipeline = false).use { src ->
                            val cd = ZipCentralDirectory.open(src) ?: return@use emptyList()
                            ZipAsDirListing.directImageNames(cd, inner)
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }
        return WebDavClient.listImageFileNames(source, password, relativeDir)
    }
}
