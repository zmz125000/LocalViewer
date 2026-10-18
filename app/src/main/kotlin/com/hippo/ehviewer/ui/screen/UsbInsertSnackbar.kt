package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.UsbVolume
import com.hippo.ehviewer.library.UsbVolumes
import com.hippo.ehviewer.ui.LocalSnackBarHostState
import com.hippo.ehviewer.ui.openUsbVolume
import com.hippo.ehviewer.ui.theme.snackbarActionContentColor
import com.hippo.ehviewer.ui.theme.snackbarContainerColor
import com.hippo.ehviewer.ui.theme.snackbarDismissContentColor
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shown when a USB volume appears after the first scan. Open always.
 * Eject only when [UsbVolume.ejectSupported].
 */
@Composable
fun UsbInsertSnackbar(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val snackbar = LocalSnackBarHostState.current
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<UsbVolume?>(null) }
    LaunchedEffect(Unit) {
        UsbVolumes.inserts.collect { notice = it }
    }
    LaunchedEffect(notice?.identity) {
        if (notice != null) {
            delay(8_000)
            notice = null
        }
    }
    val volume = notice ?: return
    Snackbar(
        containerColor = snackbarContainerColor(),
        contentColor = snackbarDismissContentColor(),
        actionContentColor = snackbarActionContentColor(),
        action = {
            Row {
                TextButton(onClick = {
                    notice = null
                    with(navigator) { openUsbVolume(volume) }
                }) {
                    Text(stringResource(R.string.usb_open))
                }
                if (volume.ejectSupported) {
                    TextButton(onClick = {
                        notice = null
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.usb_eject_failed))
                        }
                    }) {
                        Text(stringResource(R.string.usb_eject))
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.usb_connected, volume.label))
    }
}
