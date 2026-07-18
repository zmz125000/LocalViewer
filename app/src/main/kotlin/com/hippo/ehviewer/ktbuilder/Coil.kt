package com.hippo.ehviewer.ktbuilder

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.imageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

context(ctx: Context)
inline fun imageRequest(builder: ImageRequest.Builder.() -> Unit = {}) = ImageRequest.Builder(ctx).apply(builder).build()
inline fun diskCache(builder: DiskCache.Builder.() -> Unit) = DiskCache.Builder().apply(builder).build()
inline fun Context.imageLoader(builder: ImageLoader.Builder.() -> Unit) = ImageLoader.Builder(this).apply(builder).build()
suspend fun ImageRequest.execute() = context.imageLoader.execute(this)
fun ImageRequest.executeIn(scope: CoroutineScope) = scope.launch { execute() }
