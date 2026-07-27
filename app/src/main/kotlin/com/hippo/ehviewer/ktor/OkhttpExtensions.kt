package com.hippo.ehviewer.ktor

import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.AsyncDns
import okhttp3.android.AndroidAsyncDns

fun OkHttpConfig.configureClient() {
    config {
        // minSdk 32 ≥ Q: always use platform AsyncDns.
        dns(AsyncDns.toDns(AndroidAsyncDns.IPv4, AndroidAsyncDns.IPv6))
    }
}
