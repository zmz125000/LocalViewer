package com.ehviewer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.ehviewer.core.database.model.Search

@Dao
interface SearchDao {
    @Query("DELETE FROM suggestions")
    suspend fun clear()

    @Query("DELETE FROM suggestions WHERE `query` = :query AND kind = :kind")
    suspend fun deleteQuery(query: String, kind: Int)

    @Query("SELECT DISTINCT `query` FROM suggestions WHERE kind = :kind AND `query` LIKE :prefix || '%' ORDER BY date DESC LIMIT :limit")
    suspend fun rawSuggestions(prefix: String, kind: Int, limit: Int): List<String>

    @Query("SELECT DISTINCT `query` FROM suggestions WHERE kind = :kind ORDER BY date DESC LIMIT :limit")
    suspend fun list(kind: Int, limit: Int): List<String>

    @Insert
    suspend fun insert(search: Search)
}
