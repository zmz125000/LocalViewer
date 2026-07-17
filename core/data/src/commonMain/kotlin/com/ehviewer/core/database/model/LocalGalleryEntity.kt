package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val LOCAL_GALLERY_KIND_FOLDER = 0
const val LOCAL_GALLERY_KIND_ARCHIVE = 1

@Entity(
    tableName = "LOCAL_GALLERIES",
    foreignKeys = [
        ForeignKey(
            entity = LibraryRootEntity::class,
            parentColumns = ["ID"],
            childColumns = ["ROOT_ID"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["ROOT_ID", "RELATIVE_PATH"], unique = true),
        Index(value = ["TITLE"]),
    ],
)
data class LocalGalleryEntity(
    /** Stable id used as synthetic GalleryInfo.gid for progress/history. */
    @PrimaryKey
    @ColumnInfo(name = "ID")
    val id: Long,

    @ColumnInfo(name = "ROOT_ID")
    val rootId: Long,

    @ColumnInfo(name = "RELATIVE_PATH")
    val relativePath: String,

    @ColumnInfo(name = "TITLE")
    val title: String,

    @ColumnInfo(name = "KIND")
    val kind: Int,

    @ColumnInfo(name = "PAGE_COUNT")
    val pageCount: Int,

    /** Okio path string of cover image (folder galleries) or null for archives. */
    @ColumnInfo(name = "COVER_PATH")
    val coverPath: String?,

    /** Absolute okio path string used to open the gallery. */
    @ColumnInfo(name = "CONTENT_PATH")
    val contentPath: String,

    @ColumnInfo(name = "MTIME")
    val mtime: Long,
)
