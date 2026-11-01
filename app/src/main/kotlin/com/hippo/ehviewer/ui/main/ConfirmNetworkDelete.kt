package com.hippo.ehviewer.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.FileRemovalAction
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.removalActionFor
import com.hippo.ehviewer.library.removalRequiresPanel
import com.hippo.ehviewer.library.trashSelectionRoots

/** Both menus confirm an immutable snapshot; dismissing never starts a job. */
@Composable
internal fun rememberNetworkDeleteConfirmation(action: FileRemovalAction = FileRemovalAction.Trash, onConfirmed: () -> Unit = {}): (List<TransferFile>) -> Unit {
    var pending by remember { mutableStateOf<Pair<List<TransferFile>, FileRemovalAction>?>(null) }
    pending?.let { (files, confirmedAction) ->
        val label = when (confirmedAction) {
            FileRemovalAction.Trash -> R.string.action_trash
            FileRemovalAction.Delete -> R.string.delete
            FileRemovalAction.EmptyTrash -> R.string.action_empty_trash
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(label)) },
            text = {
                Text(
                    if (confirmedAction == FileRemovalAction.EmptyTrash) {
                        stringResource(R.string.empty_trash_confirm)
                    } else {
                        val folders = files.count { removalActionFor(it, confirmedAction) == FileRemovalAction.Trash }
                        val regularFiles = files.size - folders
                        when {
                            regularFiles == 0 -> stringResource(R.string.transfer_delete_confirm, folders)
                            folders == 0 -> stringResource(R.string.permanent_delete_confirm, regularFiles)
                            else -> stringResource(R.string.mixed_delete_confirm, regularFiles, folders)
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    if (UploadTransfers.delete(files, confirmedAction)) onConfirmed()
                }) { Text(stringResource(label)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    return { files ->
        pending = trashSelectionRoots(files).takeIf { it.isNotEmpty() }?.let { roots ->
            roots to if (action == FileRemovalAction.EmptyTrash) {
                action
            } else if (!removalRequiresPanel(roots, action)) {
                FileRemovalAction.Trash
            } else {
                FileRemovalAction.Delete
            }
        }
    }
}
