package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings

/**
 * Compact MediaStore fingerprint for a library root so startup can skip a full
 * image dump when the index has not changed.
 *
 * [generation] is an equality token for the versions and generations of concrete
 * external volumes (API 30+), or [GENERATION_UNKNOWN] when unavailable.
 * [count] / [maxDateModifiedSecs] / [idXor] cover the Images rows under the
 * root (DATE_MODIFIED delta when generation is missing or volume-wide noise).
 */
data class MediaStoreIndexStamp(
    val generation: Long,
    val count: Int,
    val maxDateModifiedSecs: Long,
    val idXor: Long,
) {
    fun generationUnchanged(currentGeneration: Long): Boolean = generation > 0L && currentGeneration > 0L && generation == currentGeneration

    fun sameImages(other: MediaStoreIndexStamp): Boolean = count == other.count &&
        maxDateModifiedSecs == other.maxDateModifiedSecs &&
        idXor == other.idXor

    fun encode(rootId: Long): String = "$rootId$SEP$generation$SEP$count$SEP$maxDateModifiedSecs$SEP$idXor$SEP$VERSION"

    companion object {
        const val GENERATION_UNKNOWN = -1L
        private const val SEP = ':'

        /** Images+video fingerprint. Old 5-field stamps fail [parse] so startup rescans. */
        private const val VERSION = "5"

        /** Stable across enumeration order; database resets and mount changes invalidate it too. */
        fun volumeGenerationToken(volumes: List<Triple<String, String, Long>>): Long {
            if (volumes.isEmpty() || volumes.any { it.third < 0L }) return GENERATION_UNKNOWN
            var hash = -3750763034362895579L // FNV-1a, including names and database versions.
            fun absorb(value: String) {
                for (char in value) hash = (hash xor char.code.toLong()) * 1099511628211L
                hash = (hash xor 0L) * 1099511628211L
            }
            for ((name, version, generation) in volumes.sortedBy { it.first }) {
                absorb(name)
                absorb(version)
                absorb(generation.toString())
            }
            return (hash and Long.MAX_VALUE).coerceAtLeast(1L)
        }

        fun parse(encoded: String): Pair<Long, MediaStoreIndexStamp>? {
            val parts = encoded.split(SEP)
            // Older stamps could include removable media in the device library.
            if (parts.size != 6 || parts[5] != VERSION) return null
            val rootId = parts[0].toLongOrNull() ?: return null
            val generation = parts[1].toLongOrNull() ?: return null
            val count = parts[2].toIntOrNull() ?: return null
            val maxDate = parts[3].toLongOrNull() ?: return null
            val idXor = parts[4].toLongOrNull() ?: return null
            return rootId to MediaStoreIndexStamp(generation, count, maxDate, idXor)
        }

        /**
         * Skip a full MediaStore dump when [hasGalleries] and either the volume
         * generation matches or the image fingerprint matches.
         */
        fun shouldSkip(
            stored: MediaStoreIndexStamp?,
            current: MediaStoreIndexStamp,
            hasGalleries: Boolean,
        ): Boolean {
            if (!hasGalleries || stored == null) return false
            if (stored.generationUnchanged(current.generation)) return true
            return stored.sameImages(current)
        }

        fun load(rootId: Long): MediaStoreIndexStamp? {
            val prefix = "$rootId$SEP"
            val raw = Settings.libraryMediaStoreStamps.value.firstOrNull { it.startsWith(prefix) }
                ?: return null
            val parsed = parse(raw) ?: return null
            return parsed.second.takeIf { parsed.first == rootId }
        }

        fun remember(rootId: Long, stamp: MediaStoreIndexStamp) {
            val prefix = "$rootId$SEP"
            val without = Settings.libraryMediaStoreStamps.value.filterNot { it.startsWith(prefix) }.toSet()
            Settings.libraryMediaStoreStamps.value = without + stamp.encode(rootId)
        }

        fun clear(rootId: Long) {
            val prefix = "$rootId$SEP"
            val next = Settings.libraryMediaStoreStamps.value.filterNot { it.startsWith(prefix) }.toSet()
            if (next.size != Settings.libraryMediaStoreStamps.value.size) {
                Settings.libraryMediaStoreStamps.value = next
            }
        }
    }
}
