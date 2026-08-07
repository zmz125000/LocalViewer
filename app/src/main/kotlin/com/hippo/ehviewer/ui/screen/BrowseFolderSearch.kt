package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

private val WHITESPACE_REGEX = Regex("\\s+")

/** Same normalize rules as [SearchBarScreen] live filter. */
fun normalizeBrowseSearchQuery(raw: CharSequence): String =
    raw.trim().toString().replace(WHITESPACE_REGEX, " ")

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

    val hasFilter: Boolean
        get() = keyword.isNotEmpty()

    fun open() {
        active = true
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

/** Inline search field for [androidx.compose.material3.TopAppBar] title slot. */
@Composable
fun BrowseTopBarSearchField(
    state: BrowseFolderSearchState,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.active) {
        if (state.active) {
            focusRequester.requestFocus()
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
