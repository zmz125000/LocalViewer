package com.hippo.ehviewer.library

import android.os.Looper
import android.os.ParcelFileDescriptor
import com.ehviewer.core.files.openFileDescriptor
import com.ehviewer.core.util.logcat
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.image.hdr.HdrConvertCache
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.extractToByteBuffer
import com.hippo.ehviewer.jni.getExtension
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchive
import com.hippo.ehviewer.jni.openSolidSequential
import com.hippo.ehviewer.jni.releaseByteBuffer
import com.hippo.ehviewer.jni.solidCurrentExtension
import com.hippo.ehviewer.jni.solidExtractCurrentToFd
import com.hippo.ehviewer.jni.solidNextPlayable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import splitties.init.appCtx

/**
 * Result of lazy archive cover extract.
 * [NoImages] means the archive was opened and has no playable pages — safe to hide from listings.
 * [Skip] is transient (busy, password, I/O) — keep the row.
 */
sealed interface CoverEnsureResult {
    data class Hit(val path: Path) : CoverEnsureResult
    data object NoImages : CoverEnsureResult
    data object Skip : CoverEnsureResult

    val pathOrNull: Path? get() = (this as? Hit)?.path
}

/**
 * First-page JPEG thumbs for archive galleries (library + folder / network browse).
 * Long edge [THUMB_EDGE] matches SMB/WebDAV browse thumbs ([OriginDiskCache.THUMB_EDGE]).
 *
 * - Local archives (ZIP/TAR/RAR/7z, incl. SAF `content://`): [openFileDescriptor] +
 *   libarchive page 0 — never [FileArchiveByteSource] (that only works on real file paths)
 * - Network ZIP/TAR: [ensureStreamCover] (range + coverOnly)
 * - Network RAR/7z: [ensureSolidStreamCover] (sequential first playable only)
 * - After solid reader: [writeCoverFromExtractedPage] from extract cache page 0
 *
 * Thumbs share the fixed [OriginDiskCache.THUMB_BUDGET_BYTES] pool (not origin settings).
 */
object ArchiveCoverCache {
    /** Align with [OriginDiskCache.THUMB_EDGE] / SMB/WebDAV browse thumbs. */
    const val THUMB_EDGE = OriginDiskCache.THUMB_EDGE

    private const val THUMB_JPEG_QUALITY = 85
    private const val FORMAT_VERSION = 2

    private val extractSlots = Semaphore(1)

    /** Paths known present on disk — main-thread [isCached] must not touch the filesystem. */
    private val knownPresent = ConcurrentHashMap.newKeySet<String>()

    private val thumbRoot: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
        File(appCtx.applicationInfo.dataDir, "cache/archive_thumb").toOkioPath()
    }

    fun thumbPathFor(archivePath: String, mtimeMs: Long = 0L, sizeBytes: Long = 0L): Path {
        val key = "archthumb:v$FORMAT_VERSION:$archivePath:$mtimeMs:$sizeBytes@$THUMB_EDGE"
        return thumbRoot / "${sha256Hex(key)}.jpg"
    }

    /**
     * Fast cache presence check (matches [com.hippo.ehviewer.smb.SmbCache.isCached]).
     * - **Main**: memory only — no [File] I/O / StrictMode.
     * - **Background**: probes disk and updates [knownPresent].
     */
    fun isCached(path: Path): Boolean {
        if (Looper.getMainLooper().isCurrentThread) {
            return knownPresent.contains(path.toString())
        }
        return isCachedOnDisk(path)
    }

    /** Authoritative disk probe — call from IO or accept StrictMode if on main. */
    fun isCachedOnDisk(path: Path): Boolean {
        val key = path.toString()
        val f = File(key)
        val ok = f.isFile && f.length() > 0L
        if (ok) knownPresent.add(key) else knownPresent.remove(key)
        return ok
    }

    fun markPresent(path: Path) {
        knownPresent.add(path.toString())
    }

    fun markAbsent(path: Path) {
        val key = path.toString()
        knownPresent.remove(key)
        val f = File(key)
        knownPresent.remove(f.absolutePath)
        knownPresent.remove(f.path)
    }

    /**
     * Whether a stored cover path is still openable.
     * Absolute filesystem paths (e.g. `…/cache/archive_thumb/…jpg`) are probed on disk
     * and [knownPresent] is updated. Non-absolute schemes (`content:`, `mediastore:`) are
     * trusted here — [com.hippo.ehviewer.coil.CoverPathFetcher] resolves them later.
     */
    fun isCoverPathReadable(path: String): Boolean {
        if (path.isBlank()) return false
        if (!path.startsWith('/')) return true
        return isCachedOnDisk(path.toPath())
    }

    /**
     * Ensure a small cover JPEG exists for a **local** [archivePath] (real file or SAF).
     *
     * Uses [Path.openFileDescriptor] so `content://` tree documents work. Solid RAR/7z
     * are opened the same way as the local reader ([openArchive] + page 0) — not via
     * [FileArchiveByteSource], which only accepts filesystem paths and crashes on SAF.
     *
     * [CoverEnsureResult.NoImages] when libarchive reports 0 playable images (same log as
     * "Found 0 images in archive") — callers should hide the row from library/browse.
     */
    suspend fun ensureCover(archivePath: Path): CoverEnsureResult = withIOContext {
        runCatching {
            val name = archivePath.name
            if (!isArchiveFileName(name)) return@runCatching CoverEnsureResult.Skip

            val solid = isSolidArchiveFileName(name)
            val document = isDocumentFileName(name)
            // Solid / document thumbs share the 0/0 key with tryDiskCover / reader writes.
            val key = archivePath.toString()
            if (solid || document) {
                tryDiskCover(key)?.let { return@runCatching CoverEnsureResult.Hit(it) }
            }
            if (document) {
                return@runCatching ensureLocalDocumentCover(archivePath, key)
            }

            val file = File(key)
            val mtime = if (solid) 0L else file.takeIf { it.isFile }?.lastModified() ?: 0L
            val size = if (solid) 0L else file.takeIf { it.isFile }?.length() ?: 0L
            val dest = thumbPathFor(key, mtime, size)
            if (isCachedOnDisk(dest)) return@runCatching CoverEnsureResult.Hit(dest)

            extractSlots.withPermit {
                if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
                ArchiveAccess.tryWithArchive {
                    extractCoverLocked(archivePath, dest)
                } ?: CoverEnsureResult.Skip
            }
        }.onFailure { logcat("ArchiveCover", it) }.getOrElse { CoverEnsureResult.Skip }
    }

    /**
     * Write cover from an **already open** archive (reader holds [ArchiveAccess]).
     * Extracts page 0 without reopening ([extractToByteBuffer]).
     *
     * Works for local solid after [openArchive] full index. Network solid stream
     * readers should use [writeCoverFromExtractedPage] instead (different extract API).
     *
     * Solid keys always use mtime/size = 0 so browse [ensureCover] / [tryDiskCover] hit
     * the same path as the reader-written thumb.
     *
     * [archiveKey] may be a local path or a remote stream key (`smb:id:path`).
     */
    fun writeCoverFromOpenArchive(archiveKey: String, destHintMtime: Long = 0L, destHintSize: Long = 0L): Path? {
        val base = archiveKey.substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
        val solid = base.isNotEmpty() && isSolidArchiveFileName(base)
        val dest = thumbPathFor(
            archiveKey,
            if (solid) 0L else destHintMtime,
            if (solid) 0L else destHintSize,
        )
        if (isCachedOnDisk(dest)) return dest
        return runCatching {
            extractPage0ToJpeg(dest)
            dest.takeIf { isCachedOnDisk(it) }
        }.onFailure { logcat(it) }.getOrNull()
    }

    /**
     * Cover from an already-extracted page file (solid fake-stream page 0).
     * Allows solid remote keys that [writeCoverFromOpenArchive] would skip.
     */
    fun writeCoverFromExtractedPage(archiveKey: String, pageFile: Path): Path? {
        val dest = thumbPathFor(archiveKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return dest
        return runCatching {
            val src = File(pageFile.toString())
            if (!src.isFile || src.length() == 0L) return null
            File(dest.parent!!.toString()).mkdirs()
            val jpgTmp = File("$dest.jpg.${System.nanoTime()}")
            try {
                // writeThumbJpeg: convert-path → lib+libultrahdr into this dest; else ImageDecoder.
                writeSubsampledJpeg(src, jpgTmp, THUMB_EDGE, THUMB_JPEG_QUALITY)
                val destFile = File(dest.toString())
                if (!jpgTmp.renameTo(destFile)) {
                    jpgTmp.copyTo(destFile, overwrite = true)
                    jpgTmp.delete()
                }
                if (destFile.isFile && destFile.length() > 0L) {
                    markPresent(dest)
                    OriginDiskCache.scheduleTrim()
                    dest
                } else {
                    null
                }
            } finally {
                if (jpgTmp.exists()) jpgTmp.delete()
            }
        }.onFailure { logcat(it) }.getOrNull()
    }

    /**
     * Stream-open a remote ZIP/TAR archive, extract page 0 cover, close. No full-archive download.
     *
     * [openSource] is invoked **only after** the extract slot is held so SMB/WebDAV
     * connections are not opened for every grid cell waiting in the queue.
     * Cache key uses size=0 so hits work without a network size probe.
     *
     * Solid formats: use [ensureSolidStreamCover].
     * Documents (EPUB/PDF): use [ensureDocumentStreamCover].
     */
    suspend fun ensureStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
    ): CoverEnsureResult = withIOContext {
        val base = cacheKey.substringAfterLast('/').substringAfterLast(':')
        if (base.isNotEmpty() && isSolidArchiveFileName(base)) return@withIOContext CoverEnsureResult.Skip
        if (base.isNotEmpty() && isDocumentFileName(base)) {
            return@withIOContext ensureDocumentStreamCover(cacheKey, openSource)
        }
        // Stable path without remote size (avoids opening the archive just to hash the key).
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return@withIOContext CoverEnsureResult.Hit(dest)
        extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
            ArchiveAccess.tryWithArchive {
                openSource().use { source ->
                    val bridge = ArchiveStreamBridge(source)
                    try {
                        // coverOnly: ZIP natural-first only / TAR stop at first image (EOCD/headers).
                        // Always pass coverOnly explicitly (no default on external JNI).
                        val n = com.hippo.ehviewer.jni.openArchiveStream(
                            bridge,
                            source.size,
                            /* sortEntries = */
                            false,
                            /* coverOnly = */
                            true,
                            /* progressiveTar = */
                            false,
                        )
                        when {
                            n <= 0 -> CoverEnsureResult.NoImages
                            com.hippo.ehviewer.jni.needPassword() -> CoverEnsureResult.Skip
                            else -> {
                                val thumb = writeCoverFromOpenArchive(cacheKey, 0L, 0L)
                                if (thumb != null) CoverEnsureResult.Hit(thumb) else CoverEnsureResult.Skip
                            }
                        }
                    } catch (e: Throwable) {
                        logcat("ArchiveCover", e)
                        CoverEnsureResult.Skip
                    } finally {
                        com.hippo.ehviewer.jni.closeArchive()
                        bridge.close()
                    }
                }
            } ?: CoverEnsureResult.Skip
        }
    }

    /**
     * Network/local-stream document cover (PDF/EPUB image extract).
     * Does not use libarchive / [ArchiveAccess].
     */
    suspend fun ensureDocumentStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
    ): CoverEnsureResult = withIOContext {
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return@withIOContext CoverEnsureResult.Hit(dest)
        coverFromDocumentExtractCache(cacheKey)?.let { return@withIOContext CoverEnsureResult.Hit(it) }
        extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
            coverFromDocumentExtractCache(cacheKey)?.let { return@withPermit CoverEnsureResult.Hit(it) }
            try {
                openSource().use { source ->
                    val size = runCatching { source.size }.getOrDefault(0L)
                    val engine = openDocumentCoverEngine(cacheKey, source, size)
                        ?: return@use CoverEnsureResult.Skip
                    if (engine.pageCount <= 0) return@use CoverEnsureResult.NoImages
                    // Extract page 0 only (coverOnly engine). Do **not** saveIndex:
                    // a 1-member incomplete index is treated as a full page list by
                    // openFromIndex and makes multi-page PDFs/EPUBs open as 1 page.
                    val page = engine.extractToCache(cacheKey, 0)
                        ?: return@use CoverEnsureResult.Skip
                    val thumb = writeCoverFromExtractedPage(cacheKey, page)
                    if (thumb != null) CoverEnsureResult.Hit(thumb) else CoverEnsureResult.Skip
                }
            } catch (e: Throwable) {
                logcat("ArchiveCover", e)
                CoverEnsureResult.Skip
            }
        }
    }

    private suspend fun ensureLocalDocumentCover(archivePath: Path, key: String): CoverEnsureResult {
        val dest = thumbPathFor(key, 0L, 0L)
        if (isCachedOnDisk(dest)) return CoverEnsureResult.Hit(dest)
        coverFromDocumentExtractCache(key)?.let { return CoverEnsureResult.Hit(it) }
        return extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
            coverFromDocumentExtractCache(key)?.let { return@withPermit CoverEnsureResult.Hit(it) }
            try {
                archivePath.openFileDescriptor("r").use { pfd ->
                    PfdArchiveByteSource(pfd, ownsPfd = false).use { source ->
                        val engine = openDocumentCoverEngine(key, source, pfd.statSize)
                            ?: return@withPermit CoverEnsureResult.Skip
                        if (engine.pageCount <= 0) return@withPermit CoverEnsureResult.NoImages
                        // Extract page 0 only; never persist coverOnly as document index
                        // (would pin multi-page docs to 1 page via openFromIndex).
                        val page = engine.extractToCache(key, 0)
                            ?: return@withPermit CoverEnsureResult.Skip
                        val thumb = writeCoverFromExtractedPage(key, page)
                        if (thumb != null) CoverEnsureResult.Hit(thumb) else CoverEnsureResult.Skip
                    }
                }
            } catch (e: Throwable) {
                logcat("ArchiveCover", e)
                CoverEnsureResult.Skip
            }
        }
    }

    private fun openDocumentCoverEngine(
        cacheKey: String,
        source: ArchiveByteSource,
        size: Long,
    ): com.hippo.ehviewer.library.document.DocumentImageEngine? {
        val base = cacheKey.substringAfterLast('/').substringAfterLast(':')
        return when {
            isEpubFileName(base) || cacheKey.endsWith(".epub", ignoreCase = true) ->
                com.hippo.ehviewer.library.document.EpubEngine.open(source, size, coverOnly = true)
            isPdfFileName(base) || cacheKey.endsWith(".pdf", ignoreCase = true) ->
                com.hippo.ehviewer.library.document.PdfImageEngine.open(source, size, coverOnly = true)
            else ->
                com.hippo.ehviewer.library.document.EpubEngine.open(source, size, coverOnly = true)
                    ?: com.hippo.ehviewer.library.document.PdfImageEngine.open(source, size, coverOnly = true)
        }
    }

    /**
     * Lazy browse thumb for network solid archives (RAR/CBR/7z):
     * 1. Hit existing JPEG thumb
     * 2. Hit solid extract page 0 already on disk (after a prior reader session)
     * 3. Sequential open → first playable member only → subsample JPEG → close
     *
     * Does **not** full-download the archive. Skips when [ArchiveAccess] is busy (reader open);
     * grid [ON_RESUME] retries. Passworded solids are skipped.
     */
    suspend fun ensureSolidStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
    ): CoverEnsureResult = withIOContext {
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return@withIOContext CoverEnsureResult.Hit(dest)

        // Prefer page already extracted by a prior solid reader session.
        coverFromSolidExtractCache(cacheKey)?.let { return@withIOContext CoverEnsureResult.Hit(it) }

        extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
            coverFromSolidExtractCache(cacheKey)?.let { return@withPermit CoverEnsureResult.Hit(it) }
            val locked = ArchiveAccess.tryWithArchive {
                openSource().use { source ->
                    val bridge = ArchiveStreamBridge(source)
                    try {
                        val opened = openSolidSequential(bridge, source.size)
                        if (opened == 0) {
                            logcat("SolidCover") { "openSolidSequential failed key=$cacheKey" }
                            return@tryWithArchive CoverEnsureResult.Skip
                        }
                        // Password only known after headers; don't check needPassword() pre-walk.
                        val idx = solidNextPlayable()
                        if (idx < 0) {
                            if (needPassword()) {
                                logcat("SolidCover") { "passworded solid skipped key=$cacheKey" }
                                return@tryWithArchive CoverEnsureResult.Skip
                            }
                            logcat("SolidCover") { "no playable member key=$cacheKey" }
                            return@tryWithArchive CoverEnsureResult.NoImages
                        }
                        if (needPassword()) {
                            logcat("SolidCover") { "passworded solid skipped key=$cacheKey" }
                            return@tryWithArchive CoverEnsureResult.Skip
                        }
                        val ext = solidCurrentExtension().ifBlank { "bin" }.take(8)
                        val tmp = File(
                            appCtx.cacheDir,
                            "solid_cover_${System.nanoTime()}.$ext",
                        )
                        try {
                            ParcelFileDescriptor.open(
                                tmp,
                                ParcelFileDescriptor.MODE_READ_WRITE or
                                    ParcelFileDescriptor.MODE_CREATE or
                                    ParcelFileDescriptor.MODE_TRUNCATE,
                            ).use { pfd ->
                                if (!solidExtractCurrentToFd(pfd.fd)) {
                                    logcat("SolidCover") { "extract page0 failed key=$cacheKey" }
                                    return@tryWithArchive CoverEnsureResult.Skip
                                }
                            }
                            if (!tmp.isFile || tmp.length() == 0L) {
                                return@tryWithArchive CoverEnsureResult.Skip
                            }
                            // Also seed solid extract page 0 so reader cold-open can reuse.
                            runCatching {
                                SolidExtractCache.writePageFromFdCopy(cacheKey, 0, ext, tmp)
                            }
                            val thumb = writeCoverFromExtractedPage(cacheKey, tmp.toOkioPath())
                            if (thumb != null) CoverEnsureResult.Hit(thumb) else CoverEnsureResult.Skip
                        } finally {
                            tmp.delete()
                        }
                    } catch (e: Throwable) {
                        logcat("SolidCover", e)
                        CoverEnsureResult.Skip
                    } finally {
                        closeArchive()
                        bridge.close()
                    }
                }
            }
            if (locked == null) {
                logcat("SolidCover") { "archive busy key=$cacheKey" }
            }
            locked ?: CoverEnsureResult.Skip
        }
    }

    /**
     * Disk-only cover resolve (no network): existing JPEG thumb, or extract page 0
     * (solid / document). Used by browse rows when network covers are off or before extract.
     */
    fun tryDiskCover(cacheKey: String): Path? {
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return dest
        return coverFromSolidExtractCache(cacheKey) ?: coverFromDocumentExtractCache(cacheKey)
    }

    /** Cover from solid_extract pages/000000.* if present. */
    private fun coverFromSolidExtractCache(cacheKey: String): Path? {
        val ext = SolidExtractCache.extensionFor(cacheKey, 0) ?: return null
        val page = SolidExtractCache.pagePath(cacheKey, 0, ext)
        if (!SolidExtractCache.isCachedFile(page)) return null
        return writeCoverFromExtractedPage(cacheKey, page)
    }

    /** Cover from document_extract pages/000000.* if present. */
    private fun coverFromDocumentExtractCache(cacheKey: String): Path? {
        val ext = DocumentExtractCache.extensionFor(cacheKey, 0) ?: return null
        val page = DocumentExtractCache.pagePath(cacheKey, 0, ext)
        if (!DocumentExtractCache.isCachedFile(page)) return null
        return writeCoverFromExtractedPage(cacheKey, page)
    }

    private fun extractCoverLocked(archivePath: Path, dest: Path): CoverEnsureResult {
        val pfd = try {
            archivePath.openFileDescriptor("r")
        } catch (e: Throwable) {
            logcat(e)
            return CoverEnsureResult.Skip
        }
        pfd.use { fd ->
            val count = openArchive(fd.fd, fd.statSize, true)
            try {
                // count == 0 logs "Found 0 images in archive" in native code.
                if (count <= 0) return CoverEnsureResult.NoImages
                if (needPassword()) return CoverEnsureResult.Skip
                extractPage0ToJpeg(dest)
                return dest.takeIf { isCachedOnDisk(it) }?.let { CoverEnsureResult.Hit(it) }
                    ?: CoverEnsureResult.Skip
            } finally {
                closeArchive()
            }
        }
    }

    /**
     * Page 0 → small JPEG under [dest]. Bytes stay in RAM (no full-page dump under
     * archive_thumb — a 25 MB `.raw.*` thrash would wear flash for every new cover).
     */
    private fun extractPage0ToJpeg(dest: Path) {
        val buffer = extractToByteBuffer(0) ?: error("extract page 0 failed")
        val bytes = try {
            check(buffer.isDirect)
            val dup = buffer.duplicate()
            dup.clear()
            ByteArray(dup.remaining()).also { dup.get(it) }
        } finally {
            releaseByteBuffer(buffer)
        }
        val ext = runCatching { getExtension(0) }.getOrNull()
            ?.trim()?.removePrefix(".")?.ifBlank { null }
            ?: "bin"
        val hint = "page0.$ext"
        File(dest.parent!!.toString()).mkdirs()
        val jpgTmp = File("$dest.jpg.${System.nanoTime()}")
        try {
            val ok = runBlocking {
                HdrConvertCache.writeThumbFromBytes(
                    bytes = bytes,
                    destJpeg = jpgTmp,
                    maxEdge = THUMB_EDGE,
                    quality = THUMB_JPEG_QUALITY,
                    fileNameHint = hint,
                )
            }
            check(ok && jpgTmp.isFile && jpgTmp.length() > 0L) {
                "thumb encode failed: $hint size=${bytes.size}"
            }
            val destFile = File(dest.toString())
            if (!jpgTmp.renameTo(destFile)) {
                jpgTmp.copyTo(destFile, overwrite = true)
                jpgTmp.delete()
            }
            if (destFile.isFile && destFile.length() > 0L) {
                markPresent(dest)
                OriginDiskCache.scheduleTrim()
            }
        } finally {
            if (jpgTmp.exists()) jpgTmp.delete()
        }
    }

    private fun writeSubsampledJpeg(source: File, destJpeg: File, maxEdge: Int, quality: Int) {
        // Already-on-disk extract page (solid/document cache). Convert-path → lib MaxEdge;
        // else ImageDecoder. No full re-copy of the source into archive_thumb.
        val ok = runBlocking {
            HdrConvertCache.writeThumbJpeg(
                source = source.toOkioPath(),
                destJpeg = destJpeg,
                maxEdge = maxEdge,
                quality = quality,
                fileNameHint = source.name,
            )
        }
        check(ok && destJpeg.isFile && destJpeg.length() > 0L) {
            "thumb encode failed: ${source.name}"
        }
    }

    private fun sha256Hex(s: String): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return dig.joinToString("") { "%02x".format(it) }
    }
}
