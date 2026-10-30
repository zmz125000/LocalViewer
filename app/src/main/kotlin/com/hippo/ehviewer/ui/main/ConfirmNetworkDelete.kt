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
import com.hippo.ehviewer.library.TransferFile
import com.hippo.ehviewer.library.trashSelectionRoots

/** Both menus confirm an immutable snapshot; dismissing never starts a job. */
@Composable
internal fun rememberNetworkDeleteConfirmation(onConfirmed: () -> Unit = {}): (List<TransferFile>) -> Unit {
    var pending by remember { mutableStateOf<List<TransferFile>?>(null) }
    pending?.let { files ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.transfer_delete_confirm, files.size)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    if (UploadTransfers.delete(files)) onConfirmed()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
    return { files -> pending = trashSelectionRoots(files).takeIf { it.isNotEmpty() } }
}
