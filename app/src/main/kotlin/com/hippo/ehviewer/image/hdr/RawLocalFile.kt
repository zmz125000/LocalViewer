package com.hippo.ehviewer.image.hdr

import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import okio.Path
import splitties.init.appCtx

/** LibRaw `open_file` only accepts a filesystem path. */
internal fun isPhysicalRawPath(path: String): Boolean = path.startsWith('/')

/**
 * Run [block] on a real file. A SAF or MediaStore path is copied into the cache
 * for the call and removed afterwards.
 */
internal inline fun <T> Path.withLocalRawFile(block: (File) -> T): T {
    val text = toString()
    if (isPhysicalRawPath(text)) {
        val file = File(text)
        if (!file.isFile || file.length() <= 0L) throw FileNotFoundException(text)
        return block(file)
    }
    val tmp = File.createTempFile("rawsrc", ".bin", appCtx.cacheDir)
    try {
        ParcelFileDescriptor.AutoCloseInputStream(openFileDescriptor("r")).use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }
        if (!tmp.isFile || tmp.length() <= 0L) throw FileNotFoundException(text)
        return block(tmp)
    } finally {
        tmp.delete()
    }
}
