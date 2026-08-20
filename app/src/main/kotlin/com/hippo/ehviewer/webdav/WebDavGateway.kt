package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.RemoteDirectorySlimPlan
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.hiddenDirectoriesNeedingDeepScan
import com.hippo.ehviewer.library.isDotHiddenName
import com.hippo.ehviewer.library.isPromotableLeafDirName
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.mergeRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.peekIndicatesHiddenDir
import com.hippo.ehviewer.library.planRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.withHiddenFlags
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Browse listing for WebDAV: PROPFIND + parallel peeks + same remote classify as SMB.
 * No TCP session pool — HTTP multiplexes; [WebDavClient] caps fan-out.
 */
object WebDavGateway {
    private val peekSlots = Semaphore(6)

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
        val configKey = sourceConfigKey(source)
        if (useCache) {
            val cached = BrowseSession.getWebDavCachedListing(source.id, relativeDir)
                ?: NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)?.let { entries ->
                    BrowseSession.putWebDavListing(
                        source.id,
                        relativeDir,
                        entries,
                        sessionCurrent = false,
                    )
                    BrowseSession.CachedRemoteListing(entries = entries, sessionCurrent = false)
                }
            if (cached != null) {
                onCached?.invoke(cached.entries)
                val shouldQuickScan =
                    com.hippo.ehviewer.Settings.networkFolderIndexQuickScan.value &&
                        !cached.sessionCurrent
                if (!shouldQuickScan) return cached.entries
                return try {
                    val refresh = withIOContext {
                        listDirectorySlim(source, password, relativeDir, cached.entries)
                    }
                    BrowseSession.putWebDavListing(
                        source.id,
                        relativeDir,
                        refresh.entries,
                        sessionCurrent = true,
                    )
                    if (refresh.entries != cached.entries ||
                        refresh.removedDirectoryNames.isNotEmpty()
                    ) {
                        NetworkFolderIndexCache.saveWebDav(
                            source.id,
                            configKey,
                            relativeDir,
                            refresh.entries,
                            refresh.removedDirectoryNames,
                        )
                    }
                    refresh.entries
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Leave non-current so a later visit can retry quick scan.
                    cached.entries
                }
            }
        } else {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        val result = withIOContext {
            listDirectoryUncached(source, password, relativeDir)
        }
        BrowseSession.putWebDavListing(
            source.id,
            relativeDir,
            result,
            sessionCurrent = true,
        )
        NetworkFolderIndexCache.saveWebDav(source.id, configKey, relativeDir, result)
        return result
    }

    private data class SlimDirectoryRefresh(
        val entries: List<BrowseEntryRemote>,
        val removedDirectoryNames: Set<String>,
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
        WebDavClient.listChildren(source, password, relativeDir)
            .asSequence()
            .filterNot { it.isDirectory || it.name.startsWith('.') || isProtectedSystemName(it.name) }
            .map { it.name }
            .toList()
    }

    /**
     * Cache-hit refresh: one PROPFIND for the current directory. Only new child folders
     * run the existing child/leaf peek classifier.
     */
    private suspend fun listDirectorySlim(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        cached: List<BrowseEntryRemote>,
    ): SlimDirectoryRefresh {
        val children = listChildrenForRelativeDir(source, password, relativeDir)
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        val deepHidden = if (com.hippo.ehviewer.Settings.browseShowHiddenFiles.value) {
            hiddenDirectoriesNeedingDeepScan(cached, children)
        } else {
            emptyList()
        }
        val deepNames = deepHidden.mapTo(HashSet()) { it.name }
        val toClassify = (plan.addedDirectories + deepHidden).distinctBy { it.name }
        if (plan.isUnchanged && deepHidden.isEmpty()) {
            return SlimDirectoryRefresh(cached, emptySet())
        }
        val effectivePlan = RemoteDirectorySlimPlan(
            addedDirectories = toClassify,
            removedDirectoryNames = plan.removedDirectoryNames + deepNames,
        )
        val addedEntries = if (toClassify.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(source, password, relativeDir, toClassify)
        }
        return SlimDirectoryRefresh(
            entries = mergeRemoteDirectorySlimRefresh(cached, effectivePlan, addedEntries),
            removedDirectoryNames = plan.removedDirectoryNames,
        ).also {
            plan.removedDirectoryNames.forEach { name ->
                BrowseSession.invalidateWebDavRawChildren(source.id, joinRelative(relativeDir, name))
            }
        }
    }

    private suspend fun classifyDirectoryChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
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
        val tagged = children.withHiddenFlags(peeks)
        return classifyRemoteListingWithPeeks(dirName, tagged, peeks, grandPeeks)
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
    ) = WebDavClient.listImageFileNames(source, password, relativeDir)
}
