package com.hippo.ehviewer.gallery

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/**
 * Atomic single-flight ownership for source jobs.
 *
 * A lazy coroutine is registered before it is started, so `isActive` cannot identify its owner.
 * Every registered job that has not completed owns the key until [release] succeeds.
 */
@PublishedApi
internal class KeyedJobRegistry<K> {
    private val owners = ConcurrentHashMap<K, Job>()

    fun owner(key: K): Job? = owners[key]

    fun owns(key: K, job: Job?): Boolean = job != null && owners[key] === job

    /** Register [candidate] if no unfinished owner exists. The caller starts it only on true. */
    fun register(key: K, candidate: Job): Boolean {
        while (true) {
            val owner = owners.putIfAbsent(key, candidate)
            if (owner == null) return true
            if (!owner.isCompleted) return false
            if (owners.replace(key, owner, candidate)) return true
        }
    }

    /** Compare-and-remove so an old job can never release a replacement's ownership. */
    fun release(key: K, job: Job?): Boolean = job != null && owners.remove(key, job)

    fun cancelOutside(keys: Set<K>) {
        // Use ConcurrentHashMap's BiConsumer traversal; Android's EntryIterator can throw
        // while source completion concurrently removes an owner.
        owners.forEach { key, job -> if (key !in keys) job.cancel() }
    }

    fun cancelAll(): List<Job> {
        val jobs = LinkedHashSet<Job>()
        owners.forEach { _, job -> jobs.add(job) }
        jobs.forEach(Job::cancel)
        owners.clear()
        return jobs.toList()
    }
}
