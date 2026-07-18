package com.hippo.ehviewer.smb

import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.localLibraryDb
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow

object SmbRepository {
    private val dao get() = localLibraryDb.smbSourceDao()

    fun sourcesFlow(): Flow<List<SmbSourceEntity>> = dao.listFlow()

    suspend fun load(id: Long): SmbSourceEntity? = dao.load(id)

    suspend fun add(
        displayName: String,
        host: String,
        port: Int,
        share: String,
        pathPrefix: String,
        username: String,
        domain: String,
        password: String,
    ): Long = withIOContext {
        val id = dao.insert(
            SmbSourceEntity(
                displayName = displayName.ifBlank { host },
                host = host.trim(),
                port = port,
                share = share.trim().trim('/'),
                pathPrefix = pathPrefix.trim().trim('/'),
                username = username,
                domain = domain,
                addedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )
        SmbPasswordStore.set(id, password)
        id
    }

    suspend fun update(
        source: SmbSourceEntity,
        password: String?,
    ) = withIOContext {
        dao.update(source)
        if (password != null) {
            SmbPasswordStore.set(source.id, password)
        }
        // Host/share/credentials may have changed — drop pooled session.
        SmbGateway.disconnect(source.id)
    }

    suspend fun delete(source: SmbSourceEntity) = withIOContext {
        SmbGateway.disconnect(source.id)
        SmbPasswordStore.remove(source.id)
        dao.delete(source)
    }

    suspend fun markOk(id: Long) = withIOContext {
        val src = dao.load(id) ?: return@withIOContext
        dao.update(
            src.copy(
                lastOkAt = Clock.System.now().toEpochMilliseconds(),
                lastError = null,
            ),
        )
    }

    suspend fun markError(id: Long, message: String) = withIOContext {
        val src = dao.load(id) ?: return@withIOContext
        dao.update(src.copy(lastError = message.take(500)))
    }
}
