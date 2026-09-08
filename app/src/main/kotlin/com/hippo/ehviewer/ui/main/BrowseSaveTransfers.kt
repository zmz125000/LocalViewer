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
import com.hippo.ehviewer.util.FileUtils
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx

/** One snackbar per Save-to… transfer: downloaded size, speed, cancel. */
object BrowseSaveTransfers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(1)
    private val _items = MutableStateFlow<List<SaveTransfer>>(emptyList())
    val items: StateFlow<List<SaveTransfer>> = _items

    fun start(name: String, successMessage: String, block: suspend (ByteCounter) -> Unit) {
        val id = ids.getAndIncrement()
        val counter = ByteCounter()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ticker = launch {
                var lastBytes = 0L
                var lastAt = SystemClock.elapsedRealtime()
                while (isActive) {
                    delay(250)
                    val now = SystemClock.elapsedRealtime()
                    val bytes = counter.bytes
                    val dt = (now - lastAt).coerceAtLeast(1L)
                    val speed = ((bytes - lastBytes) * 1000L) / dt
                    lastBytes = bytes
                    lastAt = now
                    patch(id) { copy(bytes = bytes, speedBps = speed) }
                }
            }
            try {
                block(counter)
                ticker.cancel()
                patch(id) {
                    copy(
                        bytes = counter.bytes,
                        speedBps = 0L,
                        status = SaveTransferStatus.Success(successMessage),
                    )
                }
                delay(2500)
            } catch (e: CancellationException) {
                ticker.cancel()
                if (_items.value.none { it.id == id && it.status is SaveTransferStatus.Success }) {
                    patch(id) { copy(status = SaveTransferStatus.Cancelled) }
                    delay(1500)
                }
            } catch (e: Throwable) {
                ticker.cancel()
                val msg = appCtx.getString(R.string.browse_save_failed) +
                    " " + (e.message ?: e.toString())
                patch(id) { copy(status = SaveTransferStatus.Failed(msg)) }
                delay(3000)
            } finally {
                _items.update { list -> list.filterNot { it.id == id } }
            }
        }
        _items.update { it + SaveTransfer(id, name, 0L, 0L, SaveTransferStatus.Running, job) }
        job.start()
    }

    fun cancel(id: Long) {
        _items.value.find { it.id == id }?.job?.cancel()
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
)

sealed interface SaveTransferStatus {
    data object Running : SaveTransferStatus
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
                action = {
                    if (item.status is SaveTransferStatus.Running) {
                        TextButton(onClick = { BrowseSaveTransfers.cancel(item.id) }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    }
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
    is SaveTransferStatus.Success -> st.message
    is SaveTransferStatus.Failed -> st.message
    SaveTransferStatus.Cancelled -> stringResource(R.string.browse_save_cancelled)
}
