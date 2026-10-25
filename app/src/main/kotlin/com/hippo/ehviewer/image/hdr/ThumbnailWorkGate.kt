package com.hippo.ehviewer.image.hdr

import com.hippo.ehviewer.Settings
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Live-adjustable admission for one thumbnail's nested extract/decode/encode pipeline. */
internal class ThumbnailWorkGate {
    private val mutex = Mutex()
    private val active = MutableStateFlow(0)

    // A PDF/archive job can call the shared encoder before it returns. Its
    // nested calls reuse the admission, including at a configured limit of one.
    private class Permit(val gate: ThumbnailWorkGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Permit>
    }

    suspend fun <T> run(block: suspend () -> T): T {
        if (currentCoroutineContext()[Permit]?.gate === this) return block()
        while (true) {
            val acquired = mutex.withLock {
                if (active.value < Settings.thumbnailConvertSlots.value.coerceIn(1, 8)) {
                    active.value++
                    true
                } else {
                    false
                }
            }
            if (acquired) break
            combine(active, Settings.thumbnailConvertSlots.valueFlow()) { count, limit ->
                count < limit.coerceIn(1, 8)
            }.first { it }
        }
        try {
            return withContext(Permit(this)) { block() }
        } finally {
            withContext(NonCancellable) { mutex.withLock { active.value-- } }
        }
    }
}
