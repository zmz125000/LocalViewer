package com.hippo.ehviewer.library

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only absent cached names need a server check; a sparse listing alone proves nothing. */
internal suspend fun confirmedMissingRemoteDirectories(
    cached: List<BrowseEntryRemote>,
    children: List<RemoteChild>,
    parallelism: Int,
    isMissing: suspend (String) -> Boolean,
): Set<String> {
    // Zip-as-dir rows are still present when the live child is a zip file.
    val liveNames = children.mapTo(HashSet()) { it.name }
    val missing = cachedDirectDirectoryNames(cached) - liveNames
    if (missing.isEmpty()) return emptySet()
    return coroutineScope {
        val gate = Semaphore(parallelism.coerceIn(1, 8))
        missing.map { name ->
            async { gate.withPermit { name.takeIf { isMissing(it) } } }
        }.awaitAll().filterNotNull().toSet()
    }
}
