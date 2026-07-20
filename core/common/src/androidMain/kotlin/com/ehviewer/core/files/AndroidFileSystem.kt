package com.ehviewer.core.files

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Int64Ref
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.core.database.getLongOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import okio.FileHandle
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source

class AndroidFileSystem(context: Context) : FileSystem() {
    private val contentResolver = context.contentResolver
    private val physicalFileSystem = SYSTEM

    override fun appendingSink(file: Path, mustExist: Boolean): Sink {
        TODO("Not yet implemented")
    }

    override fun atomicMove(source: Path, target: Path) {
        if (source.isPhysicalFile()) {
            return physicalFileSystem.atomicMove(source, target)
        }

        source.runCatching {
            DocumentsContract.renameDocument(contentResolver, toUri(), target.name)
        }.onFailure {
            // ExternalStorageProvider always throw exception when renameDocument on API 28
            // https://android.googlesource.com/platform/frameworks/base/+/7bf90408e36613a84dc2a665905fde2c83cfa797
            if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) {
                throw FileNotFoundException("Failed to move $source to $target")
            }
        }
    }

    override fun canonicalize(path: Path): Path {
        TODO("Not yet implemented")
    }

    override fun copy(source: Path, target: Path) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            source.openFileDescriptor("r").use { src ->
                target.openFileDescriptor("wt").use { dst ->
                    try {
                        Os.sendfile(dst.fileDescriptor, src.fileDescriptor, Int64Ref(0), Long.MAX_VALUE)
                        return
                    } catch (_: ErrnoException) {}
                }
            }
        }

        // Fallback to transferTo if sendfile is not available or fails
        source.inputStream().use { src ->
            target.outputStream().use { dst ->
                src.channel.transferTo(0, Long.MAX_VALUE, dst.channel)
            }
        }
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        if (dir.isPhysicalFile()) {
            return physicalFileSystem.createDirectory(dir, mustCreate)
        }

        val alreadyExist = metadataOrNull(dir)?.isDirectory == true
        if (alreadyExist) {
            if (mustCreate) {
                throw IOException("$dir already exist")
            } else {
                return
            }
        }

        dir.parent?.runCatching {
            DocumentsContract.createDocument(contentResolver, toUri(), Document.MIME_TYPE_DIR, dir.name)
        }?.getOrNull() ?: throw IOException("Failed to create directory: $dir")
    }

    override fun createSymlink(source: Path, target: Path) {
        TODO("Not yet implemented")
    }

    override fun delete(path: Path, mustExist: Boolean) {
        if (path.isPhysicalFile()) {
            return physicalFileSystem.delete(path, mustExist)
        }

        val metadata = metadataOrNull(path)

        if (metadata != null) {
            var uri = path.toUri()
            if (uri.isCifsDocument() && metadata.isDirectory) {
                uri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getDocumentId(uri) + '/')
            }

            val deleted = runCatching {
                DocumentsContract.deleteDocument(contentResolver, uri)
            }.getOrDefault(false)
            if (!deleted) {
                throw IOException("Failed to delete $path")
            }
        } else if (mustExist) {
            throw FileNotFoundException("$path does not exist")
        }
    }

    override fun deleteRecursively(fileOrDirectory: Path, mustExist: Boolean) {
        if (fileOrDirectory.isPhysicalFile()) {
            if (metadataOrNull(fileOrDirectory)?.isDirectory == true) {
                physicalFileSystem.deleteRecursively(fileOrDirectory, mustExist)
            } else {
                physicalFileSystem.delete(fileOrDirectory, mustExist)
            }
        } else {
            delete(fileOrDirectory, mustExist)
        }
    }

    override fun list(dir: Path): List<Path> = list(dir, throwOnFailure = true)!!

    override fun listOrNull(dir: Path): List<Path>? = list(dir, throwOnFailure = false)

    private fun list(dir: Path, throwOnFailure: Boolean): List<Path>? {
        if (dir.isPhysicalFile()) {
            return if (throwOnFailure) {
                physicalFileSystem.list(dir)
            } else {
                physicalFileSystem.listOrNull(dir)
            }
        }

        // Virtual MediaStore folder tree (READ_MEDIA_IMAGES) — not a DocumentsProvider path.
        if (dir.isMediaStoreVirtualDir()) {
            return runCatching {
                listMediaStoreVirtualChildren(dir)
            }.getOrElse { if (throwOnFailure) throw FileNotFoundException("Failed to list $dir") else null }
        }

        return runCatching {
            val uri = dir.toUri()
            var documentId = DocumentsContract.getDocumentId(uri)
            if (uri.isCifsDocument()) {
                documentId += '/'
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)

            contentResolver.query(childrenUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                List(c.count) {
                    c.moveToNext()
                    val displayName = c.getString(0)
                    dir / displayName
                }
            }
        }.getOrElse { if (throwOnFailure) throw FileNotFoundException("Failed to list $dir") else null }
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        if (path.isPhysicalFile()) {
            return physicalFileSystem.metadataOrNull(path)
        }

        // Synthetic MediaStore paths (mediastore:/Pictures/… or …/001.jpg)
        if (path.isMediaStoreVirtualDir()) {
            val name = path.name
            val looksLikeFile = name.contains('.') && !name.startsWith('.')
            // Heuristic: last segment with an extension is a file; otherwise directory.
            return if (looksLikeFile) {
                FileMetadata(isRegularFile = true, isDirectory = false)
            } else {
                FileMetadata(isRegularFile = false, isDirectory = true)
            }
        }

        return runCatching {
            val uri = path.toUri()
            val isMediaUri = uri.authority == MediaStore.AUTHORITY
            val projection = if (isMediaUri) {
                arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED)
            } else {
                arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
            }

            contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToNext()) return null

                val mimeType = c.getString(0)
                val lastModified = c.getLongOrNull(1)?.let { if (isMediaUri) it * 1000 else it }
                val isDirectory = mimeType == Document.MIME_TYPE_DIR

                FileMetadata(
                    isRegularFile = !isDirectory,
                    isDirectory = isDirectory,
                    lastModifiedAtMillis = lastModified,
                )
            }
        }.getOrNull()
    }

    /**
     * List virtual folder children via MediaStore RELATIVE_PATH.
     * Files use `mediastore:/…/name.jpg` so natural sort keeps real filenames.
     */
    private fun listMediaStoreVirtualChildren(dir: Path): List<Path> {
        val relativeDir = dir.toString()
            .removePrefix("mediastore:")
            .trimStart('/')
            .trimEnd('/')
        val dirs = linkedMapOf<String, Path>()
        val images = ArrayList<Path>()
        val prefix = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC",
        )?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val pathIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val displayName = c.getString(nameIdx) ?: continue
                if (displayName.startsWith('.')) continue
                val relPath = (c.getString(pathIdx) ?: "").trim('/').trimEnd('/')
                if (relativeDir.isEmpty()) {
                    if (relPath.isEmpty()) {
                        images += "mediastore:/$displayName".toPath()
                    } else {
                        val top = relPath.substringBefore('/')
                        if (top.isNotEmpty()) {
                            dirs.putIfAbsent(top, "mediastore:/$top".toPath())
                        }
                    }
                    continue
                }
                if (relPath == relativeDir) {
                    images += "mediastore:/$relativeDir/$displayName".toPath()
                    continue
                }
                if (relPath.startsWith(prefix)) {
                    val rest = relPath.removePrefix(prefix)
                    if (rest.isEmpty()) continue
                    val childName = rest.substringBefore('/')
                    if (childName.isNotEmpty()) {
                        dirs.putIfAbsent(childName, "mediastore:/$relativeDir/$childName".toPath())
                    }
                }
            }
        }
        return dirs.values.toList() + images
    }

    override fun openReadOnly(file: Path): FileHandle {
        TODO("Not yet implemented")
    }

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
        TODO("Not yet implemented")
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        TODO("Not yet implemented")
    }

    override fun source(file: Path): Source {
        TODO("Not yet implemented")
    }

    fun rawSink(file: Path) = file.outputStream().asSink()

    fun rawSource(file: Path) = file.inputStream().asSource()

    fun openFileDescriptor(path: Path, mode: String): ParcelFileDescriptor {
        if (path.isPhysicalFile()) {
            return ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.parseMode(mode))
        }

        return runCatching {
            // Resolve virtual MediaStore file path → content:// then open.
            if (path.isMediaStoreVirtualDir()) {
                val contentUri = resolveMediaStoreFileUri(path)
                    ?: throw FileNotFoundException("MediaStore file not found: $path")
                return@runCatching contentResolver.openFileDescriptor(contentUri, mode)
            }
            if ('w' in mode && !exists(path)) {
                val parent = path.parent ?: return@runCatching null
                val displayName = path.name
                val extension = displayName.substringAfterLast('.', "").ifEmpty { null }?.lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                DocumentsContract.createDocument(contentResolver, parent.toUri(), mimeType, displayName)
            }
            contentResolver.openFileDescriptor(path.toUri(), mode)
        }.getOrNull() ?: throw FileNotFoundException("Failed to open file: $path")
    }

    private fun resolveMediaStoreFileUri(path: Path): Uri? {
        val s = path.toString().removePrefix("mediastore:").trimStart('/')
        if (s.isEmpty()) return null
        val fileName = s.substringAfterLast('/')
        val relativeDir = s.substringBeforeLast('/', missingDelimiterValue = "").trimEnd('/')
        if (fileName.isEmpty() || !fileName.contains('.')) return null
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val relWithSlash = if (relativeDir.isEmpty()) "" else "$relativeDir/"
        val selection: String
        val args: Array<String>
        if (relativeDir.isEmpty()) {
            selection = "(${MediaStore.Images.Media.RELATIVE_PATH} IS NULL OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = '' OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = '/') AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            args = arrayOf(fileName)
        } else {
            selection = "(${MediaStore.Images.Media.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?) AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            args = arrayOf(relWithSlash, relativeDir, fileName)
        }
        contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                return MediaStore.Images.Media
                    .getContentUri(MediaStore.VOLUME_EXTERNAL)
                    .buildUpon()
                    .appendPath(c.getLong(0).toString())
                    .build()
            }
        }
        return null
    }

    private fun Path.inputStream() = ParcelFileDescriptor.AutoCloseInputStream(openFileDescriptor(this, "r"))

    private fun Path.outputStream() = ParcelFileDescriptor.AutoCloseOutputStream(openFileDescriptor(this, "wt"))
}

private fun Path.isPhysicalFile() = toString().startsWith('/')

/** Virtual directory from READ_MEDIA_IMAGES / MediaStore RELATIVE_PATH tree. */
private fun Path.isMediaStoreVirtualDir() = toString().startsWith("mediastore:")

private fun Uri.isCifsDocument() = authority == "com.wa2c.android.cifsdocumentsprovider.documents"
