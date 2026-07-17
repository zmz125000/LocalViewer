package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ehviewer.core.database.model.LibraryRootEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryRootDao {
    @Query("SELECT * FROM LIBRARY_ROOTS ORDER BY ADDED_AT ASC")
    fun listFlow(): Flow<List<LibraryRootEntity>>

    @Query("SELECT * FROM LIBRARY_ROOTS ORDER BY ADDED_AT ASC")
    suspend fun list(): List<LibraryRootEntity>

    @Query("SELECT * FROM LIBRARY_ROOTS WHERE ID = :id")
    suspend fun load(id: Long): LibraryRootEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(root: LibraryRootEntity): Long

    @Delete
    suspend fun delete(root: LibraryRootEntity)

    @Query("DELETE FROM LIBRARY_ROOTS WHERE ID = :id")
    suspend fun deleteById(id: Long)
}
