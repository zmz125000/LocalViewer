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

    /**
     * Map MediaStore image rows under [rootRelativeDir] to folder-gallery keys
     * relative to that root (`""` = images directly in the root).
     *
     * [parentRelativePath] is the file's MediaStore folder (`Pictures/Comics/S`),
     * not including the display name.
     */
    fun imageFoldersUnderRoot(
        rootRelativeDir: String,
        files: List<Pair<String, String>>,
    ): Map<String, List<String>> {
        val root = rootRelativeDir.replace('\\', '/').trim('/')
        val grouped = LinkedHashMap<String, ArrayList<String>>()
        for ((parentRel, name) in files) {
            if (!isImageFileName(name)) continue
            val parent = parentRel.replace('\\', '/').trim('/')
            val rel = relativeUnderRoot(root, parent) ?: continue
            grouped.getOrPut(rel) { ArrayList() }.add(name)
        }
        grouped.values.forEach { names ->
            names.sortWith { a, b -> naturalCompare(a, b) }
        }
        return grouped
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
