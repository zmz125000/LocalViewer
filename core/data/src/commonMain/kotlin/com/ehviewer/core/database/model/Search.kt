package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Library and History search field. */
const val SEARCH_KIND_LIBRARY = 0

/** Folder browsers (local, SMB, WebDAV). Separate from [SEARCH_KIND_LIBRARY]. */
const val SEARCH_KIND_FOLDER = 1

@Entity(tableName = "suggestions")
data class Search(
    @ColumnInfo(name = "date")
    val date: Long,
    @ColumnInfo(name = "query")
    val query: String,
    @ColumnInfo(name = "kind", defaultValue = "0")
    val kind: Int = SEARCH_KIND_LIBRARY,
    @PrimaryKey
    @ColumnInfo(name = "_id")
    val id: Int? = null,
)
