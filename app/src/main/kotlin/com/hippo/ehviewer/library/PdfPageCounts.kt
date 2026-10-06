package com.hippo.ehviewer.library

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splitties.init.appCtx

/**
 * PDF catalog `/Count` saved when a thumb fetch already opens the PDF parser.
 * Folder view reads this for the page badge. Seek stays on the image-page list.
 */
object PdfPageCounts {
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = ConcurrentHashMap<String, Int>()
    private val ticks = MutableStateFlow(0)

    /** Bumps after a new count is stored. Folder cells collect this to refresh. */
    val updates: StateFlow<Int> get() = ticks

    @Serializable
    private data class Body(val counts: Map<String, Int> = emptyMap())

    private val file: File by lazy {
        File(appCtx.applicationInfo.dataDir, "cache/pdf_page_counts.json")
    }
    private val lock = Any()
    private var loaded = false

    fun note(cacheKey: String, count: Int) {
        if (cacheKey.isEmpty() || count <= 0) return
        synchronized(lock) {
            loadLocked()
            if (memory[cacheKey] == count) return
            memory[cacheKey] = count
            ticks.value = ticks.value + 1
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(json.encodeToString(Body.serializer(), Body(HashMap(memory))))
            }
        }
    }

    /** In-memory hit. 0 before [get] has loaded disk. */
    fun peek(cacheKey: String): Int = memory[cacheKey] ?: 0

    fun get(cacheKey: String): Int {
        synchronized(lock) {
            loadLocked()
            return memory[cacheKey] ?: 0
        }
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        val body = runCatching { json.decodeFromString(Body.serializer(), text) }.getOrNull() ?: return
        for ((key, count) in body.counts) {
            if (key.isNotEmpty() && count > 0) memory.putIfAbsent(key, count)
        }
    }
}
