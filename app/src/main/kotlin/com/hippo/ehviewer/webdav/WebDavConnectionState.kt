package com.hippo.ehviewer.webdav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last HTTP result per source configuration; this does not imply a persistent HTTP session. */
internal class WebDavConnectionState {
    data class Request(val source: String, val generation: Long)

    private var generation = 0L
    private val connected = MutableStateFlow<Set<String>>(emptySet())
    val sources = connected.asStateFlow()

    @Synchronized
    fun begin(source: String) = Request(source, generation)

    @Synchronized
    fun response(request: Request, status: Int) {
        // Missing files, unsupported methods and name conflicts still prove reachability.
        finish(request, status in 200..499 && status != 401 && status != 403 && status != 407 && status != 429)
    }

    @Synchronized
    fun failed(request: Request) = finish(request, false)

    private fun finish(request: Request, success: Boolean) {
        if (request.generation != generation) return
        if ((request.source in connected.value) == success) return
        connected.value = if (success) connected.value + request.source else connected.value - request.source
    }

    @Synchronized
    fun invalidate(source: String? = null) {
        // Responses from the previous network or credentials must not bring the icon back.
        generation++
        connected.value = if (source == null) emptySet() else connected.value - source
    }
}
