package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ehviewer.core.database.model.WebDavSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WebDavSourceDao {
    @Query("SELECT * FROM WEBDAV_SOURCES ORDER BY DISPLAY_NAME COLLATE NOCASE ASC")
    fun listFlow(): Flow<List<WebDavSourceEntity>>

    @Query("SELECT * FROM WEBDAV_SOURCES ORDER BY DISPLAY_NAME COLLATE NOCASE ASC")
    suspend fun list(): List<WebDavSourceEntity>

    @Query("SELECT * FROM WEBDAV_SOURCES WHERE ID = :id")
    suspend fun load(id: Long): WebDavSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: WebDavSourceEntity): Long

    @Update
    suspend fun update(source: WebDavSourceEntity)

    @Delete
    suspend fun delete(source: WebDavSourceEntity)

    @Query("DELETE FROM WEBDAV_SOURCES WHERE ID = :id")
    suspend fun deleteById(id: Long)
}
