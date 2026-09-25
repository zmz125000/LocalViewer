package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.util.rememberSystemUiController
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.NavigationKind
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PdfRamPageLoader
import com.hippo.ehviewer.gallery.ReaderNavigation
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.BlockCacheArchiveByteSource
import com.hippo.ehviewer.library.DocumentExtractCache
import com.hippo.ehviewer.library.GallerySiblingNavigator
import com.hippo.ehviewer.library.OriginDiskCache
import com.hippo.ehviewer.library.PfdArchiveByteSource
import com.hippo.ehviewer.library.ReaderPageThumb
import com.hippo.ehviewer.library.document.EbookDocument
import com.hippo.ehviewer.library.document.EbookEngine
import com.hippo.ehviewer.library.document.EbookPage
import com.hippo.ehviewer.library.document.EbookPaginator
import com.hippo.ehviewer.library.document.PdfContentKind
import com.hippo.ehviewer.library.document.PdfImageEngine
import com.hippo.ehviewer.library.document.PdfTocEntry
import com.hippo.ehviewer.library.document.readPdfChapters
import com.hippo.ehviewer.library.isEbookFileName
import com.hippo.ehviewer.library.openLocalArchiveByteSource
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import com.hippo.ehviewer.ui.main.GalleryGridDefaults
import com.hippo.ehviewer.ui.reader.EInkRefreshOverlay
import com.hippo.ehviewer.ui.reader.NavigationOverlay
import com.hippo.ehviewer.ui.reader.PagerItem
import com.hippo.ehviewer.ui.reader.PendingReaderOpen
import com.hippo.ehviewer.ui.reader.ReaderScreenArgs
import com.hippo.ehviewer.ui.reader.SettingsPager
import com.hippo.ehviewer.ui.reader.applyPagerContentAlignment
import com.hippo.ehviewer.ui.reader.doubleTapAction
import com.hippo.ehviewer.ui.reader.dualFirstPageIndex
import com.hippo.ehviewer.ui.reader.dualLeftRight
import com.hippo.ehviewer.ui.reader.dualPageActive
import com.hippo.ehviewer.ui.reader.dualSpreadCount
import com.hippo.ehviewer.ui.reader.dualSpreadIndex
import com.hippo.ehviewer.ui.reader.fromPreferences
import com.hippo.ehviewer.ui.reader.insideSpreadSize
import com.hippo.ehviewer.ui.reader.isPagerDual
import com.hippo.ehviewer.ui.reader.isWebtoonHorizontal
import com.hippo.ehviewer.ui.reader.readerPdfCacheKey
import com.hippo.ehviewer.ui.reader.readerPhotoGridSheetMaxWidth
import com.hippo.ehviewer.ui.reader.readerSheetBox
import com.hippo.ehviewer.ui.reader.scrollDown
import com.hippo.ehviewer.ui.reader.scrollLeft
import com.hippo.ehviewer.ui.reader.scrollRight
import com.hippo.ehviewer.ui.reader.scrollUp
import com.hippo.ehviewer.ui.reader.webtoonReadingIndex
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.dialog
import eu.kanade.tachiyomi.ui.reader.PageIndicatorText
import eu.kanade.tachiyomi.ui.reader.ReaderAppBars
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import eu.kanade.tachiyomi.ui.reader.setting.TappingInvertMode
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.viewer.getAction
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import okio.Path.Companion.toPath

/**
 * Full-screen in-app PDF reader.
 *
 * Text / generic PDFs: [PdfRenderer] at the current zoom (vector drawing stays sharp).
 * Image / comic PDFs: native embedded bitmaps via [PdfImageEngine] when
 * [Settings.pdfDirectImage] is on. Off uses [PdfRenderer] for every page and thumb.
 *
 * Local / SMB / WebDAV image PDFs read the origin [ArchiveByteSource] directly.
 * Vector PDFs still use a streamdoc PFD with [PdfRenderer].
 */
class PdfReaderActivity : AppCompatActivity() {
    private var doc by mutableStateOf<PdfDocumentModel?>(null)
    private var imageLoader by mutableStateOf<PdfRamPageLoader?>(null)
    private var title by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var streamToken: String? = null
    private var progressGid by mutableStateOf(0L)
    private var startPage by mutableStateOf(0)
    private var lastVisiblePage = 0
    private var openJob: Job? = null
    private var openGeneration = 0
    private var sourceArgs by mutableStateOf<ReaderScreenArgs?>(null)
    private val hopBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openFromIntent(intent, replace = false)
        setMD3Content {
            PdfReaderScreen(
                title = title,
                doc = doc,
                imageLoader = imageLoader,
                error = error,
                startPage = startPage,
                progressGid = progressGid,
                onPageChanged = { lastVisiblePage = it },
                onClose = {
                    closeSession()
                    finish()
                },
                onHopSibling = { next -> hopSibling(next) },
                onDirectImageChanged = { reloadForDirectImage() },
                sourceArgs = sourceArgs,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        flushProgress()
        openFromIntent(intent, replace = true)
    }

    override fun onStop() {
        flushProgress()
        super.onStop()
    }

    override fun onDestroy() {
        flushProgress()
        openJob?.cancel()
        closeSession()
        super.onDestroy()
    }

    private fun openFromIntent(intent: Intent, replace: Boolean) {
        val token = intent.getStringExtra(EXTRA_STREAM_TOKEN)
        val nextTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val uri = intent.data
            ?: token?.let { StreamDocumentProvider.uriFor(it, nextTitle.ifBlank { "document.pdf" }) }
        if (uri == null) {
            if (!replace) {
                error = getString(R.string.pdf_reader_open_failed, "missing URI")
            }
            return
        }
        val nextGid = intent.getLongExtra(EXTRA_PROGRESS_GID, 0L)
        val nextStart = intent.getIntExtra(EXTRA_START_PAGE, 0).coerceAtLeast(0)
        val nextArgs = readerArgsFromIntent(intent)
        openJob?.cancel()
        val generation = ++openGeneration
        if (replace) closeSession(removeToken = false)
        openJob = lifecycleScope.launch {
            var pfd: ParcelFileDescriptor? = null
            var opened: PdfDocumentModel? = null
            var direct: ArchiveByteSource? = null
            try {
                val cacheKey = pdfCacheKeyFromIntent(intent)
                val directImage = Settings.pdfDirectImage.value
                opened = withContext(Dispatchers.IO) {
                    val docName = documentNameFromIntent(intent, nextTitle)
                    if (isEbookFileName(docName)) {
                        return@withContext openEbookDocument(intent, token, docName)
                    }
                    if (directImage) {
                        direct = runCatching { openDirectArchiveSource(intent, token) }
                            .onFailure { logcat("PdfReader", it) }
                            .getOrNull()
                    }
                    val fromDirect = if (directImage) {
                        direct?.let { src ->
                            tryOpenImagePdf(src, nextStart, cacheKey)
                        }
                    } else {
                        null
                    }
                    if (fromDirect != null) {
                        direct = null
                        return@withContext fromDirect
                    }
                    runCatching { direct?.close() }
                    direct = null
                    val descriptor = runCatching {
                        contentResolver.openFileDescriptor(uri, "r")
                    }.getOrNull()
                    pfd = descriptor
                    if (descriptor == null) return@withContext null
                    val reopen = token?.let { StreamDocumentRegistry.get(it)?.openFileDescriptor }
                    openPdfDocument(descriptor, nextStart, cacheKey, reopen)
                }
                val model = opened
                if (generation != openGeneration) return@launch
                if (model == null) {
                    token?.let(StreamDocumentRegistry::remove)
                    error = getString(R.string.pdf_reader_open_failed, "descriptor")
                    return@launch
                }
                val oldToken = streamToken
                closeSession(removeToken = false)
                if (oldToken != null && oldToken != token) StreamDocumentRegistry.remove(oldToken)
                streamToken = token
                title = nextTitle.ifBlank { uri.lastPathSegment.orEmpty() }
                progressGid = nextGid
                startPage = nextStart
                lastVisiblePage = nextStart
                sourceArgs = nextArgs
                error = null
                pfd = null
                doc = model
                imageLoader = (model as? PdfDocumentModel.Images)?.let { images ->
                    PdfRamPageLoader(
                        scope = lifecycleScope,
                        engine = images.engine,
                        titleHint = title,
                        startPage = startPage,
                        cacheKey = cacheKey,
                        openExtractSource = pdfExtractOpener(intent, token),
                    )
                }
                opened = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                pfd?.let { runCatching { it.close() } }
                direct?.let { runCatching { it.close() } }
                throw e
            } catch (e: Throwable) {
                logcat("PdfReader", e)
                pfd?.let { runCatching { it.close() } }
                direct?.let { runCatching { it.close() } }
                closeSession(removeToken = false)
                token?.let(StreamDocumentRegistry::remove)
                streamToken = null
                error = getString(R.string.pdf_reader_open_failed, e.message ?: e.toString())
            } finally {
                opened?.close()
            }
        }
    }

    private fun flushProgress() {
        val gid = progressGid
        if (gid == 0L) return
        runBlocking {
            runCatching { EhDB.putReadProgress(gid, lastVisiblePage.coerceAtLeast(0)) }
        }
    }

    private fun closeSession(removeToken: Boolean = true) {
        imageLoader?.close()
        imageLoader = null
        doc?.close()
        doc = null
        if (removeToken) {
            streamToken?.let(StreamDocumentRegistry::remove)
            streamToken = null
        }
    }

    private fun reloadForDirectImage() {
        intent.putExtra(EXTRA_START_PAGE, lastVisiblePage.coerceAtLeast(0))
        openFromIntent(intent, replace = true)
    }

    private fun hopSibling(next: Boolean) {
        val current = sourceArgs ?: return
        if (!hopBusy.compareAndSet(false, true)) return
        lifecycleScope.launch {
            try {
                val sibling = withContext(Dispatchers.IO) {
                    runCatching { GallerySiblingNavigator.sibling(current, next) }.getOrNull()
                } ?: return@launch
                flushProgress()
                closeSession()
                if (OpenPdfBySettings.shouldRedirect(sibling)) {
                    when (val outcome = OpenPdfBySettings.open(this@PdfReaderActivity, sibling)) {
                        is OpenPdfBySettings.Outcome.Gallery -> {
                            OpenPdfBySettings.handoffGallery(this@PdfReaderActivity, outcome.args)
                            finish()
                        }
                        OpenPdfBySettings.Outcome.Handled -> Unit
                    }
                } else {
                    PendingReaderOpen.offer(sibling)
                    startActivity(
                        Intent(this@PdfReaderActivity, MainActivity::class.java).apply {
                            action = PendingReaderOpen.ACTION
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        },
                    )
                    finish()
                }
            } finally {
                hopBusy.set(false)
            }
        }
    }

    companion object {
        const val EXTRA_STREAM_TOKEN = "stream_token"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PROGRESS_GID = "progress_gid"
        const val EXTRA_START_PAGE = "start_page"
        const val EXTRA_SOURCE_KIND = "source_kind"
        const val EXTRA_LOCAL_PATH = "local_path"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_REMOTE_PATH = "remote_path"

        const val KIND_LOCAL = "local"
        const val KIND_SMB = "smb"
        const val KIND_WEBDAV = "webdav"

        fun intent(
            context: Context,
            uri: Uri,
            title: String,
            streamToken: String,
            progressGid: Long = 0L,
            startPage: Int = 0,
            sourceKind: String? = null,
            localPath: String? = null,
            sourceId: Long = 0L,
            remotePath: String? = null,
        ): Intent = Intent(context, PdfReaderActivity::class.java).apply {
            setDataAndType(uri, DefaultPdfReader.MIME_TYPE)
            putExtra(EXTRA_STREAM_TOKEN, streamToken)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PROGRESS_GID, progressGid)
            putExtra(EXTRA_START_PAGE, startPage)
            sourceKind?.let { putExtra(EXTRA_SOURCE_KIND, it) }
            localPath?.let { putExtra(EXTRA_LOCAL_PATH, it) }
            if (sourceId != 0L) putExtra(EXTRA_SOURCE_ID, sourceId)
            remotePath?.let { putExtra(EXTRA_REMOTE_PATH, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private fun readerArgsFromIntent(intent: Intent): ReaderScreenArgs? {
    val remote = intent.getStringExtra(PdfReaderActivity.EXTRA_REMOTE_PATH).orEmpty().trim('/')
    val sourceId = intent.getLongExtra(PdfReaderActivity.EXTRA_SOURCE_ID, 0L)
    return when (intent.getStringExtra(PdfReaderActivity.EXTRA_SOURCE_KIND)) {
        PdfReaderActivity.KIND_LOCAL -> {
            val path = intent.getStringExtra(PdfReaderActivity.EXTRA_LOCAL_PATH) ?: return null
            ReaderScreenArgs.Archive(path)
        }
        PdfReaderActivity.KIND_SMB -> {
            if (sourceId == 0L || remote.isEmpty()) {
                null
            } else {
                ReaderScreenArgs.SmbStreamArchive(sourceId, remote)
            }
        }
        PdfReaderActivity.KIND_WEBDAV -> {
            if (sourceId == 0L || remote.isEmpty()) {
                null
            } else {
                ReaderScreenArgs.WebDavStreamArchive(sourceId, remote)
            }
        }
        else -> null
    }
}

private fun pdfCacheKeyFromIntent(intent: Intent): String? {
    val remote = intent.getStringExtra(PdfReaderActivity.EXTRA_REMOTE_PATH).orEmpty().trim('/')
    val sourceId = intent.getLongExtra(PdfReaderActivity.EXTRA_SOURCE_ID, 0L)
    return when (intent.getStringExtra(PdfReaderActivity.EXTRA_SOURCE_KIND)) {
        PdfReaderActivity.KIND_LOCAL ->
            intent.getStringExtra(PdfReaderActivity.EXTRA_LOCAL_PATH)?.takeIf { it.isNotBlank() }
        PdfReaderActivity.KIND_SMB ->
            if (sourceId != 0L && remote.isNotEmpty()) "smb:$sourceId:$remote" else null
        PdfReaderActivity.KIND_WEBDAV ->
            if (sourceId != 0L && remote.isNotEmpty()) "webdav:$sourceId:$remote" else null
        else -> intent.getStringExtra(PdfReaderActivity.EXTRA_LOCAL_PATH)?.takeIf { it.isNotBlank() }
    }
}

/**
 * Extra SMB/WebDAV/file handles for page-body extract. The engine source stays
 * with the serial page-tree walk.
 */
private fun pdfExtractOpener(intent: Intent, token: String?): (() -> ArchiveByteSource)? {
    val entry = token?.let { StreamDocumentRegistry.get(it) }
    val open = entry?.openSource
    if (entry != null && open != null) {
        val (blockSize, maxBlocks) = BlockCacheArchiveByteSource.forMimeType(
            entry.mimeType,
            entry.displayName,
        )
        val knownSize = entry.sizeBytes
        return {
            BlockCacheArchiveByteSource(
                open(),
                knownSize = knownSize,
                blockSize = blockSize,
                maxBlocks = maxBlocks,
            )
        }
    }
    val local = intent.getStringExtra(PdfReaderActivity.EXTRA_LOCAL_PATH)?.takeIf { it.isNotBlank() }
    if (local != null) {
        return { openLocalArchiveByteSource(local.toPath()) ?: error("PDF extract source") }
    }
    val reopen = entry?.openFileDescriptor
    if (reopen != null) {
        return { PfdArchiveByteSource(reopen(), ownsPfd = true) }
    }
    return null
}

private fun openDirectArchiveSource(intent: Intent, token: String?): ArchiveByteSource? {
    val entry = token?.let { StreamDocumentRegistry.get(it) }
    entry?.openSource?.let { open ->
        val raw = open()
        val (blockSize, maxBlocks) = BlockCacheArchiveByteSource.forMimeType(
            entry.mimeType,
            entry.displayName,
        )
        return BlockCacheArchiveByteSource(
            raw,
            knownSize = entry.sizeBytes,
            blockSize = blockSize,
            maxBlocks = maxBlocks,
        )
    }
    val local = intent.getStringExtra(PdfReaderActivity.EXTRA_LOCAL_PATH)?.takeIf { it.isNotBlank() }
        ?: return null
    return openLocalArchiveByteSource(local.toPath())
}

private fun tryOpenImagePdf(
    source: ArchiveByteSource,
    startPage: Int,
    cacheKey: String?,
): PdfDocumentModel.Images? {
    val size = source.size
    val kind = runCatching { PdfImageEngine.classify(source, size) }
        .getOrDefault(PdfContentKind.Vector)
    if (kind != PdfContentKind.Image) return null
    val cached = cacheKey?.let { DocumentExtractCache.loadUsableIndex(it, size) }
    val engine = if (cached != null) {
        PdfImageEngine.openFromIndex(
            source,
            cached,
            remoteSize = size,
            progressive = true,
        ) ?: PdfImageEngine.open(
            source,
            remoteSize = size,
            coverOnly = false,
            progressive = true,
        )
    } else {
        PdfImageEngine.open(
            source,
            remoteSize = size,
            coverOnly = false,
            progressive = true,
        )
    }
    if (engine == null || engine.ensureListedThrough(startPage.coerceAtLeast(0)) <= 0) {
        runCatching { engine?.close() }
        return null
    }
    logcat("PdfReader") {
        "image PDF pages=${engine.pageCount} cached=${cached != null} " +
            "structureComplete=${engine.structureComplete}"
    }
    return PdfDocumentModel.Images(engine, source)
}

private fun openPdfDocument(
    pfd: ParcelFileDescriptor,
    startPage: Int,
    cacheKey: String?,
    reopenPfd: (() -> ParcelFileDescriptor)? = null,
): PdfDocumentModel {
    // Do not dup()+close the original PFD: AppFuse/SAF FUSE tears down the
    // connection when the original fd is closed (ENOTCONN on later preads).
    val source = PfdArchiveByteSource(pfd, ownsPfd = false, reopen = reopenPfd)
    val chapters = readPdfChapters(source, source.size)
    runCatching { source.close() }
    val renderer = runCatching { PdfRenderer(pfd) }.getOrElse { e ->
        runCatching { pfd.close() }
        throw e
    }
    logcat("PdfReader") { "vector PDF pages=${renderer.pageCount}" }
    return PdfDocumentModel.Vector(PdfSession(renderer), chapters)
}

private fun documentNameFromIntent(intent: Intent, title: String): String {
    fun leaf(path: String?): String? = path
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.ifBlank { null }
    val fromPath = leaf(intent.getStringExtra(PdfReaderActivity.EXTRA_LOCAL_PATH))
        ?: leaf(intent.getStringExtra(PdfReaderActivity.EXTRA_REMOTE_PATH))
        ?: leaf(intent.data?.lastPathSegment)
    if (fromPath != null && isEbookFileName(fromPath)) return fromPath
    if (isEbookFileName(title)) return title
    return fromPath ?: title
}

private fun openEbookDocument(
    intent: Intent,
    token: String?,
    fileName: String,
): PdfDocumentModel.Vector? {
    var owned: ArchiveByteSource? = null
    val source = runCatching { openDirectArchiveSource(intent, token) }
        .onFailure { logcat("PdfReader", it) }
        .getOrNull()
        ?: run {
            val pfd = token?.let { StreamDocumentRegistry.get(it)?.openFileDescriptor?.invoke() }
                ?: return null
            PfdArchiveByteSource(pfd, ownsPfd = true).also { owned = it }
        }
    return try {
        val book = EbookEngine.open(source, fileName) ?: return null
        if (book.pageCount <= 0) return null
        logcat("PdfReader") { "ebook pages=${book.pageCount} chapters=${book.chapters.size}" }
        PdfDocumentModel.Vector(EbookSession(book), book.chapters)
    } finally {
        runCatching { source.close() }
        if (owned !== source) runCatching { owned?.close() }
    }
}

private sealed interface PdfDocumentModel {
    val pageCount: Int
    val chapters: List<PdfTocEntry>
    fun close()

    class Vector(
        val session: PageBitmapSession,
        override val chapters: List<PdfTocEntry>,
    ) : PdfDocumentModel {
        override val pageCount get() = session.pageCount
        override fun close() = session.close()
    }

    class Images(
        val engine: PdfImageEngine,
        private val source: ArchiveByteSource,
        override var chapters: List<PdfTocEntry> = emptyList(),
    ) : PdfDocumentModel {
        override val pageCount get() = engine.pageCount
        override fun close() {
            runCatching { engine.close() }
            runCatching { source.close() }
        }
    }
}

private interface PageBitmapSession {
    val pageCount: Int
    suspend fun render(index: Int, widthPx: Int): Bitmap
    suspend fun pageAspect(index: Int): Float
    fun close()

    /** Photo-grid thumb. PDF overrides this to measure and draw in one page open. */
    suspend fun renderLongEdge(index: Int, edge: Int): Bitmap {
        val aspect = pageAspect(index)
        val width = if (aspect >= 1f) edge else (edge * aspect).roundToInt().coerceAtLeast(1)
        return render(index, width)
    }
}

private class PdfSession(private val renderer: PdfRenderer) : PageBitmapSession {
    override val pageCount: Int get() = renderer.pageCount
    private val mutex = Mutex()
    private val aspects = java.util.concurrent.ConcurrentHashMap<Int, Float>()

    @Volatile private var closed = false

    override suspend fun render(index: Int, widthPx: Int): Bitmap = mutex.withLock {
        if (closed) error("closed")
        renderer.openPage(index).use { page ->
            noteAspect(index, page)
            renderOpened(page, widthPx)
        }
    }

    /** One [PdfRenderer.Page] open. Long edge is [edge] px (photo-grid thumb). */
    override suspend fun renderLongEdge(index: Int, edge: Int): Bitmap = mutex.withLock {
        if (closed) error("closed")
        renderer.openPage(index).use { page ->
            val aspect = noteAspect(index, page)
            val width = if (aspect >= 1f) edge else (edge * aspect).roundToInt().coerceAtLeast(1)
            renderOpened(page, width)
        }
    }

    override suspend fun pageAspect(index: Int): Float {
        aspects[index]?.let { return it }
        return mutex.withLock {
            if (closed) error("closed")
            aspects[index] ?: renderer.openPage(index).use { page -> noteAspect(index, page) }
        }
    }

    private fun noteAspect(index: Int, page: PdfRenderer.Page): Float {
        val aspect = page.width.toFloat() / page.height.coerceAtLeast(1)
        aspects[index] = aspect
        return aspect
    }

    private fun renderOpened(page: PdfRenderer.Page, widthPx: Int): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = ((page.height.toFloat() / page.width.coerceAtLeast(1)) * w)
            .toInt()
            .coerceAtLeast(1)
        val (rw, rh) = cappedBitmapSize(w, h)
        return Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    override fun close() {
        if (closed) return
        runBlocking {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
            }
        }
    }
}

private class EbookSession(private val book: EbookDocument) : PageBitmapSession {
    override val pageCount: Int get() = book.pageCount
    private val mutex = Mutex()

    @Volatile private var closed = false

    override suspend fun render(index: Int, widthPx: Int): Bitmap = mutex.withLock {
        if (closed) error("closed")
        val page = book.pages.getOrNull(index) ?: error("page")
        val w = widthPx.coerceAtLeast(1)
        val h = (w / book.pageAspect.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1)
        val (rw, rh) = cappedBitmapSize(w, h)
        Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.WHITE)
            drawEbookPage(bitmap, page)
        }
    }

    override suspend fun pageAspect(index: Int): Float = book.pageAspect

    override fun close() {
        closed = true
    }
}

private fun drawEbookPage(bitmap: Bitmap, page: EbookPage) {
    val canvas = Canvas(bitmap)
    val w = bitmap.width.toFloat().coerceAtLeast(1f)
    val h = bitmap.height.toFloat().coerceAtLeast(1f)
    val pad = w * EbookPaginator.MARGIN
    val contentW = (w - 2f * pad).coerceAtLeast(1f)
    val fontSize = contentW / EbookPaginator.CJK_PER_LINE
    val lineH = fontSize * EbookPaginator.LINE_HEIGHT_EM
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        textSize = fontSize
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }
    var y = pad + fontSize
    val maxY = h - pad
    for (line in page.lines) {
        if (y > maxY) break
        canvas.drawText(line, pad, y, paint)
        y += lineH
    }
}

private fun cappedBitmapSize(width: Int, height: Int): Pair<Int, Int> {
    val pixels = width.toLong() * height
    if (pixels <= MAX_VECTOR_PIXELS && width <= MAX_VECTOR_EDGE && height <= MAX_VECTOR_EDGE) {
        return width to height
    }
    val edgeScale = minOf(
        MAX_VECTOR_EDGE.toFloat() / width.coerceAtLeast(1),
        MAX_VECTOR_EDGE.toFloat() / height.coerceAtLeast(1),
        1f,
    )
    val pixelScale = kotlin.math.sqrt(MAX_VECTOR_PIXELS.toDouble() / pixels.coerceAtLeast(1L)).toFloat()
        .coerceAtMost(1f)
    val scale = minOf(edgeScale, pixelScale)
    return (width * scale).roundToInt().coerceAtLeast(1) to
        (height * scale).roundToInt().coerceAtLeast(1)
}

@Composable
context(_: DialogState)
private fun PdfReaderScreen(
    title: String,
    doc: PdfDocumentModel?,
    imageLoader: PdfRamPageLoader?,
    error: String?,
    startPage: Int,
    progressGid: Long,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    onHopSibling: (next: Boolean) -> Unit,
    onDirectImageChanged: () -> Unit,
    sourceArgs: ReaderScreenArgs?,
) {
    val pageCount = imageLoader?.size ?: (doc?.pageCount ?: 0)
    val initial = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val scope = rememberCoroutineScope()
    val showSeekbar by Settings.showReaderSeekbar.collectAsState()
    val hideTopBar by Settings.readerHideTopBar.collectAsState()
    val showPageNumber by Settings.showPageNumber.collectAsState()
    val readerPhotoGrid by Settings.readerPhotoGrid.collectAsState()
    val downloadNetworkThumbs by Settings.downloadNetworkPhotoGridThumb.collectAsState()
    val networkPdf = sourceArgs is ReaderScreenArgs.SmbStreamArchive ||
        sourceArgs is ReaderScreenArgs.WebDavStreamArchive
    var photoGridOpen by remember { mutableStateOf(false) }
    var contentsOpen by remember { mutableStateOf(false) }
    val thumbGridState = rememberLazyGridState()
    var thumbAspect by remember { mutableStateOf<Float?>(null) }
    val contentsListState = rememberLazyListState()
    val scrollGridToProgress by Settings.photoGridScrollToProgress.collectAsState()
    val directImage by Settings.pdfDirectImage.collectAsState()
    var appliedDirectImage by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(directImage) {
        val previous = appliedDirectImage
        appliedDirectImage = directImage
        if (previous != null && previous != directImage) onDirectImageChanged()
    }
    val fullscreen by Settings.fullscreen.collectAsState()
    val keepScreenOn by Settings.keepScreenOn.collectAsState()
    val uiController = rememberSystemUiController()
    val appDarkTheme = isSystemInDarkTheme()
    SideEffect {
        uiController.statusBarDarkContentEnabled = appDarkTheme
    }
    DisposableEffect(uiController) {
        uiController.showTransientSystemBarsBySwipe = true
        if (Settings.fullscreen.value) {
            uiController.isSystemBarsVisible = false
        }
        onDispose {
            uiController.isSystemBarsVisible = true
            uiController.showTransientSystemBarsBySwipe = false
        }
    }
    LaunchedEffect(fullscreen, uiController) {
        if (fullscreen) {
            uiController.isSystemBarsVisible = false
            uiController.showTransientSystemBarsBySwipe = true
        } else {
            uiController.isSystemBarsVisible = true
        }
    }
    val readingMode by Settings.readingMode.collectAsState {
        when (val mode = ReadingModeType.fromPreference(it)) {
            ReadingModeType.DEFAULT -> ReadingModeType.RIGHT_TO_LEFT
            else -> mode
        }
    }
    val isWebtoon = ReadingModeType.isWebtoon(readingMode)
    val dualPagePref by Settings.dualPageLandscape.collectAsState()
    val dualPageGap by Settings.dualPageGap.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dualActive = dualPageActive(dualPagePref, isLandscape)
    val pagerDual = isPagerDual(dualActive, readingMode)
    val webtoonHorizontal = isWebtoonHorizontal(dualActive, readingMode)
    val tapRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT || webtoonHorizontal
    val landscapeCoverMode by Settings.landscapeCover.collectAsState()
    var page0Landscape by remember(doc, imageLoader) { mutableStateOf(false) }
    LaunchedEffect(doc, imageLoader, pagerDual, landscapeCoverMode) {
        // Opening page 0 just to read its aspect parses that page. Skip it unless a
        // side-by-side spread actually needs to know whether the cover is landscape.
        if (!pagerDual || landscapeCoverMode != Settings.LANDSCAPE_COVER_AUTO) {
            page0Landscape = false
            return@LaunchedEffect
        }
        page0Landscape = when {
            imageLoader != null -> (imageLoader.pages.getOrNull(0)?.layoutAspect ?: 0f) > 1f
            doc is PdfDocumentModel.Vector -> withContext(Dispatchers.IO) {
                runCatching { doc.session.pageAspect(0) }.getOrDefault(0f) > 1f
            }
            else -> false
        }
    }
    val landscapeCover = pagerDual && when (landscapeCoverMode) {
        Settings.LANDSCAPE_COVER_ON -> true
        Settings.LANDSCAPE_COVER_OFF -> false
        else -> page0Landscape
    }
    val pageCountState = rememberUpdatedState(pageCount)
    val pagerDualState = rememberUpdatedState(pagerDual)
    val coverState = rememberUpdatedState(landscapeCover)
    val pagerState = rememberPagerState(initialPage = initial) {
        val count = pageCountState.value
        if (pagerDualState.value) {
            dualSpreadCount(count, coverState.value).coerceAtLeast(1)
        } else {
            count.coerceAtLeast(1)
        }
    }
    fun realPageIndex(): Int {
        val raw = if (isWebtoon) {
            listState.layoutInfo.webtoonReadingIndex(webtoonHorizontal)
                ?: listState.firstVisibleItemIndex
        } else if (pagerDual) {
            dualFirstPageIndex(pagerState.currentPage, landscapeCover)
        } else {
            pagerState.currentPage
        }
        return raw.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }
    val currentPage by remember(imageLoader, doc, pagerDual, landscapeCover, isWebtoon, webtoonHorizontal) {
        derivedStateOf {
            val n = imageLoader?.size ?: (doc?.pageCount ?: 0)
            if (n <= 0) 0 else (realPageIndex() + 1).coerceIn(1, n)
        }
    }
    LaunchedEffect(photoGridOpen) {
        if (!photoGridOpen || !scrollGridToProgress || pageCount <= 0) return@LaunchedEffect
        thumbGridState.scrollToItem((currentPage - 1).coerceIn(0, pageCount - 1))
    }
    val pagerNavigation by Settings.readerPagerNav.collectAsState()
    val pagerInvertMode by Settings.readerPagerNavInverted.collectAsState()
    val webtoonNavigation by Settings.readerWebtoonNav.collectAsState()
    val webtoonInvertMode by Settings.readerWebtoonNavInverted.collectAsState()
    val navigationType = if (isWebtoon) webtoonNavigation else pagerNavigation
    val invertMode = if (isWebtoon) webtoonInvertMode else pagerInvertMode
    val navigation = remember(navigationType, readingMode) {
        ViewerNavigation.fromPreference(navigationType, ReadingModeType.isVertical(readingMode))
    }
    val regions = remember(navigation, invertMode) {
        navigation.regions(TappingInvertMode.entries[invertMode])
    }
    val navigator by rememberUpdatedState(regions)
    var showNavigationOverlay by remember {
        val showOnStart = Settings.showNavigationOverlayNewUser.value ||
            Settings.showNavigationOverlayOnStart.value
        Settings.showNavigationOverlayNewUser.value = false
        mutableStateOf(showOnStart)
    }
    var skipPagerNavHint by remember { mutableStateOf(true) }
    var skipWebtoonNavHint by remember { mutableStateOf(true) }
    LaunchedEffect(pagerNavigation, pagerInvertMode) {
        if (skipPagerNavHint) {
            skipPagerNavHint = false
            return@LaunchedEffect
        }
        if (!isWebtoon) showNavigationOverlay = true
    }
    LaunchedEffect(webtoonNavigation, webtoonInvertMode) {
        if (skipWebtoonNavHint) {
            skipWebtoonNavHint = false
            return@LaunchedEffect
        }
        if (isWebtoon) showNavigationOverlay = true
    }
    var appbarVisible by remember { mutableStateOf(false) }
    val chromeVisible by rememberUpdatedState(appbarVisible)
    LaunchedEffect(fullscreen) {
        snapshotFlow { appbarVisible }.collect { visible ->
            uiController.isSystemBarsVisible = visible || !fullscreen
            uiController.showTransientSystemBarsBySwipe = true
        }
    }
    var suppressPageClick by remember { mutableStateOf(false) }
    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    val hopSibling by rememberUpdatedState(onHopSibling)
    val doubleTap = remember(navigator, onClose, viewportPx, readingMode, webtoonHorizontal) {
        doubleTapAction(
            isRtl = tapRtl,
            getViewportSize = {
                Size(viewportPx.width.toFloat(), viewportPx.height.toFloat())
            },
            getNavigator = { navigator },
            onPrevFolder = { hopSibling(false) },
            onNextFolder = { hopSibling(true) },
            onBack = onClose,
        )
    }
    LaunchedEffect(error) {
        if (error != null) appbarVisible = true
    }
    LaunchedEffect(imageLoader) {
        val loader = imageLoader ?: return@LaunchedEffect
        snapshotFlow {
            val count = loader.size
            if (count <= 0) return@snapshotFlow null
            val last = count - 1
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val fallback = listState.firstVisibleItemIndex.coerceIn(0, last)
            val first = visibleItems.minOfOrNull { it.index }?.coerceIn(0, last) ?: fallback
            val end = visibleItems.maxOfOrNull { it.index }?.coerceIn(first, last) ?: fallback
            ReaderNavigation(
                anchor = listState.firstVisibleItemIndex.coerceIn(first, end),
                visiblePages = first..end,
                kind = if (listState.isScrollInProgress) {
                    NavigationKind.Scroll
                } else {
                    NavigationKind.Settled
                },
            )
        }.distinctUntilChanged().collect { navigation ->
            if (navigation != null) loader.navigate(navigation)
        }
    }
    LaunchedEffect(imageLoader) {
        val loader = imageLoader ?: return@LaunchedEffect
        with(Settings) {
            merge(
                cropBorder.changesFlow(),
                stripExtraneousAds.changesFlow(),
                readerHardwareBitmap.changesFlow(),
                readerLibDirectBitmap.changesFlow(),
                readerDecodeSize.changesFlow(),
                readerAdvancedColor.changesFlow(),
                readerPlatformHighDepth.changesFlow(),
                readerOppoProxdr.changesFlow(),
            ).collect {
                loader.restart()
            }
        }
    }
    LaunchedEffect(imageLoader) {
        val loader = imageLoader ?: return@LaunchedEffect
        merge(
            Settings.preloadImage.changesFlow(),
            Settings.readerDecodeAhead.changesFlow(),
        ).collect {
            loader.replan()
        }
    }
    var anchorPage by remember { mutableIntStateOf(initial) }
    suspend fun jumpToPdfPage(index: Int) {
        val target = index.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        anchorPage = target
        if (isWebtoon) {
            listState.scrollToItem(target)
        } else {
            val slot = if (pagerDual) dualSpreadIndex(target, landscapeCover) else target
            pagerState.scrollToPage(slot)
        }
        onPageChanged(target)
    }
    suspend fun stepPdfPage(forward: Boolean) {
        when {
            webtoonHorizontal -> if (forward) listState.scrollRight() else listState.scrollLeft()
            isWebtoon -> if (forward) listState.scrollDown() else listState.scrollUp()
            else -> {
                val delta = if (forward) 1 else -1
                val last = (pagerState.pageCount - 1).coerceAtLeast(0)
                pagerState.animateScrollToPage((pagerState.currentPage + delta).coerceIn(0, last))
            }
        }
    }
    LaunchedEffect(doc, imageLoader, startPage) {
        if (doc == null || pageCount <= 0) return@LaunchedEffect
        jumpToPdfPage(startPage)
    }
    var layoutReady by remember { mutableStateOf(false) }
    LaunchedEffect(readingMode, pagerDual, webtoonHorizontal, landscapeCover) {
        if (!layoutReady) {
            layoutReady = true
            return@LaunchedEffect
        }
        if (pageCount <= 0) return@LaunchedEffect
        jumpToPdfPage(anchorPage)
    }
    LaunchedEffect(doc, readingMode, pagerDual, landscapeCover) {
        if (doc == null) return@LaunchedEffect
        snapshotFlow { realPageIndex() }
            .distinctUntilChanged()
            .collect {
                anchorPage = it
                onPageChanged(it)
            }
    }
    LaunchedEffect(progressGid, doc, readingMode, pagerDual, landscapeCover) {
        if (progressGid == 0L || doc == null) return@LaunchedEffect
        snapshotFlow { realPageIndex() }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { page ->
                runCatching { EhDB.putReadProgress(progressGid, page) }
            }
    }
    val scaleType by Settings.imageScaleType.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackdrop)
            .thenIf(keepScreenOn) { keepScreenOn() },
    ) {
        when {
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(error, color = Color.White, modifier = Modifier.padding(24.dp))
                }
            }
            doc == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                    val zoomableState = rememberZoomableState(zoomSpec = PdfZoomSpec)
                    val zoomedIn = (zoomableState.zoomFraction ?: 0f) > 0.01f
                    val gestures = if (zoomedIn) {
                        EnabledZoomGestures.ZoomAndPan
                    } else {
                        EnabledZoomGestures(zoom = true, pan = false)
                    }
                    val liveZoom by remember {
                        derivedStateOf {
                            val t = zoomableState.contentTransformation
                            if (!t.isSpecified) 1f else t.scale.scaleX.coerceAtLeast(1f)
                        }
                    }
                    var renderZoom by remember { mutableFloatStateOf(1f) }
                    LaunchedEffect(zoomableState) {
                        snapshotFlow { liveZoom }
                            .debounce(120)
                            .distinctUntilChanged { a, b -> abs(a - b) < 0.08f }
                            .collect { renderZoom = it }
                    }
                    val heightPx = with(LocalDensity.current) { maxHeight.roundToPx() }.coerceAtLeast(1)
                    val vectorWidthPx = (widthPx * renderZoom).roundToInt()
                        .coerceIn(widthPx, MAX_VECTOR_EDGE)
                    var appliedScale by remember { mutableIntStateOf(scaleType) }
                    LaunchedEffect(scaleType) {
                        if (appliedScale == scaleType) return@LaunchedEffect
                        appliedScale = scaleType
                        zoomableState.resetZoom()
                    }
                    var multiTouch by remember { mutableStateOf(false) }
                    val viewerModifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            if (size != viewportPx) viewportPx = size
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    multiTouch = event.changes.count { it.pressed } >= 2
                                    if (event.changes.any { it.changedToDown() }) {
                                        val hide = chromeVisible
                                        suppressPageClick = hide
                                        if (hide) appbarVisible = false
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                waitForUpOrCancellation()
                                showNavigationOverlay = false
                            }
                        }
                        .thenIf(isWebtoon) {
                            zoomable(
                                state = zoomableState,
                                gestures = gestures,
                                onClick = { offset ->
                                    val w = viewportPx.width.takeIf { it > 0 }
                                        ?: listState.layoutInfo.viewportSize.width
                                    val h = viewportPx.height.takeIf { it > 0 }
                                        ?: listState.layoutInfo.viewportSize.height
                                    if (w <= 0 || h <= 0) return@zoomable
                                    when (navigator.getAction(Offset(offset.x / w, offset.y / h))) {
                                        NavigationRegion.MENU -> {
                                            if (!suppressPageClick) appbarVisible = !appbarVisible
                                        }
                                        NavigationRegion.NEXT -> {
                                            scope.launch { stepPdfPage(forward = true) }
                                        }
                                        NavigationRegion.PREV -> {
                                            scope.launch { stepPdfPage(forward = false) }
                                        }
                                        NavigationRegion.RIGHT -> {
                                            scope.launch { stepPdfPage(forward = !tapRtl) }
                                        }
                                        NavigationRegion.LEFT -> {
                                            scope.launch { stepPdfPage(forward = tapRtl) }
                                        }
                                    }
                                },
                                onDoubleClick = doubleTap,
                            )
                        }
                    val sidePadding = with(LocalDensity.current) {
                        val edge = if (webtoonHorizontal) heightPx else widthPx
                        (edge * Settings.webtoonSidePadding.value / 100f).toDp()
                    }
                    val pageGap = if (readingMode == ReadingModeType.CONTINUOUS_VERTICAL) 15.dp else 0.dp
                    var dualZoom by remember { mutableFloatStateOf(1f) }
                    val pageClick: (Offset) -> Unit = { offset ->
                        val w = viewportPx.width.takeIf { it > 0 } ?: widthPx
                        val h = viewportPx.height.takeIf { it > 0 } ?: heightPx
                        if (w > 0 && h > 0) {
                            when (navigator.getAction(Offset(offset.x / w, offset.y / h))) {
                                NavigationRegion.MENU -> {
                                    if (!suppressPageClick) appbarVisible = !appbarVisible
                                }
                                NavigationRegion.NEXT -> scope.launch { stepPdfPage(forward = true) }
                                NavigationRegion.PREV -> scope.launch { stepPdfPage(forward = false) }
                                NavigationRegion.RIGHT -> scope.launch { stepPdfPage(forward = !tapRtl) }
                                NavigationRegion.LEFT -> scope.launch { stepPdfPage(forward = tapRtl) }
                            }
                        }
                    }
                    val pageAt: @Composable (Int, PdfPageBox, Int, Int) -> Unit =
                        { index, box, cellW, cellH ->
                            val viewport = Size(cellW.toFloat(), cellH.toFloat())
                            when {
                                imageLoader != null && box == PdfPageBox.Single -> {
                                    val page = imageLoader.pages.getOrNull(index)
                                    if (page != null) {
                                        PdfSingleImagePage(
                                            page = page,
                                            pageLoader = imageLoader,
                                            viewWidthPx = cellW,
                                            viewHeightPx = cellH,
                                            scaleType = scaleType,
                                            isRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT,
                                            isVertical = readingMode == ReadingModeType.VERTICAL,
                                            onClick = pageClick,
                                            onDoubleClick = doubleTap,
                                        )
                                    }
                                }
                                imageLoader != null -> {
                                    val page = imageLoader.pages.getOrNull(index)
                                    if (page != null) {
                                        val scale = when (box) {
                                            PdfPageBox.Strip -> ContentScale.FillHeight
                                            PdfPageBox.Webtoon -> ContentScale.FillWidth
                                            PdfPageBox.Cell -> ContentScale.Fit
                                            PdfPageBox.Single -> ContentScale.fromPreferences(
                                                scaleType,
                                                Size(page.layoutAspect.coerceAtLeast(0.01f), 1f),
                                                viewport,
                                            )
                                        }
                                        PagerItem(
                                            page = page,
                                            pageLoader = imageLoader,
                                            contentScale = scale,
                                            viewportSize = viewport,
                                            horizontalStrip = box == PdfPageBox.Strip,
                                            modifier = if (box == PdfPageBox.Strip) {
                                                Modifier.fillMaxHeight()
                                            } else {
                                                Modifier.fillMaxSize()
                                            },
                                        )
                                    }
                                }
                                doc is PdfDocumentModel.Vector -> if (box == PdfPageBox.Single) {
                                    PdfSingleVectorPage(
                                        session = doc.session,
                                        index = index,
                                        viewWidthPx = cellW,
                                        viewHeightPx = cellH,
                                        scaleType = scaleType,
                                        isRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT,
                                        isVertical = readingMode == ReadingModeType.VERTICAL,
                                        onDoubleClick = doubleTap,
                                        onClick = pageClick,
                                    )
                                } else {
                                    PdfVectorPage(
                                        session = doc.session,
                                        index = index,
                                        widthPx = if (box == PdfPageBox.Cell) {
                                            (vectorWidthPx * dualZoom).roundToInt().coerceIn(1, MAX_VECTOR_EDGE)
                                        } else {
                                            vectorWidthPx
                                        },
                                        viewWidthPx = cellW,
                                        viewHeightPx = cellH,
                                        box = box,
                                        scaleType = if (box == PdfPageBox.Cell) 1 else scaleType,
                                    )
                                }
                            }
                        }
                    if (isWebtoon) {
                        key(webtoonHorizontal) {
                            if (webtoonHorizontal) {
                                LazyRow(
                                    state = listState,
                                    reverseLayout = true,
                                    userScrollEnabled = !multiTouch,
                                    contentPadding = PaddingValues(vertical = sidePadding),
                                    horizontalArrangement = Arrangement.spacedBy(pageGap),
                                    modifier = viewerModifier,
                                ) {
                                    items(pageCount, key = { it }) { index ->
                                        pageAt(index, PdfPageBox.Strip, widthPx, heightPx)
                                    }
                                }
                            } else {
                                LazyColumn(
                                    state = listState,
                                    userScrollEnabled = !multiTouch,
                                    contentPadding = PaddingValues(horizontal = sidePadding),
                                    verticalArrangement = Arrangement.spacedBy(pageGap),
                                    modifier = viewerModifier,
                                ) {
                                    items(pageCount, key = { it }) { index ->
                                        pageAt(index, PdfPageBox.Webtoon, widthPx, heightPx)
                                    }
                                }
                            }
                        }
                    } else if (pagerDual) {
                        val spreadAt: @Composable (Int) -> Unit = { spread ->
                            PdfDualSpread(
                                spread = spread,
                                pageCount = pageCount,
                                isRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT,
                                cover = landscapeCover,
                                gap = dualPageGap,
                                viewWidthPx = widthPx,
                                viewHeightPx = heightPx,
                                scaleType = scaleType,
                                isVertical = readingMode == ReadingModeType.VERTICAL,
                                onClick = pageClick,
                                onDoubleClick = doubleTap,
                                onRenderZoom = { dualZoom = it },
                                aspectOf = { index ->
                                    val fromImage = imageLoader?.pages?.getOrNull(index)?.layoutAspect ?: 0f
                                    if (fromImage > 0f) {
                                        fromImage
                                    } else if (doc is PdfDocumentModel.Vector) {
                                        withContext(Dispatchers.IO) {
                                            runCatching { doc.session.pageAspect(index) }.getOrDefault(1f / 1.414f)
                                        }
                                    } else {
                                        1f / 1.414f
                                    }
                                },
                                pageAt = pageAt,
                            )
                        }
                        if (readingMode == ReadingModeType.VERTICAL) {
                            VerticalPager(
                                state = pagerState,
                                userScrollEnabled = !multiTouch,
                                modifier = viewerModifier,
                            ) { index -> spreadAt(index) }
                        } else {
                            val pagerRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT
                            val isRtlLayout = LocalLayoutDirection.current == LayoutDirection.Rtl
                            HorizontalPager(
                                state = pagerState,
                                reverseLayout = pagerRtl xor isRtlLayout,
                                userScrollEnabled = !multiTouch,
                                modifier = viewerModifier,
                            ) { index -> spreadAt(index) }
                        }
                    } else if (readingMode == ReadingModeType.VERTICAL) {
                        VerticalPager(
                            state = pagerState,
                            userScrollEnabled = !multiTouch,
                            modifier = viewerModifier,
                        ) { index -> pageAt(index, PdfPageBox.Single, widthPx, heightPx) }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            reverseLayout = (readingMode == ReadingModeType.RIGHT_TO_LEFT) xor
                                (LocalLayoutDirection.current == LayoutDirection.Rtl),
                            userScrollEnabled = !multiTouch,
                            modifier = viewerModifier,
                        ) { index -> pageAt(index, PdfPageBox.Single, widthPx, heightPx) }
                    }
                    NavigationOverlay(
                        visible = showNavigationOverlay,
                        regions = regions,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!isWebtoon) {
                        EInkRefreshOverlay(pagerState = pagerState)
                    }
                }
            }
        }
        ReaderAppBars(
            visible = appbarVisible,
            onNavigateUp = onClose,
            showTopBar = !hideTopBar,
            title = title,
            isRtl = tapRtl,
            showSeekBar = showSeekbar,
            currentPage = currentPage,
            totalPages = pageCount,
            onSliderValueChange = { page ->
                scope.launch {
                    jumpToPdfPage(page - 1)
                }
            },
            onClickSettings = {
                scope.launch {
                    dialog { cont ->
                        fun dispose() {
                            if (cont.isActive) cont.resume(Unit)
                        }
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                            ),
                            sheetState = sheetState,
                            scrimColor = Color.Transparent,
                            dragHandle = null,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            Box(Modifier.readerSheetBox(GalleryGridDefaults.capReaderSheet())) {
                                val sheetMode by Settings.readingMode.collectAsState {
                                    ReadingModeType.fromPreference(it)
                                }
                                SettingsPager(
                                    isWebtoon = ReadingModeType.isWebtoon(sheetMode),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            },
            onClickPhotoGrid = if (readerPhotoGrid && doc != null) {
                { photoGridOpen = true }
            } else {
                null
            },
            showScaleFitCycle = !isWebtoon && (!pagerDual || !dualPageGap),
            onClickContents = { contentsOpen = true },
        )
        if (contentsOpen) {
            PdfContentsSheet(
                chapters = doc?.chapters.orEmpty(),
                pageCount = pageCount,
                currentPage = currentPage,
                listState = contentsListState,
                onDismiss = { contentsOpen = false },
                onPick = { page ->
                    contentsOpen = false
                    scope.launch { jumpToPdfPage(page) }
                },
            )
        }
        if (photoGridOpen && doc != null) {
            PdfThumbGridSheet(
                doc = doc,
                pageCount = pageCount,
                currentPage = currentPage,
                cacheKey = sourceArgs?.let { readerPdfCacheKey(it) },
                allowGenerate = !networkPdf || downloadNetworkThumbs,
                gridState = thumbGridState,
                cellAspect = thumbAspect,
                onCellAspect = { aspect ->
                    if (thumbAspect == null) thumbAspect = aspect
                },
                onDismiss = { photoGridOpen = false },
                onPick = { page ->
                    photoGridOpen = false
                    scope.launch { jumpToPdfPage(page - 1) }
                },
            )
        }
        if (showPageNumber && !appbarVisible && currentPage > 0 && pageCount > 0) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                PageIndicatorText(
                    currentPage = currentPage,
                    totalPages = pageCount,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
        var showNextGalleryFab by remember { mutableStateOf(false) }
        var hasNextGallery by remember { mutableStateOf(false) }
        var lastTrackedPage by remember { mutableIntStateOf(currentPage) }
        LaunchedEffect(sourceArgs) {
            hasNextGallery = withContext(Dispatchers.IO) {
                val current = sourceArgs
                if (current == null) {
                    false
                } else {
                    runCatching { GallerySiblingNavigator.sibling(current, next = true) }
                        .getOrNull() != null
                }
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { Triple(currentPage, pageCount, hasNextGallery) }
                .collect { (page, total, canNext) ->
                    val prev = lastTrackedPage
                    lastTrackedPage = page
                    val onLast = total > 0 && page >= total
                    showNextGalleryFab = when {
                        page < prev -> false
                        onLast && canNext -> true
                        else -> false
                    }
                }
        }
        AnimatedVisibility(
            visible = showNextGalleryFab && pageCount > 0 && currentPage >= pageCount,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 16.dp),
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(onClick = { hopSibling(true) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = stringResource(R.string.go_to_next_gallery),
                )
            }
        }
    }
}

private enum class PdfPageBox { Single, Webtoon, Strip, Cell }

@Composable
private fun PdfZoomStartAlignment(
    zoomableState: ZoomableState,
    contentSize: Size,
    viewport: Size,
    contentScale: ContentScale,
    isRtl: Boolean,
    isVertical: Boolean,
    gap: Boolean = false,
) {
    val zoomStart by Settings.zoomStart.collectAsState()
    val alignment = Alignment.fromPreferences(zoomStart, isRtl, isVertical)
    if (gap) {
        zoomableState.contentAlignment = Alignment.Center
    } else {
        LaunchedEffect(contentSize, contentScale, alignment, viewport) {
            zoomableState.applyPagerContentAlignment(contentSize, contentScale, viewport, alignment)
        }
    }
}

@Composable
private fun PdfSingleImagePage(
    page: Page,
    pageLoader: PdfRamPageLoader,
    viewWidthPx: Int,
    viewHeightPx: Int,
    scaleType: Int,
    isRtl: Boolean,
    isVertical: Boolean,
    onClick: (Offset) -> Unit,
    onDoubleClick: DoubleClickToZoomListener,
) {
    val zoomableState = rememberZoomableState(zoomSpec = PdfZoomSpec)
    val aspect = page.layoutAspect.coerceAtLeast(0.01f)
    val contentSize = Size(
        viewWidthPx.toFloat().coerceAtLeast(1f),
        (viewWidthPx / aspect).coerceAtLeast(1f),
    )
    val viewport = Size(viewWidthPx.toFloat().coerceAtLeast(1f), viewHeightPx.toFloat().coerceAtLeast(1f))
    val contentScale = ContentScale.fromPreferences(scaleType, contentSize, viewport)
    zoomableState.contentScale = contentScale
    PdfZoomStartAlignment(
        zoomableState = zoomableState,
        contentSize = contentSize,
        viewport = viewport,
        contentScale = contentScale,
        isRtl = isRtl,
        isVertical = isVertical,
    )
    LaunchedEffect(contentSize) {
        zoomableState.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(contentSize))
    }
    var appliedScale by remember { mutableIntStateOf(scaleType) }
    LaunchedEffect(scaleType) {
        if (appliedScale == scaleType) return@LaunchedEffect
        appliedScale = scaleType
        zoomableState.resetZoom()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zoomable(
                state = zoomableState,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            ),
    ) {
        PagerItem(
            page = page,
            pageLoader = pageLoader,
            contentScale = ContentScale.Inside,
            viewportSize = viewport,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PdfDualSpread(
    spread: Int,
    pageCount: Int,
    isRtl: Boolean,
    cover: Boolean,
    gap: Boolean,
    viewWidthPx: Int,
    viewHeightPx: Int,
    scaleType: Int,
    isVertical: Boolean,
    onClick: (Offset) -> Unit,
    onDoubleClick: DoubleClickToZoomListener,
    onRenderZoom: (Float) -> Unit,
    aspectOf: suspend (Int) -> Float,
    pageAt: @Composable (Int, PdfPageBox, Int, Int) -> Unit,
) {
    val zoomableState = rememberZoomableState(zoomSpec = PdfZoomSpec)
    val (left, right) = dualLeftRight(spread, pageCount, isRtl, cover)
    val solo = left == null || right == null
    if (solo) {
        val only = left ?: right ?: return
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            pageAt(only, PdfPageBox.Single, viewWidthPx, viewHeightPx)
        }
        return
    }
    var leftAspect by remember(left) { mutableFloatStateOf(1f / 1.414f) }
    var rightAspect by remember(right) { mutableFloatStateOf(1f / 1.414f) }
    val leftIndex = left
    val rightIndex = right
    LaunchedEffect(leftIndex, rightIndex) {
        if (!gap) {
            leftAspect = aspectOf(leftIndex)
            rightAspect = aspectOf(rightIndex)
        }
    }
    val combined = (leftAspect + rightAspect).coerceAtLeast(0.01f)
    val contentSize = if (gap) {
        Size(viewWidthPx.toFloat().coerceAtLeast(1f), viewHeightPx.toFloat().coerceAtLeast(1f))
    } else {
        Size(
            viewWidthPx.toFloat().coerceAtLeast(1f),
            (viewWidthPx / combined).coerceAtLeast(1f),
        )
    }
    val viewport = Size(viewWidthPx.toFloat().coerceAtLeast(1f), viewHeightPx.toFloat().coerceAtLeast(1f))
    val contentScale = if (gap) {
        ContentScale.Fit
    } else {
        ContentScale.fromPreferences(scaleType, contentSize, viewport)
    }
    zoomableState.contentScale = contentScale
    PdfZoomStartAlignment(
        zoomableState = zoomableState,
        contentSize = contentSize,
        viewport = viewport,
        contentScale = contentScale,
        isRtl = isRtl,
        isVertical = isVertical,
        gap = gap,
    )
    LaunchedEffect(contentSize, gap) {
        zoomableState.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(contentSize))
    }
    var appliedScale by remember { mutableIntStateOf(scaleType) }
    LaunchedEffect(scaleType) {
        if (appliedScale == scaleType) return@LaunchedEffect
        appliedScale = scaleType
        zoomableState.resetZoom()
    }
    val liveZoom by remember {
        derivedStateOf {
            val t = zoomableState.contentTransformation
            if (!t.isSpecified) 1f else t.scale.scaleX.coerceAtLeast(1f)
        }
    }
    LaunchedEffect(zoomableState) {
        snapshotFlow { liveZoom }
            .debounce(120)
            .distinctUntilChanged { a, b -> abs(a - b) < 0.08f }
            .collect { onRenderZoom(it) }
    }
    val spreadPx = if (gap) {
        viewport
    } else {
        insideSpreadSize(contentSize, viewport).takeIf { it.width > 0f && it.height > 0f } ?: contentSize
    }
    val leftW = (spreadPx.width * if (gap) 0.5f else leftAspect / combined).roundToInt().coerceAtLeast(1)
    val rightW = (spreadPx.width.roundToInt() - leftW).coerceAtLeast(1)
    val cellH = spreadPx.height.roundToInt().coerceAtLeast(1)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zoomable(
                state = zoomableState,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = if (gap) {
                Modifier.fillMaxSize()
            } else {
                Modifier.layout { measurable, _ ->
                    val w = spreadPx.width.roundToInt().coerceAtLeast(1)
                    val h = spreadPx.height.roundToInt().coerceAtLeast(1)
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    layout(w, h) { placeable.place(0, 0) }
                }
            },
        ) {
            Box(Modifier.weight(if (gap) 1f else leftAspect).fillMaxHeight()) {
                pageAt(left, PdfPageBox.Cell, leftW, cellH)
            }
            Box(Modifier.weight(if (gap) 1f else rightAspect).fillMaxHeight()) {
                pageAt(right, PdfPageBox.Cell, rightW, cellH)
            }
        }
    }
}

@Composable
private fun PdfSingleVectorPage(
    session: PageBitmapSession,
    index: Int,
    viewWidthPx: Int,
    viewHeightPx: Int,
    scaleType: Int,
    isRtl: Boolean,
    isVertical: Boolean,
    onClick: (Offset) -> Unit,
    onDoubleClick: DoubleClickToZoomListener,
) {
    val zoomableState = rememberZoomableState(zoomSpec = PdfZoomSpec)
    var aspect by remember(index) { mutableFloatStateOf(1f / 1.414f) }
    val contentSize = Size(
        viewWidthPx.toFloat().coerceAtLeast(1f),
        (viewWidthPx / aspect.coerceAtLeast(0.01f)).coerceAtLeast(1f),
    )
    val viewport = Size(viewWidthPx.toFloat().coerceAtLeast(1f), viewHeightPx.toFloat().coerceAtLeast(1f))
    val contentScale = ContentScale.fromPreferences(scaleType, contentSize, viewport)
    zoomableState.contentScale = contentScale
    PdfZoomStartAlignment(
        zoomableState = zoomableState,
        contentSize = contentSize,
        viewport = viewport,
        contentScale = contentScale,
        isRtl = isRtl,
        isVertical = isVertical,
    )
    LaunchedEffect(contentSize) {
        zoomableState.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(contentSize))
    }
    var appliedScale by remember { mutableIntStateOf(scaleType) }
    LaunchedEffect(scaleType) {
        if (appliedScale == scaleType) return@LaunchedEffect
        appliedScale = scaleType
        zoomableState.resetZoom()
    }
    val liveZoom by remember {
        derivedStateOf {
            val t = zoomableState.contentTransformation
            if (!t.isSpecified) 1f else t.scale.scaleX.coerceAtLeast(1f)
        }
    }
    var renderZoom by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(zoomableState) {
        snapshotFlow { liveZoom }
            .debounce(120)
            .distinctUntilChanged { a, b -> abs(a - b) < 0.08f }
            .collect { renderZoom = it }
    }
    val renderWidth = (pdfScaleRenderWidth(aspect, viewWidthPx, viewHeightPx, scaleType) * renderZoom)
        .roundToInt()
        .coerceIn(1, MAX_VECTOR_EDGE)
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, index, renderWidth, scaleType) {
        var next: Bitmap? = null
        try {
            next = withContext(Dispatchers.IO) {
                runCatching { session.render(index, renderWidth) }.getOrNull()
            }
            if (next != null) {
                val measured = if (next.height > 0) next.width.toFloat() / next.height else aspect
                val prev = bitmap
                bitmap = next
                next = null
                if (prev != null && prev !== bitmap) prev.recycle()
                if (measured != aspect) aspect = measured
            }
        } finally {
            next?.recycle()
        }
    }
    DisposableEffect(index) {
        onDispose { bitmap?.recycle() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zoomable(
                state = zoomableState,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PdfPageBitmap(
            bitmap = bitmap,
            pageLabel = index + 1,
            pageCount = session.pageCount,
            box = PdfPageBox.Single,
            scaleType = scaleType,
            aspect = aspect,
        )
    }
}

@Composable
private fun PdfVectorPage(
    session: PageBitmapSession,
    index: Int,
    widthPx: Int,
    viewWidthPx: Int,
    viewHeightPx: Int,
    box: PdfPageBox,
    scaleType: Int,
) {
    var aspect by remember(index) { mutableFloatStateOf(1f / 1.414f) }
    val renderWidth = when (box) {
        PdfPageBox.Webtoon -> widthPx
        PdfPageBox.Strip -> {
            val zoom = if (viewWidthPx > 0) widthPx.toFloat() / viewWidthPx else 1f
            (viewHeightPx * aspect * zoom).roundToInt().coerceIn(1, MAX_VECTOR_EDGE)
        }
        else -> {
            val zoom = if (viewWidthPx > 0) widthPx.toFloat() / viewWidthPx else 1f
            (pdfScaleRenderWidth(aspect, viewWidthPx, viewHeightPx, scaleType) * zoom)
                .roundToInt()
                .coerceIn(1, MAX_VECTOR_EDGE)
        }
    }
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, index, renderWidth, scaleType) {
        var next: Bitmap? = null
        try {
            next = withContext(Dispatchers.IO) {
                runCatching { session.render(index, renderWidth) }.getOrNull()
            }
            if (next != null) {
                val measured = if (next.height > 0) next.width.toFloat() / next.height else aspect
                val prev = bitmap
                bitmap = next
                next = null
                if (prev != null && prev !== bitmap) prev.recycle()
                if (measured != aspect) aspect = measured
            }
        } finally {
            next?.recycle()
        }
    }
    DisposableEffect(index) {
        onDispose {
            bitmap?.recycle()
        }
    }
    PdfPageBitmap(
        bitmap = bitmap,
        pageLabel = index + 1,
        pageCount = session.pageCount,
        box = box,
        scaleType = scaleType,
        aspect = aspect,
    )
}

private fun pdfScaleRenderWidth(aspect: Float, viewW: Int, viewH: Int, scaleType: Int): Int {
    val safeAspect = aspect.coerceAtLeast(0.01f)
    val width = when (scaleType) {
        3 -> viewW.toFloat()
        4 -> viewH * safeAspect
        2 -> maxOf(viewW.toFloat(), viewH * safeAspect)
        6 -> if (safeAspect > 1f) viewH * safeAspect else viewW.toFloat()
        else -> minOf(viewW.toFloat(), viewH * safeAspect)
    }
    return width.roundToInt().coerceIn(1, MAX_VECTOR_EDGE)
}

@Composable
private fun PdfPageBitmap(
    bitmap: Bitmap?,
    pageLabel: Int,
    pageCount: Int,
    box: PdfPageBox,
    scaleType: Int,
    aspect: Float,
) {
    val frame = when (box) {
        PdfPageBox.Strip -> Modifier.fillMaxHeight().aspectRatio(aspect.coerceAtLeast(0.01f), matchHeightConstraintsFirst = true)
        // Placeholder must have a real height. A zero-height row makes LazyColumn
        // compose every page of a long book before the first bitmap exists.
        PdfPageBox.Webtoon -> Modifier.fillMaxWidth().aspectRatio(aspect.coerceAtLeast(0.01f))
        PdfPageBox.Single, PdfPageBox.Cell -> Modifier.fillMaxSize()
    }
    BoxWithConstraints(modifier = frame, contentAlignment = Alignment.Center) {
        if (bitmap == null || bitmap.isRecycled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect.coerceAtLeast(0.01f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val contentScale = when (box) {
                PdfPageBox.Single -> ContentScale.Inside
                PdfPageBox.Cell -> ContentScale.Fit
                PdfPageBox.Strip -> ContentScale.FillHeight
                PdfPageBox.Webtoon -> ContentScale.FillWidth
            }
            val imageMod = when (box) {
                PdfPageBox.Webtoon -> Modifier.fillMaxWidth()
                PdfPageBox.Strip -> Modifier.fillMaxHeight()
                else -> Modifier.fillMaxSize()
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_reader_page, pageLabel, pageCount),
                contentScale = contentScale,
                modifier = imageMod,
            )
        }
    }
}

@Composable
private fun PdfContentsSheet(
    chapters: List<PdfTocEntry>,
    pageCount: Int,
    currentPage: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val pageIndex = (currentPage - 1).coerceAtLeast(0)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        scrimColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets() },
    ) {
        val pagesOnly = chapters.isEmpty()
        val entries = remember(chapters, pageCount) {
            if (!pagesOnly) {
                chapters
            } else {
                List(pageCount) { index ->
                    PdfTocEntry(title = "${index + 1}", pageIndex = index, depth = 0)
                }
            }
        }
        val nearest = if (entries.isEmpty()) -1 else nearestPdfTocIndex(entries, pageIndex)
        var searching by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        val queryTrim = query.trim()
        val visible = remember(entries, queryTrim) {
            entries.mapIndexed { index, entry -> index to entry }.filter { (_, entry) ->
                queryTrim.isEmpty() || entry.title.contains(queryTrim, ignoreCase = true)
            }
        }
        val headerStyle = MaterialTheme.typography.bodyLarge
        val iconSize = with(LocalDensity.current) { headerStyle.fontSize.toDp() }
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(searching) {
            if (searching) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }
        LaunchedEffect(queryTrim) {
            if (queryTrim.isNotEmpty() && visible.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
        fun jumpToFirstResult() {
            val first = visible.firstOrNull()?.second ?: return
            onPick(first.pageIndex)
        }
        Column(Modifier.readerSheetBox(GalleryGridDefaults.capReaderSheet()).navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = headerStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { jumpToFirstResult() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.pdf_reader_contents_search),
                                    style = headerStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                } else {
                    Text(
                        when {
                            pagesOnly -> stringResource(R.string.pdf_reader_pages)
                            nearest >= 0 -> entries[nearest].title
                            else -> stringResource(R.string.pdf_reader_contents)
                        },
                        style = headerStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (entries.isNotEmpty()) {
                    val searchInteraction = remember { MutableInteractionSource() }
                    val locateInteraction = remember { MutableInteractionSource() }
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.pdf_reader_contents_search),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(iconSize + 4.dp)
                            .clickable(
                                interactionSource = searchInteraction,
                                indication = null,
                            ) {
                                searching = !searching
                                if (!searching) {
                                    query = ""
                                    keyboard?.hide()
                                }
                            },
                    )
                    Icon(
                        imageVector = Icons.Outlined.MyLocation,
                        contentDescription = stringResource(R.string.pdf_reader_contents_locate),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(iconSize + 4.dp)
                            .clickable(
                                interactionSource = locateInteraction,
                                indication = null,
                            ) {
                                val target = visible.indexOfFirst { it.first == nearest }.let { found ->
                                    if (found >= 0) found else 0
                                }
                                scope.launch { listState.animateScrollToItem(target) }
                            },
                    )
                }
            }
            if (visible.isEmpty()) {
                Text(
                    stringResource(R.string.pdf_reader_contents_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(visible, key = { _, item -> "${item.first}-${item.second.pageIndex}-${item.second.depth}-${item.second.title}" }) { _, item ->
                        val index = item.first
                        val entry = item.second
                        val selected = index == nearest
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { onPick(entry.pageIndex) }
                                .padding(
                                    start = if (pagesOnly) 16.dp else (12 + entry.depth * 12).dp,
                                    end = 16.dp,
                                    top = 10.dp,
                                    bottom = 10.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.title,
                                style = if (pagesOnly || entry.depth <= 1) {
                                    MaterialTheme.typography.bodyLarge
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                color = if (pagesOnly || entry.depth <= 1) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                textAlign = if (pagesOnly) TextAlign.Center else TextAlign.Start,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!pagesOnly) {
                                Text(
                                    "${entry.pageIndex + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun nearestPdfTocIndex(chapters: List<PdfTocEntry>, pageIndex: Int): Int {
    var best = -1
    var bestPage = Int.MIN_VALUE
    chapters.forEachIndexed { index, entry ->
        if (entry.pageIndex <= pageIndex && entry.pageIndex >= bestPage) {
            best = index
            bestPage = entry.pageIndex
        }
    }
    if (best >= 0) return best
    return chapters.indices.minByOrNull { kotlin.math.abs(chapters[it].pageIndex - pageIndex) } ?: 0
}

@Composable
private fun PdfThumbGridSheet(
    doc: PdfDocumentModel,
    pageCount: Int,
    currentPage: Int,
    cacheKey: String?,
    allowGenerate: Boolean,
    gridState: LazyGridState,
    cellAspect: Float?,
    onCellAspect: (Float) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = readerPhotoGridSheetMaxWidth(),
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
        scrimColor = Color.Transparent,
        dragHandle = null,
        contentWindowInsets = { WindowInsets() },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(GalleryGridDefaults.columnCount()),
            state = gridState,
            modifier = Modifier
                .readerSheetBox(GalleryGridDefaults.capReaderSheet())
                .navigationBarsPadding()
                .padding(8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            items(pageCount, key = { it }) { index ->
                PdfPageThumb(
                    doc = doc,
                    index = index,
                    cacheKey = cacheKey,
                    allowGenerate = allowGenerate,
                    selected = index == currentPage - 1,
                    cellAspect = cellAspect,
                    onCellAspect = onCellAspect,
                    onClick = { onPick(index + 1) },
                )
            }
        }
    }
}

@Composable
private fun PdfPageThumb(
    doc: PdfDocumentModel,
    index: Int,
    cacheKey: String?,
    allowGenerate: Boolean,
    selected: Boolean,
    cellAspect: Float?,
    onCellAspect: (Float) -> Unit,
    onClick: () -> Unit,
) {
    val identity = cacheKey?.let { key ->
        val prefix = if (doc is PdfDocumentModel.Vector) "pdfrender" else "doc"
        "$prefix:$key:$index"
    }
    var bitmap by remember(identity, doc, index) { mutableStateOf<Bitmap?>(null) }
    var skipped by remember(identity) { mutableStateOf(false) }
    LaunchedEffect(doc, index, identity, allowGenerate) {
        skipped = false
        val cached = identity?.let { id ->
            withContext(Dispatchers.IO) {
                ReaderPageThumb.find(id)?.let { BitmapFactory.decodeFile(it.toString()) }
            }
        }
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        if (!allowGenerate) {
            skipped = true
            return@LaunchedEffect
        }
        var rendered: Bitmap? = null
        try {
            rendered = withContext(Dispatchers.IO) {
                when (doc) {
                    is PdfDocumentModel.Vector -> runCatching {
                        doc.session.renderLongEdge(index, OriginDiskCache.THUMB_EDGE)
                    }.getOrNull()
                    is PdfDocumentModel.Images -> runCatching {
                        doc.engine.ensureListedThrough(index)
                        doc.engine.extractBytes(index)?.let(::decodePdfThumb)
                    }.getOrNull()
                }
            }
            val thumb = rendered
            if (thumb != null && identity != null) {
                // Keep the file even if this cell scrolls off before the encode returns.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { ReaderPageThumb.ensureFromBitmap(identity, thumb) }
                }
            }
            if (!isActive) return@LaunchedEffect
            bitmap = rendered
            rendered = null
        } finally {
            rendered?.recycle()
        }
    }
    DisposableEffect(index) {
        onDispose { bitmap?.recycle() }
    }
    val bmp = bitmap
    val measured = if (bmp != null && !bmp.isRecycled && bmp.height > 0) {
        bmp.width.toFloat() / bmp.height
    } else {
        null
    }
    if (cellAspect == null && measured != null) {
        SideEffect { onCellAspect(measured) }
    }
    val aspect = cellAspect ?: measured ?: (1f / 1.414f)
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(shape)
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null || bmp.isRecycled) {
            if (!skipped) CircularProgressIndicator()
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_reader_page, index + 1, doc.pageCount),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun decodePdfThumb(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val edge = maxOf(bounds.outWidth, bounds.outHeight)
    if (edge <= 0) return null
    val target = OriginDiskCache.THUMB_EDGE
    var sample = 1
    while (edge / sample > target * 2 && sample < 32) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val longEdge = maxOf(decoded.width, decoded.height)
    if (longEdge <= target) return decoded
    val scale = target.toFloat() / longEdge
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== decoded && !decoded.isRecycled) decoded.recycle()
    return scaled
}

private val PageBackdrop = Color(0xFF2B2B2B)

private val PdfZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 5f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)

private const val MAX_VECTOR_EDGE = 6144
private const val MAX_VECTOR_PIXELS = 6144 * 6144
