package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ehviewer.core.database.model.LibraryRootEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryRootDao {
    @Query("SELECT * FROM LIBRARY_ROOTS ORDER BY ADDED_AT ASC")
    fun listFlow(): Flow<List<LibraryRootEntity>>

    @Query("SELECT * FROM LIBRARY_ROOTS ORDER BY ADDED_AT ASC")
    suspend fun list(): List<LibraryRootEntity>

    @Query("SELECT * FROM LIBRARY_ROOTS WHERE ROLE = :role ORDER BY ADDED_AT ASC")
    fun listByRoleFlow(role: Int): Flow<List<LibraryRootEntity>>

    @Query("SELECT * FROM LIBRARY_ROOTS WHERE ROLE = :role ORDER BY ADDED_AT ASC")
    suspend fun listByRole(role: Int): List<LibraryRootEntity>

    @Query("SELECT * FROM LIBRARY_ROOTS WHERE ID = :id")
    suspend fun load(id: Long): LibraryRootEntity?

    @Query("SELECT * FROM LIBRARY_ROOTS WHERE TREE_URI = :treeUri LIMIT 1")
    suspend fun loadByTreeUri(treeUri: String): LibraryRootEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(root: LibraryRootEntity): Long

    @Update
    suspend fun update(root: LibraryRootEntity)

    @Delete
    suspend fun delete(root: LibraryRootEntity)

    @Query("DELETE FROM LIBRARY_ROOTS WHERE ID = :id")
    suspend fun deleteById(id: Long)
}
