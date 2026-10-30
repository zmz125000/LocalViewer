package com.hippo.ehviewer.library

/** Shared source ownership survives job dismissal. Cached listings cannot revive a moved path. */
internal class NetworkSourceGuard {
    private data class Active(val deleting: Boolean, val roots: Set<TransferLocation>, val attempted: MutableSet<TransferLocation> = HashSet())
    private val active = HashMap<Long, Active>()
    private val unavailable = HashMap<TransferLocation, Long>()
    private var ids = 0L
    private var revision = 0L

    inner class Lease internal constructor(private val id: Long) : AutoCloseable {
        fun attempted(source: TransferLocation) = markAttempted(id, source)
        override fun close() = release(id)
    }

    private fun normalize(source: TransferLocation) = source.copy(path = source.path.replace('\\', '/').trim('/'))
    private fun contains(parent: TransferLocation, child: TransferLocation): Boolean = parent.kind == child.kind && parent.sourceId == child.sourceId &&
        (parent.path == child.path || parent.path.isEmpty() || child.path.startsWith(parent.path + "/"))

    /** Upload reads may coexist; a delete excludes overlapping reads and other deletes. */
    @Synchronized
    fun acquire(sources: List<TransferLocation>, deleting: Boolean): Lease? {
        val roots = sources.filter { it.kind != ExplorerWindows.Kind.Local }.mapTo(HashSet(), ::normalize)
        if (roots.any { source -> unavailable.keys.any { contains(it, source) } }) return null
        if (active.values.any { entry ->
                (deleting || entry.deleting) && roots.any { source -> entry.roots.any { contains(it, source) || contains(source, it) } }
            }
        ) {
            return null
        }
        val id = ++ids
        active[id] = Active(deleting, roots)
        return Lease(id)
    }

    @Synchronized
    private fun markAttempted(id: Long, source: TransferLocation) {
        val entry = active[id] ?: return
        if (entry.deleting) entry.attempted += normalize(source)
    }

    @Synchronized
    private fun release(id: Long) {
        val entry = active.remove(id) ?: return
        if (entry.deleting && entry.attempted.isNotEmpty()) {
            val completed = ++revision
            entry.attempted.forEach { unavailable[it] = completed }
        }
    }

    @Synchronized
    fun listingStarted(): Long = revision

    /** Only a request started after the move finished can confirm a restored/recreated path. */
    @Synchronized
    fun listed(parent: TransferLocation, names: List<String>, started: Long) {
        val directory = normalize(parent)
        val present = names.filter { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
            .mapTo(HashSet()) { normalize(directory.child(it)) }
        unavailable.entries.removeAll { (source, completed) -> completed <= started && source in present }
    }
}

internal val networkSourceGuard = NetworkSourceGuard()
