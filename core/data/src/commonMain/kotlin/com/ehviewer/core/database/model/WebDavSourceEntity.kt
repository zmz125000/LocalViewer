package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "WEBDAV_SOURCES")
data class WebDavSourceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "ID")
    val id: Long = 0,

    @ColumnInfo(name = "DISPLAY_NAME")
    val displayName: String,

    /**
     * Base WebDAV URL ending with `/`, e.g.
     * `https://cloud.example/remote.php/dav/files/user/`.
     */
    @ColumnInfo(name = "BASE_URL")
    val baseUrl: String,

    /** Optional subpath under [baseUrl], no leading slash. */
    @ColumnInfo(name = "PATH_PREFIX")
    val pathPrefix: String = "",

    @ColumnInfo(name = "USERNAME")
    val username: String = "",

    @ColumnInfo(name = "ADDED_AT")
    val addedAt: Long,

    @ColumnInfo(name = "LAST_OK_AT")
    val lastOkAt: Long? = null,

    @ColumnInfo(name = "LAST_ERROR")
    val lastError: String? = null,
)
