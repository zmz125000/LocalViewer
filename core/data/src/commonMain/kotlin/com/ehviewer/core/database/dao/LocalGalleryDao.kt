package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ehviewer.core.database.model.LocalGalleryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalGalleryDao {
    @Query("SELECT * FROM LOCAL_GALLERIES ORDER BY TITLE COLLATE NOCASE ASC")
    fun listFlow(): Flow<List<LocalGalleryEntity>>

    @Query("SELECT * FROM LOCAL_GALLERIES WHERE TITLE LIKE '%' || :keyword || '%' ORDER BY TITLE COLLATE NOCASE ASC")
    fun searchFlow(keyword: String): Flow<List<LocalGalleryEntity>>

    @Query("SELECT * FROM LOCAL_GALLERIES WHERE ID = :id")
    suspend fun load(id: Long): LocalGalleryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(galleries: List<LocalGalleryEntity>)

    @Query("DELETE FROM LOCAL_GALLERIES WHERE ROOT_ID = :rootId")
    suspend fun deleteByRootId(rootId: Long)

    @Query("DELETE FROM LOCAL_GALLERIES")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceForRoot(rootId: Long, galleries: List<LocalGalleryEntity>) {
        deleteByRootId(rootId)
        if (galleries.isNotEmpty()) {
            upsertAll(galleries)
        }
    }
}
