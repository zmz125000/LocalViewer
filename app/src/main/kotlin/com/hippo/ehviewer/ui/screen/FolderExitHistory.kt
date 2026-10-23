package com.hippo.ehviewer.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Save the visible folder using the activity scope so navigation cannot cancel the write. */
class FolderExitHistoryActions(private val scope: CoroutineScope) {
    internal var recorder: (suspend () -> Unit)? = null

    fun record() {
        val save = recorder ?: return
        // Read the screen's current folder before navigation can replace its state.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { save() }
    }
}

val LocalFolderExitHistory = compositionLocalOf<FolderExitHistoryActions> {
    error("Folder exit history actions are not provided")
}

@Composable
fun BindFolderExitHistory(record: suspend () -> Unit) {
    val actions = LocalFolderExitHistory.current
    val currentRecord by rememberUpdatedState(record)
    DisposableEffect(actions) {
        val recorder: suspend () -> Unit = { currentRecord() }
        actions.recorder = recorder
        onDispose {
            if (actions.recorder === recorder) actions.recorder = null
        }
    }
}
