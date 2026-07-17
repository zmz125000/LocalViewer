package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SMB_SOURCES")
data class SmbSourceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID")
    val id: Long = 0,

    @ColumnInfo(name = "DISPLAY_NAME")
    val displayName: String,

    @ColumnInfo(name = "HOST")
    val host: String,

    @ColumnInfo(name = "PORT")
    val port: Int = 445,

    @ColumnInfo(name = "SHARE")
    val share: String,

    @ColumnInfo(name = "PATH_PREFIX")
    val pathPrefix: String = "",

    @ColumnInfo(name = "USERNAME")
    val username: String = "",

    @ColumnInfo(name = "DOMAIN")
    val domain: String = "",

    @ColumnInfo(name = "ADDED_AT")
    val addedAt: Long,

    @ColumnInfo(name = "LAST_OK_AT")
    val lastOkAt: Long? = null,

    @ColumnInfo(name = "LAST_ERROR")
    val lastError: String? = null,
)
