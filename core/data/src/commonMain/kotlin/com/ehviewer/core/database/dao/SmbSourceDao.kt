package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ehviewer.core.database.model.SmbSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SmbSourceDao {
    @Query("SELECT * FROM SMB_SOURCES ORDER BY DISPLAY_NAME COLLATE NOCASE ASC")
    fun listFlow(): Flow<List<SmbSourceEntity>>

    @Query("SELECT * FROM SMB_SOURCES ORDER BY DISPLAY_NAME COLLATE NOCASE ASC")
    suspend fun list(): List<SmbSourceEntity>

    @Query("SELECT * FROM SMB_SOURCES WHERE ID = :id")
    suspend fun load(id: Long): SmbSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: SmbSourceEntity): Long

    @Update
    suspend fun update(source: SmbSourceEntity)

    @Delete
    suspend fun delete(source: SmbSourceEntity)

    @Query("DELETE FROM SMB_SOURCES WHERE ID = :id")
    suspend fun deleteById(id: Long)
}
