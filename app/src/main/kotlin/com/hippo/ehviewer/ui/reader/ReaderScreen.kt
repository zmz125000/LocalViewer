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
import com.hippo.ehviewer.gallery.status
import com.hippo.ehviewer.gallery.unblock
import com.hippo.ehviewer.gallery.useArchivePageLoader
import com.hippo.ehviewer.gallery.useFolderPageLoader
import com.hippo.ehviewer.gallery.useSmbFolderPageLoader
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.GallerySiblingNavigator
import com.hippo.ehviewer.library.LocalHistory
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
    @Serializable
    data class Archive(val path: String) : ReaderScreenArgs

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
                    is ReaderScreenArgs.Archive -> null
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
    LaunchedEffect(pageLoader) {
        with(Settings) {
            merge(cropBorder.changesFlow(), stripExtraneousAds.changesFlow()).collect {
                pageLoader.restart()
            }
        }
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
    val pagerState = rememberPagerState(pageLoader.startPage) { pageLoader.size }
    val syncState = rememberSliderPagerDoubleSyncState(lazyListState, pagerState, pageLoader)
    var appbarVisible by remember { mutableStateOf(false) }
    val isWebtoon by rememberUpdatedState(ReadingModeType.isWebtoon(readingMode))
    val focusRequester = remember { FocusRequester() }

    // SMB: after app background, pool sockets are closed (ON_STOP). Re-request the
    // current page (and nearby errors) so the reader recovers without a manual retry.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentArgs by rememberUpdatedState(args)
    val currentLoader by rememberUpdatedState(pageLoader)
    val currentPage1 by rememberUpdatedState(syncState.sliderValue)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (currentArgs !is ReaderScreenArgs.SmbFolder) return@LifecycleEventObserver
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
                    // Progress FK for sibling gallery + bump browse folder history
                    sibling.let { s ->
                        withIOContext {
                            when (s) {
                                is ReaderScreenArgs.LocalFolder -> {
                                    s.info?.let { LocalHistory.ensureGalleryForProgress(it) }
                                    // Browse stack only — library playlist siblings skip path history.
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
                                is ReaderScreenArgs.Archive -> Unit
                                else -> Unit
                            }
                        }
                    }
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
    is ReaderScreenArgs.Archive -> useArchivePageLoader(
        args.path.toPath(),
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
