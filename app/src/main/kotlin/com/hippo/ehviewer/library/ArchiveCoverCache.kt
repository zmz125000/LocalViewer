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
import com.hippo.ehviewer.jni.getStreamMemberLength
import com.hippo.ehviewer.jni.getStreamMemberMethod
import com.hippo.ehviewer.jni.getStreamMemberOffset
import com.hippo.ehviewer.jni.getStreamMemberUncSize
import com.hippo.ehviewer.jni.isArchiveScanLimited
import com.hippo.ehviewer.jni.isStreamIndexComplete
import com.hippo.ehviewer.jni.isStreamIndexFinishedEmpty
import com.hippo.ehviewer.jni.loadStreamIndex
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openArchiveStream
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
 * [Skip] is transient (busy, password, I/O, or scan-budget abort on a large archive) — keep the row.
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
 * - Local ZIP/TAR: stream open via [PfdArchiveByteSource] (SAF-safe) + same paths as network
 * - Local/network ZIP: EOCD+CD (uncapped); seek index load/save via [ArchiveStreamPageCache]
 * - Local/network non-ZIP: scan budget ([LOCAL_NON_ZIP_SCAN_CAP] / [NETWORK_NON_ZIP_SCAN_CAP])
 * - Network RAR/7z: [ensureSolidStreamCover] (sequential first playable only)
 * - After solid reader: [writeCoverFromExtractedPage] from extract cache page 0
 *
 * Confirmed empty (whole CD / EOF / archive fully inside budget) → [CoverEnsureResult.NoImages]
 * (callers mark [EmptyArchiveRegistry] / [LocalLibrary.hideEmptyArchive]).
 *
 * Thumbs share the fixed [OriginDiskCache.THUMB_BUDGET_BYTES] pool (not origin settings).
 */
object ArchiveCoverCache {
    /** Align with [OriginDiskCache.THUMB_EDGE] / SMB/WebDAV browse thumbs. */
    const val THUMB_EDGE = OriginDiskCache.THUMB_EDGE

    /**
     * Network TAR / solid / libarchive-fallback cover scan budget.
     * ZIP EOCD+CD is never budgeted (always small vs archive body).
     */
    const val NETWORK_NON_ZIP_SCAN_CAP = 30L * 1024L * 1024L

    /** Local non-ZIP cover scan budget (mmap-free stream path). */
    const val LOCAL_NON_ZIP_SCAN_CAP = 100L * 1024L * 1024L

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
     * Uses [Path.openFileDescriptor] + [PfdArchiveByteSource] so `content://` tree documents
     * work. ZIP reuses [ArchiveStreamPageCache] seek index (same as the stream reader).
     * Non-ZIP scans abort after [LOCAL_NON_ZIP_SCAN_CAP] unless the whole file is smaller.
     *
     * [CoverEnsureResult.NoImages] when the archive is confirmed empty — callers should
     * hide the row ([LocalLibrary.hideEmptyArchive] / [EmptyArchiveRegistry]).
     */
    suspend fun ensureCover(archivePath: Path): CoverEnsureResult = withIOContext {
        runCatching {
            val name = archivePath.name
            if (!isArchiveFileName(name)) return@runCatching CoverEnsureResult.Skip

            val solid = isSolidArchiveFileName(name)
            val document = isDocumentFileName(name)
            val key = archivePath.toString()
            if (solid || document) {
                tryDiskCover(key)?.let { return@runCatching CoverEnsureResult.Hit(it) }
            }
            if (document) {
                return@runCatching ensureLocalDocumentCover(archivePath, key)
            }

            val openLocalSource: suspend () -> ArchiveByteSource = {
                val pfd = archivePath.openFileDescriptor("r")
                // Dup so the source owns a FD independent of the temporary open handle.
                val owned = ParcelFileDescriptor.dup(pfd.fileDescriptor)
                pfd.close()
                PfdArchiveByteSource(owned, ownsPfd = true)
            }

            if (solid) {
                return@runCatching ensureSolidStreamCoverInternal(
                    cacheKey = key,
                    openSource = openLocalSource,
                    scanCap = LOCAL_NON_ZIP_SCAN_CAP,
                )
            }

            // ZIP/TAR (and other non-solid archives): stream path + caps / index cache.
            val file = File(key)
            val realFile = file.isFile
            val mtime = if (!realFile) 0L else file.lastModified()
            val sizeHint = if (!realFile) 0L else file.length()
            val dest = thumbPathFor(key, mtime, sizeHint)
            if (isCachedOnDisk(dest)) return@runCatching CoverEnsureResult.Hit(dest)

            ensureStreamCoverInternal(
                cacheKey = key,
                openSource = openLocalSource,
                destOverride = dest,
                scanCapNonZip = LOCAL_NON_ZIP_SCAN_CAP,
                fileName = name,
            )
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
     * ZIP: loads/saves [ArchiveStreamPageCache] seek index (same as stream reader).
     * Non-ZIP: [NETWORK_NON_ZIP_SCAN_CAP] budget.
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
        ensureStreamCoverInternal(
            cacheKey = cacheKey,
            openSource = openSource,
            destOverride = null,
            scanCapNonZip = NETWORK_NON_ZIP_SCAN_CAP,
            fileName = base,
        )
    }

    /**
     * Shared ZIP/TAR cover open for local + network.
     * @param destOverride when set (local real-file mtime/size key), write thumb there;
     *   otherwise [thumbPathFor](cacheKey, 0, 0).
     */
    private suspend fun ensureStreamCoverInternal(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
        destOverride: Path?,
        scanCapNonZip: Long,
        fileName: String,
    ): CoverEnsureResult {
        val dest = destOverride ?: thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return CoverEnsureResult.Hit(dest)
        val isZip = fileName.isNotEmpty() && isZipArchiveFileName(fileName)
        return extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
            // Cached empty ZIP from a prior thumb/reader open — skip re-parse.
            if (isZip) {
                val cachedEmpty = ArchiveStreamPageCache.loadIndex(cacheKey)
                    ?.takeIf { it.format == "zip" && it.members.isEmpty() && it.complete }
                if (cachedEmpty != null) return@withPermit CoverEnsureResult.NoImages
            }
            ArchiveAccess.tryWithArchive {
                openSource().use { source ->
                    val archiveSize = runCatching { source.size }.getOrDefault(0L)
                    val bridge = ArchiveStreamBridge(source)
                    try {
                        // Prefer disk seek index (reader or prior thumb EOCD parse).
                        var n = tryOpenCoverFromSeekIndex(cacheKey, bridge, archiveSize)
                        val openedFromDisk = n > 0
                        if (n <= 0) {
                            // ZIP: full CD (same net cost as coverOnly) so we persist the
                            // full seek table for the reader. TAR: stop at first image.
                            n = openArchiveStream(
                                bridge,
                                archiveSize,
                                /* sortEntries = */
                                false,
                                /* coverOnly = */
                                !isZip,
                                /* progressiveTar = */
                                false,
                                /* maxScanBytes = */
                                if (isZip) 0L else scanCapNonZip,
                            )
                        }
                        when {
                            n <= 0 -> {
                                val empty = zeroImagesResult(
                                    archiveSize,
                                    if (isZip) 0L else scanCapNonZip,
                                )
                                if (empty is CoverEnsureResult.NoImages && isZip && !openedFromDisk) {
                                    persistEmptyZipIndex(cacheKey, archiveSize)
                                }
                                empty
                            }
                            needPassword() -> CoverEnsureResult.Skip
                            else -> {
                                if (!openedFromDisk && isZip) {
                                    persistZipSeekIndex(cacheKey, archiveSize, n)
                                }
                                val thumb = if (destOverride != null) {
                                    runCatching {
                                        extractPage0ToJpeg(dest)
                                        dest.takeIf { isCachedOnDisk(it) }
                                    }.getOrNull()
                                } else {
                                    writeCoverFromOpenArchive(cacheKey, 0L, 0L)
                                }
                                if (thumb != null) CoverEnsureResult.Hit(thumb) else CoverEnsureResult.Skip
                            }
                        }
                    } catch (e: Throwable) {
                        logcat("ArchiveCover", e)
                        CoverEnsureResult.Skip
                    } finally {
                        closeArchive()
                        bridge.close()
                    }
                }
            } ?: CoverEnsureResult.Skip
        }
    }

    /** Load [ArchiveStreamPageCache] seek table and bind for page-0 extract. */
    private fun tryOpenCoverFromSeekIndex(
        cacheKey: String,
        bridge: ArchiveStreamBridge,
        archiveSize: Long,
    ): Int {
        val idx = ArchiveStreamPageCache.loadIndex(cacheKey) ?: return 0
        if (archiveSize > 0L && idx.remoteSize > 0L && idx.remoteSize != archiveSize) return 0
        // Prefer full ZIP index; for cover alone the first seekable member is enough.
        val members = when {
            idx.format == "zip" && idx.hasFullSeekIndex() -> idx.members.sortedBy { it.i }
            else -> {
                val first = idx.members.filter { it.hasSeek }.minByOrNull { it.i }
                    ?: return 0
                listOf(first)
            }
        }
        if (members.isEmpty()) return 0
        val n = members.size
        val offsets = LongArray(n) { members[it].offset }
        val unc = LongArray(n) { members[it].uncSize }
        val comp = LongArray(n) {
            val c = members[it].compSize
            if (c > 0L) c else members[it].uncSize
        }
        val methods = IntArray(n) {
            val m = members[it].method
            if (m >= 0) m else 0
        }
        val names = Array(n) { i ->
            val ext = members[i].ext.ifBlank { "bin" }
            members[i].name.ifBlank { "%06d.%s".format(members[i].i, ext) }
        }
        val isTar = idx.format == "tar"
        return runCatching {
            loadStreamIndex(
                bridge,
                archiveSize.coerceAtLeast(1L),
                offsets,
                unc,
                comp,
                methods,
                names,
                isTar,
            )
        }.getOrDefault(0)
    }

    /** Persist full ZIP CD member table after a cold cover open (warms the reader). */
    private fun persistZipSeekIndex(cacheKey: String, archiveSize: Long, pageCount: Int) {
        if (pageCount <= 0) return
        val members = ArrayList<ArchiveStreamPageCache.Member>(pageCount)
        for (i in 0 until pageCount) {
            val ext = runCatching { getExtension(i) }.getOrNull()?.ifBlank { null } ?: "bin"
            val off = getStreamMemberOffset(i)
            val comp = getStreamMemberLength(i)
            val unc = getStreamMemberUncSize(i)
            val method = getStreamMemberMethod(i)
            if (off < 0L || unc <= 0L) continue
            members += ArchiveStreamPageCache.Member(
                i = i,
                name = "",
                ext = ext,
                uncSize = unc,
                offset = off,
                compSize = if (comp >= 0L) comp else unc,
                method = if (method >= 0) method else 0,
            )
        }
        if (members.isEmpty() || !members.all { it.hasSeek }) return
        ArchiveStreamPageCache.saveIndexAsync(
            ArchiveStreamPageCache.Index(
                v = ArchiveStreamPageCache.INDEX_VERSION,
                cacheKey = cacheKey,
                remoteSize = archiveSize,
                format = "zip",
                complete = false,
                members = members,
            ),
        )
    }

    private fun persistEmptyZipIndex(cacheKey: String, archiveSize: Long) {
        ArchiveStreamPageCache.saveIndexAsync(
            ArchiveStreamPageCache.Index(
                v = ArchiveStreamPageCache.INDEX_VERSION,
                cacheKey = cacheKey,
                remoteSize = archiveSize,
                format = "zip",
                complete = true,
                members = emptyList(),
            ),
        )
    }

    /**
     * Decide [NoImages] vs [Skip] after a zero-page open.
     * Confirmed empty when the container finished, or the whole file fits in the scan budget.
     * Large archives aborted by the budget stay [Skip] (may still contain images later).
     */
    private fun zeroImagesResult(archiveSize: Long, scanCap: Long): CoverEnsureResult {
        val limited = isArchiveScanLimited()
        val finishedEmpty = isStreamIndexFinishedEmpty()
        val complete = isStreamIndexComplete()
        val wholeFileInBudget = scanCap > 0L && archiveSize > 0L && archiveSize <= scanCap
        return when {
            // Uncapped ZIP CD (or finished TAR/libarchive) with 0 playable.
            finishedEmpty && !limited -> CoverEnsureResult.NoImages
            complete && !limited -> CoverEnsureResult.NoImages
            // Whole archive fits in budget — we effectively scanned it all.
            wholeFileInBudget -> CoverEnsureResult.NoImages
            // Budget abort on a larger archive — do not hide the row.
            limited -> CoverEnsureResult.Skip
            else -> CoverEnsureResult.Skip
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
     * Scan budget [NETWORK_NON_ZIP_SCAN_CAP]. Does **not** full-download a huge archive
     * when the first image is early; aborts after the budget if none found.
     * Passworded solids are skipped. [ArchiveAccess] busy → [Skip] (grid ON_RESUME retries).
     */
    suspend fun ensureSolidStreamCover(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
    ): CoverEnsureResult = withIOContext {
        ensureSolidStreamCoverInternal(cacheKey, openSource, NETWORK_NON_ZIP_SCAN_CAP)
    }

    private suspend fun ensureSolidStreamCoverInternal(
        cacheKey: String,
        openSource: suspend () -> ArchiveByteSource,
        scanCap: Long,
    ): CoverEnsureResult {
        val dest = thumbPathFor(cacheKey, 0L, 0L)
        if (isCachedOnDisk(dest)) return CoverEnsureResult.Hit(dest)

        // Prefer page already extracted by a prior solid reader session.
        coverFromSolidExtractCache(cacheKey)?.let { return CoverEnsureResult.Hit(it) }

        return extractSlots.withPermit {
            if (isCachedOnDisk(dest)) return@withPermit CoverEnsureResult.Hit(dest)
            coverFromSolidExtractCache(cacheKey)?.let { return@withPermit CoverEnsureResult.Hit(it) }
            val locked = ArchiveAccess.tryWithArchive {
                openSource().use { source ->
                    val archiveSize = runCatching { source.size }.getOrDefault(0L)
                    val bridge = ArchiveStreamBridge(source)
                    try {
                        val opened = openSolidSequential(bridge, archiveSize, scanCap)
                        if (opened == 0) {
                            logcat("SolidCover") { "openSolidSequential failed key=$cacheKey" }
                            return@tryWithArchive CoverEnsureResult.Skip
                        }
                        val idx = solidNextPlayable()
                        if (idx < 0) {
                            if (needPassword()) {
                                logcat("SolidCover") { "passworded solid skipped key=$cacheKey" }
                                return@tryWithArchive CoverEnsureResult.Skip
                            }
                            val limited = isArchiveScanLimited()
                            val wholeFileInBudget =
                                scanCap > 0L && archiveSize > 0L && archiveSize <= scanCap
                            return@tryWithArchive when {
                                limited && !wholeFileInBudget -> {
                                    logcat("SolidCover") {
                                        "scan cap hit key=$cacheKey size=$archiveSize cap=$scanCap"
                                    }
                                    CoverEnsureResult.Skip
                                }
                                else -> {
                                    logcat("SolidCover") { "no playable member key=$cacheKey" }
                                    CoverEnsureResult.NoImages
                                }
                            }
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
