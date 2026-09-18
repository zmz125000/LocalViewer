package com.hippo.ehviewer.library

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Folder-view submit search: live filter stays in Directories/Galleries/Videos/Files;
 * these hits populate the Search section.
 *
 * SMB uses the server QUERY_DIRECTORY search pattern ([smbSearchPattern]); local and
 * WebDAV walk with [nameMatches] (same wildcard / contains rules).
 */
object FolderSearch {
    const val MAX_RESULTS = 500
    const val MAX_DIRS = 2000

    fun nameMatches(name: String, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        return if (hasWildcard(q)) globMatches(name, q) else name.contains(q, ignoreCase = true)
    }

    fun smbSearchPattern(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return ""
        return if (hasWildcard(q)) q else "*$q*"
    }

    fun isMatchAllPattern(pattern: String): Boolean {
        val p = pattern.trim()
        return p.isEmpty() || p == "*" || p == "*.*"
    }

    fun hasWildcard(query: String): Boolean = query.any { it == '*' || it == '?' }

    fun globMatches(name: String, pattern: String): Boolean {
        val regex = buildString {
            append('^')
            for (c in pattern) {
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }.toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(name)
    }

    fun joinRelative(parent: String, child: String): String {
        val p = parent.replace('\\', '/').trim('/')
        val c = child.replace('\\', '/').trim('/')
        return when {
            p.isEmpty() -> c
            c.isEmpty() -> p
            else -> "$p/$c"
        }
    }

    fun parentRelative(path: String): String {
        val rel = path.replace('\\', '/').trim('/')
        if (rel.isEmpty()) return ""
        val slash = rel.lastIndexOf('/')
        return if (slash < 0) "" else rel.substring(0, slash)
    }

    fun baseName(path: String): String = path.replace('\\', '/').trim('/').substringAfterLast('/')

    /**
     * Folder to enter for overflow "Open folder", relative to the current listing.
     * Empty means the item already lives in this folder — caller must no-op.
     *
     * Directories / folder galleries: the item itself. Files / videos / archives:
     * the parent of [relativePath].
     */
    fun openFolderTarget(relativePath: String, isDirectory: Boolean): String {
        val rel = relativePath.replace('\\', '/').trim('/')
        if (rel.isEmpty()) return ""
        return if (isDirectory) rel else parentRelative(rel)
    }

    fun relativeFromRoot(searchRoot: String, childRel: String): String {
        val root = searchRoot.replace('\\', '/').trim('/')
        val child = childRel.replace('\\', '/').trim('/')
        if (root.isEmpty()) return child
        if (child == root) return child.substringAfterLast('/').ifEmpty { child }
        val prefix = "$root/"
        return if (child.startsWith(prefix)) child.removePrefix(prefix) else child
    }

    fun hitFromChild(
        relFromRoot: String,
        child: RemoteChild,
        zipAsDir: Boolean,
    ): BrowseEntryRemote {
        val display = relFromRoot.ifEmpty { child.name }
        val rel = relFromRoot.ifEmpty { child.name }
        val hidden = child.hidden
        return when {
            child.isDirectory || (zipAsDir && isZipArchiveFileName(child.name)) ->
                BrowseEntryRemote.Directory(
                    name = display,
                    relativeName = rel,
                    hasVideo = false,
                    hasGallery = false,
                    presence = DirPresence.Navigable,
                    lastModifiedMs = child.lastModifiedMs,
                    size = child.size,
                    hidden = hidden,
                )
            isBrowseVideoFileName(child.name) ->
                BrowseEntryRemote.VideoFile(
                    name = display,
                    fileName = rel,
                    size = child.size,
                    lastModifiedMs = child.lastModifiedMs,
                    hidden = hidden,
                )
            isArchiveFileName(child.name) || isDocumentFileName(child.name) -> {
                val slash = rel.lastIndexOf('/')
                BrowseEntryRemote.ArchiveGallery(
                    name = display,
                    fileName = child.name,
                    parentRelativeName = if (slash < 0) "" else rel.substring(0, slash),
                    size = child.size,
                    lastModifiedMs = child.lastModifiedMs,
                    hidden = hidden,
                )
            }
            else -> BrowseEntryRemote.RegularFile(
                name = display,
                fileName = rel,
                size = child.size,
                lastModifiedMs = child.lastModifiedMs,
                hidden = hidden,
            )
        }
    }

    /**
     * Client-side recursive walk (local / WebDAV / zip CD). Caller supplies per-dir
     * children; cancellation is [ensureActive] plus aborting [listChildren].
     */
    suspend fun deepSearch(
        searchRoot: String,
        query: String,
        includeHidden: Boolean,
        zipAsDir: Boolean,
        parallelism: Int,
        listChildren: suspend (relativeDir: String) -> List<RemoteChild>,
        onHits: suspend (List<BrowseEntryRemote>) -> Unit = {},
    ): List<BrowseEntryRemote> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = Collections.synchronizedList(ArrayList<BrowseEntryRemote>())
        val seen = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.add(searchRoot)
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        var visited = 0
        while (queue.isNotEmpty() &&
            hits.size < MAX_RESULTS &&
            visited < MAX_DIRS
        ) {
            coroutineContext.ensureActive()
            val batch = ArrayList<String>()
            while (queue.isNotEmpty() &&
                batch.size < parallelism.coerceAtLeast(1) &&
                visited + batch.size < MAX_DIRS
            ) {
                val dir = queue.removeFirst()
                if (seen.add(dir)) batch += dir
            }
            if (batch.isEmpty()) break
            visited += batch.size
            val nextDirs = ConcurrentLinkedQueue<String>()
            coroutineScope {
                for (dir in batch) {
                    launch {
                        gate.withPermit {
                            val children = try {
                                listChildren(dir)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                emptyList()
                            }
                            for (child in children) {
                                if (hits.size >= MAX_RESULTS) return@withPermit
                                if (isProtectedSystemName(child.name)) continue
                                if (!includeHidden && (child.hidden || isDotHiddenName(child.name))) {
                                    continue
                                }
                                val childRel = joinRelative(dir, child.name)
                                if (nameMatches(child.name, q)) {
                                    val rel = relativeFromRoot(searchRoot, childRel)
                                    val hit = hitFromChild(rel, child, zipAsDir)
                                    synchronized(hits) {
                                        if (hits.size < MAX_RESULTS) hits += hit
                                    }
                                }
                                if (child.isDirectory) nextDirs += childRel
                            }
                        }
                    }
                }
            }
            onHits(ArrayList(hits))
            if (hits.size >= MAX_RESULTS) break
            queue.addAll(nextDirs)
        }
        val sorted = hits.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        onHits(sorted)
        return sorted
    }

    suspend fun searchZipCentralDirectory(
        cd: ZipCentralDirectory,
        inner: String,
        query: String,
        includeHidden: Boolean,
        onHits: suspend (List<BrowseEntryRemote>) -> Unit = {},
    ): List<BrowseEntryRemote> = deepSearch(
        searchRoot = inner,
        query = query,
        includeHidden = includeHidden,
        zipAsDir = true,
        parallelism = 1,
        listChildren = { prefix -> ZipAsDirListing.listChildren(cd, prefix) },
        onHits = onHits,
    )
}
