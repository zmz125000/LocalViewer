package com.hippo.ehviewer.library

import com.ehviewer.core.database.model.SmbSourceEntity
import java.io.InputStream

/** Reuse one bounded block. Await each write before reading more source data. */
internal suspend fun copyTransferBlocks(
    input: InputStream,
    checkpoint: suspend () -> Unit,
    progress: (Long) -> Unit,
    write: suspend (ByteArray, Int) -> Unit,
) {
    val buffer = ByteArray(256 * 1024)
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        if (count < 0) return
        if (count == 0) continue
        checkpoint()
        write(buffer, count)
        progress(count.toLong())
    }
}

internal fun canCopySmbOnServer(from: SmbSourceEntity, fromPassword: String, to: SmbSourceEntity, toPassword: String): Boolean = from.host.trim().equals(to.host.trim(), ignoreCase = true) && from.port == to.port &&
    from.username == to.username && from.domain == to.domain && fromPassword == toPassword

/** The destination rejected an exclusive create; callers may skip or choose another name. */
class TransferNameConflict(name: String) : java.io.IOException("Destination already exists: $name")

internal data class TransferCreation<T>(val name: String, val value: T)

/** Both preflight collisions and exclusive-create races use the same skip/rename policy. */
internal suspend fun <T> createTransferWithoutOverwrite(
    name: String,
    skipExisting: Boolean,
    checkpoint: suspend () -> Unit,
    exists: suspend (String) -> Boolean,
    create: suspend (String) -> T,
): TransferCreation<T>? {
    var candidate = name
    var number = 1L
    while (true) {
        checkpoint()
        if (!exists(candidate)) {
            try {
                return TransferCreation(candidate, create(candidate))
            } catch (_: TransferNameConflict) {
                // Someone created the target between exists() and create().
            }
        }
        if (skipExisting) return null
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        candidate = "${name.take(dot)} (${++number})${name.drop(dot)}"
    }
}
