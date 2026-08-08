/*
 * Copyright 2023-2024 Tarsin Norbin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer.gallery

import arrow.autoCloseScope
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.byteBufferSource
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.extractToFd
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.LocalLibrary
import com.hippo.ehviewer.util.displayName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import moe.tarsin.kt.install
import okio.Path

typealias PasswdInvalidator = (String) -> Boolean
typealias PasswdProvider = suspend (PasswdInvalidator) -> String

/** Library metadata must finish even if the reader destination is replaced. */
private val archiveMetadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

suspend inline fun <T> useArchivePageLoader(
    file: Path,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    crossinline passwdProvider: PasswdProvider,
    crossinline block: suspend (PageLoader) -> T,
) = ArchiveAccess.withArchive {
    autoCloseScope {
        coroutineScope {
            val pfd = install(file.openFileDescriptor("r"))
            // sortEntries: EH downloads with real gallery info keep pack order; local / zip sort by name.
            // Must not key only on info==null — local archives now pass GalleryInfo for read progress.
            val sortEntries = info == null ||
                file.name.endsWith(".zip", ignoreCase = true) ||
                info.token == com.hippo.ehviewer.library.LOCAL_GALLERY_TOKEN ||
                info.token == com.hippo.ehviewer.library.LOCAL_ARCHIVE_TOKEN ||
                info.token == com.hippo.ehviewer.library.SMB_ARCHIVE_TOKEN ||
                info.token == com.hippo.ehviewer.library.WEBDAV_ARCHIVE_TOKEN
            val size = install(
                { openArchive(pfd.fd, pfd.statSize, sortEntries) },
                { _, _ -> closeArchive() },
            )
            check(size > 0) { "Archive have no content!" }
            if (needPassword() && archivePasswds.none(::providePassword)) {
                archivePasswds += passwdProvider(::providePassword)
            }
            // Serialize JNI extract; libarchive is process-global and not MT-safe.
            val extractLock = Any()
            val pathStr = file.toString()
            val f = File(pathStr)
            val mtime = f.takeIf { it.isFile }?.lastModified() ?: 0L
            val len = f.takeIf { it.isFile }?.length() ?: 0L
            val loader = install(
                object : PageLoader(this, info, startPage, size, hasAds) {
                    override val title by lazy {
                        if (info != null) {
                            info.title ?: ""
                        } else {
                            // Full filename incl. extension (archive/pdf, not folder).
                            file.displayName.ifEmpty { file.name }
                        }
                    }

                    override fun getImageExtension(index: Int) = getExtension(index)

                    override fun save(index: Int, file: Path) = runCatching {
                        file.openFileDescriptor("w").use {
                            synchronized(extractLock) {
                                extractToFd(index, it.fd)
                            }
                        }
                    }.getOrElse {
                        logcat(it)
                        false
                    }

                    override fun openSource(index: Int): ImageSource {
                        val buffer = synchronized(extractLock) {
                            extractToByteBuffer(index)
                        }
                        checkNotNull(buffer) { "Extract archive content $index failed!" }
                        check(buffer.isDirect)
                        return byteBufferSource(buffer) { releaseByteBuffer(buffer) }
                    }

                    override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

                    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                        notifySourceReady(index, orgImg)
                    }
                },
            )
            // Page count (and any existing cover) independent of cover encode path.
            // DAO COALESCE keeps a null cover from wiping a prior thumb path.
            archiveMetadataScope.launch {
                updateLocalArchiveLibraryCover(
                    pathStr = pathStr,
                    mtime = mtime,
                    len = len,
                    pageCount = size,
                    info = info,
                )
            }
            block(loader)
        }
    }
}

suspend fun updateLocalArchiveLibraryCover(
    pathStr: String,
    mtime: Long,
    len: Long,
    pageCount: Int,
    info: GalleryInfo?,
    coverStr: String? = null,
) {
    // Called either from the reader for the initial count or from the application-owned
    // cover encoder for the final path. The latter must survive reader navigation.
    try {
        val resolved = coverStr
            ?: ArchiveCoverCache.resolveCoverDest(pathStr, mtime, len)
                .takeIf { ArchiveCoverCache.isCachedOnDisk(it) }
                ?.toString()
        val gid = info?.gid
        withIOContext {
            if (gid != null && gid != 0L) {
                LocalLibrary.updateGalleryPageAndCover(gid, pageCount, resolved)
            } else {
                LocalLibrary.updateGalleryPageAndCoverByContentPath(pathStr, pageCount, resolved)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat("ArchiveCover", e)
    }
}
