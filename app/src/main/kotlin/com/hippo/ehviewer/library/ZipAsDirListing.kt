package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import okio.Path
import okio.Path.Companion.toPath

/**
 * Treat a ZIP/CBZ central directory as a virtual folder tree for browse / library.
 * Other archives (RAR/CBR/7z/TAR/CBT/PDF/EPUB) stay [BrowseEntryRemote.ArchiveGallery].
 *
 * Listing and classify are pure over an already-open [ZipCentralDirectory] (EOCD+CD
 * only — no member extract). Peeks for promote/dual-gallery come from filtering the
 * same CD by prefix (no extra IO).
 *
 * Parent-folder listings must [expandZipFilesAsFakeFolders] **before**
 * [classifyRemoteListingWithPeeks]: zip/cbz files become directory children whose
 * peeks are the CD listing. DirectoryListing then tags them like a real folder
 * (Directory in Folder view; FolderGallery / `@` promote in Galleries). Otherwise
 * `isArchiveFileName` classifies them as ArchiveGallery. [rewriteZipArchivesAsFolders]
 * is only a cache/toggle fallback.
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
     * Folder-index key for a zip/cbz virtual directory (`dir/file.zip` or
     * `dir/file.zip/Album`). Same relativeDir shape as a normal folder listing.
     */
    fun virtualRelativeDir(zipRel: String, inner: String = ""): String = joinPrefix(zipRel, inner)

    /**
     * Persist classified zip-as-dir folders under [parentRelativeDir].
     *
     * [interiors] keys are zip-relative (`pack.zip`, `pack.zip/Album`, …) so one EOCD
     * parse can store the whole virtual tree. Entering the zip or a subdir then hits
     * RAM/disk without another CD / quick scan. Zip/cbz only.
     *
     * @return saved listings keyed by full relativeDir (`parent/pack.zip/Album`).
     */
    suspend fun persistFolderIndexes(
        parentRelativeDir: String,
        interiors: Map<String, List<BrowseEntryRemote>>,
        save: suspend (relativeDir: String, entries: List<BrowseEntryRemote>) -> List<BrowseEntryRemote>,
        putRam: (relativeDir: String, entries: List<BrowseEntryRemote>) -> Unit,
    ): Map<String, List<BrowseEntryRemote>> {
        if (interiors.isEmpty()) return emptyMap()
        val stored = LinkedHashMap<String, List<BrowseEntryRemote>>(interiors.size)
        for ((rel, entries) in interiors) {
            val zipName = rel.substringBefore('/')
            if (!isZipArchiveFileName(zipName)) continue
            val dir = joinPrefix(parentRelativeDir, rel)
            val kept = save(dir, entries)
            putRam(dir, kept)
            stored[dir] = kept
        }
        return stored
    }

    /** Parent of a zip-relative path (`share/pack.zip` → `share`, `pack.zip` → `""`). */
    fun parentRelative(zipRel: String): String {
        val n = zipRel.replace('\\', '/').trim('/')
        val slash = n.lastIndexOf('/')
        return if (slash <= 0) "" else n.substring(0, slash)
    }

    /**
     * Every virtual folder in [cd] (zip root + nested dirs), keyed by zip-relative
     * path (`pack.zip`, `pack.zip/Album`). Cheap: filters the already-parsed CD.
     */
    fun classifyAllVirtualFolders(
        cd: ZipCentralDirectory,
        zipFileName: String,
    ): Map<String, List<BrowseEntryRemote>> {
        val prefixes = allDirectoryPrefixes(cd)
        val out = LinkedHashMap<String, List<BrowseEntryRemote>>(prefixes.size)
        for (prefix in prefixes) {
            val title = prefix.substringAfterLast('/').ifEmpty { zipFileName }
            val key = if (prefix.isEmpty()) zipFileName else "$zipFileName/$prefix"
            out[key] = classifyAt(cd, prefix, title)
        }
        return out
    }

    /** Zip-root `""` plus every nested directory prefix (skips encrypted / dot-hidden). */
    fun allDirectoryPrefixes(cd: ZipCentralDirectory): List<String> {
        val prefixes = LinkedHashSet<String>()
        prefixes.add("")
        for (entry in cd.entries) {
            if (entry.isEncrypted) continue
            val name = normalizeMember(entry.name) ?: continue
            val parts = name.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) continue
            val isDir = entry.isDirectory || name.endsWith('/')
            val dirParts = if (isDir) parts else parts.dropLast(1)
            var acc = ""
            for (seg in dirParts) {
                if (seg.startsWith('.')) break
                acc = if (acc.isEmpty()) seg else "$acc/$seg"
                prefixes.add(acc)
            }
        }
        return prefixes.toList()
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
     * DirectoryListing then tags the zip the same way as a real folder (Directory +
     * FolderGallery / @ promote), so Folder view shows the zip as a dir.
     */
    data class ZipFakeFolderExpansion(
        val children: List<RemoteChild>,
        val peeks: Map<String, List<RemoteChild>>,
        val grandPeeks: Map<String, List<RemoteChild>>,
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
            peeks[child.name] = listing.children
            for ((leaf, leafPeek) in listing.grandPeeks) {
                grandPeeks["${child.name}/$leaf"] = leafPeek
            }
            out += child.copy(isDirectory = true, size = 0L)
        }
        return ZipFakeFolderExpansion(out, peeks, grandPeeks)
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
        return classifyRemoteListingWithPeeks(currentDirName, tagged, peeks, grands)
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
     * History relative path for a zip-as-dir gallery: `dir/file.zip` or
     * `dir/file.zip|Album` (pipe keeps the zip file distinct from inner folders).
     */
    fun historyGalleryRelative(zipRel: String, inner: String = ""): String {
        val zip = zipRel.replace('\\', '/').trim('/')
        val prefix = normalizePrefix(inner)
        return if (prefix.isEmpty()) zip else "$zip|$prefix"
    }

    /**
     * History / library relative path for a zip gallery: `dir/file.zip|Album` or
     * `dir/file.zip/Album`.
     *
     * Also recovers a zip-as-dir gallery from a `zipfile:` cover when [rel] was
     * wrongly saved as a real folder (`parent/inner` without the `.zip` segment).
     */
    fun recoverZipGalleryRelative(
        rootAbsolutePath: String,
        relativePath: String,
        coverPath: String?,
    ): Pair<String, String>? {
        parseZipGalleryRelative(relativePath)?.let { return it }
        val (zipAbs, member) = ZipPaths.parseGallery(coverPath.orEmpty()) ?: return null
        val root = rootAbsolutePath.trimEnd('/')
        val zipRel = when {
            zipAbs == root -> zipAbs.substringAfterLast('/')
            zipAbs.startsWith("$root/") -> zipAbs.removePrefix("$root/")
            else -> return null
        }
        if (!isZipArchiveFileName(zipRel.substringAfterLast('/'))) return null
        val normalized = normalizePrefix(member)
        val inner = if (normalized.isNotEmpty() && isImageFileName(normalized.substringAfterLast('/'))) {
            normalizePrefix(normalized.substringBeforeLast('/'))
        } else {
            normalized
        }
        return zipRel to inner
    }

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

    /**
     * Browse-directory pin for a zip-as-dir gallery relative path.
     * Inner gallery → the zip folder (`dir/file.zip`); zip-root gallery → parent of the zip.
     */
    fun parentBrowseRelative(galleryRel: String): String {
        val parsed = parseZipGalleryRelative(galleryRel)
        if (parsed != null) {
            val (zipRel, inner) = parsed
            return if (inner.isEmpty()) parentRelativeOfFile(zipRel) else zipRel
        }
        return parentRelativeOfFile(galleryRel.replace('|', '/'))
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

    /**
     * Zip/cbz file names already represented as zip-as-dir rows in a classified listing.
     * Includes wrapper galleries (`file.zip/Album`) so slim refresh does not re-add them.
     */
    fun cachedDirectZipAsDirNames(entries: List<BrowseEntryRemote>): Set<String> {
        val names = HashSet<String>()
        for (entry in entries) {
            when (entry) {
                is BrowseEntryRemote.Directory ->
                    zipFileSegment(entry.relativeName.ifEmpty { entry.name }, entry.name)?.let { names += it }
                is BrowseEntryRemote.FolderGallery ->
                    zipFileSegment(entry.relativeName, entry.name)?.let { names += it }
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
        ensureZipAsDirDirectoryRows(rewriteZipArchivesAsFolders(entries, openCd))
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
     * After [classifyRemoteListing] / peeks: replace leftover zip/cbz
     * [BrowseEntryRemote.ArchiveGallery] rows when [Settings.browseZipAsDir] is on.
     *
     * Classifies each zip as a fake folder (same tags as a real directory): Directory
     * (Folder view) plus FolderGallery / @ promote (Galleries view). Unreadable zips
     * stay archives.
     */
    fun rewriteZipArchivesAsFolders(
        entries: List<BrowseEntryRemote>,
        openCd: (fileName: String) -> ZipCentralDirectory?,
    ): List<BrowseEntryRemote> {
        if (entries.none { it is BrowseEntryRemote.ArchiveGallery && isZipArchiveFileName(it.fileName) }) {
            return entries
        }
        val out = ArrayList<BrowseEntryRemote>(entries.size)
        for (entry in entries) {
            if (entry !is BrowseEntryRemote.ArchiveGallery || !isZipArchiveFileName(entry.fileName)) {
                out += entry
                continue
            }
            val cd = openCd(entry.fileName)
            if (cd == null) {
                out += entry
                continue
            }
            out += classifyZipFileAsFolderRows(cd, entry)
        }
        return out
    }

    /** Local FS helper: open CD for [fileName] under [listedDir] (SAF-safe). */
    fun rewriteZipArchivesAsFoldersLocal(
        entries: List<BrowseEntryRemote>,
        listedDir: Path,
    ): List<BrowseEntryRemote> = rewriteZipArchivesAsFolders(entries) { fileName ->
        withLocalZipCentralDirectory(listedDir / fileName) { it }
    }

    /** When zip-as-dir is off, restore zip FolderGallery / Directory rows to ArchiveGallery. */
    fun demoteZipFoldersToArchives(entries: List<BrowseEntryRemote>): List<BrowseEntryRemote> {
        val seen = HashSet<String>()
        val out = ArrayList<BrowseEntryRemote>(entries.size)
        for (entry in entries) {
            val zipSeg = when (entry) {
                is BrowseEntryRemote.FolderGallery -> zipFileSegment(entry.relativeName, entry.name)
                is BrowseEntryRemote.Directory -> zipFileSegment(entry.relativeName, entry.name)
                else -> null
            }
            if (zipSeg == null) {
                out += entry
                continue
            }
            if (!seen.add(zipSeg)) continue
            out += BrowseEntryRemote.ArchiveGallery(
                name = zipSeg,
                fileName = zipSeg,
                parentRelativeName = "",
                lastModifiedMs = (entry as? BrowseEntryRemote.Directory)?.lastModifiedMs ?: 0L,
                hidden = entry.hidden,
            )
        }
        return out
    }

    /**
     * Classify a zip/cbz file as if it were a child directory of the parent listing
     * (Directory + FolderGallery / @ promote — same tags as a real folder).
     */
    fun classifyZipFileAsFolderRows(
        cd: ZipCentralDirectory,
        archive: BrowseEntryRemote.ArchiveGallery,
    ): List<BrowseEntryRemote> {
        val listing = zipRootListingFromCd(cd)
        val fake = RemoteChild(
            name = archive.fileName,
            isDirectory = true,
            lastModifiedMs = archive.lastModifiedMs,
            hidden = archive.hidden,
        )
        val grands = LinkedHashMap<String, List<RemoteChild>>(listing.grandPeeks.size)
        for ((leaf, peek) in listing.grandPeeks) {
            grands["${archive.fileName}/$leaf"] = peek
        }
        return classifyRemoteListingWithPeeks(
            currentDirName = archive.name,
            entries = listOf(fake),
            childPeeks = mapOf(archive.fileName to listing.children),
            grandPeeks = grands,
        )
    }

    fun classifyZipFileAsBrowseEntry(
        cd: ZipCentralDirectory,
        archive: BrowseEntryRemote.ArchiveGallery,
    ): BrowseEntryRemote {
        val rows = classifyZipFileAsFolderRows(cd, archive)
        return rows.firstOrNull { it is BrowseEntryRemote.Directory }
            ?: rows.firstOrNull()
            ?: archive
    }

    /**
     * Old cache may have zip-as-dir as FolderGallery only (hidden in Folder view).
     * Add the missing Directory row so Folder mode shows the zip as a dir.
     */
    fun ensureZipAsDirDirectoryRows(entries: List<BrowseEntryRemote>): List<BrowseEntryRemote> {
        val zipDirs = HashSet<String>()
        for (entry in entries) {
            if (entry is BrowseEntryRemote.Directory) {
                zipFileSegment(entry.relativeName, entry.name)?.let { zipDirs += it }
            }
        }
        var extra: ArrayList<BrowseEntryRemote.Directory>? = null
        for (entry in entries) {
            if (entry !is BrowseEntryRemote.FolderGallery) continue
            val zipSeg = zipFileSegment(entry.relativeName, entry.name) ?: continue
            if (!zipDirs.add(zipSeg)) continue
            val inner = zipInnerPrefix(entry.relativeName)
            val dir = BrowseEntryRemote.Directory(
                name = zipSeg,
                relativeName = zipSeg,
                hasVideo = false,
                hasGallery = true,
                presence = if (inner.isEmpty()) DirPresence.LeafImages else DirPresence.PromotedShell,
                coverFileName = if (inner.isEmpty()) entry.coverFileName else null,
                hidden = entry.hidden,
            )
            if (extra == null) extra = ArrayList()
            extra += dir
        }
        return if (extra == null) entries else entries + extra
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
                        pageCount = entry.pageCount,
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
