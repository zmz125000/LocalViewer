package com.hippo.ehviewer.ui.reader

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import com.ehviewer.core.files.sendTo
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.image.hdr.DirectPixels
import com.hippo.ehviewer.image.hdr.LibDirectDecode
import com.hippo.ehviewer.image.hdr.StillExportTarget
import com.hippo.ehviewer.image.hdr.copyExportFrame
import com.hippo.ehviewer.image.hdr.mimeType
import com.hippo.ehviewer.image.hdr.residentExportImage
import com.hippo.ehviewer.image.hdr.resolve
import com.hippo.ehviewer.image.hdr.stillExportFileName
import com.hippo.ehviewer.image.hdr.usesAutoRule
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
    val frame = if (target == StillExportTarget.Auto) {
        residentExportImage(loader, page)?.copyExportFrame()
    } else {
        null
    }
    if (target == StillExportTarget.Auto && frame == null) {
        snackbar(string(R.string.error_cant_save_image))
        return
    }
    val original = if (target == StillExportTarget.AutoOriginal) {
        decodeOriginalPixels(loader, page.index, leaf)
    } else {
        null
    }
    if (target == StillExportTarget.AutoOriginal && original == null) {
        snackbar(string(R.string.error_cant_save_image))
        return
    }
    try {
        val resolved = when {
            frame != null -> target.resolve(frame.hdr, frame.f16)
            original != null -> target.resolve(original.isHdr, original.f16)
            else -> target
        }
        val saved = pickAndWrite(leaf, resolved) { dest ->
            when {
                frame != null -> writeTemp(dest) { frame.writeResolved(resolved, it) }
                original != null -> writeTemp(dest) { original.writeResolved(resolved, it) }
                else -> writeFullRes(loader, page.index, leaf, resolved, dest)
            }
        }
        if (!saved) snackbar(string(R.string.error_cant_save_image))
    } finally {
        frame?.close()
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
    val autoPixels = if (target.usesAutoRule()) {
        val file = withIOContext { stage() }
        try {
            withIOContext { LibDirectDecode.decodePixels(file.asPathSource(), leaf, maxEdge = 0) }
        } finally {
            file.delete()
        }
    } else {
        null
    }
    if (target.usesAutoRule() && autoPixels == null) {
        snackbar(string(R.string.error_cant_save_image))
        return
    }
    val resolved = autoPixels?.let { target.resolve(it.isHdr, it.f16) } ?: target
    val saved = pickAndWrite(leaf, resolved) { dest ->
        val pixels = autoPixels ?: withIOContext {
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
            writeTemp(dest) { pixels.writeResolved(resolved, it) }
        }
    }
    if (!saved) snackbar(string(R.string.error_cant_save_image))
}

context(_: SnackbarHostState, _: Context)
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
        val wrote = withIOContext { write(uri.toOkioPath()) }
        if (wrote) snackbar(string(R.string.image_saved, uri.displayPath ?: uri.toString()))
        wrote
    }.onFailure {
        if (it is CancellationException) throw it
        it.logcatExport()
        snackbar(string(R.string.error_cant_find_activity))
    }.getOrDefault(true)
    return !picked || ok
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
