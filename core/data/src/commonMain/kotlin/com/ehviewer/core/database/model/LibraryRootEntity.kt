package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** SAF tree scanned into Library tab and listed under Browse. */
const val LIBRARY_ROOT_ROLE_LIBRARY = 1

/** SAF tree listed under Browse only (not library-scanned). */
const val LIBRARY_ROOT_ROLE_FOLDER = 2

/**
 * Prefer MediaStore for this source (images + videos, fast index).
 * Pure device-media roots are always this mode.
 */
const val LIBRARY_ROOT_ACCESS_MEDIA = 0

/**
 * SAF / file access for this source — images **and** local archives for
 * library scan / folder browse (no MediaStore rewrite).
 */
const val LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE = 1

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

    /**
     * [LIBRARY_ROOT_ROLE_LIBRARY] = scan + browse;
     * [LIBRARY_ROOT_ROLE_FOLDER] = browse only.
     */
    @ColumnInfo(name = "ROLE", defaultValue = "1")
    val role: Int = LIBRARY_ROOT_ROLE_LIBRARY,

    /**
     * [LIBRARY_ROOT_ACCESS_MEDIA] (default) or [LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE].
     * New and migrated roots use MediaStore; Manage Sources can switch a SAF root
     * to file access for local archive scan/browse.
     */
    @ColumnInfo(name = "ACCESS_MODE", defaultValue = "0")
    val accessMode: Int = LIBRARY_ROOT_ACCESS_MEDIA,
) {
    val isLibraryRole: Boolean get() = role == LIBRARY_ROOT_ROLE_LIBRARY
    val isFolderOnlyRole: Boolean get() = role == LIBRARY_ROOT_ROLE_FOLDER

    /** Prefer MediaStore rewrite when media permission is available (default). */
    val prefersMediaStore: Boolean get() = accessMode != LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE

    /** File access so archives stay visible in scan/browse. */
    val includesArchives: Boolean get() = accessMode == LIBRARY_ROOT_ACCESS_MEDIA_ARCHIVE
}
