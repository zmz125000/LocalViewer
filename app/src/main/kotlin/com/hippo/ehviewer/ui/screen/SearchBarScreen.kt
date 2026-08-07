package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarDefaults.InputField
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
import com.hippo.ehviewer.ui.theme.scrim
import com.hippo.ehviewer.ui.tools.DialogState
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WhitespaceRegex = Regex("\\s+")
private val M3SearchBarMaxWidth = 720.dp
private const val SearchHistoryLimit = 24

/** Normalize and persist a device search query (Library / History filter history). */
suspend fun recordDeviceSearchHistory(raw: String) {
    val query = raw.trim().replace(WhitespaceRegex, " ")
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

    var searchFocused by remember { mutableStateOf(false) }
    var historyTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var historyBlockHeightPx by remember { mutableIntStateOf(0) }

    fun refreshHistoryTags() {
        scope.launch {
            historyTags = withContext(Dispatchers.IO) {
                mSearchDatabase.list(SearchHistoryLimit)
            }
        }
    }

    fun normalizeQuery(raw: CharSequence = searchFieldState.text): String =
        raw.trim().replace(WhitespaceRegex, " ").toString()

    fun applyFilter(raw: CharSequence = searchFieldState.text) {
        onFilterChange(normalizeQuery(raw))
    }

    fun recordCurrentQuery() {
        val query = normalizeQuery()
        if (query.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            recordDeviceSearchHistory(query)
            if (searchFocused) {
                historyTags = mSearchDatabase.list(SearchHistoryLimit)
            }
        }
    }

    // Live filter as the user types (no submit required).
    LaunchedEffect(searchFieldState) {
        snapshotFlow { searchFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { applyFilter(it) }
    }

    LaunchedEffect(searchFocused) {
        onFocusChange(searchFocused)
        if (searchFocused) refreshHistoryTags()
    }

    // Match gallery list side inset so the search field edges line up with cards.
    val searchBarHorizontalPadding = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    // M3 SearchBar uses 8.dp above and below the field when collapsed; reserve that total.
    val searchBarVerticalPadding = 8.dp
    val showHistory = searchFocused && historyTags.isNotEmpty()
    val historyBlockHeight = with(density) { historyBlockHeightPx.toDp() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val fieldMaxWidth = (maxWidth - searchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth)

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

                    // Placeholder: field + margins + focused history chips (measured).
                    Spacer(
                        modifier = Modifier.height(
                            SearchBarDefaults.InputFieldHeight + searchBarVerticalPadding * 2 +
                                if (showHistory) historyBlockHeight else 0.dp,
                        ),
                    )
                }
            },
            floatingActionButton = floatingActionButton,
            content = content,
        )

        // Workaround for can't exit SearchBar due to refocus in non-touch mode
        // https://issuetracker.google.com/337191298
        Box(Modifier.size(1.dp).focusable())

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, searchBarOffsetY()) }
                .windowInsetsPadding(
                    WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Always collapsed: no full-screen suggestion overlay. Filtering is live in-list.
            SearchBar(
                inputField = {
                    InputField(
                        state = searchFieldState,
                        onSearch = {
                            recordCurrentQuery()
                            focusManager.clearFocus()
                        },
                        expanded = false,
                        onExpandedChange = { /* never expand overlay */ },
                        modifier = Modifier
                            .widthIn(max = fieldMaxWidth)
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
                                if (searchFieldState.text.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            searchFieldState.clearText()
                                            onFilterChange("")
                                        },
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
                    )
                },
                expanded = false,
                onExpandedChange = { /* never expand overlay */ },
                modifier = Modifier.widthIn(max = fieldMaxWidth).fillMaxWidth(),
            ) {
                // Expanded content never shown (expanded is always false).
            }

            AnimatedVisibility(visible = showHistory) {
                FlowRow(
                    modifier = Modifier
                        .widthIn(max = fieldMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = searchBarHorizontalPadding)
                        .padding(top = 6.dp, bottom = 2.dp)
                        .onGloballyPositioned { historyBlockHeightPx = it.size.height },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                                historyTags = mSearchDatabase.list(SearchHistoryLimit)
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
