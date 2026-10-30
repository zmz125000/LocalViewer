package com.hippo.ehviewer.smb

/** DiskShare accepts slashes, but rename information needs a canonical share-relative path. */
internal fun smbTrashDirectories(dateDirectory: String): Pair<String, String> {
    val date = dateDirectory.replace('/', '\\').trim('\\')
    val root = date.substringBeforeLast('\\', "")
    require(root.isNotEmpty() && date.substringAfterLast('\\').isNotEmpty())
    return root to date
}

internal fun smbTrashTarget(directory: String, name: String): String {
    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
    return directory.replace('/', '\\').trim('\\') + "\\" + name
}
