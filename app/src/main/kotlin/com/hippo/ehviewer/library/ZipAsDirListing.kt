package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import okio.Path
import okio.Path.Companion.toPath

/**
 * Treat a ZIP/CBZ central directory as a virtual folder tree for browse / library.
 *
 * Listing and classify are pure over an already-open [ZipCentralDirectory] (EOCD+CD
 * only — no member extract). Peeks for promote/dual-gallery come from filtering the
 * same CD by prefix (no extra IO).
 *
 * Parent-folder listings must [expandZipFilesAsFakeFolders] **before**
 * [classifyRemoteListingWithPeeks]: zip/cbz files become directory children whose
 * peeks are the CD listing. Otherwise DirectoryListing's `isArchiveFileName` branch
 * classifies them as [BrowseEntryRemote.ArchiveGallery] and folder view keeps showing
 * archive galleries. [rewriteZipArchivesAsFolders] is only a cache/toggle fallback.
 */
object ZipAsDirListing {
    fun normalizePrefix(prefix: String): String = prefix.replace('\\', '/').trim('/').let { if (it == "." || it.isEmpty()) "" else it }

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

    /** One-level CD listing of a zip root plus grand-peeks for promote/cover. */
    data class ZipRootListing(
        val children: List<RemoteChild>,
        /** Leaf basename → listing; caller prefixes with `zipName/`. */
        val grandPeeks: Map<String, List<RemoteChild>>,
    )

    /**
     * Parent listing after zip/cbz files were rewritten to directory children.
     * [peeks] / [grandPeeks] keys use the zip file name as the directory name.
     * [galleryListings] are flat / single-folder zips left as files for gallery classify.
     */
    data class ZipFakeFolderExpansion(
        val children: List<RemoteChild>,
        val peeks: Map<String, List<RemoteChild>>,
        val grandPeeks: Map<String, List<RemoteChild>>,
        val galleryListings: Map<String, ZipRootListing> = emptyMap(),
    )

    /** Flat images, or exactly one wrapper folder of images — open as a gallery, not a dir. */
    data class ZipSimpleGallery(
        val innerPrefix: String,
        val imageNames: List<String>,
    )

    fun simpleGalleryFromListing(listing: ZipRootListing): ZipSimpleGallery? {
        val dirs = listing.children.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
        val files = listing.children.filter { !it.isDirectory }
        val images = files.filter { isImageFileName(it.name) }
        if (dirs.isEmpty() && images.isNotEmpty() &&
            files.none { isArchiveFileName(it.name) || isBrowseVideoFileName(it.name) }
        ) {
            val names = images.map { it.name }.sortedWith { a, b -> naturalCompare(a, b) }
            return ZipSimpleGallery("", names)
        }
        if (dirs.size == 1 && files.isEmpty()) {
            val leaf = dirs.single().name
            val peek = listing.grandPeeks[leaf] ?: return null
            if (peek.any { it.isDirectory && isPromotableLeafDirName(it.name) }) return null
            val leafImages = peek.filter { !it.isDirectory && isImageFileName(it.name) }
            if (leafImages.isEmpty()) return null
            val names = leafImages.map { it.name }.sortedWith { a, b -> naturalCompare(a, b) }
            return ZipSimpleGallery(leaf, names)
        }
        return null
    }

    fun zipRootListingFromCd(cd: ZipCentralDirectory, innerPrefix: String = ""): ZipRootListing {
        val peek = listChildren(cd, innerPrefix)
        val leaves = peek.filter { it.isDirectory && isPromotableLeafDirName(it.name) }
        val leavesToPeek = if (leaves.size in 1..SMB_PROMOTE_MAX_LEAVES) {
            leaves
        } else if (leaves.isNotEmpty()) {
            listOf(leaves.first())
        } else {
            emptyList()
        }
        val grand = LinkedHashMap<String, List<RemoteChild>>()
        for (leaf in leavesToPeek) {
            grand[leaf.name] = listChildren(cd, joinPrefix(innerPrefix, leaf.name))
        }
        return ZipRootListing(peek, grand)
    }

    /**
     * Replace zip/cbz **files** with fake directories and attach CD peeks so
     * [classifyRemoteListingWithPeeks] treats them like real folders (Directory /
     * FolderGallery / promote) instead of [BrowseEntryRemote.ArchiveGallery].
     *
     * Unreadable zips stay files. Real directories named `*.zip` are not touched.
     */
    fun expandZipFilesAsFakeFolders(
        children: List<RemoteChild>,
        listZipRoot: (fileName: String) -> ZipRootListing?,
    ): ZipFakeFolderExpansion {
        if (children.none { !it.isDirectory && isZipArchiveFileName(it.name) }) {
            return ZipFakeFolderExpansion(children, emptyMap(), emptyMap())
        }
        val peeks = LinkedHashMap<String, List<RemoteChild>>()
        val grandPeeks = LinkedHashMap<String, List<RemoteChild>>()
        val galleryListings = LinkedHashMap<String, ZipRootListing>()
        val out = ArrayList<RemoteChild>(children.size)
        for (child in children) {
            if (child.isDirectory || !isZipArchiveFileName(child.name)) {
                out += child
                continue
            }
            val listing = listZipRoot(child.name)
            if (listing == null) {
                out += child
                continue
            }
            if (simpleGalleryFromListing(listing) != null) {
                galleryListings[child.name] = listing
                out += child
                continue
            }
            peeks[child.name] = listing.children
            for ((leaf, leafPeek) in listing.grandPeeks) {
                grandPeeks["${child.name}/$leaf"] = leafPeek
            }
            out += child.copy(isDirectory = true, size = 0L)
        }
        return ZipFakeFolderExpansion(out, peeks, grandPeeks, galleryListings)
    }

    /**
     * Classify a parent listing after injecting zip/cbz files as fake folders with CD peeks.
     * [listZipRoot] returns null to leave that zip as a file (ArchiveGallery).
     */
    fun classifyListingWithZipAsDirs(
        currentDirName: String,
        children: List<RemoteChild>,
        childPeeks: Map<String, List<RemoteChild>>,
        grandPeeks: Map<String, List<RemoteChild>>,
        listZipRoot: (fileName: String) -> ZipRootListing?,
    ): List<BrowseEntryRemote> {
        val expansion = expandZipFilesAsFakeFolders(children, listZipRoot)
        val peeks = HashMap<String, List<RemoteChild>>(childPeeks.size + expansion.peeks.size)
        peeks.putAll(childPeeks)
        peeks.putAll(expansion.peeks)
        val grands = HashMap<String, List<RemoteChild>>(grandPeeks.size + expansion.grandPeeks.size)
        grands.putAll(grandPeeks)
        grands.putAll(expansion.grandPeeks)
        val tagged = expansion.children.withHiddenFlags(peeks)
        val classified = classifyRemoteListingWithPeeks(currentDirName, tagged, peeks, grands)
        if (expansion.galleryListings.isEmpty()) return classified
        return classified.map { entry ->
            if (entry !is BrowseEntryRemote.ArchiveGallery || !isZipArchiveFileName(entry.fileName)) {
                return@map entry
            }
            val listing = expansion.galleryListings[entry.fileName] ?: return@map entry
            folderGalleryForZip(entry, listing)
        }
    }

    fun folderGalleryForZip(
        archive: BrowseEntryRemote.ArchiveGallery,
        listing: ZipRootListing,
    ): BrowseEntryRemote {
        val simple = simpleGalleryFromListing(listing) ?: return archive
        val rel = if (simple.innerPrefix.isEmpty()) {
            archive.fileName
        } else {
            "${archive.fileName}/${simple.innerPrefix}"
        }
        return BrowseEntryRemote.FolderGallery(
            name = archive.name,
            relativeName = rel,
            pageCount = simple.imageNames.size,
            pageCountCapped = false,
            coverFileName = simple.imageNames.first(),
            imageFileNames = simple.imageNames,
            hidden = archive.hidden,
            virtual = false,
        )
    }

    /** Shallow paint: zip/cbz files become Pending directories (no CD yet). */
    fun zipFilesAsPendingDirectories(children: List<RemoteChild>): List<RemoteChild> {
        if (children.none { !it.isDirectory && isZipArchiveFileName(it.name) }) return children
        return children.map { child ->
            if (!child.isDirectory && isZipArchiveFileName(child.name)) {
                child.copy(isDirectory = true, size = 0L)
            } else {
                child
            }
        }
    }

    /**
     * When [relativeFilePath] is `file.zip/inner/member` under zip-as-dir, the zip file
     * and the member path. Null for a bare zip file or a normal remote path.
     */
    fun zipMemberPath(relativeFilePath: String): Pair<String, String>? {
        if (!Settings.browseZipAsDir.value) return null
        val split = splitZipBrowsePath(relativeFilePath) ?: return null
        if (split.second.isEmpty()) return null
        return split
    }

    /**
     * History / library relative path for a zip gallery: `dir/file.zip|Album` or
     * `dir/file.zip/Album`.
     */
    fun parseZipGalleryRelative(rel: String): Pair<String, String>? {
        val n = rel.replace('\\', '/').trim('/')
        if (n.isEmpty()) return null
        val pipe = n.indexOf('|')
        if (pipe >= 0) {
            val zipRel = n.substring(0, pipe)
            val inner = n.substring(pipe + 1)
            if (isZipArchiveFileName(zipRel.substringAfterLast('/'))) return zipRel to inner
        }
        return splitZipBrowsePath(n)
    }

    /** Split `dir/file.zip/Album` into zip relative path + inner prefix. */
    fun splitZipBrowsePath(relativeDir: String): Pair<String, String>? {
        val segs = relativeDir.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
        val i = segs.indexOfFirst { isZipArchiveFileName(it) }
        if (i < 0) return null
        val zipRel = segs.take(i + 1).joinToString("/")
        val inner = segs.drop(i + 1).joinToString("/")
        return zipRel to inner
    }

    /**
     * Cover member inside a zip for a classified row in [listedDir].
     * @return zip relative path + member path, or null when this is not a zip-as-dir row.
     */
    fun zipAsDirCoverParts(
        listedDir: String,
        relativeName: String,
        coverFileName: String?,
    ): Pair<String, String>? {
        if (coverFileName.isNullOrEmpty()) return null
        val zipSeg = zipFileSegment(relativeName)
        val listed = splitZipBrowsePath(listedDir)
        val (zipRel, inner) = when {
            zipSeg != null -> {
                val parent = listedDir.replace('\\', '/').trim('/')
                val zip = if (parent.isEmpty()) zipSeg else "$parent/$zipSeg"
                zip to zipInnerPrefix(relativeName)
            }
            listed != null -> listed.first to joinPrefix(listed.second, relativeName)
            else -> return null
        }
        return zipRel to joinPrefix(inner, coverFileName)
    }

    /** Live zip/cbz **files** (not directories) in a parent listing. */
    fun zipFileNames(children: List<RemoteChild>): Set<String> = children.mapNotNull { child ->
        child.name.takeIf { !child.isDirectory && isZipArchiveFileName(child.name) }
    }.toSet()

    /**
     * First path segment when it is a zip/cbz file name (parent listing zip-as-dir).
     * [fallbackName] is used when [relativeName] is empty (current-dir gallery).
     */
    fun zipFileSegment(relativeName: String, fallbackName: String = ""): String? {
        val rel = relativeName.replace('\\', '/').trim('/')
        val first = rel.substringBefore('/').ifEmpty { fallbackName }
        // Virtual `@S` display names can end in `.zip`; they are not the zip file.
        if (first.startsWith('@')) return null
        return first.takeIf { isZipArchiveFileName(it) }
    }

    /**
     * Prefix inside the zip for a classified row whose [relativeName] is
     * `file.zip` or `file.zip/Album` / `file.zip/S/leaf`.
     */
    fun zipInnerPrefix(relativeName: String): String {
        val rel = relativeName.replace('\\', '/').trim('/')
        if (rel.isEmpty()) return ""
        val first = rel.substringBefore('/')
        if (!isZipArchiveFileName(first)) return rel
        return if ('/' in rel) rel.substringAfter('/') else ""
    }

    /** Direct zip-as-dir Directory / FolderGallery names already in a classified listing. */
    fun cachedDirectZipAsDirNames(entries: List<BrowseEntryRemote>): Set<String> {
        fun direct(name: String): String? {
            val rel = name.replace('\\', '/').trim('/')
            return rel.takeIf { it.isNotEmpty() && '/' !in it && isZipArchiveFileName(it) }
        }
        val names = HashSet<String>()
        for (entry in entries) {
            when (entry) {
                is BrowseEntryRemote.Directory -> direct(entry.relativeName.ifEmpty { entry.name })?.let { names += it }
                is BrowseEntryRemote.FolderGallery -> direct(entry.relativeName)?.let { names += it }
                else -> Unit
            }
        }
        return names
    }

    /** Direct image basenames under [innerPrefix] (natural sort). */
    fun directImageNames(cd: ZipCentralDirectory, innerPrefix: String = ""): List<String> = listChildren(cd, innerPrefix)
        .asSequence()
        .filter { !it.isDirectory && isImageFileName(it.name) }
        .map { it.name }
        .sortedWith { a, b -> naturalCompare(a, b) }
        .toList()

    /**
     * Apply [Settings.browseZipAsDir] to a classified listing (also when loading cache).
     * On: zip/cbz ArchiveGallery → FolderGallery (flat) or Directory (tree).
     * Off: reverse those rows back to ArchiveGallery.
     */
    fun applyZipAsDirPreference(
        entries: List<BrowseEntryRemote>,
        openCd: (fileName: String) -> ZipCentralDirectory?,
    ): List<BrowseEntryRemote> = if (Settings.browseZipAsDir.value) {
        rewriteZipArchivesAsFolders(entries, openCd)
    } else {
        demoteZipFoldersToArchives(entries)
    }

    /** Local FS helper for [applyZipAsDirPreference] (SAF-safe). */
    fun applyZipAsDirPreferenceLocal(
        entries: List<BrowseEntryRemote>,
        listedDir: Path,
    ): List<BrowseEntryRemote> = applyZipAsDirPreference(entries) { fileName ->
        withLocalZipCentralDirectory(listedDir / fileName) { it }
    }

    /**
     * After [classifyRemoteListing] / peeks: replace zip/cbz [BrowseEntryRemote.ArchiveGallery]
     * rows when [Settings.browseZipAsDir] is on.
     *
     * - Root has **no** subdirs and has images → [BrowseEntryRemote.FolderGallery]
     *   (`relativeName` = zip file name under the listed dir).
     * - Root has subdirs → [BrowseEntryRemote.Directory] (enter = zip-as-dir browse).
     * - Unreadable / empty → leave as archive.
     */
    fun rewriteZipArchivesAsFolders(
        entries: List<BrowseEntryRemote>,
        openCd: (fileName: String) -> ZipCentralDirectory?,
    ): List<BrowseEntryRemote> {
        if (entries.none { it is BrowseEntryRemote.ArchiveGallery && isZipArchiveFileName(it.fileName) }) {
            return entries
        }
        return entries.map { entry ->
            if (entry !is BrowseEntryRemote.ArchiveGallery || !isZipArchiveFileName(entry.fileName)) {
                return@map entry
            }
            val cd = openCd(entry.fileName) ?: return@map entry
            classifyZipFileAsBrowseEntry(cd, entry)
        }
    }

    /** Local FS helper: open CD for [fileName] under [listedDir] (SAF-safe). */
    fun rewriteZipArchivesAsFoldersLocal(
        entries: List<BrowseEntryRemote>,
        listedDir: Path,
    ): List<BrowseEntryRemote> = rewriteZipArchivesAsFolders(entries) { fileName ->
        withLocalZipCentralDirectory(listedDir / fileName) { it }
    }

    /** When zip-as-dir is off, restore zip FolderGallery / Directory rows to ArchiveGallery. */
    fun demoteZipFoldersToArchives(entries: List<BrowseEntryRemote>): List<BrowseEntryRemote> = entries.map { entry ->
        when (entry) {
            is BrowseEntryRemote.FolderGallery -> {
                val zipSeg = zipFileSegment(entry.relativeName, entry.name) ?: return@map entry
                BrowseEntryRemote.ArchiveGallery(
                    name = entry.name,
                    fileName = zipSeg,
                    parentRelativeName = "",
                    hidden = entry.hidden,
                )
            }
            is BrowseEntryRemote.Directory -> {
                val zipSeg = zipFileSegment(entry.relativeName, entry.name) ?: return@map entry
                BrowseEntryRemote.ArchiveGallery(
                    name = entry.name,
                    fileName = zipSeg,
                    parentRelativeName = "",
                    lastModifiedMs = entry.lastModifiedMs,
                    hidden = entry.hidden,
                )
            }
            else -> entry
        }
    }

    fun classifyZipFileAsBrowseEntry(
        cd: ZipCentralDirectory,
        archive: BrowseEntryRemote.ArchiveGallery,
    ): BrowseEntryRemote {
        val listing = zipRootListingFromCd(cd)
        val asGallery = folderGalleryForZip(archive, listing)
        if (asGallery is BrowseEntryRemote.FolderGallery) return asGallery
        val root = listing.children
        val hasSubdir = root.any { it.isDirectory }
        return when {
            hasSubdir -> {
                val anyImage = imageBearingPrefixes(cd).isNotEmpty()
                val anyVideo = cd.entries.any { e ->
                    !e.isEncrypted && !e.isDirectory &&
                        isBrowseVideoFileName(normalizeMember(e.name)?.substringAfterLast('/') ?: "")
                }
                // Cover: first image anywhere in the zip (member path relative to zip root).
                val cover = firstImageMemberAnywhere(cd)
                BrowseEntryRemote.Directory(
                    name = archive.name,
                    relativeName = archive.fileName,
                    hasVideo = anyVideo,
                    hasGallery = anyImage,
                    presence = DirPresence.Navigable,
                    coverFileName = cover,
                    lastModifiedMs = archive.lastModifiedMs,
                    hidden = archive.hidden,
                    virtual = false,
                )
            }
            else -> archive
        }
    }

    /** First image member path under the zip root (may be nested `Album/a.jpg`). */
    fun firstImageMemberAnywhere(cd: ZipCentralDirectory): String? {
        var best: String? = null
        for (entry in cd.entries) {
            if (entry.isEncrypted || entry.isDirectory) continue
            val name = normalizeMember(entry.name) ?: continue
            if (!isImageFileName(name.substringAfterLast('/'))) continue
            if (best == null || naturalCompare(name, best) < 0) best = name
        }
        return best
    }

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
