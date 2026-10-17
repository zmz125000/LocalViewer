package com.hippo.ehviewer.ui.reader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.hdr.DirectPixels
import com.hippo.ehviewer.image.hdr.LibDirectDecode
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.image.hdr.mimeType
import com.hippo.ehviewer.image.hdr.resolve
import com.hippo.ehviewer.image.hdr.stillExportFileName
import com.hippo.ehviewer.image.hdr.writeResolved
import com.hippo.ehviewer.ui.main.BrowseSaveAs
import com.hippo.ehviewer.util.awaitActivityResult
import com.hippo.ehviewer.util.displayPath
import java.io.File
import kotlinx.coroutines.CancellationException
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

context(_: SnackbarHostState, _: Context, loader: ReaderSession)
suspend fun convertTo(page: Page, target: StillExportTarget) {
    val leaf = loader.sourceLeafName(page.index)
    if (leaf == null) {
        snackbar(string(R.string.error_cant_save_image))
        return
    }
    if (target == StillExportTarget.AutoOriginal) {
        val saved = saveOriginalSize(leaf) { decodeOriginalPixels(loader, page.index, leaf) }
        if (!saved) snackbar(string(R.string.error_cant_save_image))
        return
    }
    val saved = pickAndWrite(leaf, target) { dest ->
        writeFullRes(loader, page.index, leaf, target, dest)
    }
    if (!saved) snackbar(string(R.string.error_cant_save_image))
}

context(_: SnackbarHostState, _: Context)
suspend fun convertLocalStill(path: Path, leaf: String, target: StillExportTarget) {
    convertStaged(leaf, target) { BrowseSaveAs.stageLocalForConvert(path) }
}

context(_: SnackbarHostState, _: Context)
suspend fun convertSmbStill(sourceId: Long, relativeFile: String, leaf: String, target: StillExportTarget) {
    convertStaged(leaf, target) { BrowseSaveAs.stageSmbForConvert(sourceId, relativeFile) }
}

context(_: SnackbarHostState, _: Context)
suspend fun convertWebDavStill(sourceId: Long, relativeFile: String, leaf: String, target: StillExportTarget) {
    convertStaged(leaf, target) { BrowseSaveAs.stageWebDavForConvert(sourceId, relativeFile) }
}

/** Decode [stage] and write one still into [dir]. No per-file picker or snackbar. */
suspend fun exportStillToDirectory(
    leaf: String,
    target: StillExportTarget,
    dir: Path,
    stage: suspend () -> File,
): Boolean {
    return try {
        val pixels = withIOContext {
            val file = stage()
            try {
                LibDirectDecode.decodePixels(file.asPathSource(), leaf, maxEdge = 0)
            } finally {
                file.delete()
            }
        } ?: return false
        val resolved = if (target == StillExportTarget.AutoOriginal) {
            target.resolve(pixels.isHdr, pixels.f16)
        } else {
            target
        }
        val name = stillExportFileName(leaf, resolved)
        val out = File.createTempFile("still-out", null, appCtx.cacheDir)
        try {
            val dest = withIOContext {
                if (!pixels.writeResolved(resolved, out) || out.length() <= 0L) {
                    null
                } else {
                    BrowseSaveAs.writeConvertFile(dir, name, out)
                }
            }
            dest != null
        } finally {
            out.delete()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        t.logcatExport()
        false
    }
}

context(_: SnackbarHostState, _: Context)
private suspend fun convertStaged(leaf: String, target: StillExportTarget, stage: suspend () -> File) {
    try {
        convertStagedUnchecked(leaf, target, stage)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        snackbar(string(R.string.error_cant_save_image))
    }
}

context(_: SnackbarHostState, _: Context)
private suspend fun convertStagedUnchecked(leaf: String, target: StillExportTarget, stage: suspend () -> File) {
    if (target == StillExportTarget.AutoOriginal) {
        val saved = saveOriginalSize(leaf) {
            val file = withIOContext { stage() }
            try {
                withIOContext { LibDirectDecode.decodePixels(file.asPathSource(), leaf, maxEdge = 0) }
            } finally {
                file.delete()
            }
        }
        if (!saved) snackbar(string(R.string.error_cant_save_image))
        return
    }
    val saved = pickAndWrite(leaf, target) { dest ->
        val pixels = withIOContext {
            val file = stage()
            try {
                LibDirectDecode.decodePixels(file.asPathSource(), leaf, maxEdge = 0)
            } finally {
                file.delete()
            }
        }
        if (pixels == null) {
            false
        } else {
            writeTemp(dest) { pixels.writeResolved(target, it) }
        }
    }
    if (!saved) snackbar(string(R.string.error_cant_save_image))
}

context(_: SnackbarHostState, ctx: Context)
private suspend fun pickAndWrite(
    leaf: String,
    resolved: StillExportTarget,
    write: suspend (Path) -> Boolean,
): Boolean {
    var picked = false
    val ok = runSuspendCatching {
        val uri = awaitActivityResult(
            ActivityResultContracts.CreateDocument(resolved.mimeType()),
            stillExportFileName(leaf, resolved),
        )
        if (uri == null) return@runSuspendCatching true
        picked = true
        // The picker creates an empty file before encode. Hold it pending so MediaStore
        // does not publish that row, then publish only after the finished bytes land.
        withIOContext { ctx.holdUnpublished(uri) }
        val wrote = try {
            withIOContext { write(uri.toOkioPath()) }
        } catch (t: Throwable) {
            withIOContext { ctx.discardUnpublished(uri) }
            throw t
        }
        withIOContext {
            if (wrote) ctx.publishMedia(uri) else ctx.discardUnpublished(uri)
        }
        if (wrote) snackbar(string(R.string.image_saved, uri.displayPath ?: uri.toString()))
        wrote
    }.onFailure {
        if (it is CancellationException) throw it
        it.logcatExport()
        snackbar(string(R.string.error_cant_find_activity))
    }.getOrDefault(true)
    return !picked || ok
}

private fun Context.holdUnpublished(uri: Uri) {
    runCatching {
        contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 1) },
            null,
            null,
        )
    }
}

private fun Context.publishMedia(uri: Uri) {
    runCatching {
        contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }
}

private fun Context.discardUnpublished(uri: Uri) {
    runCatching { contentResolver.delete(uri, null, null) }
    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
}

/**
 * Picks a folder immediately, then decodes. The file name follows the Auto rule
 * (`.jpg` for JPEG and Ultra HDR JPEG, `.png` for 16-bit PNG).
 * @return false when decode or write fails. Cancel returns true.
 */
context(_: SnackbarHostState, _: Context)
private suspend fun saveOriginalSize(leaf: String, decode: suspend () -> DirectPixels?): Boolean {
    val dir = BrowseSaveAs.pickConvertDirectory() ?: return true
    val pixels = decode() ?: return false
    val resolved = StillExportTarget.AutoOriginal.resolve(pixels.isHdr, pixels.f16)
    val name = stillExportFileName(leaf, resolved)
    val out = File.createTempFile("still-out", null, appCtx.cacheDir)
    return try {
        val dest = withIOContext {
            if (!pixels.writeResolved(resolved, out) || out.length() <= 0L) {
                null
            } else {
                BrowseSaveAs.writeConvertFile(dir, name, out)
            }
        } ?: return false
        snackbar(string(R.string.image_saved, dest.toUri().displayPath ?: dest.toString()))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        t.logcatExport()
        false
    } finally {
        out.delete()
    }
}

/** Full-resolution file decode. HDR pixels are later encoded as Ultra HDR JPEG. */
private suspend fun decodeOriginalPixels(
    loader: ReaderSession,
    index: Int,
    leaf: String,
): DirectPixels? = withIOContext {
    val src = File.createTempFile("still-src", null, appCtx.cacheDir)
    try {
        if (!loader.saveOriginal(index, src.toOkioPath())) {
            null
        } else {
            LibDirectDecode.decodePixels(src.asPathSource(), leaf, maxEdge = 0)
        }
    } finally {
        src.delete()
    }
}

private suspend fun writeFullRes(
    loader: ReaderSession,
    index: Int,
    leaf: String,
    resolved: StillExportTarget,
    dest: Path,
): Boolean {
    val src = File.createTempFile("still-src", null, appCtx.cacheDir)
    return try {
        if (!loader.saveOriginal(index, src.toOkioPath())) return false
        val pixels = LibDirectDecode.decodePixels(src.asPathSource(), leaf, maxEdge = 0) ?: return false
        writeTemp(dest) { pixels.writeResolved(resolved, it) }
    } finally {
        src.delete()
    }
}

private fun writeTemp(dest: Path, encode: (File) -> Boolean): Boolean {
    val out = File.createTempFile("still-out", null, appCtx.cacheDir)
    return try {
        if (!encode(out) || out.length() <= 0L) {
            false
        } else {
            out.toOkioPath() sendTo dest
            true
        }
    } catch (_: Throwable) {
        false
    } finally {
        out.delete()
    }
}

private fun File.asPathSource() = object : PathSource {
    override val source = toOkioPath()
    override val type = "application/octet-stream"
    override fun close() = Unit
}

private fun Throwable.logcatExport() {
    android.util.Log.e("StillConvert", message, this)
}
