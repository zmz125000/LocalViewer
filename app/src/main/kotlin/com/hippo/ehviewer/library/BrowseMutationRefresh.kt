package com.hippo.ehviewer.library

/** Mutation hints survive navigation and failed scans. Newer changes cannot be cleared by an older scan. */
internal class BrowseMutationRefresh {
    data class Snapshot(val directory: TransferLocation, val versions: Map<TransferLocation, Long>) {
        val pending get() = versions.isNotEmpty()
        val currentChanged get() = directory in versions
        val childNames: Set<String> get() = versions.keys.filter { it != directory }
            .mapTo(HashSet()) { it.path.removePrefix(directory.path.let { p -> if (p.isEmpty()) "" else "$p/" }).substringBefore('/') }

        fun changedChildren(live: List<RemoteChild>): List<RemoteChild> {
            val names = childNames
            return live.filter { it.isDirectory && it.name in names && !isProtectedSystemName(it.name) }
        }
    }

    private var revision = 0L
    private val changed = HashMap<TransferLocation, Long>()
    private val checked = HashMap<TransferLocation, Long>()

    @Synchronized
    fun changed(directories: List<TransferLocation>) {
        val version = ++revision
        directories.forEach { changed[canonical(it)] = version }
    }

    @Synchronized
    fun snapshot(directory: TransferLocation): Snapshot {
        val key = canonical(directory)
        val previous = checked[key] ?: 0L
        return Snapshot(key, changed.filter { (path, version) -> version > previous && contains(key, path) })
    }

    @Synchronized
    fun complete(snapshot: Snapshot) {
        val version = snapshot.versions.values.maxOrNull() ?: return
        checked[snapshot.directory] = maxOf(checked[snapshot.directory] ?: 0L, version)
    }

    companion object {
        private fun contains(parent: TransferLocation, child: TransferLocation): Boolean = parent.kind == child.kind && parent.sourceId == child.sourceId &&
            (parent.path == child.path || parent.path.isEmpty() || child.path.startsWith(parent.path + "/"))

        /** Match SAF grants and MediaStore aliases without probing any storage device. */
        internal fun canonical(source: TransferLocation): TransferLocation {
            var path = source.path.replace('\\', '/').trimEnd('/')
            if (source.kind == ExplorerWindows.Kind.Local && path.startsWith("content:")) {
                val decoded = decodedTrashPath(path)
                val authority = decoded.substringAfter("content:").trimStart('/').substringBefore('/')
                val document = decoded.substringAfter("/document/", "")
                path = if (authority == "com.android.externalstorage.documents" && ':' in document) {
                    val volume = document.substringBefore(':')
                    val relative = document.substringAfter(':').trim('/')
                    val prefix = if (volume.equals("primary", true)) "mediastore:" else "mediastore:/@volume-${volume.lowercase()}"
                    listOf(prefix, relative).filter { it.isNotEmpty() }.joinToString("/")
                } else if (document.isNotEmpty()) {
                    "content:/$authority/document/$document"
                } else {
                    decoded
                }
            }
            if (source.kind != ExplorerWindows.Kind.Local) path = path.trim('/')
            return source.copy(path = path.trimEnd('/'))
        }
    }
}
