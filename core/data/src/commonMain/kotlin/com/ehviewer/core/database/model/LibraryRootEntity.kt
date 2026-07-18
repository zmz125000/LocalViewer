package com.ehviewer.core.database.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** SAF tree scanned into Library tab and listed under Browse. */
const val LIBRARY_ROOT_ROLE_LIBRARY = 1

/** SAF tree listed under Browse only (not library-scanned). */
const val LIBRARY_ROOT_ROLE_FOLDER = 2

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
) {
    val isLibraryRole: Boolean get() = role == LIBRARY_ROOT_ROLE_LIBRARY
    val isFolderOnlyRole: Boolean get() = role == LIBRARY_ROOT_ROLE_FOLDER
}
