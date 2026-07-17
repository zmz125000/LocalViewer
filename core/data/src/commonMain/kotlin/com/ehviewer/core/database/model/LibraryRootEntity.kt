package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "LIBRARY_ROOTS")
data class LibraryRootEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID")
    val id: Long = 0,

    @ColumnInfo(name = "TREE_URI")
    val treeUri: String,

    @ColumnInfo(name = "DISPLAY_NAME")
    val displayName: String,

    @ColumnInfo(name = "ADDED_AT")
    val addedAt: Long,
)
