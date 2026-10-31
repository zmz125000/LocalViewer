package com.hippo.ehviewer.library

import com.hippo.ehviewer.Settings
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persist file/folder and destination references, never a snapshot of their contents. */
internal object SavedUploads {
    @Serializable
    data class Location(val kind: String, val sourceId: Long, val path: String) {
        fun transfer() = TransferLocation(ExplorerWindows.Kind.valueOf(kind), sourceId, path)

        companion object {
            fun of(location: TransferLocation) = Location(location.kind.name, location.sourceId, location.path)
        }
    }

    @Serializable
    data class Entry(val id: String, val title: String, val source: Location, val destination: Location, val destinationLabel: String, val directory: Boolean = true, val gallery: Boolean = false) {
        fun file() = TransferFile(source.transfer(), title, directory, -1, 0)
    }

    fun decode(raw: String): List<Entry> = runCatching {
        Json.decodeFromString<List<Entry>>(raw).filter { entry ->
            runCatching {
                entry.source.transfer()
                entry.destination.transfer()
            }.isSuccess
        }
    }.getOrDefault(emptyList())

    fun encode(entries: List<Entry>): String = Json.encodeToString(entries)

    fun entries(): List<Entry> = decode(Settings.savedUploads.value)

    fun remember(file: TransferFile, destination: TransferLocation, label: String, gallery: Boolean = false) {
        val source = Location.of(file.source)
        val target = Location.of(destination)
        Settings.savedUploads.value = encode(
            listOf(Entry(UUID.randomUUID().toString(), file.relative, source, target, label, file.directory, gallery)) +
                entries().filterNot { it.source == source && it.destination == target },
        )
    }

    fun forget(id: String) {
        Settings.savedUploads.value = encode(entries().filterNot { it.id == id })
    }
}
