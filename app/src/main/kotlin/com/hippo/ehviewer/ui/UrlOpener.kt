package com.hippo.ehviewer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Open arbitrary http(s) links in a custom tab / browser. EH deep links removed. */
fun Context.openBrowser(url: String) {
    if (url.isBlank()) return
    try {
        CustomTabsIntent.Builder().build().launchUrl(this, url.toUri())
    } catch (_: ActivityNotFoundException) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: Throwable) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }
}
