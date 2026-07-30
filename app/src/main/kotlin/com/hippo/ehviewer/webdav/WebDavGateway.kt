package com.hippo.ehviewer.webdav

import com.ehviewer.core.database.model.WebDavSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.RemoteChild
import com.hippo.ehviewer.library.SMB_PROMOTE_MAX_LEAVES
import com.hippo.ehviewer.library.classifyRemoteListingWithPeeks
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

    /**
     * Stable identity for browse config / content (regular base URL only).
     * EasyTier alternate host is connect-path only and must not fork cache keys.
     */
    fun sourceConfigKey(source: WebDavSourceEntity): String =
        "${source.id}|${source.baseUrl}|${source.pathPrefix}|${source.username}"

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
    ): List<BrowseEntryRemote> = withIOContext {
        if (useCache) {
            BrowseSession.getWebDavListing(source.id, relativeDir)?.let { return@withIOContext it }
        } else {
            BrowseSession.invalidateWebDavListing(source.id, relativeDir)
        }
        val result = listDirectoryUncached(source, password, relativeDir)
        BrowseSession.putWebDavListing(source.id, relativeDir, result)
        result
    }

    private suspend fun listDirectoryUncached(
        source: WebDavSourceEntity,
        password: String,
        relativeDir: String,
    ): List<BrowseEntryRemote> {
        val children = WebDavClient.listChildren(source, password, relativeDir)
            .filterNot { it.name.startsWith('.') }

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
                            }.getOrDefault(emptyList())
                        }
                    }
                }.awaitAll()
            }
        }

        val grandPeeks = ConcurrentHashMap<String, List<RemoteChild>>()
        val leavesToPeek = ArrayList<Pair<String, String>>()
        for ((subName, peek) in peeks) {
            val leaves = peek.filter { it.isDirectory && !it.name.startsWith('.') }
            if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                for (leaf in leaves) {
                    leavesToPeek += subName to leaf.name
                }
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
