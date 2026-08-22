package com.hippo.ehviewer.library

/**
 * Merge MediaStore rows into a SAF listing, and group MediaStore image files into
 * folder-gallery relative paths. Pure so browse/scan hybrid listing can be tested
 * without Android ContentResolver.
 *
 * SAF mode still needs DocumentsContract for archives and folders MediaStore never
 * indexed. When media permission exists we list MediaStore first (files + subdirs)
 * and drop SAF rows whose names are already present — including skipping those files
 * entirely rather than re-reading SIZE/LAST_MODIFIED from the provider.
 */
object SafMediaStoreListing {
    /**
     * [mediaStore] first (attributes already known). [saf] contributes only names
     * MediaStore did not list (archives, empty dirs, not-yet-indexed files).
     */
    fun merge(
        mediaStore: List<BrowseChild>,
        saf: List<BrowseChild>,
    ): List<BrowseChild> {
        if (mediaStore.isEmpty()) return saf
        if (saf.isEmpty()) return mediaStore
        val seen = HashSet<String>(mediaStore.size)
        mediaStore.forEach { seen += it.name }
        return buildList(mediaStore.size + saf.size) {
            addAll(mediaStore)
            for (child in saf) {
                if (child.name !in seen) add(child)
            }
        }
    }

    /** One MediaStore image row used when grouping folder galleries. */
    data class ImageFile(
        val parentRelativePath: String,
        val name: String,
        /** Epoch ms ([MediaStore.MediaColumns.DATE_MODIFIED] × 1000). */
        val lastModifiedMs: Long = 0L,
    )

    /** Folder-gallery payload: natural-sorted names + latest image mtime for Date sort. */
    data class ImageFolder(
        val names: List<String>,
        val latestImageMs: Long,
    )

    /**
     * Map MediaStore image rows under [rootRelativeDir] to folder-gallery keys
     * relative to that root (`""` = images directly in the root).
     *
     * [ImageFile.parentRelativePath] is the file's MediaStore folder
     * (`Pictures/Comics/S`), not including the display name.
     */
    fun imageFoldersUnderRoot(
        rootRelativeDir: String,
        files: List<ImageFile>,
    ): Map<String, ImageFolder> {
        val root = rootRelativeDir.replace('\\', '/').trim('/')
        val namesByRel = LinkedHashMap<String, ArrayList<String>>()
        val latestByRel = HashMap<String, Long>()
        for (file in files) {
            if (!isImageFileName(file.name)) continue
            val parent = file.parentRelativePath.replace('\\', '/').trim('/')
            val rel = relativeUnderRoot(root, parent) ?: continue
            namesByRel.getOrPut(rel) { ArrayList() }.add(file.name)
            val prev = latestByRel[rel] ?: 0L
            if (file.lastModifiedMs > prev) latestByRel[rel] = file.lastModifiedMs
        }
        return namesByRel.mapValues { (rel, names) ->
            names.sortWith { a, b -> naturalCompare(a, b) }
            ImageFolder(names = names, latestImageMs = latestByRel[rel] ?: 0L)
        }
    }

    /**
     * [childRelative] resolved against [root], or null when [childRelative] is not
     * under the root (including the root itself → `""`).
     */
    fun relativeUnderRoot(rootRelativeDir: String, childRelative: String): String? {
        val root = rootRelativeDir.replace('\\', '/').trim('/')
        val child = childRelative.replace('\\', '/').trim('/')
        if (root.isEmpty()) return child
        if (child == root) return ""
        val prefix = "$root/"
        if (!child.startsWith(prefix)) return null
        return child.removePrefix(prefix)
    }
}
