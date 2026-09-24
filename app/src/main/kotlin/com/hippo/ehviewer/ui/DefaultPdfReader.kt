package com.hippo.ehviewer.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withUIContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.provider.StreamDocumentProvider

/** Tap PDF: 0 image reader (default), 1 built-in PDF reader, 2 external app, 3 auto. */
object PdfReaderMode {
    const val IMAGE = 0
    const val PDF = 1
    const val EXTERNAL = 2

    /** Image PDFs open in the gallery. Text PDFs open in the built-in PDF reader. */
    const val AUTO = 3
}

// Preferred external app for ACTION_VIEW of PDF files.
// Stored as flattened ComponentName (package/class) in Settings.defaultPdfReaderComponent.
// Empty = system chooser. Listing needs manifest <queries> for VIEW + application/pdf (API 30+).
object DefaultPdfReader {
    const val MIME_TYPE = "application/pdf"

    data class Candidate(
        val component: ComponentName,
        val label: String,
    ) {
        val flattened: String get() = component.flattenToString()
    }

    fun isPdfMime(mimeType: String): Boolean = mimeType.equals(MIME_TYPE, ignoreCase = true)

    fun listCandidates(context: Context): List<Candidate> {
        val pm = context.packageManager
        val ourPkg = context.packageName
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Candidate>()
        for (info in queryPdfViewers(pm)) {
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
        val flat = Settings.defaultPdfReaderComponent.value
        if (flat.isBlank()) return null
        val cn = ComponentName.unflattenFromString(flat) ?: return null
        return cn.takeIf { canResolve(context, it) }
    }

    fun canResolve(context: Context, component: ComponentName): Boolean {
        val probe = pdfViewIntent(
            Uri.Builder()
                .scheme("content")
                .authority(StreamDocumentProvider.authority())
                .appendPath("probe.pdf")
                .build(),
        ).apply {
            this.component = component
        }
        if (resolveActivity(context.packageManager, probe) != null) return true
        return activityInfo(context.packageManager, component) != null
    }

    fun bindPreferredReader(context: Context, intent: Intent, preferred: ComponentName) {
        val probe = Intent(intent).apply {
            component = null
            setPackage(null)
        }
        val matchesComponent = query(context.packageManager, probe).any { info ->
            val ai = info.activityInfo ?: return@any false
            ai.packageName == preferred.packageName && ai.name == preferred.className
        }
        if (matchesComponent) {
            intent.component = preferred
            intent.setPackage(null)
            return
        }
        intent.component = null
        intent.setPackage(preferred.packageName)
    }

    fun pdfViewIntent(uri: Uri, displayName: String? = null): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, MIME_TYPE)
        addCategory(Intent.CATEGORY_DEFAULT)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayName != null) {
            putExtra(Intent.EXTRA_TITLE, displayName)
            clipData = ClipData.newRawUri(displayName, uri)
        }
    }

    /**
     * Start the preferred PDF app when set and [usePreferredReader] is true; otherwise the
     * system chooser. Matches [DefaultVideoPlayer] + HTTP video launch.
     */
    suspend fun startView(
        context: Context,
        uri: Uri,
        displayName: String,
        usePreferredReader: Boolean = true,
    ) {
        val view = pdfViewIntent(uri, displayName)
        val preferred = if (usePreferredReader) preferredComponentOrNull(context) else null
        if (preferred != null) {
            bindPreferredReader(context, view, preferred)
            val launched = withUIContext {
                try {
                    context.startActivity(view)
                    true
                } catch (e: ActivityNotFoundException) {
                    logcat("DefaultPdfReader", e)
                    view.component = null
                    view.setPackage(null)
                    false
                }
            }
            if (launched) return
        }
        val title = context.getString(R.string.open_in_other_app)
        val chooser = Intent.createChooser(view, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withUIContext {
            try {
                context.startActivity(chooser)
            } catch (e: ActivityNotFoundException) {
                logcat("DefaultPdfReader", e)
                error(context.getString(R.string.open_pdf_no_app))
            }
        }
    }

    private fun queryPdfViewers(pm: PackageManager): List<ResolveInfo> {
        val out = ArrayList<ResolveInfo>()
        out += query(
            pm,
            Intent(Intent.ACTION_VIEW).apply {
                type = MIME_TYPE
                addCategory(Intent.CATEGORY_DEFAULT)
            },
        )
        out += query(
            pm,
            pdfViewIntent(
                Uri.Builder()
                    .scheme("content")
                    .authority(StreamDocumentProvider.authority())
                    .appendPath("probe.pdf")
                    .build(),
            ),
        )
        out += query(
            pm,
            pdfViewIntent(Uri.parse("content://media/external/file/1")),
        )
        out += query(
            pm,
            pdfViewIntent(Uri.parse("file:///storage/emulated/0/Download/probe.pdf")),
        )
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
