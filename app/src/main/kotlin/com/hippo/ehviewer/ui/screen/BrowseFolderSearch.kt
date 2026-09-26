package com.hippo.ehviewer.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.database.model.SEARCH_KIND_FOLDER
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.launchIO
import com.hippo.ehviewer.EhApplication.Companion.searchDatabase
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.library.BrowseSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val WHITESPACE_REGEX = Regex("\\s+")
private const val SEARCH_HISTORY_LIMIT = 24
private const val HISTORY_TAG_MAX_ROWS = 2

/** Same normalize rules as [SearchBarScreen] live filter. */
fun normalizeBrowseSearchQuery(raw: CharSequence): String = raw.trim().toString().replace(WHITESPACE_REGEX, " ")

/**
 * Top-bar instant filter for folder browsers (local / SMB / WebDAV).
 * Keyword history is one shared device list (same store as Library and History).
 */
@Stable
class BrowseFolderSearchState internal constructor(
    val textFieldState: TextFieldState,
) {
    var active by mutableStateOf(false)
        private set

    /** Keyboard/focus currently on the search field. */
    var focused by mutableStateOf(false)

    /** Live normalized query applied to the listing. */
    var keyword by mutableStateOf("")
        private set

    /**
     * Last IME Search / submit query. Drives the Search section; independent of
     * [keyword] so typing still filters Directories/Galleries/Videos/Files live.
     */
    var submittedKeyword by mutableStateOf("")
        private set

    /** Bumped on every submit so the same query can be run again. */
    var submitGeneration by mutableStateOf(0)
        private set

    /**
     * One-shot: request keyboard focus when the search field composes.
     * Set by [open] only — [restore] keeps the filter without focusing.
     */
    var wantFocus by mutableStateOf(false)
        internal set

    val hasFilter: Boolean
        get() = keyword.isNotEmpty()

    fun open() {
        active = true
        wantFocus = true
    }

    fun clearFilter() {
        textFieldState.clearText()
        keyword = ""
        submittedKeyword = ""
        submitGeneration++
    }

    /** Commit the current field as a Search-section query (IME Search). */
    fun submit() {
        syncKeywordFromField()
        submittedKeyword = keyword
        submitGeneration++
    }

    /** Exit search mode and clear the filter. */
    fun close() {
        textFieldState.clearText()
        keyword = ""
        submittedKeyword = ""
        submitGeneration++
        active = false
        focused = false
        wantFocus = false
    }

    /**
     * Single back: unfocus + clear filter + exit search mode.
     * @return true if the event was consumed.
     */
    fun handleBack(clearFocus: () -> Unit): Boolean {
        if (!active) return false
        clearFocus()
        close()
        return true
    }

    fun snapshot(): BrowseSession.FolderSearchUi = BrowseSession.FolderSearchUi(
        active = active,
        keyword = keyword,
        submittedKeyword = submittedKeyword,
        submitGeneration = submitGeneration,
    )

    /**
     * Restore a previously saved filter for this folder.
     * Does not request focus (returning from reader / climbing path stack).
     */
    fun restore(saved: BrowseSession.FolderSearchUi) {
        wantFocus = false
        if (saved.isEmpty) {
            close()
            return
        }
        val k = normalizeBrowseSearchQuery(saved.keyword)
        submittedKeyword = saved.submittedKeyword
        submitGeneration = saved.submitGeneration
        if (k.isEmpty()) {
            textFieldState.clearText()
            keyword = ""
            active = saved.active || saved.submittedKeyword.isNotEmpty()
            focused = false
            return
        }
        if (textFieldState.text.toString() != k) {
            textFieldState.setTextAndPlaceCursorAtEnd(k)
        }
        keyword = k
        // Keep the search field visible when a filter or Search-section query is restored.
        active = saved.active || k.isNotEmpty() || saved.submittedKeyword.isNotEmpty()
        focused = false
    }

    /** Query to store when a result is opened. Field text wins; otherwise the last submit. */
    fun openedSearchKeyword(): String = keyword.ifBlank { submittedKeyword }.trim()

    internal fun syncKeywordFromField() {
        keyword = normalizeBrowseSearchQuery(textFieldState.text)
    }
}

/** Save the active folder query when a search result is opened. No-op when the field is idle. */
context(_: CoroutineScope)
fun BrowseFolderSearchState.recordOpenedResult() {
    val q = openedSearchKeyword()
    if (q.isEmpty()) return
    launchIO { recordDeviceSearchHistory(q, SEARCH_KIND_FOLDER) }
}

@Composable
fun rememberBrowseFolderSearchState(): BrowseFolderSearchState {
    val textFieldState = rememberTextFieldState()
    val state = remember(textFieldState) { BrowseFolderSearchState(textFieldState) }
    // Live filter as the user types (no submit).
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { state.syncKeywordFromField() }
    }
    return state
}

/**
 * Persist folder search across dir enter/up and reader navigation (session lifetime).
 * Call instead of unconditionally [BrowseFolderSearchState.close] on path change.
 *
 * Saves the leaving folder on dispose (path change or leave screen), then restores
 * the destination folder's saved filter (or clears if none).
 */
@Composable
fun BindBrowseFolderSearch(
    folderKey: String?,
    search: BrowseFolderSearchState,
    onPathChange: () -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val latestSearch = rememberUpdatedState(search)
    // Restore in this composition — not LaunchedEffect — so the Search section is
    // already in the list when scroll restores after goUp. Save the leaving folder
    // first; DisposableEffect(folderKey) would snapshot the restored state onto the
    // old key if restore ran during composition.
    var bound by remember { mutableStateOf(false) }
    var boundKey by remember { mutableStateOf<String?>(null) }
    if (!bound || boundKey != folderKey) {
        if (bound && boundKey != null) {
            BrowseSession.putFolderSearch(boundKey!!, search.snapshot())
        }
        bound = true
        boundKey = folderKey
        if (folderKey == null) {
            search.close()
        } else {
            search.restore(BrowseSession.getFolderSearch(folderKey))
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val key = boundKey
            if (bound && key != null) {
                BrowseSession.putFolderSearch(key, latestSearch.value.snapshot())
            }
        }
    }
    LaunchedEffect(folderKey) {
        onPathChange()
        focusManager.clearFocus()
    }
    // Persist while typing / submitting / closing so reader return restores both
    // the live filter and the Search-section query.
    LaunchedEffect(
        folderKey,
        search.keyword,
        search.active,
        search.submittedKeyword,
        search.submitGeneration,
    ) {
        if (folderKey != null) {
            BrowseSession.putFolderSearch(folderKey, search.snapshot())
        }
    }
}

/**
 * Tap or scroll on folder-list content dismisses search keyboard/focus.
 * Same interaction model as [SearchBarScreen] content, but only for folder browsers
 * (local / SMB / WebDAV) — do not attach to the main Library/History search bar.
 *
 * Apply to the content area under the top bar (e.g. PullToRefreshBox), not the search field.
 */
@Composable
fun Modifier.browseSearchClearFocusOnInteract(state: BrowseFolderSearchState): Modifier {
    val focusManager = LocalFocusManager.current
    val focused = state.focused
    val clearFocus = rememberUpdatedState {
        if (state.focused) focusManager.clearFocus()
    }
    // Scroll (touch fling, mouse wheel, nested list scroll) unfocuses without consuming delta.
    val clearFocusOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available != Offset.Zero) clearFocus.value()
                return Offset.Zero
            }
        }
    }
    return this
        .nestedScroll(clearFocusOnScroll)
        // Tap/press on list content (not the top-bar field) exits focus.
        // Initial pass observes without consuming so item clicks still work.
        .pointerInput(focused) {
            if (!focused) return@pointerInput
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressedDown = event.changes.any {
                        it.pressed && !it.previousPressed
                    }
                    if (pressedDown) clearFocus.value()
                }
            }
        }
}

/** Inline search field for [androidx.compose.material3.TopAppBar] title slot. */
@Composable
fun BrowseTopBarSearchField(
    state: BrowseFolderSearchState,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // Only auto-focus when the user taps search ([BrowseFolderSearchState.open]),
    // not when restoring a filter after go-back / reader return.
    LaunchedEffect(state.active, state.wantFocus) {
        if (state.active && state.wantFocus) {
            focusRequester.requestFocus()
            state.wantFocus = false
        }
    }
    TextField(
        state = state.textFieldState,
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { state.focused = it.isFocused },
        textStyle = LocalTextStyle.current.copy(color = LocalContentColor.current),
        placeholder = {
            Text(
                text = hint,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        // Match TopAppBar title text origin — default TextField has 16.dp start inset.
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        onKeyboardAction = {
            state.submit()
            focusManager.clearFocus()
            val submitted = state.submittedKeyword
            if (submitted.isNotEmpty()) {
                scope.launch(Dispatchers.IO) { recordDeviceSearchHistory(submitted, SEARCH_KIND_FOLDER) }
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * Search / close action for folder top bars.
 * When inactive: opens search. When active: closes search (clears filter).
 */
@Composable
fun BrowseTopBarSearchAction(
    state: BrowseFolderSearchState,
    onBeforeClose: () -> Unit = {},
) {
    if (state.active) {
        IconButton(
            onClick = {
                onBeforeClose()
                state.close()
            },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.clear_all),
            )
        }
    } else {
        IconButton(
            onClick = { state.open() },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.keyword_search),
            )
        }
    }
}

/**
 * Keyword chips under the folder search field. One list for every folder,
 * separate from Library and History search history.
 * Shown while the field is focused and Privacy → Save history is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseFolderSearchHistory(state: BrowseFolderSearchState) {
    val saveHistory by Settings.saveHistory.collectAsState()
    val scope = rememberCoroutineScope()
    val dao = searchDatabase.searchDao()
    var historyTags by remember { mutableStateOf<List<String>>(emptyList()) }
    val barColor = adaptiveTopAppBarColors().containerColor

    LaunchedEffect(state.focused, saveHistory) {
        if (state.focused && saveHistory) {
            historyTags = withContext(Dispatchers.IO) { dao.list(SEARCH_KIND_FOLDER, SEARCH_HISTORY_LIMIT) }
        } else if (!saveHistory) {
            historyTags = emptyList()
        }
    }

    val wantHistory = state.active && state.focused && saveHistory && historyTags.isNotEmpty()
    val historyVisibleState = remember { MutableTransitionState(false) }
    historyVisibleState.targetState = wantHistory

    AnimatedVisibility(
        visibleState = historyVisibleState,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(barColor)
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = HISTORY_TAG_MAX_ROWS,
        ) {
            historyTags.forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { state.textFieldState.setTextAndPlaceCursorAtEnd(tag) },
                    modifier = Modifier.longPressDelete {
                        scope.launch(Dispatchers.IO) {
                            dao.deleteQuery(tag, SEARCH_KIND_FOLDER)
                            historyTags = dao.list(SEARCH_KIND_FOLDER, SEARCH_HISTORY_LIMIT)
                        }
                    },
                    label = {
                        Text(text = tag, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

/** Long-press deletes a history chip. A short tap still reaches [InputChip] onClick. */
@Composable
internal fun Modifier.longPressDelete(onDelete: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    val delete by rememberUpdatedState(onDelete)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var timedOut = true
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation()
                timedOut = false
            }
            if (timedOut) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delete()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

fun <T> List<T>.filterByBrowseSearch(keyword: String, nameOf: (T) -> String): List<T> {
    val q = keyword.trim()
    if (q.isEmpty()) return this
    return filter { nameOf(it).contains(q, ignoreCase = true) }
}
