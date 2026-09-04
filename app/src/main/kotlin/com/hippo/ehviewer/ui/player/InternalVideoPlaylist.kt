package com.hippo.ehviewer.ui.player

import android.net.Uri
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.mimeTypeForFileName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Lightweight playlist entry. Stream tokens are created when the item is selected. */
sealed interface InternalVideoSource {
    val displayName: String
    val mimeType: String get() = mimeTypeForFileName(displayName)
    val identity: String

    data class Local(val path: String) : InternalVideoSource {
        override val displayName: String
            get() = ZipPaths.memberLeafName(path)
                ?: path.substringAfterLast('/').substringAfterLast('\\')
        override val identity: String get() = "local:$path"
    }

    data class Smb(val sourceId: Long, val remotePath: String) : InternalVideoSource {
        override val displayName: String
            get() = remotePath.substringAfterLast('/').substringAfterLast('\\')
        override val identity: String get() = "smb:$sourceId:$remotePath"
    }

    data class WebDav(val sourceId: Long, val remotePath: String) : InternalVideoSource {
        override val displayName: String
            get() = remotePath.substringAfterLast('/').substringAfterLast('\\')
        override val identity: String get() = "dav:$sourceId:$remotePath"
    }
}

data class PreparedInternalVideo(
    val token: String,
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val network: Boolean,
)

object InternalVideoPlaylistRegistry {
    data class Session(
        val id: String,
        val items: List<InternalVideoSource>,
    )

    data class Created(
        val session: Session,
        val initialIndex: Int,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(
        current: InternalVideoSource,
        candidates: List<InternalVideoSource>,
    ): Created {
        val items = buildList {
            val seen = HashSet<String>()
            for (item in candidates) {
                if (seen.add(item.identity)) add(item)
            }
            if (seen.add(current.identity)) add(current)
        }
        val id = UUID.randomUUID().toString()
        val session = Session(id = id, items = items)
        sessions[id] = session
        return Created(
            session = session,
            initialIndex = items.indexOfFirst { it.identity == current.identity }.coerceAtLeast(0),
        )
    }

    fun get(id: String?): Session? = id?.let(sessions::get)

    fun remove(id: String?) {
        if (id != null) sessions.remove(id)
    }
}
