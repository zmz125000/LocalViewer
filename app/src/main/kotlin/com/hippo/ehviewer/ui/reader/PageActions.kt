package com.hippo.ehviewer.ui.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.FileProvider
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.isAtLeastT
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.BuildConfig.APPLICATION_ID
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.ReaderSession
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.FileUtils
import com.hippo.ehviewer.util.awaitActivityResult
import com.hippo.ehviewer.util.displayPath
import java.io.File
import kotlin.time.Clock
import moe.tarsin.coroutines.runSuspendCatching
import moe.tarsin.snackbar
import moe.tarsin.string
import splitties.systemservices.clipboardManager

context(loader: ReaderSession, ctx: Context)
private fun provideImage(index: Int): Uri? {
    val dir = AppConfig.externalTempDir ?: return null
    val name = loader.getImageFilename(index) ?: return null
    val file = (dir / name).takeIf { loader.save(index, it) } ?: return null
    return FileProvider.getUriForFile(ctx, "$APPLICATION_ID.fileprovider", file.toFile())
}

context(_: SnackbarHostState, ctx: Context, _: ReaderSession)
suspend fun shareImage(page: Page, info: GalleryInfo? = null) {
    val error = string(R.string.error_cant_save_image)
    val share = string(R.string.share_image)
    val noActivity = string(R.string.error_cant_find_activity)
    val uri = provideImage(page.index)
    if (uri == null) {
        snackbar(error)
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_STREAM, uri)
        info?.apply { putExtra(Intent.EXTRA_TEXT, "") }
        val extension = FileUtils.getExtensionFromFilename(uri.path)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
        setDataAndType(uri, mimeType)
    }
    try {
        ctx.startActivity(Intent.createChooser(intent, share))
    } catch (_: ActivityNotFoundException) {
        snackbar(noActivity)
    }
}

context(_: SnackbarHostState, ctx: Context, _: ReaderSession)
suspend fun copy(page: Page) {
    val error = string(R.string.error_cant_save_image)
    val copied = string(R.string.copied_to_clipboard)
    val uri = provideImage(page.index)
    if (uri == null) {
        snackbar(error)
        return
    }
    val clipData = ClipData.newUri(ctx.contentResolver, "ehviewer", uri)
    clipboardManager.setPrimaryClip(clipData)
    if (!isAtLeastT) {
        snackbar(copied)
    }
}

context(_: SnackbarHostState, ctx: Context, loader: ReaderSession)
suspend fun save(page: Page) {
    // minSdk 32: MediaStore scoped storage only (no WRITE_EXTERNAL_STORAGE).
    val cannotSave = string(R.string.error_cant_save_image)
    val filename = loader.getImageFilename(page.index)
    if (filename == null) {
        snackbar(cannotSave)
        return
    }
    val extension = FileUtils.getExtensionFromFilename(filename)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
    val realPath = Environment.DIRECTORY_PICTURES + File.separator + AppConfig.APP_DIRNAME
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.DATE_ADDED, Clock.System.now().epochSeconds)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, realPath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val imageUri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    if (imageUri != null) {
        if (!loader.save(page.index, imageUri.toOkioPath())) {
            try {
                ctx.contentResolver.delete(imageUri, null, null)
            } catch (e: Exception) {
                logcat("SavePage", e)
            }
            snackbar(cannotSave)
        } else {
            ctx.contentResolver.update(
                imageUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            snackbar(string(R.string.image_saved, realPath + File.separator + filename))
        }
    } else {
        snackbar(cannotSave)
    }
}

context(_: SnackbarHostState, _: Context, loader: ReaderSession)
suspend fun saveTo(page: Page) {
    val filename = loader.getImageFilename(page.index)
    if (filename == null) {
        snackbar(string(R.string.error_cant_save_image))
        return
    }
    val extension = FileUtils.getExtensionFromFilename(filename)
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "image/jpeg"
    page.runSuspendCatching {
        val uri = awaitActivityResult(ActivityResultContracts.CreateDocument(mimeType), filename)
        if (uri != null) {
            loader.save(index, uri.toOkioPath())
            snackbar(string(R.string.image_saved, uri.displayPath))
        }
    }.onFailure {
        it.logcat(it)
        snackbar(string(R.string.error_cant_find_activity))
    }
}
