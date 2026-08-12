package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.NetworkFolderIndexCache
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
import com.hippo.ehviewer.library.isPromotableLeafDirName
import com.hippo.ehviewer.library.isProtectedSystemName
import com.hippo.ehviewer.library.mergeRemoteDirectorySlimRefresh
import com.hippo.ehviewer.library.planRemoteDirectorySlimRefresh
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
            val cached = BrowseSession.getWebDavListing(source.id, relativeDir)
                ?: NetworkFolderIndexCache.loadWebDav(source.id, configKey, relativeDir)?.also {
                    BrowseSession.putWebDavListing(source.id, relativeDir, it)
                }
            if (cached != null) {
                onCached?.invoke(cached)
                if (!com.hippo.ehviewer.Settings.networkFolderIndexCache.value) return cached
                return try {
                    val refresh = withIOContext {
                        listDirectorySlim(source, password, relativeDir, cached)
                    }
                    if (refresh.entries != cached) {
                        BrowseSession.putWebDavListing(source.id, relativeDir, refresh.entries)
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
                    cached
                }
            }
        } else {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        val result = withIOContext {
            listDirectoryUncached(source, password, relativeDir)
        }
        BrowseSession.putWebDavListing(source.id, relativeDir, result)
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
        val children = WebDavClient.listChildren(source, password, relativeDir)
            .filterNot { it.name.startsWith('.') || isProtectedSystemName(it.name) }
        return classifyDirectoryChildren(source, password, relativeDir, children)
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
        val children = WebDavClient.listChildren(source, password, relativeDir)
            .filterNot { it.name.startsWith('.') || isProtectedSystemName(it.name) }
        val plan = planRemoteDirectorySlimRefresh(cached, children)
        if (plan.isUnchanged) return SlimDirectoryRefresh(cached, emptySet())
        val addedEntries = if (plan.addedDirectories.isEmpty()) {
            emptyList()
        } else {
            classifyDirectoryChildren(source, password, relativeDir, plan.addedDirectories)
        }
        return SlimDirectoryRefresh(
            entries = mergeRemoteDirectorySlimRefresh(cached, plan, addedEntries),
            removedDirectoryNames = plan.removedDirectoryNames,
        )
    }

    private suspend fun classifyDirectoryChildren(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
        children: List<RemoteChild>,
    ): List<BrowseEntryRemote> {
        val dirsToPeek = children.filter { it.isDirectory }
        val peeks = ConcurrentHashMap<String, List<RemoteChild>>()
        if (dirsToPeek.isNotEmpty()) {
            coroutineScope {
                dirsToPeek.map { c ->
                    async {
                        peekSlots.withPermit {
                            val childRel = joinRelative(relativeDir, c.name)
                            peeks[c.name] = runCatching {
                                WebDavClient.listChildren(source, password, childRel)
                                    .filterNot { isProtectedSystemName(it.name) }
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll()
            }
        }

        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>()
        for ((subName, peek) in peeks) {
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
                                WebDavClient.listChildren(source, password, leafRel)
                                    .filterNot { isProtectedSystemName(it.name) }
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll()
            }
        }

        val dirName = relativeDir.substringAfterLast('/').ifEmpty { source.displayName }
        return classifyRemoteListingWithPeeks(dirName, children, peeks, grandPeeks)
    }

    suspend fun listImageFileNames(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ) = WebDavClient.listImageFileNames(source, password, relativeDir)
}
