package com.hippo.ehviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.LibraryRootEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.EhDB
import com.hippo.ehviewer.library.BrowseEntry
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.library.listLocalDirectory
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseArchiveGalleryRow
import com.hippo.ehviewer.ui.main.BrowseDirectoryRow
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryRow
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.ui.navToLocalFolderReader
import com.hippo.ehviewer.ui.navToReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import okio.Path
import okio.Path.Companion.toPath

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.FolderBrowserScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    DrawerHandle(true)
    val roots by LocalLibrary.rootsFlow().collectAsState(initial = emptyList())
    // Session-scoped stack survives reader navigation (unlike remember {}).
    var stack by remember {
        mutableStateOf(BrowseSession.localStack)
    }
    fun updateStack(newStack: List<BrowseSession.LocalFrame>) {
        stack = newStack
        BrowseSession.localStack = newStack
    }

    var entries by remember { mutableStateOf<List<BrowseEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val current = stack.lastOrNull()
    val title = current?.title ?: stringResource(R.string.folder)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    suspend fun reload(force: Boolean = false) {
        val frame = stack.lastOrNull()
        if (frame == null) {
            entries = emptyList()
            error = null
            return
        }
        loading = true
        error = null
        runCatching {
            withIOContext {
                if (force) BrowseSession.invalidateLocalListing(frame.path)
                listLocalDirectory(frame.path.toPath(), useCache = !force)
            }
        }.onSuccess {
            entries = it
        }.onFailure {
            error = it.message
            entries = emptyList()
        }
        loading = false
    }

    LaunchedEffect(stack) { reload(force = false) }

    fun enterRoot(root: LibraryRootEntity) {
        val path = LocalLibrary.rootPath(root) ?: return
        updateStack(
            listOf(
                BrowseSession.LocalFrame(
                    rootId = root.id,
                    path = path.toString(),
                    title = root.displayName,
                    relativePath = "",
                ),
            ),
        )
    }

    fun enterDir(entry: BrowseEntry.Directory) {
        val frame = stack.lastOrNull() ?: return
        val rel = if (frame.relativePath.isEmpty()) entry.name else "${frame.relativePath}/${entry.name}"
        updateStack(
            stack + BrowseSession.LocalFrame(
                rootId = frame.rootId,
                path = entry.path.toString(),
                title = entry.name,
                relativePath = rel,
            ),
        )
    }

    fun goUp() {
        if (stack.isNotEmpty()) updateStack(stack.dropLast(1))
    }

    BackHandler(enabled = stack.isNotEmpty()) { goUp() }

    fun openFolderGallery(entry: BrowseEntry.FolderGallery) {
        val frame = stack.lastOrNull() ?: return
        val rel = when {
            frame.relativePath.isEmpty() && entry.path.toString() == frame.path -> "."
            frame.relativePath.isEmpty() -> entry.name
            entry.path.toString() == frame.path -> frame.relativePath.ifEmpty { "." }
            else -> "${frame.relativePath}/${entry.name}"
        }
        val gid = stableGalleryId(frame.rootId, rel)
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_GALLERY_TOKEN,
            title = entry.name,
            pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
        )
        launchIO { EhDB.putHistoryInfo(info) }
        navToLocalFolderReader(entry.path.toString(), info)
    }

    fun openArchive(entry: BrowseEntry.ArchiveGallery) {
        navToReader(entry.path.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (stack.isNotEmpty()) {
                        IconButton(onClick = { goUp() }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    } else {
                        NavigationIcon()
                    }
                },
                actions = {
                    if (stack.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                launch {
                                    refreshing = true
                                    reload(force = true)
                                    refreshing = false
                                }
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing || loading,
            onRefresh = {
                launch {
                    refreshing = true
                    reload(force = true)
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when {
                stack.isEmpty() -> {
                    if (roots.isEmpty()) {
                        BrowseEmptyHint(stringResource(R.string.folder_no_roots))
                    } else {
                        FastScrollLazyColumn(Modifier.fillMaxSize()) {
                            items(roots, key = { it.id }) { root ->
                                BrowseDirectoryRow(
                                    name = root.displayName,
                                    onClick = { enterRoot(root) },
                                )
                            }
                        }
                    }
                }
                loading && entries.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                error != null && entries.isEmpty() -> {
                    BrowseEmptyHint(error!!)
                }
                entries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    val dirs = entries.filterIsInstance<BrowseEntry.Directory>()
                    val galleries = entries.filter { it !is BrowseEntry.Directory }
                    val listState = rememberLazyListState()
                    FastScrollLazyColumn(
                        state = listState,
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).fillMaxSize(),
                    ) {
                        if (dirs.isNotEmpty()) {
                            item(key = "hdr-dirs") { BrowseSectionHeader(stringResource(R.string.browse_directories)) }
                            items(dirs, key = { "d-${it.path}" }) { dir ->
                                BrowseDirectoryRow(name = dir.name, onClick = { enterDir(dir) })
                            }
                        }
                        if (galleries.isNotEmpty()) {
                            item(key = "hdr-gal") { BrowseSectionHeader(stringResource(R.string.browse_galleries)) }
                            items(galleries, key = { "g-${it.name}-${it.hashCode()}" }) { entry ->
                                when (entry) {
                                    is BrowseEntry.FolderGallery -> BrowseFolderGalleryRow(
                                        name = entry.name,
                                        pageCount = entry.pageCount,
                                        pageCountCapped = entry.pageCountCapped,
                                        coverPath = entry.coverPath,
                                        onClick = { openFolderGallery(entry) },
                                    )
                                    is BrowseEntry.ArchiveGallery -> BrowseArchiveGalleryRow(
                                        name = entry.name,
                                        onClick = { openArchive(entry) },
                                    )
                                    is BrowseEntry.Directory -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
