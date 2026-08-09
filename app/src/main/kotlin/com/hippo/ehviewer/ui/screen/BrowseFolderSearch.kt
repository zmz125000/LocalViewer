package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.BrowseSession
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private val WHITESPACE_REGEX = Regex("\\s+")

/** Same normalize rules as [SearchBarScreen] live filter. */
fun normalizeBrowseSearchQuery(raw: CharSequence): String = raw.trim().toString().replace(WHITESPACE_REGEX, " ")

/**
 * Top-bar instant filter for folder browsers (local / SMB / WebDAV).
 * No search history — only in-list name filtering.
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
    }

    /** Exit search mode and clear the filter. */
    fun close() {
        textFieldState.clearText()
        keyword = ""
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

    fun snapshot(): BrowseSession.FolderSearchUi =
        BrowseSession.FolderSearchUi(active = active, keyword = keyword)

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
        if (k.isEmpty()) {
            textFieldState.clearText()
            keyword = ""
            active = saved.active
            focused = false
            return
        }
        if (textFieldState.text.toString() != k) {
            textFieldState.setTextAndPlaceCursorAtEnd(k)
        }
        keyword = k
        // Keep the search field visible when a filter is restored.
        active = saved.active || k.isNotEmpty()
        focused = false
    }

    internal fun syncKeywordFromField() {
        keyword = normalizeBrowseSearchQuery(textFieldState.text)
    }
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
    // Save on leave (path change / reader / screen dispose).
    DisposableEffect(folderKey) {
        onDispose {
            if (folderKey != null) {
                BrowseSession.putFolderSearch(folderKey, latestSearch.value.snapshot())
            }
        }
    }
    // Restore for the destination folder (or clear). Declared before the persist
    // effect so a same-frame path change restores first, then persists the result.
    LaunchedEffect(folderKey) {
        onPathChange()
        focusManager.clearFocus()
        if (folderKey == null) {
            search.close()
        } else {
            search.restore(BrowseSession.getFolderSearch(folderKey))
        }
    }
    // Persist while typing / closing search so X-clear is not re-applied after reader.
    LaunchedEffect(folderKey, search.keyword, search.active) {
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
        onKeyboardAction = { focusManager.clearFocus() },
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

fun <T> List<T>.filterByBrowseSearch(keyword: String, nameOf: (T) -> String): List<T> {
    val q = keyword.trim()
    if (q.isEmpty()) return this
    return filter { nameOf(it).contains(q, ignoreCase = true) }
}
