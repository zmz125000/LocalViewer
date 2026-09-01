package com.hippo.ehviewer.library

import okio.Path.Companion.toPath

/**
 * Treat a ZIP/CBZ central directory as a virtual folder tree for browse / library.
 *
 * Listing and classify are pure over an already-open [ZipCentralDirectory] (EOCD+CD
 * only — no member extract). Peeks for promote/dual-gallery come from filtering the
 * same CD by prefix (no extra IO).
 */
object ZipAsDirListing {
    fun normalizePrefix(prefix: String): String =
        prefix.replace('\\', '/').trim('/').let { if (it == "." || it.isEmpty()) "" else it }

    fun joinPrefix(parent: String, child: String): String {
        val p = normalizePrefix(parent)
        val c = child.replace('\\', '/').trim('/')
        if (c.isEmpty()) return p
        return if (p.isEmpty()) c else "$p/$c"
    }

    /**
     * Immediate children under [innerPrefix] as [RemoteChild] rows.
     * Directories are inferred from nested paths and explicit `…/` entries.
     * Encrypted members are skipped.
     */
    fun listChildren(cd: ZipCentralDirectory, innerPrefix: String = ""): List<RemoteChild> {
        val prefix = normalizePrefix(innerPrefix)
        val prefixSlash = if (prefix.isEmpty()) "" else "$prefix/"
        val dirs = LinkedHashMap<String, RemoteChild>()
        val files = LinkedHashMap<String, RemoteChild>()

        for (entry in cd.entries) {
            if (entry.isEncrypted) continue
            val name = normalizeMember(entry.name) ?: continue
            val rel = if (prefixSlash.isEmpty()) {
                name
            } else if (name.startsWith(prefixSlash)) {
                name.removePrefix(prefixSlash)
            } else {
                continue
            }
            if (rel.isEmpty()) continue

            val slash = rel.indexOf('/')
            if (slash < 0) {
                // File at this level (directory markers end with / and become empty after trim).
                if (entry.isDirectory) continue
                files.putIfAbsent(
                    rel,
                    RemoteChild(
                        name = rel,
                        isDirectory = false,
                        path = rel,
                        size = entry.uncompressedSize.coerceAtLeast(0L),
                    ),
                )
            } else {
                val seg = rel.substring(0, slash)
                if (seg.isEmpty() || seg.startsWith('.')) continue
                dirs.putIfAbsent(
                    seg,
                    RemoteChild(name = seg, isDirectory = true, path = seg),
                )
            }
        }

        val out = ArrayList<RemoteChild>(dirs.size + files.size)
        out += dirs.values.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        out += files.values.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        return out
    }

    /**
     * Classify [innerPrefix] like a remote folder listing, with peeks filled from the CD.
     */
    fun classifyAt(
        cd: ZipCentralDirectory,
        innerPrefix: String = "",
        currentDirName: String = "",
    ): List<BrowseEntryRemote> {
        val children = listChildren(cd, innerPrefix)
        val childPeeks = HashMap<String, List<RemoteChild>>()
        val grandPeeks = HashMap<String, List<RemoteChild>>()
        for (child in children) {
            if (!child.isDirectory) continue
            val childPrefix = joinPrefix(innerPrefix, child.name)
            val peek = listChildren(cd, childPrefix)
            childPeeks[child.name] = peek
            val leaves = peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
            val leavesToPeek = if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
                leaves
            } else if (leaves.isNotEmpty()) {
                listOf(leaves.first())
            } else {
                emptyList()
            }
            for (leaf in leavesToPeek) {
                val key = "${child.name}/${leaf.name}"
                grandPeeks[key] = listChildren(cd, joinPrefix(childPrefix, leaf.name))
            }
        }
        val title = currentDirName.ifBlank {
            normalizePrefix(innerPrefix).substringAfterLast('/').ifEmpty { "Gallery" }
        }
        return classifyRemoteListingWithPeeks(
            currentDirName = title,
            entries = children,
            childPeeks = childPeeks,
            grandPeeks = grandPeeks,
        )
    }

    /**
     * Flat comic heuristic: no subdirectory children, and every file at this level is an image.
     * Used when zip-as-dir is on so classic CBZ still opens the archive reader.
     */
    fun shouldOpenAsArchiveReader(cd: ZipCentralDirectory, innerPrefix: String = ""): Boolean {
        val children = listChildren(cd, innerPrefix)
        if (children.any { it.isDirectory }) return false
        val files = children.filter { !it.isDirectory }
        if (files.isEmpty()) return true
        return files.all { isImageFileName(it.name) }
    }

    /** Direct image basenames under [innerPrefix] (natural sort). */
    fun directImageNames(cd: ZipCentralDirectory, innerPrefix: String = ""): List<String> =
        listChildren(cd, innerPrefix)
            .asSequence()
            .filter { !it.isDirectory && isImageFileName(it.name) }
            .map { it.name }
            .sortedWith { a, b -> naturalCompare(a, b) }
            .toList()

    /**
     * Every directory prefix (including `""` for zip root) that has at least one
     * direct image — for library scan when zip-as-dir is on.
     */
    fun imageBearingPrefixes(cd: ZipCentralDirectory): List<String> {
        val prefixes = LinkedHashSet<String>()
        for (entry in cd.entries) {
            if (entry.isEncrypted || entry.isDirectory) continue
            val name = normalizeMember(entry.name) ?: continue
            val fileName = name.substringAfterLast('/')
            if (!isImageFileName(fileName)) continue
            val parent = name.substringBeforeLast('/', missingDelimiterValue = "")
            prefixes += parent
        }
        return prefixes.sortedWith { a, b -> naturalCompare(a, b) }
    }

    /**
     * Turn classified remote rows into local [BrowseEntry] paths using [ZipPaths]
     * for files / gallery covers. Directory [BrowseEntry.Directory.path] is the zip
     * file itself (navigation uses [BrowseEntry.Directory.relativeName]).
     */
    fun materializeLocal(
        zipAbsolutePath: String,
        innerPrefix: String,
        remote: List<BrowseEntryRemote>,
    ): List<BrowseEntry> {
        val zipPath = zipAbsolutePath.toPath()
        return remote.map { entry ->
            when (entry) {
                is BrowseEntryRemote.Directory -> {
                    val childInner = joinPrefix(innerPrefix, entry.relativeName.ifEmpty { entry.name })
                    BrowseEntry.Directory(
                        name = entry.name,
                        path = zipPath,
                        relativeName = entry.relativeName.ifEmpty { entry.name },
                        hasVideo = entry.hasVideo,
                        hasGallery = entry.hasGallery,
                        presence = entry.presence,
                        coverPath = entry.coverFileName?.let { cover ->
                            ZipPaths.encodePath(zipAbsolutePath, joinPrefix(childInner, cover))
                        },
                        lastModifiedMs = entry.lastModifiedMs,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
                is BrowseEntryRemote.FolderGallery -> {
                    val galleryInner = if (entry.relativeName.isEmpty()) {
                        normalizePrefix(innerPrefix)
                    } else {
                        joinPrefix(innerPrefix, entry.relativeName)
                    }
                    val coverMember = entry.coverFileName?.let { joinPrefix(galleryInner, it) }
                    BrowseEntry.FolderGallery(
                        name = entry.name,
                        path = zipPath,
                        relativeName = galleryInner,
                        pageCount = entry.pageCount,
                        pageCountCapped = entry.pageCountCapped,
                        coverPath = coverMember?.let { ZipPaths.encodePath(zipAbsolutePath, it) },
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
                is BrowseEntryRemote.ArchiveGallery -> {
                    val parent = if (entry.parentRelativeName.isEmpty()) {
                        normalizePrefix(innerPrefix)
                    } else {
                        joinPrefix(innerPrefix, entry.parentRelativeName)
                    }
                    val member = joinPrefix(parent, entry.fileName)
                    BrowseEntry.ArchiveGallery(
                        name = entry.name,
                        path = ZipPaths.encodePath(zipAbsolutePath, member),
                        size = entry.size,
                        lastModifiedMs = entry.lastModifiedMs,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
                is BrowseEntryRemote.VideoFile -> {
                    val member = joinPrefix(innerPrefix, entry.fileName)
                    BrowseEntry.VideoFile(
                        name = entry.name,
                        path = ZipPaths.encodePath(zipAbsolutePath, member),
                        size = entry.size,
                        lastModifiedMs = entry.lastModifiedMs,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
                is BrowseEntryRemote.RegularFile -> {
                    val member = joinPrefix(innerPrefix, entry.fileName)
                    BrowseEntry.RegularFile(
                        name = entry.name,
                        path = ZipPaths.encodePath(zipAbsolutePath, member),
                        size = entry.size,
                        lastModifiedMs = entry.lastModifiedMs,
                        hidden = entry.hidden,
                        virtual = entry.virtual,
                    )
                }
            }
        }
    }

    /** First image member path relative to the zip root under [innerPrefix] (cover). */
    fun firstImageMember(cd: ZipCentralDirectory, innerPrefix: String = ""): String? {
        val prefix = normalizePrefix(innerPrefix)
        val prefixSlash = if (prefix.isEmpty()) "" else "$prefix/"
        var best: String? = null
        for (entry in cd.entries) {
            if (entry.isEncrypted || entry.isDirectory) continue
            val name = normalizeMember(entry.name) ?: continue
            if (prefixSlash.isNotEmpty() && !name.startsWith(prefixSlash)) continue
            val rel = if (prefixSlash.isEmpty()) name else name.removePrefix(prefixSlash)
            if (rel.isEmpty() || '/' in rel) continue
            if (!isImageFileName(rel)) continue
            if (best == null || naturalCompare(rel, best) < 0) best = rel
        }
        return best?.let { if (prefix.isEmpty()) it else "$prefix/$it" }
    }

    private fun normalizeMember(raw: String): String? {
        val name = raw.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name == ".") return null
        // Skip absolute / drive-style / traversal junk.
        if (name.startsWith("../") || name.contains("/../") || name == "..") return null
        return name
    }
}
