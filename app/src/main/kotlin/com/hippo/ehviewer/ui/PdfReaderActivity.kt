package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.PfdArchiveByteSource
import com.hippo.ehviewer.library.document.PdfContentKind
import com.hippo.ehviewer.library.document.PdfImageEngine
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import eu.kanade.tachiyomi.ui.reader.PageIndicatorText
import eu.kanade.tachiyomi.ui.reader.ReaderAppBars
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/**
 * Full-screen in-app PDF reader.
 *
 * Text / generic PDFs: [PdfRenderer] at the current zoom (vector drawing stays sharp).
 * Image / comic PDFs: native embedded bitmaps via [PdfImageEngine].
 *
 * Local: streamdoc content URI (seekable PFD). Network: same URI through
 * [StreamDocumentProvider] proxy FD (PDF sparse block cache).
 */
class PdfReaderActivity : AppCompatActivity() {
    private var doc by mutableStateOf<PdfDocumentModel?>(null)
    private var title by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var streamToken: String? = null
    private var progressGid by mutableStateOf(0L)
    private var startPage by mutableStateOf(0)
    private var lastVisiblePage = 0
    private var openJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openFromIntent(intent, replace = false)
        setMD3Content {
            PdfReaderScreen(
                title = title,
                doc = doc,
                error = error,
                startPage = startPage,
                progressGid = progressGid,
                onPageChanged = { lastVisiblePage = it },
                onClose = { finish() },
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
        val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (pfd == null) {
            token?.let(StreamDocumentRegistry::remove)
            error = getString(R.string.pdf_reader_open_failed, "descriptor")
            return
        }
        val oldToken = streamToken
        closeSession(removeToken = false)
        if (oldToken != null && oldToken != token) StreamDocumentRegistry.remove(oldToken)
        streamToken = token
        title = nextTitle.ifBlank { uri.lastPathSegment.orEmpty() }
        progressGid = intent.getLongExtra(EXTRA_PROGRESS_GID, 0L)
        startPage = intent.getIntExtra(EXTRA_START_PAGE, 0).coerceAtLeast(0)
        lastVisiblePage = startPage
        error = null
        openJob?.cancel()
        openJob = lifecycleScope.launch {
            var opened: PdfDocumentModel? = null
            try {
                opened = withContext(Dispatchers.IO) { openPdfDocument(pfd) }
                doc = opened
                opened = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (opened == null) runCatching { pfd.close() }
                throw e
            } catch (e: Throwable) {
                logcat("PdfReader", e)
                runCatching { pfd.close() }
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
        doc?.close()
        doc = null
        if (removeToken) {
            streamToken?.let(StreamDocumentRegistry::remove)
            streamToken = null
        }
    }

    companion object {
        const val EXTRA_STREAM_TOKEN = "stream_token"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PROGRESS_GID = "progress_gid"
        const val EXTRA_START_PAGE = "start_page"

        fun intent(
            context: Context,
            uri: Uri,
            title: String,
            streamToken: String,
            progressGid: Long = 0L,
            startPage: Int = 0,
        ): Intent = Intent(context, PdfReaderActivity::class.java).apply {
            setDataAndType(uri, DefaultPdfReader.MIME_TYPE)
            putExtra(EXTRA_STREAM_TOKEN, streamToken)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PROGRESS_GID, progressGid)
            putExtra(EXTRA_START_PAGE, startPage)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

private fun openPdfDocument(pfd: ParcelFileDescriptor): PdfDocumentModel {
    val dup = runCatching { pfd.dup() }.getOrNull()
    if (dup != null) {
        val source = PfdArchiveByteSource(dup, ownsPfd = true)
        val size = source.size
        val kind = runCatching { PdfImageEngine.classify(source, size) }
            .getOrDefault(PdfContentKind.Vector)
        if (kind == PdfContentKind.Image) {
            val engine = PdfImageEngine.open(
                source,
                remoteSize = size,
                coverOnly = false,
                progressive = true,
            )
            if (engine != null && engine.ensureListedThrough(0) > 0) {
                logcat("PdfReader") { "image PDF pages=${engine.pageCount}" }
                runCatching { pfd.close() }
                return PdfDocumentModel.Images(engine, source)
            }
        }
        runCatching { source.close() }
    }
    val renderer = runCatching { PdfRenderer(pfd) }.getOrElse { e ->
        runCatching { pfd.close() }
        throw e
    }
    logcat("PdfReader") { "vector PDF pages=${renderer.pageCount}" }
    return PdfDocumentModel.Vector(PdfSession(renderer))
}

private sealed interface PdfDocumentModel {
    val pageCount: Int
    fun close()

    class Vector(val session: PdfSession) : PdfDocumentModel {
        override val pageCount get() = session.pageCount
        override fun close() = session.close()
    }

    class Images(
        val engine: PdfImageEngine,
        private val source: ArchiveByteSource,
    ) : PdfDocumentModel {
        override val pageCount get() = engine.pageCount
        override fun close() {
            runCatching { engine.close() }
            runCatching { source.close() }
        }
    }
}

private class PdfSession(private val renderer: PdfRenderer) {
    val pageCount: Int get() = renderer.pageCount
    private val mutex = Mutex()

    suspend fun render(index: Int, widthPx: Int): Bitmap = mutex.withLock {
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceAtLeast(1)
            val h = ((page.height.toFloat() / page.width.coerceAtLeast(1)) * w)
                .toInt()
                .coerceAtLeast(1)
            val (rw, rh) = cappedBitmapSize(w, h)
            Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    fun close() {
        runCatching { renderer.close() }
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
private fun PdfReaderScreen(
    title: String,
    doc: PdfDocumentModel?,
    error: String?,
    startPage: Int,
    progressGid: Long,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
) {
    var pageCount by remember(doc) { mutableIntStateOf(doc?.pageCount ?: 0) }
    val initial = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val currentPage by remember {
        derivedStateOf {
            if (pageCount <= 0) 0 else listState.firstVisibleItemIndex + 1
        }
    }
    val scope = rememberCoroutineScope()
    val showSeekbar by Settings.showReaderSeekbar.collectAsState()
    val hideTopBar by Settings.readerHideTopBar.collectAsState()
    val showPageNumber by Settings.showPageNumber.collectAsState()
    var appbarVisible by remember { mutableStateOf(false) }
    val chromeVisible by rememberUpdatedState(appbarVisible)
    var suppressPageClick by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error != null) appbarVisible = true
    }
    LaunchedEffect(doc) {
        val images = doc as? PdfDocumentModel.Images ?: run {
            pageCount = doc?.pageCount ?: 0
            return@LaunchedEffect
        }
        while (true) {
            val ahead = (
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: listState.firstVisibleItemIndex
                ) + 8
            images.engine.ensureListedThrough(ahead.coerceAtLeast(0))
            val n = images.engine.pageCount
            if (n != pageCount) pageCount = n
            if (images.engine.structureComplete) {
                pageCount = images.engine.pageCount
                break
            }
            delay(80)
        }
    }
    LaunchedEffect(doc, startPage, pageCount) {
        if (doc == null || pageCount <= 0) return@LaunchedEffect
        val target = startPage.coerceIn(0, pageCount - 1)
        if (listState.firstVisibleItemIndex != target) {
            listState.scrollToItem(target)
        }
        onPageChanged(target)
    }
    LaunchedEffect(doc) {
        if (doc == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }
    LaunchedEffect(progressGid, doc) {
        if (progressGid == 0L || doc == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { page ->
                runCatching { EhDB.putReadProgress(progressGid, page) }
            }
    }
    Box(modifier = Modifier.fillMaxSize().background(PageBackdrop)) {
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
                        .coerceIn(360, 2048)
                    val pages = remember(pageCount) { (0 until pageCount).toList() }
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
                    val vectorWidthPx = (widthPx * renderZoom).roundToInt()
                        .coerceIn(widthPx, MAX_VECTOR_EDGE)
                    var multiTouch by remember { mutableStateOf(false) }
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = !multiTouch,
                        modifier = Modifier
                            .fillMaxSize()
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
                            .zoomable(
                                state = zoomableState,
                                gestures = gestures,
                                onClick = {
                                    if (!suppressPageClick) appbarVisible = !appbarVisible
                                },
                            ),
                    ) {
                        items(pages, key = { it }) { index ->
                            when (doc) {
                                is PdfDocumentModel.Vector -> PdfVectorPage(
                                    session = doc.session,
                                    index = index,
                                    widthPx = vectorWidthPx,
                                )
                                is PdfDocumentModel.Images -> PdfEmbeddedImage(
                                    engine = doc.engine,
                                    index = index,
                                )
                            }
                        }
                    }
                }
            }
        }
        ReaderAppBars(
            visible = appbarVisible,
            onNavigateUp = onClose,
            showTopBar = !hideTopBar,
            title = title,
            isRtl = false,
            showSeekBar = showSeekbar,
            currentPage = currentPage,
            totalPages = pageCount,
            onSliderValueChange = { page ->
                scope.launch {
                    val target = (page - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    listState.scrollToItem(target)
                    onPageChanged(target)
                }
            },
        )
        if (showPageNumber && !appbarVisible && currentPage > 0 && pageCount > 0) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                PageIndicatorText(
                    currentPage = currentPage,
                    totalPages = pageCount,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
    }
}

@Composable
private fun PdfVectorPage(
    session: PdfSession,
    index: Int,
    widthPx: Int,
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, index, widthPx) {
        var next: Bitmap? = null
        try {
            next = withContext(Dispatchers.IO) {
                runCatching { session.render(index, widthPx) }.getOrNull()
            }
            if (next != null) {
                val prev = bitmap
                bitmap = next
                next = null
                if (prev != null && prev !== bitmap) prev.recycle()
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
    PdfPageBitmap(bitmap = bitmap, pageLabel = index + 1, pageCount = session.pageCount)
}

@Composable
private fun PdfEmbeddedImage(
    engine: PdfImageEngine,
    index: Int,
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(engine, index) {
        var next: Bitmap? = null
        try {
            next = withContext(Dispatchers.IO) {
                runCatching {
                    engine.ensureListedThrough(index)
                    engine.extractBytes(index)?.let(::decodeFullResBitmap)
                }.getOrNull()
            }
            if (next != null) {
                val prev = bitmap
                bitmap = next
                next = null
                if (prev != null && prev !== bitmap) prev.recycle()
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
    PdfPageBitmap(bitmap = bitmap, pageLabel = index + 1, pageCount = engine.pageCount)
}

@Composable
private fun PdfPageBitmap(
    bitmap: Bitmap?,
    pageLabel: Int,
    pageCount: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PageBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null || bitmap.isRecycled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.414f)
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_reader_page, pageLabel, pageCount),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun decodeFullResBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    var sample = 1
    if (w > 0 && h > 0) {
        var pw = w.toLong()
        var ph = h.toLong()
        while (pw * ph > MAX_IMAGE_PIXELS && sample < 16) {
            sample *= 2
            pw = w / sample.toLong()
            ph = h / sample.toLong()
        }
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

private val PageBackdrop = Color(0xFF2B2B2B)

private val PdfZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 5f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)

private const val MAX_VECTOR_EDGE = 4096
private const val MAX_VECTOR_PIXELS = 12_000_000
private const val MAX_IMAGE_PIXELS = 24_000_000
