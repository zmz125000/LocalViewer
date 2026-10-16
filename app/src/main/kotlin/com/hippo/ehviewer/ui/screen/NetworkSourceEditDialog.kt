package com.hippo.ehviewer.ui.screen

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hippo.ehviewer.ui.theme.snackbarActionContentColor
import com.hippo.ehviewer.ui.theme.snackbarContainerColor
import com.hippo.ehviewer.ui.theme.snackbarDismissContentColor

/**
 * Form dialog for SMB / WebDAV editors.
 *
 * A floating alert dialog pans on every keystroke: text fields call
 * `requestRectangleOnScreen`, and that pan is not in layout coordinates, so
 * the next character pans again. This window does not fit the decor, and the
 * form is laid out in the space above the IME instead. Bring-into-view is
 * handled by the form's own scroll and is not forwarded to the window.
 *
 * The test-connection snackbar is hosted here. The activity snackbar is a
 * window behind this dialog, so it would show underneath.
 */
@Composable
internal fun NetworkSourceEditDialog(
    title: String,
    onDismissRequest: () -> Unit,
    snackbarHostState: SnackbarHostState,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().swallowWindowPan()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(onDismissRequest) {
                        detectTapGestures(onTap = { onDismissRequest() })
                    },
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                        .widthIn(min = 280.dp, max = 560.dp)
                        .fillMaxWidth()
                        .consumePointer(),
                    shape = AlertDialogDefaults.shape,
                    color = AlertDialogDefaults.containerColor,
                    tonalElevation = AlertDialogDefaults.TonalElevation,
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = AlertDialogDefaults.titleContentColor,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(scrollState),
                        ) {
                            content()
                        }
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier.fillMaxWidth(),
                        ) { data ->
                            val message = snackbarDismissContentColor()
                            Snackbar(
                                snackbarData = data,
                                modifier = Modifier.padding(top = 12.dp),
                                containerColor = snackbarContainerColor(),
                                contentColor = message,
                                actionContentColor = snackbarActionContentColor(),
                                dismissActionContentColor = message,
                            )
                        }
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            dismissButton()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/** Hit-test this node so taps do not fall through to the scrim. Does not handle the gesture. */
private fun Modifier.consumePointer(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Final)
        }
    }
}

/**
 * Stops [bringIntoView] from reaching the dialog window. The form scroll already
 * keeps the focused field in view. The window would call `requestRectangleOnScreen`
 * and shift the whole dialog up on every character.
 */
private fun Modifier.swallowWindowPan(): Modifier = this.then(SwallowWindowPanElement)

private object SwallowWindowPanElement : ModifierNodeElement<SwallowWindowPanNode>() {
    override fun create(): SwallowWindowPanNode = SwallowWindowPanNode()

    override fun update(node: SwallowWindowPanNode) = Unit

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0

    override fun InspectorInfo.inspectableProperties() {
        name = "swallowWindowPan"
    }
}

private class SwallowWindowPanNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: LayoutCoordinates,
        boundsProvider: () -> Rect?,
    ) = Unit
}
