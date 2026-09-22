package com.hippo.ehviewer.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.provider.StreamDocumentProvider
import com.hippo.ehviewer.provider.StreamDocumentRegistry
import eu.kanade.tachiyomi.ui.reader.PageIndicatorText
import eu.kanade.tachiyomi.ui.reader.ReaderAppBars
import kotlinx.coroutines.Dispatchers
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
 * Full-screen in-app PDF reader ([PdfRenderer] page bitmaps).
 *
 * Local: streamdoc content URI (seekable PFD). Network: same URI through
 * [StreamDocumentProvider] proxy FD (PDF sparse block cache).
 */
class PdfReaderActivity : AppCompatActivity() {
    private var session by mutableStateOf<PdfSession?>(null)
    private var title by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var streamToken: String? = null
    private var progressGid by mutableStateOf(0L)
    private var startPage by mutableStateOf(0)
    private var lastVisiblePage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openFromIntent(intent, replace = false)
        setMD3Content {
            PdfReaderScreen(
                title = title,
                session = session,
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
            if (!replace) return
            return
        }
        val renderer = runCatching { PdfRenderer(pfd) }.getOrElse { e ->
            logcat("PdfReader", e)
            runCatching { pfd.close() }
            token?.let(StreamDocumentRegistry::remove)
            error = getString(R.string.pdf_reader_open_failed, e.message ?: e.toString())
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
        session = PdfSession(renderer)
    }

    private fun flushProgress() {
        val gid = progressGid
        if (gid == 0L) return
        runBlocking {
            runCatching { EhDB.putReadProgress(gid, lastVisiblePage.coerceAtLeast(0)) }
        }
    }

    private fun closeSession(removeToken: Boolean = true) {
        session?.close()
        session = null
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

private class PdfSession(private val renderer: PdfRenderer) {
    val pageCount: Int get() = renderer.pageCount
    private val mutex = Mutex()

    suspend fun render(index: Int, widthPx: Int): Bitmap = mutex.withLock {
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceAtLeast(1)
            val h = ((page.height.toFloat() / page.width.coerceAtLeast(1)) * w)
                .toInt()
                .coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    fun close() {
        runCatching { renderer.close() }
    }
}

@Composable
private fun PdfReaderScreen(
    title: String,
    session: PdfSession?,
    error: String?,
    startPage: Int,
    progressGid: Long,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val pageCount = session?.pageCount ?: 0
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
    LaunchedEffect(session, startPage, pageCount) {
        if (session == null || pageCount <= 0) return@LaunchedEffect
        val target = startPage.coerceIn(0, pageCount - 1)
        if (listState.firstVisibleItemIndex != target) {
            listState.scrollToItem(target)
        }
        onPageChanged(target)
    }
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { onPageChanged(it) }
    }
    LaunchedEffect(progressGid, session) {
        if (progressGid == 0L || session == null) return@LaunchedEffect
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
            session == null -> {
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
                            PdfPageImage(
                                session = session,
                                index = index,
                                widthPx = widthPx,
                            )
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
private fun PdfPageImage(
    session: PdfSession,
    index: Int,
    widthPx: Int,
) {
    var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, index, widthPx) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { session.render(index, widthPx) }.getOrNull()
        }
    }
    DisposableEffect(index, widthPx) {
        onDispose {
            bitmap?.recycle()
        }
    }
    val page = bitmap
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PageBackdrop),
        contentAlignment = Alignment.Center,
    ) {
        if (page == null || page.isRecycled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_reader_page, index + 1, session.pageCount),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val PageBackdrop = Color(0xFF2B2B2B)

private val PdfZoomSpec = ZoomSpec(
    maximum = ZoomLimit(factor = 5f),
    minimum = ZoomLimit(factor = 1f, overzoomEffect = OverzoomEffect.Disabled),
)
