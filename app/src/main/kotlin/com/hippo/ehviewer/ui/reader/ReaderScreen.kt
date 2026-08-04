package com.hippo.ehviewer.ui.reader

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.raise.ensure
import arrow.core.right
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.ui.util.Await
import com.ehviewer.core.ui.util.asyncInVM
import com.ehviewer.core.ui.util.rememberSystemUiController
import com.ehviewer.core.ui.util.thenIf
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.unreachable
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.gallery.Page
import com.hippo.ehviewer.gallery.PageLoader
import com.hippo.ehviewer.gallery.PageStatus
import com.hippo.ehviewer.gallery.PasswdProvider
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.gallery.unblock
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.useDocumentExtractPageLoader
import com.hippo.ehviewer.gallery.useFolderPageLoader
import com.hippo.ehviewer.gallery.useLocalDocumentExtractPageLoader
import com.hippo.ehviewer.gallery.useSmbFolderPageLoader
import com.hippo.ehviewer.gallery.useSolidExtractPageLoader
import com.hippo.ehviewer.gallery.useStreamArchivePageLoader
import com.hippo.ehviewer.gallery.useTarChunkPageLoader
import com.hippo.ehviewer.gallery.useWebDavFolderPageLoader
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.GallerySiblingNavigator
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.isDocumentFileName
import com.hippo.ehviewer.library.isEpubFileName
import com.hippo.ehviewer.library.isSolidArchiveFileName
import com.hippo.ehviewer.library.isTarArchiveFileName
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.theme.EhTheme
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.hasAds
import com.hippo.ehviewer.util.setHdrColorMode
import com.hippo.ehviewer.webdav.WebDavGateway
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import eu.kanade.tachiyomi.ui.reader.PageIndicatorText
import eu.kanade.tachiyomi.ui.reader.ReaderAppBars
import eu.kanade.tachiyomi.ui.reader.ReaderContentOverlay
import eu.kanade.tachiyomi.ui.reader.ReaderPageSheetMeta
import eu.kanade.tachiyomi.ui.reader.setting.ReadingModeType
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import moe.tarsin.string
import okio.Path.Companion.toPath

/**
 * Counts overlapping [ReaderScreen] destinations (e.g. prev/next folder replace with
 * exit/enter animations). System bars are only restored when the last instance leaves,
 * so a sibling switch cannot permanently exit immersive mode.
 */
private val activeReaderSessions = AtomicInteger(0)

@Serializable
sealed interface ReaderScreenArgs {
    /** Local archive file (ZIP/RAR/7z/PDF/EPUB). [info]/[page] optional; resolved on open. */
    @Serializable
    data class Archive(
        val path: String,
        val page: Int = -1,
        val info: BaseGalleryInfo? = null,
    ) : ReaderScreenArgs

    /** Local image folder (direct children only). */
    @Serializable
    data class LocalFolder(
        val path: String,
        val page: Int = -1,
        val info: BaseGalleryInfo? = null,
    ) : ReaderScreenArgs

    /** SMB image folder — pages fetched into local disk cache on demand. */
    @Serializable
    data class SmbFolder(
        val sourceId: Long,
        val remoteDir: String,
        val imageNames: List<String>,
        val page: Int = -1,
        val info: BaseGalleryInfo? = null,
    ) : ReaderScreenArgs

    /** WebDAV image folder — pages fetched into local disk cache on demand. */
    @Serializable
    data class WebDavFolder(
        val sourceId: Long,
        val remoteDir: String,
        val imageNames: List<String>,
        val page: Int = -1,
        val info: BaseGalleryInfo? = null,
    ) : ReaderScreenArgs

    /**
     * Stream-open SMB archive (ZIP/CBZ/TAR/CBT): range reads + extract pages to image cache.
     * Does not download the whole archive.
     */
    @Serializable
    data class SmbStreamArchive(
        val sourceId: Long,
        val remotePath: String,
        val page: Int = -1,
        val info: BaseGalleryInfo? = null,
    ) : ReaderScreenArgs

    /** Stream-open WebDAV archive (ZIP/CBZ/TAR/CBT). */
    @Serializable
    data class WebDavStreamArchive(
        val sourceId: Long,
        val remotePath: String,
        val page: Int = -1,
        val info: BaseGalleryInfo? = null,
    ) : ReaderScreenArgs
}

@Composable
private fun Background(
    color: Color,
    content: @Composable () -> Unit,
) = Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
    EhTheme(useDarkTheme = color != Color.White, content = content)
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.ReaderScreen(args: ReaderScreenArgs, navigator: DestinationsNavigator) = Screen(navigator) {
    val bgColor by collectBackgroundColorAsState()
    val fullscreen by Settings.fullscreen.collectAsState()
    val uiController = rememberSystemUiController()
    // Own immersive mode for the whole destination (including page-loader wait).
    // Sibling folder nav replaces this screen; without a session refcount the exiting
    // instance would show system bars after the new one already hid them (or while
    // the new one is still loading), leaving the reader out of fullscreen.
    DisposableEffect(uiController) {
        val lightStatusBar = uiController.statusBarDarkContentEnabled
        activeReaderSessions.incrementAndGet()
        uiController.showTransientSystemBarsBySwipe = true
        uiController.statusBarDarkContentEnabled = bgColor == Color.White
        if (Settings.fullscreen.value) {
            uiController.isSystemBarsVisible = false
        }
        onDispose {
            uiController.statusBarDarkContentEnabled = lightStatusBar
            if (activeReaderSessions.decrementAndGet() == 0) {
                uiController.isSystemBarsVisible = true
                uiController.showTransientSystemBarsBySwipe = false
            }
        }
    }
    // Re-apply while this destination is active (covers fullscreen pref + post-nav races)
    LaunchedEffect(fullscreen, uiController) {
        if (fullscreen) {
            uiController.isSystemBarsVisible = false
            uiController.showTransientSystemBarsBySwipe = true
        } else {
            uiController.isSystemBarsVisible = true
        }
    }

    Await(
        block = asyncInVM(args) { alive ->
            suspendCancellableCoroutine { cont ->
                with(alive) {
                    launchIO {
                        catch {
                            usePageLoader(args) { loader ->
                                cont.resume(loader.right())
                                awaitCancellation()
                            }
                        }.let { left -> cont.resume(left) }
                    }
                }
            }
        }.value.run {
            { await() }
        },
        placeholder = {
            Background(bgColor) {
                CircularWavyProgressIndicator()
            }
        },
    ) { result ->
        when (result) {
            is Either.Left -> Background(bgColor) {
                Text(
                    text = result.value.displayString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            is Either.Right -> {
                val loader = result.value
                val info = when (args) {
                    is ReaderScreenArgs.LocalFolder -> args.info
                    is ReaderScreenArgs.SmbFolder -> args.info
                    is ReaderScreenArgs.WebDavFolder -> args.info
                    is ReaderScreenArgs.SmbStreamArchive -> args.info
                    is ReaderScreenArgs.WebDavStreamArchive -> args.info
                    // Prefer args.info; PageLoader also carries resolved local-archive info.
                    is ReaderScreenArgs.Archive ->
                        args.info
                            ?: (loader.info as? BaseGalleryInfo)
                }
                // Explicit dispose path: system back / pop also abort archive extract so
                // ArchiveAccess is not held after the reader leaves.
                DisposableEffect(loader) {
                    onDispose {
                        runCatching { loader.close() }
                    }
                }
                key(loader) {
                    ReaderScreen(pageLoader = loader, info = info, args = args)
                }
            }
        }
    }
}

@Composable
context(activity: MainActivity, _: SnackbarHostState, _: DialogState, _: CoroutineScope, nav: DestinationsNavigator)
fun ReaderScreen(pageLoader: PageLoader, info: BaseGalleryInfo?, args: ReaderScreenArgs) {
    LaunchedEffect(Unit) {
        val orientation = activity.requestedOrientation
        Settings.orientationMode.valueFlow()
            .onCompletion { activity.requestedOrientation = orientation }
            .collect { activity.setOrientation(it) }
    }
    // History for archive opens (browse/library may also record; upsert is fine).
    LaunchedEffect(args) {
        withIOContext {
            when (args) {
                is ReaderScreenArgs.Archive -> LocalHistory.recordLocalArchive(args.path)
                is ReaderScreenArgs.SmbStreamArchive -> LocalHistory.recordSmbStreamArchive(
                    sourceId = args.sourceId,
                    remotePath = args.remotePath,
                    title = args.info?.title,
                    pages = args.info?.pages ?: 0,
                    info = args.info,
                )
                is ReaderScreenArgs.WebDavStreamArchive -> LocalHistory.recordWebDavStreamArchive(
                    sourceId = args.sourceId,
                    remotePath = args.remotePath,
                    title = args.info?.title,
                    pages = args.info?.pages ?: 0,
                    info = args.info,
                )
                else -> Unit
            }
        }
    }
    LaunchedEffect(pageLoader) {
        with(Settings) {
            merge(
                cropBorder.changesFlow(),
                stripExtraneousAds.changesFlow(),
                readerHardwareBitmap.changesFlow(),
                // readerHdrDisplay only toggles window COLOR_MODE_HDR — no page restart.
            ).collect {
                pageLoader.restart()
            }
        }
    }
    // Ultra HDR: COLOR_MODE_HDR for the composed page window (not only the current page).
    // Pager uses beyondViewportPageCount=1; webtoon uses visible items ±1. Mixed SDR/HDR
    // in that range must not flip the window mode per page (brightness thrash).
    val hdrDisplayEnabled by Settings.readerHdrDisplay.collectAsState()
    DisposableEffect(activity) {
        onDispose { activity.setHdrColorMode(false) }
    }
    val webtoon = remember(info) {
        // Tags in database may or may not have the prefix "other:"
        info?.simpleTags?.any { it.endsWith("webtoon") } == true
    }
    val showSeekbar by Settings.showReaderSeekbar.collectAsState()
    val readingMode by Settings.readingMode.collectAsState {
        when (val mode = ReadingModeType.fromPreference(it)) {
            ReadingModeType.DEFAULT -> if (webtoon) ReadingModeType.WEBTOON else ReadingModeType.RIGHT_TO_LEFT
            else -> mode
        }
    }
    val volumeKeysEnabled by Settings.readWithVolumeKeys.collectAsState()
    val volumeKeysInverted by Settings.readWithVolumeKeysInverted.collectAsState()
    val fullscreen by Settings.fullscreen.collectAsState()
    val cutoutShort by Settings.cutoutShort.collectAsState()
    val keepScreenOn by Settings.keepScreenOn.collectAsState()
    val uiController = rememberSystemUiController()
    // Immersive enter/exit is owned by the outer ReaderScreen destination so loading
    // placeholders and sibling replace do not drop fullscreen. Only sync chrome here.
    val lazyListState = rememberLazyListState(LazyLayoutCacheWindow(SCROLL_FRACTION, SCROLL_FRACTION), pageLoader.startPage)
    // Snapshot-backed [PageLoader.size] so pager pageCount tracks solid lazy-list growth.
    val pagerState = rememberPagerState(pageLoader.startPage) { pageLoader.size.coerceAtLeast(1) }
    val syncState = rememberSliderPagerDoubleSyncState(lazyListState, pagerState, pageLoader)
    var appbarVisible by remember { mutableStateOf(false) }
    val isWebtoon by rememberUpdatedState(ReadingModeType.isWebtoon(readingMode))
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(pageLoader, hdrDisplayEnabled) {
        if (!hdrDisplayEnabled) {
            activity.setHdrColorMode(false)
            return@LaunchedEffect
        }
        // Compose range from layout (pager beyondViewport / list visible) with ±1 fallback.
        // Status is Flow-backed — nest collectLatest and re-scan Ready gain maps in range.
        snapshotFlow {
            val size = pageLoader.size
            if (size <= 0) return@snapshotFlow IntRange.EMPTY
            val last = size - 1
            fun around(center: Int): IntRange {
                val c = center.coerceIn(0, last)
                return (c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(last)
            }
            if (isWebtoon) {
                val items = lazyListState.layoutInfo.visibleItemsInfo
                if (items.isEmpty()) {
                    around(syncState.sliderValue - 1)
                } else {
                    // Prefetch / cache window may keep neighbors composed — keep ±1.
                    val first = items.first().index
                    val end = items.last().index
                    (first - 1).coerceAtLeast(0)..(end + 1).coerceAtMost(last)
                }
            } else {
                val pages = pagerState.layoutInfo.visiblePagesInfo
                if (pages.isEmpty()) {
                    around(pagerState.currentPage)
                } else {
                    // visiblePagesInfo already includes beyondViewportPageCount (=1).
                    pages.first().index..pages.last().index
                }
            }
        }.collectLatest { range ->
            if (range.isEmpty()) {
                activity.setHdrColorMode(false)
                return@collectLatest
            }
            val statusFlows = range.mapNotNull { idx ->
                pageLoader.pages.getOrNull(idx)?.statusFlow
            }
            if (statusFlows.isEmpty()) {
                activity.setHdrColorMode(false)
                return@collectLatest
            }
            // Any status emission in the window → re-evaluate whether HDR stays on.
            statusFlows.merge().collect {
                var anyHdr = false
                var maxBoost = 1f
                for (idx in range) {
                    val img = (pageLoader.pages.getOrNull(idx)?.status as? PageStatus.Ready)
                        ?.image
                        ?: continue
                    if (img.hasGainmap) {
                        anyHdr = true
                        maxBoost = maxOf(maxBoost, img.contentHdrBoost)
                    }
                }
                // Keep COLOR_MODE_HDR while any gain-map page is still composed.
                activity.setHdrColorMode(anyHdr, contentBoost = maxBoost)
            }
        }
    }

    // SMB: after app background, pool sockets are closed (ON_STOP). Re-request the
    // current page (and nearby errors) so the reader recovers without a manual retry.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentArgs by rememberUpdatedState(args)
    val currentLoader by rememberUpdatedState(pageLoader)
    val currentPage1 by rememberUpdatedState(syncState.sliderValue)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val remote = currentArgs is ReaderScreenArgs.SmbFolder ||
                currentArgs is ReaderScreenArgs.WebDavFolder ||
                currentArgs is ReaderScreenArgs.SmbStreamArchive ||
                currentArgs is ReaderScreenArgs.WebDavStreamArchive
            if (!remote) return@LifecycleEventObserver
            val loader = currentLoader
            if (loader.size <= 0) return@LifecycleEventObserver
            val center = (currentPage1 - 1).coerceIn(0, loader.size - 1)
            val from = (center - 2).coerceAtLeast(0)
            val to = (center + 2).coerceAtMost(loader.size - 1)
            for (i in from..to) {
                when (loader.pages[i].status) {
                    is PageStatus.Ready, is PageStatus.Blocked -> Unit
                    else -> loader.retryPage(i)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(
        Modifier.keyEventHandler(
            volumeKeysEnabled = { volumeKeysEnabled && !appbarVisible },
            volumeKeysInverted = { volumeKeysInverted },
            movePrevious = { launch { if (isWebtoon) lazyListState.scrollUp() else pagerState.moveToPrevious() } },
            moveNext = { launch { if (isWebtoon) lazyListState.scrollDown() else pagerState.moveToNext() } },
        ).focusRequester(focusRequester).focusable().thenIf(keepScreenOn) { keepScreenOn() },
    ) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        syncState.Sync(isWebtoon) { appbarVisible = false }
        val bgColor by collectBackgroundColorAsState()
        val isDarkTheme = isSystemInDarkTheme()
        LaunchedEffect(isDarkTheme, fullscreen, bgColor) {
            snapshotFlow { appbarVisible }.collect { visible ->
                // Show bars only for in-reader chrome; keep immersive otherwise.
                uiController.isSystemBarsVisible = visible || !fullscreen
                uiController.showTransientSystemBarsBySwipe = true
                uiController.statusBarDarkContentEnabled = if (visible) !isDarkTheme else bgColor == Color.White
            }
        }
        var showNavigationOverlay by remember {
            val showOnStart = Settings.showNavigationOverlayNewUser.value || Settings.showNavigationOverlayOnStart.value
            Settings.showNavigationOverlayNewUser.value = false
            mutableStateOf(showOnStart)
        }
        val onSelectPage = { page: Page ->
            if (Settings.readerLongTapAction.value) {
                launch {
                    val blocked = page.status is PageStatus.Blocked
                    dialog { cont ->
                        fun dispose() = cont.resume(Unit)
                        val state = rememberModalBottomSheetState()
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                            sheetState = state,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            ReaderPageSheetMeta(
                                retry = { pageLoader.retryPage(page.index) },
                                retryOrigin = { pageLoader.retryPage(page.index, true) },
                                share = { launchIO { with(pageLoader) { shareImage(page, info) } } },
                                copy = { launchIO { with(pageLoader) { copy(page) } } },
                                save = { launchIO { with(pageLoader) { save(page) } } },
                                saveTo = { launchIO { with(pageLoader) { saveTo(page) } } },
                                showAds = { page.unblock() }.takeIf { blocked },
                                dismiss = { launch { state.hide().also { dispose() } } },
                            )
                        }
                    }
                }
            }
        }
        // Same path as double-tap prev/next gallery (folder mode); shared by gesture + FABs.
        val folderNavBusy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        fun goFolder(next: Boolean) {
            if (!folderNavBusy.compareAndSet(false, true)) return
            // Navigate on Main after the gesture/input frame finishes — avoids
            // Compose "Cannot start a writer when a reader is pending" crashes.
            launch {
                try {
                    val sibling = withIOContext {
                        GallerySiblingNavigator.sibling(args, next)
                    } ?: return@launch
                    // Progress FK for sibling gallery + bump History (library gallery or browse path).
                    sibling.let { s ->
                        withIOContext {
                            when (s) {
                                is ReaderScreenArgs.LocalFolder -> {
                                    s.info?.let { LocalHistory.ensureGalleryForProgress(it) }
                                    // Library / History playlist: real LocalLibrary row → history as gallery.
                                    // (Previously only browse-stack path was recorded, so Library next/prev
                                    // never appeared in History.)
                                    val libId = s.info?.gid
                                    val lib = libId?.let { LocalLibrary.loadGallery(it) }
                                    if (lib != null) {
                                        LocalHistory.recordLibraryGallery(lib)
                                        return@withIOContext
                                    }
                                    // Browse folder path link (lazy galleries are not permanent library rows).
                                    val frame = BrowseSession.localStack.lastOrNull()
                                        ?: return@withIOContext
                                    val rel = if (s.path == frame.path) {
                                        frame.relativePath
                                    } else {
                                        val name = s.path.toPath().name
                                        if (frame.relativePath.isEmpty()) name else "${frame.relativePath}/$name"
                                    }
                                    LocalHistory.recordLocalBrowseFolder(
                                        rootId = frame.rootId,
                                        relativePath = rel,
                                        title = s.info?.title ?: s.path.toPath().name,
                                        pages = s.info?.pages ?: 0,
                                    )
                                }
                                is ReaderScreenArgs.WebDavFolder -> {
                                    s.info?.let { LocalHistory.ensureGalleryForProgress(it) }
                                    LocalHistory.recordWebDavBrowseFolder(
                                        sourceId = s.sourceId,
                                        relativePath = s.remoteDir,
                                        title = s.info?.title
                                            ?: s.remoteDir.substringAfterLast('/').ifEmpty { "WebDAV" },
                                        pages = s.info?.pages ?: 0,
                                    )
                                }
                                is ReaderScreenArgs.SmbFolder -> {
                                    s.info?.let { LocalHistory.ensureGalleryForProgress(it) }
                                    LocalHistory.recordSmbBrowseFolder(
                                        sourceId = s.sourceId,
                                        relativePath = s.remoteDir,
                                        title = s.info?.title
                                            ?: s.remoteDir.substringAfterLast('/').ifEmpty { "Share" },
                                        pages = s.info?.pages ?: 0,
                                    )
                                }
                                is ReaderScreenArgs.SmbStreamArchive -> {
                                    s.info?.let { LocalHistory.ensureGalleryForProgress(it) }
                                    LocalHistory.recordSmbStreamArchive(
                                        sourceId = s.sourceId,
                                        remotePath = s.remotePath,
                                        title = s.info?.title,
                                        pages = s.info?.pages ?: 0,
                                        info = s.info,
                                    )
                                }
                                is ReaderScreenArgs.WebDavStreamArchive -> {
                                    s.info?.let { LocalHistory.ensureGalleryForProgress(it) }
                                    LocalHistory.recordWebDavStreamArchive(
                                        sourceId = s.sourceId,
                                        remotePath = s.remotePath,
                                        title = s.info?.title,
                                        pages = s.info?.pages ?: 0,
                                        info = s.info,
                                    )
                                }
                                is ReaderScreenArgs.Archive -> {
                                    LocalHistory.recordLocalArchive(s.path)
                                }
                            }
                        }
                    }
                    // Stop this archive extract before replace so the next reader can
                    // preempt ArchiveAccess without waiting on solid decompress.
                    runCatching { pageLoader.close() }
                    // Replace current reader so back still returns to folder browser once
                    nav.navigate(ReaderScreenDestination(sibling)) {
                        launchSingleTop = true
                        popUpTo(ReaderScreenDestination) {
                            inclusive = true
                        }
                    }
                } finally {
                    folderNavBusy.set(false)
                }
            }
        }
        EhTheme(useDarkTheme = bgColor != Color.White) {
            val insets = if (fullscreen) {
                if (cutoutShort) {
                    WindowInsets()
                } else {
                    WindowInsets.displayCutout
                }
            } else {
                WindowInsets.systemBars
            }
            GalleryPager(
                type = readingMode,
                pagerState = pagerState,
                lazyListState = lazyListState,
                pageLoader = pageLoader,
                showNavigationOverlay = showNavigationOverlay,
                onNavigationModeChange = { showNavigationOverlay = true },
                onSelectPage = onSelectPage,
                onMenuRegionClick = { appbarVisible = !appbarVisible },
                onPrevFolder = { goFolder(next = false) },
                onNextFolder = { goFolder(next = true) },
                // Same path as edge-swipe / system back (OnBackPressedDispatcher callbacks).
                onBack = { activity.onBackPressedDispatcher.onBackPressed() },
                modifier = Modifier.background(bgColor).pointerInput(syncState) {
                    awaitEachGesture {
                        waitForUpOrCancellation()
                        syncState.reset()
                        showNavigationOverlay = false
                    }
                }.fillMaxSize().windowInsetsPadding(insets),
            )
        }
        val brightness by Settings.customBrightness.collectAsState()
        val brightnessValue by Settings.customBrightnessValue.collectAsState()
        val colorOverlayEnabled by Settings.colorFilter.collectAsState()
        val colorOverlay by Settings.colorFilterValue.collectAsState()
        val colorOverlayMode by Settings.colorFilterMode.collectAsState {
            when (it) {
                0 -> BlendMode.SrcOver
                1 -> BlendMode.Multiply
                2 -> BlendMode.Screen
                3 -> BlendMode.Overlay
                4 -> BlendMode.Lighten
                5 -> BlendMode.Darken
                else -> unreachable()
            }
        }
        ReaderContentOverlay(
            brightness = { brightnessValue }.takeIf { brightness && brightnessValue < 0 },
            color = { colorOverlay }.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayMode,
        )
        if (brightness) {
            LaunchedEffect(Unit) {
                Settings.customBrightnessValue.valueFlow().sample(100)
                    .onCompletion { activity.setCustomBrightnessValue(0) }
                    .collect { activity.setCustomBrightnessValue(it) }
            }
        }
        val showPageNumber by Settings.showPageNumber.collectAsState()
        if (showPageNumber && !appbarVisible) {
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodySmall) {
                PageIndicatorText(
                    currentPage = syncState.sliderValue,
                    totalPages = pageLoader.size,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
            }
        }
        // Go-to-first FAB: hidden by default; show after scrolling toward earlier pages
        // quickly (jump ≥2) or by more than 3 pages; hide again when scrolling forward.
        // Next-gallery FAB: show on last page (if a sibling exists); hide on page-up.
        var showGoFirstFab by remember { mutableStateOf(false) }
        var showNextGalleryFab by remember { mutableStateOf(false) }
        var hasNextGallery by remember { mutableStateOf(false) }
        var lastTrackedPage by remember { mutableIntStateOf(syncState.sliderValue) }
        var peakPageSinceScrollDown by remember { mutableIntStateOf(syncState.sliderValue) }
        LaunchedEffect(args) {
            hasNextGallery = withIOContext {
                GallerySiblingNavigator.sibling(args, next = true) != null
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow {
                Triple(syncState.sliderValue, pageLoader.size, hasNextGallery)
            }.collect { (page, total, canNext) ->
                val prev = lastTrackedPage
                lastTrackedPage = page
                val onLast = total > 0 && page >= total
                // Page-up always dismisses next-gallery FAB; landing on last (or sibling
                // check finishing while already there) shows it.
                showNextGalleryFab = when {
                    page < prev -> false
                    onLast && canNext -> true
                    else -> false
                }
                if (page <= 1) {
                    showGoFirstFab = false
                    peakPageSinceScrollDown = 1
                    return@collect
                }
                when {
                    page > prev -> {
                        showGoFirstFab = false
                        peakPageSinceScrollDown = page
                    }
                    page < prev -> {
                        val stepUp = prev - page
                        val climbed = peakPageSinceScrollDown - page
                        if (stepUp >= 2 || climbed > 2) {
                            showGoFirstFab = true
                        }
                    }
                }
            }
        }
        val fabPad = Modifier
            .align(Alignment.BottomEnd)
            .navigationBarsPadding()
            .padding(end = 16.dp, bottom = 16.dp)
        AnimatedVisibility(
            visible = showNextGalleryFab &&
                pageLoader.size > 0 &&
                syncState.sliderValue >= pageLoader.size,
            modifier = fabPad,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(onClick = { goFolder(next = true) }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = stringResource(R.string.go_to_next_gallery),
                )
            }
        }
        AnimatedVisibility(
            visible = showGoFirstFab && syncState.sliderValue > 1 && !showNextGalleryFab,
            modifier = fabPad,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            FloatingActionButton(
                onClick = {
                    syncState.sliderScrollTo(1)
                    showGoFirstFab = false
                    peakPageSinceScrollDown = 1
                    lastTrackedPage = 1
                },
            ) {
                Icon(
                    imageVector = Icons.Default.VerticalAlignTop,
                    contentDescription = stringResource(R.string.go_to_first_page),
                )
            }
        }
        ReaderAppBars(
            visible = appbarVisible,
            title = pageLoader.title,
            isRtl = readingMode == ReadingModeType.RIGHT_TO_LEFT,
            showSeekBar = showSeekbar,
            currentPage = syncState.sliderValue,
            totalPages = pageLoader.size,
            onSliderValueChange = syncState::sliderScrollTo,
            onClickSettings = {
                launch {
                    dialog { cont ->
                        fun dispose() = cont.resume(Unit)
                        var isColorFilter by remember { mutableStateOf(false) }
                        val scrim by animateColorAsState(
                            targetValue = if (isColorFilter) Color.Transparent else BottomSheetDefaults.ScrimColor,
                            label = "ScrimColor",
                        )
                        ModalBottomSheet(
                            onDismissRequest = { dispose() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                            // Yeah, I know color state should not be read here, but we have to do it...
                            scrimColor = scrim,
                            dragHandle = null,
                            contentWindowInsets = { WindowInsets() },
                        ) {
                            SettingsPager(isWebtoon = isWebtoon, modifier = Modifier.fillMaxSize()) { page ->
                                isColorFilter = page == 2
                                appbarVisible = !isColorFilter
                            }
                        }
                    }
                }
            },
        )
    }
}

context(_: Context, _: DialogState, nav: DestinationsNavigator)
suspend inline fun <T> usePageLoader(args: ReaderScreenArgs, crossinline block: suspend (PageLoader) -> T) = when (args) {
    is ReaderScreenArgs.LocalFolder -> {
        val info = args.info
        val page = when {
            args.page != -1 -> args.page
            info != null -> EhDB.getReadProgress(info.gid)
            else -> 0
        }
        useFolderPageLoader(args.path.toPath(), info, page, block)
    }
    is ReaderScreenArgs.SmbFolder -> {
        val source = requireNotNull(SmbRepository.load(args.sourceId)) { "SMB source not found" }
        val info = args.info
        val page = when {
            args.page != -1 -> args.page
            info != null -> EhDB.getReadProgress(info.gid)
            else -> 0
        }
        val names = args.imageNames.ifEmpty {
            com.hippo.ehviewer.smb.SmbGateway.listImageFileNames(
                source,
                com.hippo.ehviewer.smb.SmbPasswordStore.get(source.id),
                args.remoteDir,
            )
        }
        useSmbFolderPageLoader(source, args.remoteDir, names, info, page, block)
    }
    is ReaderScreenArgs.WebDavFolder -> {
        val source = requireNotNull(WebDavRepository.load(args.sourceId)) { "WebDAV source not found" }
        val info = args.info
        val page = when {
            args.page != -1 -> args.page
            info != null -> EhDB.getReadProgress(info.gid)
            else -> 0
        }
        val names = args.imageNames.ifEmpty {
            WebDavGateway.listImageFileNames(
                source,
                WebDavPasswordStore.get(source.id),
                args.remoteDir,
            )
        }
        useWebDavFolderPageLoader(source, args.remoteDir, names, info, page, block)
    }
    is ReaderScreenArgs.Archive -> {
        val path = args.path.toPath()
        // Same progress path as network archives: GalleryInfo on PageLoader → putReadProgress on close.
        val info = args.info
            ?: LocalHistory.galleryInfoForLocalArchive(args.path)
        LocalHistory.ensureGalleryForProgress(info)
        val page = when {
            args.page != -1 -> args.page
            else -> EhDB.getReadProgress(info.gid)
        }
        if (isDocumentFileName(path.name)) {
            useLocalDocumentExtractPageLoader(
                path,
                info = info,
                startPage = page,
                block = block,
            )
        } else {
            useArchivePageLoader(
                path,
                info = info,
                startPage = page,
                passwdProvider = { invalidator ->
                    awaitInputText(
                        title = string(R.string.archive_need_passwd),
                        hint = string(R.string.archive_passwd),
                        onUserDismiss = { nav.popBackStack() },
                    ) { text ->
                        ensure(text.isNotBlank()) { string(R.string.passwd_cannot_be_empty) }
                        ensure(invalidator(text)) { string(R.string.passwd_wrong) }
                    }
                },
                block = block,
            )
        }
    }
    is ReaderScreenArgs.SmbStreamArchive -> {
        val source = requireNotNull(SmbRepository.load(args.sourceId)) { "SMB source not found" }
        val password = SmbPasswordStore.get(source.id)
        val info = args.info
        val page = when {
            args.page != -1 -> args.page
            info != null -> EhDB.getReadProgress(info.gid)
            else -> 0
        }
        val remote = args.remotePath
        val solid = isSolidArchiveFileName(remote)
        val tar = isTarArchiveFileName(remote)
        val document = isDocumentFileName(remote)
        val byteSource = com.hippo.ehviewer.smb.SmbArchiveByteSource(
            source,
            password,
            remote,
            // Archives: sequential windows (RAR-like). Documents (PDF) keep sparse probes.
            preferSequential = !document,
            pipeline = true,
        )
        val cacheKey = "smb:${source.id}:$remote"
        val titleHint = remote.substringAfterLast('/').ifEmpty { source.displayName }
        val passwdProvider: PasswdProvider = { invalidator ->
            awaitInputText(
                title = string(R.string.archive_need_passwd),
                hint = string(R.string.archive_passwd),
                onUserDismiss = { nav.popBackStack() },
            ) { text ->
                ensure(text.isNotBlank()) { string(R.string.passwd_cannot_be_empty) }
                ensure(invalidator(text)) { string(R.string.passwd_wrong) }
            }
        }
        if (document) {
            useDocumentExtractPageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                formatHint = if (isEpubFileName(remote)) "epub" else "pdf",
                info = info,
                startPage = page,
                remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                block = block,
            )
        } else if (solid) {
            // RAR/CBR/7z: sequential extract to solid_extract cache (fake stream).
            // On open failure (e.g. awkward 7z), fall back to full download + local open.
            try {
                useSolidExtractPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = info,
                    startPage = page,
                    passwdProvider = passwdProvider,
                    block = block,
                )
            } catch (e: Throwable) {
                com.ehviewer.core.util.logcat("SolidExtract", e)
                runCatching { byteSource.close() }
                val local = com.hippo.ehviewer.library.RemoteArchiveOpen.ensureSmbArchive(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    allowLarge = true,
                )
                useArchivePageLoader(
                    local.path,
                    info = info,
                    startPage = page,
                    passwdProvider = passwdProvider,
                    block = block,
                )
            }
        } else if (tar) {
            // TAR/CBT: fixed-window readahead indexes + extracts from the same bytes.
            useTarChunkPageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = info,
                startPage = page,
                block = block,
            )
        } else {
            useStreamArchivePageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = info,
                startPage = page,
                passwdProvider = passwdProvider,
                block = block,
            )
        }
    }
    is ReaderScreenArgs.WebDavStreamArchive -> {
        val source = requireNotNull(WebDavRepository.load(args.sourceId)) { "WebDAV source not found" }
        val password = WebDavPasswordStore.get(source.id)
        val info = args.info
        val page = when {
            args.page != -1 -> args.page
            info != null -> EhDB.getReadProgress(info.gid)
            else -> 0
        }
        val remote = args.remotePath
        val solid = isSolidArchiveFileName(remote)
        val tar = isTarArchiveFileName(remote)
        val document = isDocumentFileName(remote)
        val byteSource = com.hippo.ehviewer.webdav.WebDavArchiveByteSource(
            source,
            password,
            remote,
            // Archives: sequential windows (RAR-like). Documents (PDF) keep sparse probes.
            preferSequential = !document,
            pipeline = true,
        )
        val cacheKey = "webdav:${source.id}:$remote"
        val titleHint = remote.substringAfterLast('/').ifEmpty { source.displayName }
        val passwdProvider: PasswdProvider = { invalidator ->
            awaitInputText(
                title = string(R.string.archive_need_passwd),
                hint = string(R.string.archive_passwd),
                onUserDismiss = { nav.popBackStack() },
            ) { text ->
                ensure(text.isNotBlank()) { string(R.string.passwd_cannot_be_empty) }
                ensure(invalidator(text)) { string(R.string.passwd_wrong) }
            }
        }
        if (document) {
            useDocumentExtractPageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                formatHint = if (isEpubFileName(remote)) "epub" else "pdf",
                info = info,
                startPage = page,
                remoteSize = runCatching { byteSource.size }.getOrDefault(0L),
                block = block,
            )
        } else if (solid) {
            try {
                useSolidExtractPageLoader(
                    source = byteSource,
                    cacheKey = cacheKey,
                    titleHint = titleHint,
                    info = info,
                    startPage = page,
                    passwdProvider = passwdProvider,
                    block = block,
                )
            } catch (e: Throwable) {
                com.ehviewer.core.util.logcat("SolidExtract", e)
                runCatching { byteSource.close() }
                val local = com.hippo.ehviewer.library.RemoteArchiveOpen.ensureWebDavArchive(
                    source = source,
                    password = password,
                    remoteRelativeFile = remote,
                    allowLarge = true,
                )
                useArchivePageLoader(
                    local.path,
                    info = info,
                    startPage = page,
                    passwdProvider = passwdProvider,
                    block = block,
                )
            }
        } else if (tar) {
            useTarChunkPageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = info,
                startPage = page,
                block = block,
            )
        } else {
            useStreamArchivePageLoader(
                source = byteSource,
                cacheKey = cacheKey,
                titleHint = titleHint,
                info = info,
                startPage = page,
                passwdProvider = passwdProvider,
                block = block,
            )
        }
    }
}

@Composable
private fun collectBackgroundColorAsState(): State<Color> {
    val grey = colorResource(com.hippo.ehviewer.R.color.reader_background_dark)
    val dark = isSystemInDarkTheme()
    return Settings.readerTheme.collectAsState { theme ->
        when (theme) {
            0 -> Color.White
            2 -> grey
            3 -> if (dark) grey else Color.White
            else -> Color.Black
        }
    }
}
