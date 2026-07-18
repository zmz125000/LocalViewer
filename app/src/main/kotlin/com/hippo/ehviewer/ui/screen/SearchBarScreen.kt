package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarDefaults.InputField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.dao.SearchDao
import com.ehviewer.core.database.model.Search
import com.ehviewer.core.i18n.R
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.ui.util.ifNotNullThen
import com.ehviewer.core.ui.util.ifTrueThen
import com.ehviewer.core.ui.util.thenIf
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ui.LocalNavDrawerState
import com.hippo.ehviewer.ui.theme.scrim
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.awaitConfirmationOrCancel
import com.hippo.ehviewer.ui.tools.rememberCompositionActiveState
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import moe.tarsin.navigate

fun interface SuggestionProvider {
    suspend fun providerSuggestions(text: String): List<Suggestion>
}

abstract class Suggestion {
    abstract val keyword: String
    open val hint: String? = null
    abstract fun onClick()
    open val canDelete: Boolean = false
    open val canOpenDirectly: Boolean = false
}

suspend fun SearchDao.suggestions(prefix: String, limit: Int) = (if (prefix.isBlank()) list(limit) else rawSuggestions(prefix, limit))

@Composable
context(_: DialogState, _: DestinationsNavigator)
fun SearchBarScreen(
    onApplySearch: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    title: String?,
    searchFieldHint: String,
    searchFieldState: TextFieldState = rememberTextFieldState(),
    suggestionProvider: SuggestionProvider? = null,
    localSearch: Boolean = true,
    searchBarOffsetY: () -> Int = { 0 },
    /** Shown on the left of the search field when collapsed (e.g. list-mode toggle). */
    leadingIcon: @Composable () -> Unit = {},
    trailingIcon: @Composable () -> Unit = {},
    filter: @Composable (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var mSuggestionList by remember { mutableStateOf(emptyList<Suggestion>()) }
    val mSearchDatabase = searchDatabase.searchDao()
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val context = LocalContext.current
    val animateItems by Settings.animateItems.collectAsState()

    class TagSuggestion(
        override val hint: String?,
        override val keyword: String,
    ) : Suggestion() {
        override fun onClick() {
            val query = searchFieldState.text.toString()
            val (index, keyword) = if (localSearch) {
                query.lastIndexOf(' ') to
                    "${keyword.substringAfter(':')} "
            } else {
                query.lastIndexOfAny(TagTerminators) to
                    if (keyword.endsWith(':')) keyword else "${wrapTagKeyword(keyword)} "
            }
            val keywords = if (index == -1) {
                keyword
            } else {
                "${query.substring(0, index + 1).trimEnd()} $keyword"
            }
            searchFieldState.setTextAndPlaceCursorAtEnd(keywords)
        }
    }

    class KeywordSuggestion(
        override val keyword: String,
    ) : Suggestion() {
        override val canDelete = true
        override fun onClick() {
            searchFieldState.setTextAndPlaceCursorAtEnd(keyword)
        }
    }

    fun mergedSuggestionFlow(): Flow<Suggestion> = with(context) {
        flow {
            val query = searchFieldState.text.toString()
            suggestionProvider?.run { providerSuggestions(query).forEach { emit(it) } }
            mSearchDatabase.suggestions(query, 128).forEach { emit(KeywordSuggestion(it)) }
            val index = if (localSearch) query.lastIndexOf(' ') else query.lastIndexOfAny(TagTerminators)
            val keyword = query.substring(index + 1).trimStart()
        }
    }

    suspend fun updateSuggestions() {
        mSuggestionList = mergedSuggestionFlow().toList()
    }

    if (expanded) {
        LaunchedEffect(Unit) {
            snapshotFlow { searchFieldState.text }.collectLatest {
                updateSuggestions()
            }
        }
    }

    fun hideSearchView() {
        onExpandedChange(false)
    }

    fun onApplySearch() {
        // May have invalid whitespaces if pasted from clipboard, replace them with spaces
        val query = searchFieldState.text.trim().replace(WhitespaceRegex, " ")
        if (query.isNotEmpty()) {
            scope.launch {
                mSearchDatabase.deleteQuery(query)
                val search = Search(System.currentTimeMillis(), query)
                mSearchDatabase.insert(search)
            }
        }
        onApplySearch(query)
    }

    fun deleteKeyword(keyword: String) {
        scope.launch {
            awaitConfirmationOrCancel(confirmText = R.string.delete) {
                Text(text = stringResource(id = R.string.delete_search_history, keyword))
            }
            mSearchDatabase.deleteQuery(keyword)
            updateSuggestions()
        }
    }

    // Match gallery list side inset so the search field edges line up with cards.
    val searchBarHorizontalPadding = dimensionResource(id = com.hippo.ehviewer.R.dimen.gallery_list_margin_h)
    // M3 SearchBar uses 8.dp above and below the field when collapsed; reserve that total.
    val searchBarVerticalPadding = 8.dp

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column {
                    val scrim = MaterialTheme.colorScheme.background.scrim()
                    Box(Modifier.windowInsetsTopHeight(WindowInsets.statusBars).fillMaxWidth().background(scrim))

                    // Placeholder: field height + equal top/bottom margin (same as top margin under status bar).
                    Spacer(
                        modifier = Modifier.height(
                            SearchBarDefaults.InputFieldHeight + searchBarVerticalPadding * 2,
                        ),
                    )
                }
            },
            floatingActionButton = floatingActionButton,
            content = content,
        )
        // https://issuetracker.google.com/337191298
        // Workaround for can't exit SearchBar due to refocus in non-touch mode
        Box(Modifier.size(1.dp).focusable())
        val activeState = rememberCompositionActiveState()
        SearchBar(
            modifier = Modifier.align(Alignment.TopCenter).thenIf(!expanded) { offset { IntOffset(0, searchBarOffsetY()) } }
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            inputField = {
                InputField(
                    state = searchFieldState,
                    onSearch = {
                        hideSearchView()
                        onApplySearch()
                    },
                    expanded = expanded,
                    onExpandedChange = onExpandedChange,
                    modifier = Modifier.widthIn(max = (maxWidth - searchBarHorizontalPadding * 2).coerceAtMost(M3SearchBarMaxWidth)).fillMaxWidth(),
                    placeholder = {
                        val contentActive by activeState.state
                        val text = title.takeUnless { expanded || contentActive } ?: searchFieldHint
                        Text(text, overflow = TextOverflow.Ellipsis, maxLines = 1)
                    },
                    leadingIcon = {
                        if (expanded) {
                            IconButton(onClick = { hideSearchView() }, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = null)
                            }
                        } else {
                            leadingIcon()
                        }
                    },
                    trailingIcon = {
                        if (expanded) {
                            if (searchFieldState.text.isNotEmpty()) {
                                IconButton(onClick = { searchFieldState.clearText() }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                }
                            }
                        } else {
                            Row {
                                trailingIcon()
                            }
                        }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = onExpandedChange,
        ) {
            activeState.Anchor()
            filter?.invoke()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues(),
            ) {
                // Workaround for prepending before the first item
                item {}
                items(mSuggestionList, key = { it.keyword.hashCode() * 31 + it.canDelete.hashCode() }) {
                    ListItem(
                        headlineContent = { Text(text = it.keyword) },
                        supportingContent = it.hint.ifNotNullThen { Text(text = it.hint!!) },
                        leadingContent = it.canOpenDirectly.ifTrueThen {
                            Icon(
                                imageVector = Icons.AutoMirrored.Default.MenuBook,
                                contentDescription = null,
                            )
                        },
                        trailingContent = it.canDelete.ifTrueThen {
                            IconButton(onClick = { deleteKeyword(it.keyword) }, shapes = IconButtonDefaults.shapes()) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { it.onClick() }.thenIf(animateItems) { animateItem() },
                    )
                }
            }
        }
    }
}

fun wrapTagKeyword(keyword: String, translate: Boolean = false): String = keyword

private val TagTerminators = charArrayOf('"', '$')
private val WhitespaceRegex = Regex("\\s+")
private val M3SearchBarMaxWidth = 720.dp
