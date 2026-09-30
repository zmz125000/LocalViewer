package com.hippo.ehviewer.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.library.BrowseSession
import com.hippo.ehviewer.library.parentRelativeOfFile
import com.hippo.ehviewer.ui.MainActivity
import java.security.MessageDigest

/**
 * Pinned home-screen shortcut that opens one browse folder.
 * A file item pins the folder that contains it.
 */
data class FolderShortcutTarget(
    val kind: String,
    val ownerId: Long,
    val path: String,
    val label: String,
)

object FolderHomeShortcut {
    const val ACTION = "moe.tarsin.ehviewer.action.OPEN_FOLDER"
    const val EXTRA_KIND = "folder_shortcut_kind"
    const val EXTRA_OWNER = "folder_shortcut_owner"
    const val EXTRA_PATH = "folder_shortcut_path"

    const val KIND_LOCAL = "local"
    const val KIND_SMB = "smb"
    const val KIND_WEBDAV = "webdav"

    /** Path of the folder a shortcut should open for this listed child. */
    fun localPath(frame: BrowseSession.LocalFrame, childRelative: String, isDirectory: Boolean): String {
        val listed = buildString {
            append(frame.relativePath.trim('/'))
            val inner = frame.zipInnerRel?.trim('/')?.takeIf { it.isNotEmpty() }
            if (inner != null) {
                if (isNotEmpty()) append('/')
                append(inner)
            }
        }
        return folderPath(listed, childRelative, isDirectory)
    }

    fun folderPath(parent: String, childRelative: String, isDirectory: Boolean): String {
        val child = childRelative.trim('/').let { if (it == ".") "" else it }
        val base = parent.trim('/').let { if (it == ".") "" else it }
        val combined = when {
            child.isEmpty() -> base
            base.isEmpty() -> child
            else -> "$base/$child"
        }
        return if (isDirectory) combined else parentRelativeOfFile(combined)
    }

    /** @return false when this launcher cannot pin shortcuts. */
    fun request(context: Context, target: FolderShortcutTarget): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val label = target.label.trim().ifEmpty { return false }
        val info = ShortcutInfoCompat.Builder(context, shortcutId(target))
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(
                Intent(context, MainActivity::class.java).apply {
                    action = ACTION
                    putExtra(EXTRA_KIND, target.kind)
                    putExtra(EXTRA_OWNER, target.ownerId)
                    putExtra(EXTRA_PATH, target.path)
                },
            )
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    private fun shortcutId(target: FolderShortcutTarget): String {
        val raw = "${target.kind}:${target.ownerId}:${target.path}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
