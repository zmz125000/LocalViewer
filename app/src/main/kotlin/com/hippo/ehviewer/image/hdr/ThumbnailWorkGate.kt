package com.hippo.ehviewer.image.hdr

import com.ehviewer.core.preferences.PrefDelegate
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

/** Live-adjustable admission for nested work sharing the same thumbnail resource. */
internal class ThumbnailWorkGate(
    private val slots: PrefDelegate<Int> = Settings.thumbnailConvertSlots,
    private val maxSlots: Int = 3,
) {
    private val mutex = Mutex()
    private val active = MutableStateFlow(0)

    // A thumbnail job can call the shared encoder before it returns. Its
    // nested calls reuse the admission, including at a configured limit of one.
    private class Permit(val gate: ThumbnailWorkGate) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Permit>
    }

    suspend fun <T> run(block: suspend () -> T): T {
        if (currentCoroutineContext()[Permit]?.gate === this) return block()
        while (true) {
            val acquired = mutex.withLock {
                if (active.value < slots.value.coerceIn(1, maxSlots)) {
                    active.value++
                    true
                } else {
                    false
                }
            }
            if (acquired) break
            combine(active, slots.valueFlow()) { count, limit ->
                count < limit.coerceIn(1, maxSlots)
            }.first { it }
        }
        try {
            return withContext(Permit(this)) { block() }
        } finally {
            withContext(NonCancellable) { mutex.withLock { active.value-- } }
        }
    }
}
