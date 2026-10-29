package com.hippo.ehviewer.library

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal const val SMALL_TRANSFER_BYTES = 8L * 1024 * 1024
internal const val SMALL_TRANSFER_LANES = 4

internal fun transferBatchParallelism(files: List<TransferFile>, destination: ExplorerWindows.Kind, enabled: Boolean = true): Int = if (enabled && files.size > 1 && files.all { it.size in 0..SMALL_TRANSFER_BYTES } &&
    (destination != ExplorerWindows.Kind.Local || files.any { it.source.kind != ExplorerWindows.Kind.Local })
) {
    SMALL_TRANSFER_LANES
} else {
    1
}

/** A fixed number of sequential lanes; never launch one coroutine per file. */
internal suspend fun <T> forEachTransferLane(items: List<T>, parallelism: Int, block: suspend (Int, List<T>) -> Unit) = coroutineScope {
    require(parallelism > 0)
    if (items.isEmpty()) return@coroutineScope
    val lanes = List(minOf(parallelism, items.size)) { ArrayList<T>() }
    items.forEachIndexed { index, item -> lanes[index % lanes.size].add(item) }
    lanes.forEachIndexed { index, lane -> launch { block(index, lane) } }
    Unit
}
