package com.hippo.ehviewer.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.provider.StreamDocumentProvider

// Preferred external app for ACTION_VIEW of video files.
// Stored as flattened ComponentName (package/class) in Settings.defaultVideoPlayerComponent.
// Empty = system chooser. Listing needs manifest <queries> for VIEW + video MIME (API 30+).
object DefaultVideoPlayer {
    data class Candidate(
        val component: ComponentName,
        val label: String,
    ) {
        val flattened: String get() = component.flattenToString()
    }

    private val videoMimes = listOf(
        "video/*",
        "video/mp4",
        "video/x-matroska",
        "video/webm",
        "video/3gpp",
    )

    fun isVideoMime(mimeType: String): Boolean = mimeType.startsWith("video/", ignoreCase = true)

    fun listCandidates(context: Context): List<Candidate> {
        val pm = context.packageManager
        val ourPkg = context.packageName
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Candidate>()
        for (info in queryVideoViewers(pm)) {
            val ai = info.activityInfo ?: continue
            if (ai.packageName == ourPkg) continue
            if (!ai.exported) continue
            val cn = ComponentName(ai.packageName, ai.name)
            val key = cn.flattenToString()
            if (!seen.add(key)) continue
            val label = info.loadLabel(pm).toString().ifBlank { null }
                ?: ai.loadLabel(pm).toString().ifBlank { null }
                ?: ai.packageName
            out += Candidate(cn, label)
        }
        return out.sortedBy { it.label.lowercase() }
    }

    // Preference summary: always-ask string, or "label · package/class".
    fun summary(context: Context, flattened: String, alwaysAskLabel: String): String {
        if (flattened.isBlank()) return alwaysAskLabel
        val cn = ComponentName.unflattenFromString(flattened) ?: return flattened
        val label = activityInfo(context.packageManager, cn)
            ?.loadLabel(context.packageManager)
            ?.toString()
            ?.takeIf { it.isNotBlank() }
        return if (label != null) "$label · $flattened" else flattened
    }

    fun preferredComponentOrNull(context: Context): ComponentName? {
        val flat = Settings.defaultVideoPlayerComponent.value
        if (flat.isBlank()) return null
        val cn = ComponentName.unflattenFromString(flat) ?: return null
        return cn.takeIf { canResolve(context, it) }
    }

    fun canResolve(context: Context, component: ComponentName): Boolean {
        val probe = videoViewIntent(
            Uri.Builder()
                .scheme("content")
                .authority(StreamDocumentProvider.authority())
                .appendPath("probe.mp4")
                .build(),
            "video/mp4",
        ).apply {
            this.component = component
        }
        if (resolveActivity(context.packageManager, probe) != null) return true
        return activityInfo(context.packageManager, component) != null
    }

    fun videoViewIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addCategory(Intent.CATEGORY_DEFAULT)
        // content/file grants only — http(s) loopback streams need no URI permission.
        when (uri.scheme?.lowercase()) {
            "content", "file" -> addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun queryVideoViewers(pm: PackageManager): List<ResolveInfo> {
        val out = ArrayList<ResolveInfo>()
        for (mime in videoMimes) {
            out += query(
                pm,
                Intent(Intent.ACTION_VIEW).apply {
                    type = mime
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
            )
            out += query(
                pm,
                videoViewIntent(
                    Uri.Builder()
                        .scheme("content")
                        .authority(StreamDocumentProvider.authority())
                        .appendPath("probe.mp4")
                        .build(),
                    mime,
                ),
            )
            out += query(
                pm,
                videoViewIntent(Uri.parse("content://media/external/video/media/1"), mime),
            )
            out += query(
                pm,
                videoViewIntent(Uri.parse("file:///storage/emulated/0/DCIM/probe.mp4"), mime),
            )
            out += query(
                pm,
                videoViewIntent(Uri.parse("http://127.0.0.1:1/probe.mp4"), mime),
            )
        }
        return out
    }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
    }.getOrDefault(emptyList())

    private fun resolveActivity(pm: PackageManager, intent: Intent): ResolveInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, 0)
        }
    }.getOrNull()

    private fun activityInfo(pm: PackageManager, component: ComponentName) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }
    }.getOrNull()
}
