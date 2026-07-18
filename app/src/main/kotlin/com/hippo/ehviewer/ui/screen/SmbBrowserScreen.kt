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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.database.model.SmbSourceEntity
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryInfo.Companion.NOT_FAVORITED
import com.ehviewer.core.ui.component.FastScrollLazyColumn
import com.ehviewer.core.util.launch
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.BrowseEntryRemote
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN
import com.hippo.ehviewer.library.LocalHistory
import com.hippo.ehviewer.library.stableGalleryId
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.DrawerHandle
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.BrowseArchiveGalleryRow
import com.hippo.ehviewer.ui.main.BrowseCover
import com.hippo.ehviewer.ui.main.BrowseDirectoryRow
import com.hippo.ehviewer.ui.main.BrowseEmptyHint
import com.hippo.ehviewer.ui.main.BrowseFolderGalleryRow
import com.hippo.ehviewer.ui.main.BrowseSectionHeader
import com.hippo.ehviewer.ui.navToSmbFolderReader
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import moe.tarsin.snackbar
import moe.tarsin.string

@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SmbBrowserScreen(
    sourceId: Long,
    initialRelativePath: String = "",
    navigator: DestinationsNavigator,
) = Screen(navigator) {
    DrawerHandle(false)
    var source by remember { mutableStateOf<SmbSourceEntity?>(null) }

    // Session-scoped path; seed from args only if session empty for this source.
    var segments by remember {
        val stored = BrowseSession.smbSegments(sourceId)
        mutableStateOf(
            stored.ifEmpty {
                initialRelativePath.split('/').filter { it.isNotEmpty() }
            },
        )
    }
    fun updateSegments(new: List<String>) {
        segments = new
        BrowseSession.setSmbSegments(sourceId, new)
    }

    var entries by remember { mutableStateOf<List<BrowseEntryRemote>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val relativeDir = segments.joinToString("/")
    val title = segments.lastOrNull() ?: source?.displayName ?: stringResource(R.string.network)

    LaunchedEffect(sourceId) {
        source = withIOContext { SmbRepository.load(sourceId) }
    }

    suspend fun reload(force: Boolean = false) {
        val src = source ?: SmbRepository.load(sourceId)?.also { source = it } ?: run {
            error = "Source missing"
            loading = false
            return
        }
        loading = true
        error = null
        val password = SmbPasswordStore.get(src.id)
        runCatching {
            withIOContext {
                if (force) BrowseSession.invalidateSmbListing(src.id, relativeDir)
                SmbGateway.listDirectory(src, password, relativeDir, useCache = !force)
            }
        }.onSuccess {
            entries = it
            SmbRepository.markOk(src.id)
        }.onFailure {
            error = it.message
            entries = emptyList()
            SmbRepository.markError(src.id, it.message ?: "error")
        }
        loading = false
    }

    LaunchedEffect(sourceId, relativeDir, source?.id) {
        if (source != null || SmbRepository.load(sourceId) != null) {
            reload(force = false)
        }
    }

    fun enterDir(name: String) {
        updateSegments(segments + name)
    }

    fun goUp() {
        if (segments.isNotEmpty()) {
            updateSegments(segments.dropLast(1))
        } else {
            navigator.popBackStack()
        }
    }

    BackHandler(enabled = segments.isNotEmpty()) {
        updateSegments(segments.dropLast(1))
    }

    fun openFolderGallery(entry: BrowseEntryRemote.FolderGallery) {
        val src = source ?: return
        val remote = if (entry.relativeName.isEmpty()) {
            relativeDir
        } else {
            SmbGateway.joinRelativePath(relativeDir, entry.relativeName)
        }
        val gid = stableGalleryId(src.id, "smb:$remote")
        val info = BaseGalleryInfo(
            gid = gid,
            token = LOCAL_GALLERY_TOKEN,
            title = entry.name,
            pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            favoriteSlot = NOT_FAVORITED,
            rating = -1f,
        )
        launchIO {
            // Progress FK for reader; History stores the SMB folder path link.
            LocalHistory.ensureGalleryForProgress(info)
            LocalHistory.recordSmbBrowseFolder(
                sourceId = src.id,
                relativePath = remote,
                title = entry.name,
                pages = if (entry.pageCountCapped) 0 else entry.pageCount,
            )
        }
        // When capped or partial, pass empty names so reader re-lists full set
        val names = if (entry.pageCountCapped) emptyList() else entry.imageFileNames
        navToSmbFolderReader(src.id, remote, names, info)
    }

    fun openArchive(@Suppress("UNUSED_PARAMETER") entry: BrowseEntryRemote.ArchiveGallery) {
        launch { snackbar(string(R.string.smb_archive_not_supported)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { goUp() }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
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
                        Icon(Icons.Default.Refresh, contentDescription = null)
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
                loading && entries.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularWavyProgressIndicator()
                    }
                }
                error != null && entries.isEmpty() -> {
                    BrowseEmptyHint(string(R.string.smb_listing_error, error!!))
                }
                entries.isEmpty() -> {
                    BrowseEmptyHint(stringResource(R.string.folder_empty))
                }
                else -> {
                    val dirs = entries.filterIsInstance<BrowseEntryRemote.Directory>()
                    val galleries = entries.filter { it !is BrowseEntryRemote.Directory }
                    val listState = rememberLazyListState()
                    FastScrollLazyColumn(
                        state = listState,
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection).fillMaxSize(),
                    ) {
                        if (dirs.isNotEmpty()) {
                            item(key = "hdr-dirs") {
                                BrowseSectionHeader(stringResource(R.string.browse_directories))
                            }
                            items(dirs, key = { "d-${it.name}" }) { dir ->
                                BrowseDirectoryRow(name = dir.name, onClick = { enterDir(dir.name) })
                            }
                        }
                        if (galleries.isNotEmpty()) {
                            item(key = "hdr-gal") {
                                BrowseSectionHeader(stringResource(R.string.browse_galleries))
                            }
                            items(galleries, key = { "g-${it.name}-${it.hashCode()}" }) { entry ->
                                when (entry) {
                                    is BrowseEntryRemote.FolderGallery -> {
                                        val coverRemote = entry.coverFileName?.let { fileName ->
                                            val remote = if (entry.relativeName.isEmpty()) {
                                                SmbGateway.joinRelativePath(relativeDir, fileName)
                                            } else {
                                                SmbGateway.joinRelativePath(
                                                    SmbGateway.joinRelativePath(relativeDir, entry.relativeName),
                                                    fileName,
                                                )
                                            }
                                            BrowseCover.Smb(sourceId, remote)
                                        }
                                        BrowseFolderGalleryRow(
                                            name = entry.name,
                                            pageCount = entry.pageCount,
                                            pageCountCapped = entry.pageCountCapped,
                                            cover = coverRemote,
                                            onClick = { openFolderGallery(entry) },
                                        )
                                    }
                                    is BrowseEntryRemote.ArchiveGallery -> BrowseArchiveGalleryRow(
                                        name = entry.name,
                                        onClick = { openArchive(entry) },
                                    )
                                    is BrowseEntryRemote.Directory -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
