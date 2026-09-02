package com.hippo.ehviewer.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarDefaults.InputField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.Search
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.theme.scrim
import com.hippo.ehviewer.ui.tools.DialogState
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WHITESPACE_REGEX = Regex("\\s+")
private val M3_SEARCH_BAR_MAX_WIDTH = 720.dp
private const val SEARCH_HISTORY_LIMIT = 24
private const val HISTORY_TAG_MAX_ROWS = 2

/**
 * Persist a device search query when Privacy → Save history is enabled.
 * No-op when the toggle is off (same gate as browse/library history).
 */
suspend fun recordDeviceSearchHistory(raw: String) {
    if (!Settings.saveHistory.value) return
    val query = raw.trim().replace(WHITESPACE_REGEX, " ")
    if (query.isEmpty()) return
    val dao = searchDatabase.searchDao()
    dao.deleteQuery(query)
    dao.insert(Search(System.currentTimeMillis(), query))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
context(_: DialogState, _: DestinationsNavigator)
fun SearchBarScreen(
    onFilterChange: (String) -> Unit,
    title: String?,
    searchFieldHint: String,
    searchFieldState: TextFieldState = rememberTextFieldState(),
    onFocusChange: (Boolean) -> Unit = {},
    searchBarOffsetY: () -> Int = { 0 },
    /** Shown on the left of the search field (e.g. list-mode toggle). */
    leadingIcon: @Composable () -> Unit = {},
    trailingIcon: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val mSearchDatabase = searchDatabase.searchDao()
    val saveHistory by Settings.saveHistory.collectAsState()

    var searchFocused by remember { mutableStateOf(false) }
    var historyTags by remember { mutableStateOf<List<String>>(emptyList()) }
    // Full search chrome height (field + expanded history) for list top inset.
    var searchChromeHeightPx by remember { mutableIntStateOf(0) }

    fun refreshHistoryTags() {
        if (!saveHistory) {
            historyTags = emptyList()
            return
        }
        scope.launch {
            historyTags = withContext(Dispatchers.IO) {
                mSearchDatabase.list(SEARCH_HISTORY_LIMIT)
            }
        }
    }

    fun normalizeQuery(raw: CharSequence = searchFieldState.text): String = raw.trim().replace(WHITESPACE_REGEX, " ")

    fun applyFilter(raw: CharSequence = searchFieldState.text) {
        onFilterChange(normalizeQuery(raw))
    }

    fun clearSearchFilter() {
        searchFieldState.clearText()
        onFilterChange("")
    }

    fun recordCurrentQuery() {
        if (!saveHistory) return
        val query = normalizeQuery()
        if (query.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            recordDeviceSearchHistory(query)
            if (searchFocused) {
                historyTags = mSearchDatabase.list(SEARCH_HISTORY_LIMIT)
            }
        }
    }

    // Live filter as the user types (no submit required).
    LaunchedEffect(searchFieldState) {
        snapshotFlow { searchFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { applyFilter(it) }
    }

    LaunchedEffect(searchFocused, saveHistory) {
        onFocusChange(searchFocused)
        if (searchFocused && saveHistory) {
            refreshHistoryTags()
        } else if (!saveHistory) {
            historyTags = emptyList()
        }
    }

    fun exitSearchFocus() {
        if (searchFocused) focusManager.clearFocus()
    }

    val hasFilter = searchFieldState.text.isNotEmpty()
    // Back once: unfocus and clear filter together.
    BackHandler(enabled = searchFocused || hasFilter) {
        exitSearchFocus()
        clearSearchFilter()
    }

    // Scroll on the list (touch fling, mouse wheel, etc.) dismisses search focus.
    val clearFocusOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available != Offset.Zero) exitSearchFocus()
                return Offset.Zero
            }
        }
    }

    // Match gallery list side inset so the search field edges line up with cards.
    val searchBarHorizontalPadding = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    // M3 SearchBar uses 8.dp above and below the field when collapsed; reserve that total.
    val searchBarVerticalPadding = 8.dp
    val wantHistory = saveHistory && searchFocused && historyTags.isNotEmpty()
    // Drive expand/collapse with a transition state so the surface shape stays stable for the
    // whole animation (no pill↔docked snap mid-collapse).
    val historyVisibleState = remember { MutableTransitionState(false) }
    historyVisibleState.targetState = wantHistory
    val searchBarColors = SearchBarDefaults.colors()
    val searchChromeHeight = with(density) {
        if (searchChromeHeightPx > 0) {
            searchChromeHeightPx.toDp()
        } else {
            SearchBarDefaults.InputFieldHeight
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fieldMaxWidth = (maxWidth - searchBarHorizontalPadding * 2).coerceAtMost(M3_SEARCH_BAR_MAX_WIDTH)

        Scaffold(
            // Do not reflow list content when reader toggles system bar visibility.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Column {
                    val scrim = MaterialTheme.colorScheme.background.scrim()
                    Box(
                        Modifier
                            .windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility)
                            .fillMaxWidth()
                            .background(scrim),
                    )

                    // Placeholder tracks animated search surface height.
                    Spacer(
                        modifier = Modifier.height(
                            searchChromeHeight + searchBarVerticalPadding * 2,
                        ),
                    )
                }
            },
            floatingActionButton = floatingActionButton,
            content = { paddingValues ->
                // Tap / press on list content (not the search chrome above) exits focus.
                // Initial pass observes without consuming so item clicks still work.
                Box(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(clearFocusOnScroll)
                        .pointerInput(searchFocused) {
                            if (!searchFocused) return@pointerInput
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pressedDown = event.changes.any {
                                        it.pressed && !it.previousPressed
                                    }
                                    if (pressedDown) exitSearchFocus()
                                }
                            }
                        },
                ) {
                    content(paddingValues)
                }
            },
        )

        // Workaround for can't exit SearchBar due to refocus in non-touch mode
        // https://issuetracker.google.com/337191298
        Box(Modifier.size(1.dp).focusable())

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, searchBarOffsetY()) }
                .windowInsetsPadding(
                    WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top),
                )
                .windowInsetsPadding(
                    WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal),
                )
                .padding(top = searchBarVerticalPadding)
                .widthIn(max = fieldMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = searchBarHorizontalPadding)
                .onGloballyPositioned { searchChromeHeightPx = it.size.height },
        ) {
            // One surface holds the field and animates open for history tags (max 2 rows).
            // Always use dockedShape so expand/collapse only changes height — no mid-animation
            // pill ↔ docked corner jump.
            Surface(
                shape = SearchBarDefaults.dockedShape,
                color = searchBarColors.containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = SearchBarDefaults.TonalElevation,
                shadowElevation = SearchBarDefaults.ShadowElevation,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    InputField(
                        state = searchFieldState,
                        onSearch = {
                            recordCurrentQuery()
                            focusManager.clearFocus()
                        },
                        expanded = false,
                        onExpandedChange = { /* docked surface holds history; never full-screen */ },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { searchFocused = it.isFocused },
                        placeholder = {
                            val text = title.takeUnless { searchFocused || searchFieldState.text.isNotEmpty() }
                                ?: searchFieldHint
                            Text(text, overflow = TextOverflow.Ellipsis, maxLines = 1)
                        },
                        leadingIcon = leadingIcon,
                        trailingIcon = {
                            Row {
                                if (hasFilter) {
                                    IconButton(
                                        onClick = { clearSearchFilter() },
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.clear_all),
                                        )
                                    }
                                }
                                trailingIcon()
                            }
                        },
                        colors = searchBarColors.inputFieldColors,
                    )

                    AnimatedVisibility(
                        visibleState = historyVisibleState,
                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                    ) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            maxLines = HISTORY_TAG_MAX_ROWS,
                        ) {
                            historyTags.forEach { tag ->
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        searchFieldState.setTextAndPlaceCursorAtEnd(tag)
                                        onFilterChange(normalizeQuery(tag))
                                    },
                                    label = {
                                        Text(
                                            text = tag,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = stringResource(R.string.delete),
                                            modifier = Modifier
                                                .size(InputChipDefaults.IconSize)
                                                .clickable {
                                                    scope.launch(Dispatchers.IO) {
                                                        mSearchDatabase.deleteQuery(tag)
                                                        historyTags =
                                                            mSearchDatabase.list(SEARCH_HISTORY_LIMIT)
                                                    }
                                                },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
