package com.hippo.ehviewer.ui.main

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.library.ZipAsDirListing
import com.hippo.ehviewer.library.ZipPaths
import com.hippo.ehviewer.library.mimeTypeForFileName
import com.hippo.ehviewer.provider.ExternalHttpStreamServer
import com.hippo.ehviewer.provider.requestStreamNotificationPermission
import com.hippo.ehviewer.smb.SmbPasswordStore
import com.hippo.ehviewer.smb.SmbRepository
import com.hippo.ehviewer.ui.OpenFileExternally
import com.hippo.ehviewer.ui.tools.DialogState
import com.hippo.ehviewer.ui.tools.dialog
import com.hippo.ehviewer.util.addTextToClipboard
import com.hippo.ehviewer.webdav.WebDavPasswordStore
import com.hippo.ehviewer.webdav.WebDavRepository
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class HttpShareItem(
    val id: String,
    val dirKey: String,
    val title: String,
    val url: String,
    val isFolder: Boolean,
)

/**
 * LAN HTTP shares started from folder-view overflow. Each share is a session on
 * the dedicated 0.0.0.0 listener (not the loopback player server).
 */
object HttpShare {
    private val _items = MutableStateFlow<List<HttpShareItem>>(emptyList())
    val items: StateFlow<List<HttpShareItem>> = _items

    fun canShareLocal(pathStr: String, isZipBrowse: Boolean): Boolean {
        if (isZipBrowse) return false
        return ZipPaths.parse(pathStr) == null
    }

    fun canShareLocalFolder(relativeName: String, isZipBrowse: Boolean): Boolean {
        if (isZipBrowse) return false
        return ZipAsDirListing.zipFileSegment(relativeName) == null &&
            ZipAsDirListing.splitZipBrowsePath(relativeName) == null
    }

    fun canShareRemote(listedDir: String, itemRelative: String = "", folderLike: Boolean = false): Boolean {
        if (ZipAsDirListing.splitZipBrowsePath(listedDir) != null) return false
        val target = listOf(listedDir, itemRelative)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .replace('\\', '/')
            .trim('/')
        if (target.isEmpty()) return true
        if (folderLike && ZipAsDirListing.zipFileSegment(itemRelative.ifEmpty { target }) != null) {
            return false
        }
        return ZipAsDirListing.splitZipBrowsePath(target) == null || !folderLike
    }

    suspend fun startLocalFile(
        context: Context,
        pathStr: String,
        displayName: String,
        mimeType: String = mimeTypeForFileName(displayName),
    ): HttpShareItem = startShare(
        context = context,
        dirKey = "share-local-file:$pathStr",
        title = displayName,
        network = false,
        fileName = displayName,
    ) { session ->
        session.put(OpenFileExternally.localFileEntry(pathStr, displayName, mimeType))
    }

    suspend fun startLocalFolder(
        context: Context,
        dirPathStr: String,
        displayName: String,
    ): HttpShareItem = startShare(
        context = context,
        dirKey = "share-local-dir:$dirPathStr",
        title = displayName,
        network = false,
        fileName = "",
    ) { session ->
        session.dirSource = OpenFileExternally.localShareDirSource(dirPathStr)
    }

    suspend fun startSmbFile(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String = mimeTypeForFileName(displayName),
    ): HttpShareItem {
        requestStreamNotificationPermission(context)
        return startShare(
            context = context,
            dirKey = "share-smb-file:$sourceId:$remoteRelativeFile",
            title = displayName,
            network = true,
            fileName = displayName,
        ) { session ->
            val source = SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
            val password = SmbPasswordStore.get(sourceId)
            session.put(
                OpenFileExternally.smbFileEntry(
                    source,
                    password,
                    remoteRelativeFile,
                    displayName,
                    mimeType,
                    sizeBytes = -1L,
                ),
            )
        }
    }

    suspend fun startSmbFolder(
        context: Context,
        sourceId: Long,
        remoteRelativeDir: String,
        displayName: String,
    ): HttpShareItem {
        requestStreamNotificationPermission(context)
        return startShare(
            context = context,
            dirKey = "share-smb-dir:$sourceId:$remoteRelativeDir",
            title = displayName,
            network = true,
            fileName = "",
        ) { session ->
            val source = SmbRepository.load(sourceId) ?: throw IOException("SMB source missing")
            val password = SmbPasswordStore.get(sourceId)
            session.dirSource = OpenFileExternally.smbHtmlDirSource(source, password, remoteRelativeDir)
        }
    }

    suspend fun startWebDavFile(
        context: Context,
        sourceId: Long,
        remoteRelativeFile: String,
        displayName: String,
        mimeType: String = mimeTypeForFileName(displayName),
    ): HttpShareItem {
        requestStreamNotificationPermission(context)
        return startShare(
            context = context,
            dirKey = "share-dav-file:$sourceId:$remoteRelativeFile",
            title = displayName,
            network = true,
            fileName = displayName,
        ) { session ->
            val source = WebDavRepository.load(sourceId) ?: throw IOException("WebDAV source missing")
            val password = WebDavPasswordStore.get(sourceId)
            session.put(
                OpenFileExternally.webDavFileEntry(
                    source,
                    password,
                    remoteRelativeFile,
                    displayName,
                    mimeType,
                    sizeBytes = -1L,
                ),
            )
        }
    }

    suspend fun startWebDavFolder(
        context: Context,
        sourceId: Long,
        remoteRelativeDir: String,
        displayName: String,
    ): HttpShareItem {
        requestStreamNotificationPermission(context)
        return startShare(
            context = context,
            dirKey = "share-dav-dir:$sourceId:$remoteRelativeDir",
            title = displayName,
            network = true,
            fileName = "",
        ) { session ->
            val source = WebDavRepository.load(sourceId) ?: throw IOException("WebDAV source missing")
            val password = WebDavPasswordStore.get(sourceId)
            session.dirSource = OpenFileExternally.webDavHtmlDirSource(source, password, remoteRelativeDir)
        }
    }

    fun stop(id: String) {
        ExternalHttpStreamServer.removeShareSession(id)
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun find(dirKey: String): HttpShareItem? = _items.value.firstOrNull { it.dirKey == dirKey }

    private suspend fun startShare(
        context: Context,
        dirKey: String,
        title: String,
        network: Boolean,
        fileName: String,
        configure: suspend (ExternalHttpStreamServer.Session) -> Unit,
    ): HttpShareItem {
        requestStreamNotificationPermission(context)
        find(dirKey)?.let { existing ->
            if (ExternalHttpStreamServer.hasShareSession(existing.id)) return existing
            stop(existing.id)
        }
        val item = withIOContext {
            val host = LanAddresses.preferredHost()
                ?: throw IOException(context.getString(R.string.browse_http_share_no_address))
            val (session, reused) = ExternalHttpStreamServer.obtainShareSession(network, dirKey)
            try {
                if (!reused) configure(session)
            } catch (e: Throwable) {
                if (!reused) ExternalHttpStreamServer.removeShareSession(session.id)
                throw e
            }
            val url = ExternalHttpStreamServer.lanUriFor(session.id, fileName, host).toString()
            HttpShareItem(
                id = session.id,
                dirKey = dirKey,
                title = title,
                url = url,
                isFolder = fileName.isEmpty(),
            )
        }
        _items.update { list ->
            list.filterNot { it.id == item.id || it.dirKey == item.dirKey } + item
        }
        return item
    }
}

context(_: DialogState, ctx: Context)
suspend fun awaitHttpShareQr(item: HttpShareItem) = dialog { cont ->
    val qr = remember(item.url) { encodeQrBitmap(item.url).asImageBitmap() }
    AlertDialog(
        onDismissRequest = { cont.resume(Unit) },
        confirmButton = {
            TextButton(onClick = { cont.resume(Unit) }, shapes = ButtonDefaults.shapes()) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { with(ctx) { addTextToClipboard(item.url) } },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(R.string.browse_http_share_copy))
                }
                TextButton(
                    onClick = {
                        HttpShare.stop(item.id)
                        cont.resume(Unit)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(text = stringResource(R.string.browse_http_share_stop))
                }
            }
        },
        title = { Text(text = item.title.ifBlank { stringResource(R.string.browse_share_via_http) }) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = item.url,
                    modifier = Modifier
                        .size(220.dp)
                        .padding(bottom = 16.dp),
                )
                SelectionContainer {
                    Text(text = item.url)
                }
            }
        },
    )
}

@Composable
fun HttpShareSnackbars(
    onShare: (HttpShareItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by HttpShare.items.collectAsState()
    if (items.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Snackbar(
                modifier = Modifier.padding(bottom = 8.dp),
                action = {
                    TextButton(onClick = { onShare(item) }) {
                        Text(stringResource(R.string.share))
                    }
                },
                dismissAction = {
                    TextButton(onClick = { HttpShare.stop(item.id) }) {
                        Text(stringResource(R.string.browse_http_share_stop))
                    }
                },
            ) {
                Text(
                    text = stringResource(R.string.browse_http_share_snackbar, item.title),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
