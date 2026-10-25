package com.hippo.ehviewer.ui.reader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.ReaderOriginalFile
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.hdr.LibDirectDecode
import com.hippo.ehviewer.image.hdr.StillExportQuality
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.image.hdr.StillExportWork
import com.hippo.ehviewer.image.hdr.mimeType
import com.hippo.ehviewer.image.hdr.stillExportFileName
import com.hippo.ehviewer.image.hdr.withRawExportControls
import com.hippo.ehviewer.image.hdr.withStillInput
import com.hippo.ehviewer.image.hdr.writeResolved
import com.hippo.ehviewer.jni.transcodeJpegBufferToJxl
import com.hippo.ehviewer.ui.main.BrowseSaveAs
import com.hippo.ehviewer.ui.main.enqueueSingleConvert
import com.hippo.ehviewer.util.awaitActivityResult
import com.hippo.ehviewer.util.displayPath
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.string
import okio.Path
import okio.Path.Companion.toOkioPath
import splitties.init.appCtx

context(snackbar: SnackbarHostState, ctx: Context, loader: ReaderSession)
suspend fun convertTo(page: Page, target: StillExportTarget) {
    val leaf = loader.sourceLeafName(page.index)
    if (leaf == null) {
        snackbar(string(R.string.error_cant_save_image))
        return
    }
    // Capture the page's original identity before the picker/queue can outlive
    // this session or its RAM/cache entry. Network reader caches can contain
    // embedded RAW previews or converted JPEGs instead of original codec bytes.
    val stage: suspend () -> File = when (val original = loader.originalFile(page.index)) {
        null -> {
            { stageOriginal(loader, page.index) }
        }
        else -> {
            { stageOriginal(original) }
        }
    }
    enqueueSingleConvert(snackbar, ctx, leaf, target) { dir, selected ->
        exportStillToDirectory(leaf, selected, dir, stage = stage)
    }
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
    localSource: Path? = null,
    stage: suspend () -> File,
): Boolean {
    return try {
        val encoded = encodeStaged(leaf, target, stage, localSource) ?: return false
        try {
            withIOContext { BrowseSaveAs.writeConvertFile(dir, stillExportFileName(leaf, encoded.target), encoded.file) }
        } finally {
            encoded.file.delete()
        }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        t.logcatExport()
        false
    }
}

context(_: SnackbarHostState, _: Context)
private suspend fun convertStaged(leaf: String, target: StillExportTarget, stage: suspend () -> File) = withRawExportControls(target) {
    try {
        convertStagedUnchecked(leaf, target, stage)
    } catch (e: CancellationException) {
        throw e
    } catch (error: Throwable) {
        error.logcatExport()
        snackbar(string(R.string.error_cant_save_image))
    }
}

context(_: SnackbarHostState, _: Context)
private suspend fun convertStagedUnchecked(leaf: String, target: StillExportTarget, stage: suspend () -> File) {
    val saved = if (Settings.browseAskWhereToSave.value) {
        pickAndWrite(leaf, target) { dest -> writeStaged(leaf, target, dest, stage) }
    } else {
        val dir = BrowseSaveAs.pickConvertDirectory() ?: return
        val encoded = encodeStaged(leaf, target, stage) ?: return
        try {
            val dest = withIOContext {
                BrowseSaveAs.writeConvertFile(dir, stillExportFileName(leaf, encoded.target), encoded.file)
            }
            snackbar(string(R.string.image_saved, dest.toUri().displayPath ?: dest.toString()))
            true
        } finally {
            encoded.file.delete()
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

private data class EncodedStill(val file: File, val target: StillExportTarget)

/** Download/stage first; retain the memory lease until encoding releases its pixels. */
private suspend fun encodeStaged(leaf: String, target: StillExportTarget, stage: suspend () -> File, localSource: Path? = null): EncodedStill? = withRawExportControls(target) {
    encodeStagedCaptured(leaf, target, stage, localSource)
}

private suspend fun encodeStagedCaptured(leaf: String, target: StillExportTarget, stage: suspend () -> File, localSource: Path?): EncodedStill? = withIOContext {
    check(target.available) { "Export encoder unavailable: $target" }
    val quality = currentCoroutineContext()[StillExportQuality] ?: StillExportQuality.current()
    withExportSource(localSource, stage) { source, decodeSource ->
        android.util.Log.i("StillConvert", "Start $leaf -> $target source=$decodeSource")
        StillExportWork.withSource(source, leaf) {
            if (target == StillExportTarget.Jxl && (quality.value == 0 || quality.jpegTranscoding) &&
                leaf.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "jpe", "jfif")
            ) {
                val out = File.createTempFile("still-out", null, appCtx.cacheDir)
                var success = false
                try {
                    val rc = withStillInput(decodeSource) { transcodeJpegBufferToJxl(it, out.absolutePath) } ?: -60
                    success = rc == 0 && out.length() > 0L
                    if (!success) {
                        android.util.Log.e("StillConvert", "Lossless JPEG transcoding failed: $leaf rc=$rc")
                    }
                    return@withSource if (success) EncodedStill(out, target) else null
                } finally {
                    if (!success) out.delete()
                }
            }
            LibDirectDecode.withExportPixels(decodeSource.asPathSource(), leaf, maxEdge = 0, target = target) { pixels ->
                val out = File.createTempFile("still-out", null, appCtx.cacheDir)
                var success = false
                try {
                    success = pixels.writeResolved(target, out, quality) && out.length() > 0L
                    if (success) {
                        EncodedStill(out, target)
                    } else {
                        android.util.Log.e("StillConvert", "Encode failed: $leaf -> $target bytes=${out.length()}")
                        null
                    }
                } finally {
                    if (!success) out.delete()
                }
            } ?: run {
                android.util.Log.e("StillConvert", "No encoded output: $leaf -> $target source=$decodeSource")
                null
            }
        }
    }
}

/** Borrow originals without deleting them; only temporary staged copies belong to this job. */
private suspend fun <T> withExportSource(
    localSource: Path?,
    stage: suspend () -> File,
    work: suspend (File, Path) -> T,
): T {
    localSource?.toString()?.takeIf { it.startsWith('/') }?.let(::File)?.takeIf { it.isFile }?.let {
        return work(it, it.toOkioPath())
    }
    if (localSource?.toUri()?.scheme == "content") {
        val descriptor = runCatching { localSource.openFileDescriptor("r") }.getOrNull()
        descriptor?.use { opened ->
            val regular = runCatching { OsConstants.S_ISREG(Os.fstat(opened.fileDescriptor).st_mode) }.getOrDefault(false)
            // The descriptor alias is only a header-probe hint. Decode the original
            // SAF path: native codecs map it; platform codecs use their file decoder.
            if (regular) return work(File("/proc/self/fd/${opened.fd}"), localSource)
        }
    }
    val source = stage()
    return try {
        work(source, source.toOkioPath())
    } finally {
        source.delete()
    }
}

private suspend fun stageOriginal(original: ReaderOriginalFile): File {
    original.cachedOriginal?.get()?.let { bytes ->
        return BrowseSaveAs.stageBytesForConvert(bytes)
    }
    return when (original) {
        is ReaderOriginalFile.Smb -> BrowseSaveAs.stageSmbForConvert(original.sourceId, original.relativePath)
        is ReaderOriginalFile.WebDav -> BrowseSaveAs.stageWebDavForConvert(original.sourceId, original.relativePath)
    }
}

private suspend fun stageOriginal(loader: ReaderSession, index: Int): File = withIOContext {
    val src = File.createTempFile("still-src", null, appCtx.cacheDir)
    try {
        check(loader.saveOriginal(index, src.toOkioPath())) { "Cannot read original" }
        src
    } catch (t: Throwable) {
        src.delete()
        throw t
    }
}

private suspend fun writeStaged(leaf: String, target: StillExportTarget, dest: Path, stage: suspend () -> File): Boolean {
    val encoded = encodeStaged(leaf, target, stage) ?: return false
    return try {
        withIOContext { encoded.file.toOkioPath() sendTo dest }
        true
    } finally {
        encoded.file.delete()
    }
}

private fun Path.asPathSource() = object : PathSource {
    override val source = this@asPathSource
    override val type = "application/octet-stream"
    override fun close() = Unit
}

private fun Throwable.logcatExport() {
    android.util.Log.e("StillConvert", message, this)
}
