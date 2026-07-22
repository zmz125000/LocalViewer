/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.ui

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ContentResolver.SCHEME_CONTENT
import android.content.ContentResolver.SCHEME_FILE
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberDrawerState2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ehviewer.core.files.isDirectory
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.i18n.R
import com.ehviewer.core.ui.component.LabeledCheckbox
import com.ehviewer.core.ui.component.LocalSideSheetState
import com.ehviewer.core.ui.component.MutableSideSheet
import com.ehviewer.core.ui.icons.EhIcons
import com.ehviewer.core.ui.icons.filled.Subscriptions
import com.ehviewer.core.ui.util.LocalSnackBarFabPadding
import com.ehviewer.core.ui.util.LocalWindowSizeClass
import com.ehviewer.core.ui.util.isMediumWidthOrWider
import com.ehviewer.core.util.isAtLeastQ
import com.ehviewer.core.util.isAtLeastR
import com.ehviewer.core.util.isAtLeastS
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhApplication.Companion.initialized
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.destinations.AboutScreenDestination
import com.hippo.ehviewer.ui.destinations.AdvancedScreenDestination
import com.hippo.ehviewer.ui.destinations.BrowseScreenDestination
import com.hippo.ehviewer.ui.destinations.DownloadScreenDestination
import com.hippo.ehviewer.ui.destinations.EhScreenDestination
import com.hippo.ehviewer.ui.destinations.FolderBrowserScreenDestination
import com.hippo.ehviewer.ui.destinations.HistoryScreenDestination
import com.hippo.ehviewer.ui.destinations.LibraryScreenDestination
import com.hippo.ehviewer.ui.destinations.LibrarySettingsScreenDestination
import com.hippo.ehviewer.ui.destinations.LicenseScreenDestination
import com.hippo.ehviewer.ui.destinations.NetworkScreenDestination
import com.hippo.ehviewer.ui.destinations.PrivacyScreenDestination
import com.hippo.ehviewer.ui.destinations.ReaderScreenDestination
import com.hippo.ehviewer.ui.destinations.SettingsScreenDestination
import com.hippo.ehviewer.ui.destinations.SmbBrowserScreenDestination
import com.hippo.ehviewer.ui.navToReader
import com.hippo.ehviewer.ui.settings.showNewVersion
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.awaitInputText
import com.hippo.ehviewer.updater.AppUpdater
import com.hippo.ehviewer.util.AppConfig
import com.hippo.ehviewer.util.addTextToClipboard
import com.hippo.ehviewer.util.displayString
import com.hippo.ehviewer.util.getParcelableExtraCompat
import com.hippo.ehviewer.util.getUrlFromClipboard
import com.hippo.ehviewer.util.sha1
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.spec.DestinationSpec
import com.ramcosta.composedestinations.spec.Direction
import com.ramcosta.composedestinations.utils.currentDestinationAsState
import com.ramcosta.composedestinations.utils.rememberDestinationsNavigator
import eu.kanade.tachiyomi.util.view.setSecureScreen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import moe.tarsin.coroutines.runSuspendCatching
import splitties.systemservices.clipboardManager
import splitties.systemservices.connectivityManager

private data class MainNavItem(
    val destination: DestinationSpec,
    val direction: Direction,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

/** Primary main-nav destinations (order = bar/rail order). */
private val mainNavItems = listOf(
    MainNavItem(
        LibraryScreenDestination,
        LibraryScreenDestination,
        R.string.library,
        Icons.AutoMirrored.Filled.LibraryBooks,
        Icons.AutoMirrored.Outlined.LibraryBooks,
    ),
    MainNavItem(
        BrowseScreenDestination,
        BrowseScreenDestination,
        R.string.browse,
        Icons.Filled.Explore,
        Icons.Outlined.Explore,
    ),
    MainNavItem(
        HistoryScreenDestination,
        HistoryScreenDestination,
        R.string.history,
        Icons.Filled.History,
        Icons.Outlined.History,
    ),
    MainNavItem(
        SettingsScreenDestination,
        SettingsScreenDestination,
        R.string.settings,
        Icons.Filled.Settings,
        Icons.Outlined.Settings,
    ),
)

private val mainTabDestinations: Set<DestinationSpec> = mainNavItems.map { it.destination }.toSet()

/** Destinations that belong under Settings. */
private val settingsNestedDestinations: Set<DestinationSpec> = setOf(
    EhScreenDestination,
    PrivacyScreenDestination,
    AdvancedScreenDestination,
    AboutScreenDestination,
    LicenseScreenDestination,
    DownloadScreenDestination,
    LibrarySettingsScreenDestination,
)

/** Folder / SMB / network browse stack (not the main Browse hub). */
private val browseNestedDestinations: Set<DestinationSpec> = setOf(
    FolderBrowserScreenDestination,
    SmbBrowserScreenDestination,
    NetworkScreenDestination,
    LibrarySettingsScreenDestination,
)

/**
 * Which main tab should appear selected for [dest].
 * Reader returns null (hide chrome). Nested folder screens map to Browse or History
 * via [fromHistory].
 */
private fun selectedMainTab(
    dest: DestinationSpec?,
    fromHistory: Boolean,
    navController: NavController,
): Direction? {
    if (dest == null) return LibraryScreenDestination
    return when (dest) {
        LibraryScreenDestination -> LibraryScreenDestination
        BrowseScreenDestination -> BrowseScreenDestination
        HistoryScreenDestination -> HistoryScreenDestination
        SettingsScreenDestination -> SettingsScreenDestination
        ReaderScreenDestination -> null
        FolderBrowserScreenDestination, SmbBrowserScreenDestination -> {
            if (fromHistory) HistoryScreenDestination else BrowseScreenDestination
        }
        NetworkScreenDestination -> BrowseScreenDestination
        LibrarySettingsScreenDestination -> {
            // Opened from Settings hub or Browse gear — prefer Settings if still on stack.
            val hasSettings = navController.currentBackStack.value.any {
                it.destination.route?.startsWith(SettingsScreenDestination.baseRoute) == true
            }
            if (hasSettings) SettingsScreenDestination else BrowseScreenDestination
        }
        in settingsNestedDestinations -> SettingsScreenDestination
        else -> null
    }
}

/** Whether main NavigationBar / NavigationRail should be visible. */
private fun shouldShowMainNav(
    dest: DestinationSpec?,
    persistMainNav: Boolean,
    useRail: Boolean,
): Boolean {
    if (dest == null) return true
    if (dest == ReaderScreenDestination) return false
    if (dest in mainTabDestinations) return true
    // Nested browse / settings: show when user opts in, or always on tablet rail.
    if (dest in browseNestedDestinations || dest in settingsNestedDestinations) {
        return persistMainNav || useRail
    }
    return false
}

private fun navigateMainTab(navigator: DestinationsNavigator, item: MainNavItem, selectedTab: Direction?) {
    // Re-tap active tab (including while nested under it) → pop to that tab root.
    if (selectedTab == item.direction) {
        navigator.popBackStack(item.direction, inclusive = false)
        return
    }
    if (item.direction == LibraryScreenDestination) {
        navigator.popBackStack(LibraryScreenDestination, inclusive = false)
    } else {
        navigator.navigate(item.direction) {
            popUpTo(LibraryScreenDestination) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }
}

class MainActivity : AppCompatActivity() {

    private var shareUrl: String? = null

    @Composable
    fun ProvideAssistContent(url: String) {
        val urlState by rememberUpdatedState(url)
        DisposableEffect(urlState) {
            shareUrl = urlState
            onDispose {
                shareUrl = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentChannel.trySend(intent)
    }

    private val tipFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val intentChannel = Channel<Intent>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val intentFlow = intentChannel.receiveAsFlow()

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !initialized }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // https://issuetracker.google.com/204791558
        // Fix system bars insets still exist in fullscreen mode on API < 30
        @Suppress("DEPRECATION")
        if (!isAtLeastR) {
            with(window.decorView) {
                systemUiVisibility = systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_STABLE.inv()
            }
        }
        setMD3Content {
            val navDrawerState = rememberDrawerState(DrawerValue.Closed)
            val sideSheetState = rememberDrawerState2(DrawerValue.Closed)
            val snackbarState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val navController = rememberNavController()
            val navigator = navController.rememberDestinationsNavigator()

            val hasNetwork = remember { connectivityManager.activeNetwork != null }
            if (!AppConfig.isBenchmark) {
                val noNetwork = stringResource(R.string.no_network)
                LaunchedEffect(Unit) {
                    // Local viewer: skip EH download-location / app-link checks
                    if (hasNetwork) {
                        runSuspendCatching {
                            withIOContext {
                                AppUpdater.checkForUpdate()?.let { showNewVersion(it) }
                            }
                        }.onFailure {
                            snackbarState.showSnackbar(getString(R.string.update_failed, it.displayString()))
                        }
                    } else {
                        snackbarState.showSnackbar(noNetwork)
                    }
                }
            }

            LaunchedEffect(Unit) {
                Settings.enabledSecurity.valueFlow().collect {
                    window.setSecureScreen(it)
                }
            }
            if (isAuthenticationSupported()) {
                SecurityScreen(onError = { moveTaskToBack(true) }, modifier = Modifier.zIndex(1f))
            }

            val cannotParse = stringResource(R.string.error_cannot_parse_the_url)
            LaunchedEffect(Unit) {
                intentFlow.collect { intent ->
                    when (intent.action) {
                        Intent.ACTION_VIEW -> with(navigator) {
                            val uri = intent.data ?: return@collect
                            when (uri.scheme) {
                                SCHEME_FILE -> navToReader(uri.path!!)
                                SCHEME_CONTENT -> navToReader(uri.toString())
                                else -> {
                                    // Non file/content schemes unsupported after EH purge
                                    snackbarState.showSnackbar(cannotParse)
                                }
                            }
                        }
                        Intent.ACTION_SEND -> {
                            // Local viewer: only open shared files/archives if content URI present
                            val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                            if (uri != null) {
                                with(navigator) { navToReader(uri.toString()) }
                            }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                tipFlow.collectLatest {
                    snackbarState.showSnackbar(it)
                }
            }
            val warning = stringResource(R.string.metered_network_warning)
            val settings = stringResource(R.string.settings)
            val checkMeteredNetwork by Settings.meteredNetworkWarning.collectAsState()
            if (checkMeteredNetwork) {
                LaunchedEffect(Unit) {
                    if (connectivityManager.isActiveNetworkMetered) {
                        if (isAtLeastQ) {
                            val ret = snackbarState.showSnackbar(warning, settings, true)
                            if (ret == SnackbarResult.ActionPerformed) {
                                val panelIntent = Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                                startActivity(panelIntent)
                            }
                        } else {
                            snackbarState.showSnackbar(warning)
                        }
                    }
                }
            }
            val currentDestination by navController.currentDestinationAsState()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val fromHistoryArg = when (currentDestination) {
                FolderBrowserScreenDestination ->
                    navBackStackEntry?.arguments
                        ?.let { FolderBrowserScreenDestination.argsFrom(it).fromHistory }
                        ?: false
                SmbBrowserScreenDestination ->
                    navBackStackEntry?.arguments
                        ?.let { SmbBrowserScreenDestination.argsFrom(it).fromHistory }
                        ?: false
                else -> false
            }
            val drawerHandle = remember { mutableStateListOf<Long>() }
            var snackbarFabPadding by remember { mutableStateOf(0.dp) }
            val drawerEnabled = drawerHandle.isNotEmpty()
            val density = LocalDensity.current
            val adaptiveInfo = currentWindowAdaptiveInfoV2()
            val windowSizeClass = adaptiveInfo.windowSizeClass
            val useRail = windowSizeClass.isMediumWidthOrWider
            val persistMainNav by Settings.persistMainNav.collectAsState()
            val selectedTab = selectedMainTab(currentDestination, fromHistoryArg, navController)
            val showMainNav = shouldShowMainNav(currentDestination, persistMainNav, useRail)
            // Shortcut FABs only on compact phones without persistent nav.
            val showNavShortcutFab = !useRail && !persistMainNav
            CompositionLocalProvider(
                LocalNavDrawerState provides navDrawerState,
                LocalSideSheetState provides sideSheetState,
                LocalDrawerHandle provides drawerHandle,
                LocalSnackBarHostState provides snackbarState,
                LocalSnackBarFabPadding provides animateDpAsState(snackbarFabPadding, label = "SnackbarFabPadding"),
                LocalWindowSizeClass provides windowSizeClass,
                LocalShowNavShortcutFab provides showNavShortcutFab,
            ) {
                // Do not consume status-bar insets here — each screen TopAppBar already does.
                // Only reserve space for the bottom nav so we don't double-pad under the notch/status bar.
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = {
                        SnackbarHost(
                            hostState = snackbarState,
                            modifier = Modifier.onGloballyPositioned {
                                with(density) {
                                    snackbarFabPadding = it.size.height.toDp()
                                }
                            },
                        )
                    },
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showMainNav && !useRail,
                            enter = slideInVertically { it } + fadeIn(),
                            // exit = slideOutVertically { it } + fadeOut(),
                        ) {
                            NavigationBar {
                                mainNavItems.forEach { item ->
                                    val selected = selectedTab == item.direction
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            navigateMainTab(navigator, item, selectedTab)
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) {
                                                    item.selectedIcon
                                                } else {
                                                    item.unselectedIcon
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        label = {
                                            Text(text = stringResource(id = item.labelRes))
                                        },
                                    )
                                }
                            }
                        }
                    },
                ) { paddingValues ->
                    Row(Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = showMainNav && useRail,
                            enter = slideInHorizontally { -it } + fadeIn(),
                            // exit = slideOutHorizontally { -it } + fadeOut(),
                        ) {
                            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                                Spacer(Modifier.height(8.dp))
                                mainNavItems.forEach { item ->
                                    val selected = selectedTab == item.direction
                                    NavigationRailItem(
                                        selected = selected,
                                        onClick = {
                                            navigateMainTab(navigator, item, selectedTab)
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) {
                                                    item.selectedIcon
                                                } else {
                                                    item.unselectedIcon
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        label = {
                                            Text(text = stringResource(id = item.labelRes))
                                        },
                                    )
                                }
                            }
                        }
                        MutableSideSheet(
                            drawerState = sideSheetState,
                            // Only bottom padding for NavigationBar height (not status bar / rail)
                            modifier = Modifier
                                .weight(1f)
                                .padding(bottom = paddingValues.calculateBottomPadding()),
                            enabled = drawerEnabled,
                        ) {
                            SharedTransitionLayout {
                                CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                                    val start = LibraryScreenDestination
                                    DestinationsNavHost(
                                        navGraph = NavGraphs.root,
                                        start = start,
                                        defaultTransitions = rememberEhNavAnim(),
                                        navController = navController,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            if (intent.action != Intent.ACTION_MAIN) {
                onNewIntent(intent)
            }
        }
    }

    private suspend fun DialogState.checkAppLinkVerify() {
        if (isAtLeastS && !Settings.appLinkVerifyTip) {
            val manager = getSystemService(DomainVerificationManager::class.java)
            val packageName = packageName
            val userState = manager.getDomainVerificationUserState(packageName) ?: return
            val hasUnverified = userState.hostToStateMap.values.any { it == DOMAIN_STATE_NONE }
            if (hasUnverified) {
                var checked by mutableStateOf(false)
                awaitConfirmationOrCancel(
                    confirmText = R.string.open_settings,
                    title = R.string.app_link_not_verified_title,
                    onCancelButtonClick = {
                        if (checked) Settings.appLinkVerifyTip = true
                    },
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.app_link_not_verified_message),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        LabeledCheckbox(
                            modifier = Modifier.fillMaxWidth(),
                            checked = checked,
                            onCheckedChange = { checked = it },
                            label = stringResource(id = R.string.dont_show_again),
                            indication = null,
                        )
                    }
                }
                if (checked) {
                    Settings.appLinkVerifyTip = true
                }
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        "package:$packageName".toUri(),
                    )
                    startActivity(intent)
                } catch (_: Throwable) {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        "package:$packageName".toUri(),
                    )
                    startActivity(intent)
                }
            }
        }
    }

    fun showTip(message: String, useToast: Boolean = false) {
        if (useToast || !tipFlow.tryEmit(message)) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent?) {
        super.onProvideAssistContent(outContent)
        shareUrl?.let { outContent?.webUri = it.toUri() }
    }
}

/**
 * When true, folder browsers may show the “Back to browse/history” FAB.
 * False on tablets (NavigationRail) and when Settings → General “Keep main navigation” is on.
 */
val LocalShowNavShortcutFab = compositionLocalOf { true }

val LocalNavDrawerState = compositionLocalOf<DrawerState> { error("CompositionLocal LocalNavDrawerState not present!") }

val LocalDrawerHandle = compositionLocalOf<SnapshotStateList<Long>> { error("CompositionLocal LocalDrawerHandle not present!") }
val LocalSnackBarHostState = compositionLocalOf<SnackbarHostState> { error("CompositionLocal LocalSnackBarHostState not present!") }
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope> { error("CompositionLocal LocalSharedTransitionScope not present!") }

@Composable
fun DrawerHandle(enabled: Boolean) {
    if (enabled) {
        val current = currentCompositeKeyHashCode
        val handle = LocalDrawerHandle.current
        DisposableEffect(current) {
            handle.add(current)
            onDispose {
                handle.remove(current)
            }
        }
    }
}
