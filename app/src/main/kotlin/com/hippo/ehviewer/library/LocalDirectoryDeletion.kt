package com.hippo.ehviewer.library

/** Include gallery/promoted rows even when a disk snapshot has no matching Directory row. */
internal fun missingLocalDirectoryRoots(cached: List<BrowseEntryRemote>, children: List<RemoteChild>): Set<String> = cached.mapNotNullTo(HashSet(), ::browseEntryFolderRoot) - children.mapTo(HashSet()) { it.name }

/** A second complete parent listing confirms absence; denied/null/loading queries prove nothing. */
internal suspend fun confirmedMissingLocalDirectoryRoots(
    cached: List<BrowseEntryRemote>,
    children: List<RemoteChild>,
    parentNames: suspend () -> Set<String>?,
): Set<String> {
    val missing = missingLocalDirectoryRoots(cached, children)
    if (missing.isEmpty()) return emptySet()
    val verified = parentNames() ?: return emptySet()
    return missing - verified
}
