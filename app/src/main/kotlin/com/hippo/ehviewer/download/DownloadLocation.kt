package com.hippo.ehviewer.download

import android.net.Uri
import com.ehviewer.core.files.toOkioPath
import com.ehviewer.core.files.toUri
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.util.AppConfig
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath

var downloadLocation: Path
    get() {
        val scheme = Settings.downloadScheme
        return if (scheme != null) {
            Uri.Builder().apply {
                scheme(scheme)
                encodedAuthority(Settings.downloadAuthority)
                encodedPath(Settings.downloadPath)
                encodedQuery(Settings.downloadQuery)
                encodedFragment(Settings.downloadFragment)
            }.build().toOkioPath()
        } else {
            AppConfig.defaultDownloadDir?.toOkioPath() ?: "".toPath()
        }
    }
    set(value) {
        val uri = value.toUri()
        Settings.downloadScheme = uri.scheme
        Settings.downloadAuthority = uri.encodedAuthority
        Settings.downloadPath = uri.encodedPath
        Settings.downloadQuery = uri.encodedQuery
        Settings.downloadFragment = uri.encodedFragment
    }
