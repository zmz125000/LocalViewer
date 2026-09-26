package com.hippo.ehviewer.ui.main

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.library.OPEN_CACHE_WARN_BYTES
import com.hippo.ehviewer.ui.theme.snackbarActionButtonColors
import com.hippo.ehviewer.ui.theme.snackbarContainerColor
import com.hippo.ehviewer.ui.theme.snackbarDismissButtonColors
import com.hippo.ehviewer.ui.theme.snackbarDismissContentColor
import com.hippo.ehviewer.util.FileUtils
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/** One snackbar per Save-to… / Share / Open transfer: downloaded size, speed, cancel. */
object BrowseSaveTransfers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(1)
    private val _items = MutableStateFlow<List<SaveTransfer>>(emptyList())
    val items: StateFlow<List<SaveTransfer>> = _items

    /**
     * @param successMessage Shown after [block] succeeds. Null dismisses immediately
     *   (share / open: chooser is the success UI).
     * @param confirmMessage When set, the snackbar asks before [block] runs
     *   (large-file Open / Share). [confirm] continues; [cancel] aborts.
     * @param confirmAction Label for the confirm button. Defaults to Open.
     */
    fun start(
        name: String,
        successMessage: String? = null,
        confirmMessage: String? = null,
        confirmAction: String? = null,
        block: suspend (ByteCounter) -> Unit,
    ) {
        val id = ids.getAndIncrement()
        val counter = ByteCounter()
        val gate = if (confirmMessage != null) CompletableDeferred<Boolean>() else null
        val initial = if (confirmMessage != null) {
            SaveTransferStatus.Confirming(
                confirmMessage,
                confirmAction ?: appCtx.getString(R.string.browse_open_large_continue),
            )
        } else {
            SaveTransferStatus.Running
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ticker: Job? = null
            try {
                if (gate != null) {
                    if (!gate.await()) {
                        patch(id) { copy(status = SaveTransferStatus.Cancelled) }
                        withContext(NonCancellable) { delay(1500) }
                        return@launch
                    }
                    patch(id) { copy(status = SaveTransferStatus.Running) }
                }
                ticker = launch {
                    var lastBytes = 0L
                    var lastAt = SystemClock.elapsedRealtime()
                    while (isActive) {
                        delay(250)
                        val now = SystemClock.elapsedRealtime()
                        val bytes = counter.bytes
                        val dt = (now - lastAt).coerceAtLeast(1L)
                        val speed = (bytes - lastBytes) * 1000L / dt
                        lastBytes = bytes
                        lastAt = now
                        patch(id) { copy(bytes = bytes, speedBps = speed) }
                    }
                }
                block(counter)
                ticker.cancel()
                if (successMessage != null) {
                    patch(id) {
                        copy(
                            bytes = counter.bytes,
                            speedBps = 0L,
                            status = SaveTransferStatus.Success(successMessage),
                        )
                    }
                    delay(2500)
                }
            } catch (e: CancellationException) {
                ticker?.cancel()
                val current = _items.value.find { it.id == id }?.status
                if (current !is SaveTransferStatus.Success &&
                    current !is SaveTransferStatus.Cancelled &&
                    current !is SaveTransferStatus.Failed
                ) {
                    patch(id) { copy(status = SaveTransferStatus.Cancelled) }
                }
                if (current !is SaveTransferStatus.Success) {
                    withContext(NonCancellable) { delay(1500) }
                }
                throw e
            } catch (e: Throwable) {
                ticker?.cancel()
                val msg = appCtx.getString(R.string.browse_save_failed) +
                    " " + (e.message ?: e.toString())
                patch(id) { copy(status = SaveTransferStatus.Failed(msg)) }
                delay(3000)
            } finally {
                ticker?.cancel()
                _items.update { list -> list.filterNot { it.id == id } }
            }
        }
        _items.update {
            it + SaveTransfer(id, name, 0L, 0L, initial, job, gate)
        }
        job.start()
    }

    fun confirm(id: Long) {
        _items.value.find { it.id == id }?.confirmGate?.complete(true)
    }

    fun cancel(id: Long) {
        val item = _items.value.find { it.id == id } ?: return
        when (item.status) {
            is SaveTransferStatus.Confirming -> {
                patch(id) { copy(status = SaveTransferStatus.Cancelled) }
                item.confirmGate?.complete(false)
            }
            is SaveTransferStatus.Running -> {
                patch(id) { copy(status = SaveTransferStatus.Cancelled) }
                item.job.cancel()
            }
            is SaveTransferStatus.Failed, SaveTransferStatus.Cancelled -> {
                item.job.cancel()
                _items.update { list -> list.filterNot { it.id == id } }
            }
            else -> Unit
        }
    }

    /**
     * Path change: in-flight Share/Save/Open copies sit on a dropped SMB/WebDAV client.
     * Fail them immediately so the snackbar can be dismissed; Confirming stays so
     * Continue after restore opens a fresh transfer.
     */
    fun abortRunningForNetwork() {
        val running = _items.value.filter { it.status is SaveTransferStatus.Running }
        if (running.isEmpty()) return
        val msg = appCtx.getString(R.string.browse_save_failed)
        for (item in running) {
            patch(item.id) { copy(status = SaveTransferStatus.Failed(msg), speedBps = 0L) }
            item.job.cancel()
        }
    }

    fun confirmMessage(sizeBytes: Long, messageRes: Int): String {
        val mb = ((sizeBytes + 1024L * 1024L - 1) / (1024L * 1024L)).toInt()
        val limit = (OPEN_CACHE_WARN_BYTES / (1024L * 1024L)).toInt()
        return appCtx.getString(messageRes, mb, limit)
    }

    private fun patch(id: Long, transform: SaveTransfer.() -> SaveTransfer) {
        _items.update { list ->
            list.map { if (it.id == id) it.transform() else it }
        }
    }
}

class ByteCounter {
    private val n = AtomicLong(0)
    val bytes: Long get() = n.get()
    fun add(delta: Int) {
        if (delta > 0) n.addAndGet(delta.toLong())
    }
}

class CountingOutputStream(
    dest: OutputStream,
    private val counter: ByteCounter,
) : FilterOutputStream(dest) {
    override fun write(b: Int) {
        out.write(b)
        counter.add(1)
    }

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        counter.add(len)
    }
}

data class SaveTransfer(
    val id: Long,
    val name: String,
    val bytes: Long,
    val speedBps: Long,
    val status: SaveTransferStatus,
    val job: Job,
    val confirmGate: CompletableDeferred<Boolean>? = null,
)

sealed interface SaveTransferStatus {
    data object Running : SaveTransferStatus
    data class Confirming(val message: String, val action: String) : SaveTransferStatus
    data class Success(val message: String) : SaveTransferStatus
    data class Failed(val message: String) : SaveTransferStatus
    data object Cancelled : SaveTransferStatus
}

@Composable
fun BrowseSaveSnackbars(modifier: Modifier = Modifier) {
    val items by BrowseSaveTransfers.items.collectAsState()
    if (items.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Snackbar(
                modifier = Modifier.padding(bottom = 8.dp),
                containerColor = snackbarContainerColor(),
                contentColor = snackbarDismissContentColor(),
                action = {
                    when (val st = item.status) {
                        is SaveTransferStatus.Confirming -> {
                            TextButton(
                                onClick = { BrowseSaveTransfers.confirm(item.id) },
                                colors = snackbarActionButtonColors(),
                            ) {
                                Text(st.action)
                            }
                        }
                        is SaveTransferStatus.Running, is SaveTransferStatus.Failed -> {
                            TextButton(
                                onClick = { BrowseSaveTransfers.cancel(item.id) },
                                colors = snackbarActionButtonColors(),
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                        else -> Unit
                    }
                },
                dismissAction = if (item.status is SaveTransferStatus.Confirming) {
                    {
                        TextButton(
                            onClick = { BrowseSaveTransfers.cancel(item.id) },
                            colors = snackbarDismissButtonColors(),
                        ) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
                } else {
                    null
                },
            ) {
                Text(text = item.label())
            }
        }
    }
}

@Composable
private fun SaveTransfer.label(): String = when (val st = status) {
    SaveTransferStatus.Running -> stringResource(
        R.string.browse_saving_progress,
        name,
        FileUtils.humanReadableByteCount(bytes),
        FileUtils.humanReadableByteCount(speedBps),
    )
    is SaveTransferStatus.Confirming -> st.message
    is SaveTransferStatus.Success -> st.message
    is SaveTransferStatus.Failed -> st.message
    SaveTransferStatus.Cancelled -> stringResource(R.string.browse_save_cancelled)
}
