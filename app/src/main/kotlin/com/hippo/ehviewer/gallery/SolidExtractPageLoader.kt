package com.hippo.ehviewer.gallery

import android.os.ParcelFileDescriptor
import arrow.autoCloseScope
import com.ehviewer.core.model.GalleryInfo
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.Settings.archivePasswds
import com.hippo.ehviewer.image.ImageSource
import com.hippo.ehviewer.image.PathSource
import com.hippo.ehviewer.jni.closeArchive
import com.hippo.ehviewer.jni.needPassword
import com.hippo.ehviewer.jni.openSolidSequential
import com.hippo.ehviewer.jni.providePassword
import com.hippo.ehviewer.jni.solidCurrentExtension
import com.hippo.ehviewer.jni.solidCurrentName
import com.hippo.ehviewer.jni.solidCurrentUncSize
import com.hippo.ehviewer.jni.solidExtractCurrentToFd
import com.hippo.ehviewer.jni.solidNextPlayable
import com.hippo.ehviewer.jni.solidSkipCurrent
import com.hippo.ehviewer.library.ArchiveAccess
import com.hippo.ehviewer.library.ArchiveByteSource
import com.hippo.ehviewer.library.ArchiveCoverCache
import com.hippo.ehviewer.library.ArchiveStreamBridge
import com.hippo.ehviewer.library.ArchiveStreamClosedException
import com.hippo.ehviewer.library.CachePagePublish
import com.hippo.ehviewer.library.SolidExtractCache
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.tarsin.kt.install
import okio.Path

/**
 * Fake-stream solid archives (RAR/CBR/7z): sequential network I/O + extract to
 * [SolidExtractCache], driven by reader request/prefetch.
 *
 * **Lazy list:** member list grows as headers are discovered; [PageLoader.size] is the
 * listed count. Seek bar only lands on listed indices (never past unknown pages).
 *
 * **Resume:**
 * - complete+all-pages → offline [cachedSolidLoader] (no network)
 * - half-cache → seed from `pages/` + `index.json` **before** native open; serve cached
 *   pages immediately; open solid only when an uncached page is needed (skip-write past
 *   cached prefix). remoteSize mismatch purges the extract dir.
 */
suspend inline fun <T> useSolidExtractPageLoader(
    source: ArchiveByteSource,
    cacheKey: String,
    titleHint: String,
    info: GalleryInfo? = null,
    startPage: Int = 0,
    hasAds: Boolean = false,
    remoteSize: Long = 0L,
    crossinline passwdProvider: PasswdProvider,
    crossinline block: suspend (PageLoader) -> T,
): T = ArchiveAccess.withArchive {
    autoCloseScope {
        coroutineScope {
            // Soft-fail remote size (WebDAV restart): do not crash; fall back to 0 and open checks.
            val sizeHint = remoteSize.takeIf { it > 0L }
                ?: runCatching { source.size }.getOrDefault(0L)
            // Hard invalidate before any resume/cold path; pin while reader owns this key.
            SolidExtractCache.invalidateIfRemoteSizeMismatch(cacheKey, sizeHint)
            SolidExtractCache.pin(cacheKey)
            install({ }, { _, _ -> SolidExtractCache.unpin(cacheKey) })

            val ready = SolidExtractCache.isCompleteAndReady(cacheKey, remoteSize = sizeHint)
            if (ready != null) {
                // LRU bump off the open critical path (setLastModified is disk I/O).
                SolidExtractCache.touchAsync(cacheKey)
                val loader = install(
                    cachedSolidLoader(
                        scope = this,
                        cacheKey = cacheKey,
                        index = ready,
                        titleHint = titleHint,
                        info = info,
                        startPage = startPage,
                        hasAds = hasAds,
                    ),
                )
                return@coroutineScope block(loader)
            }

            check(sizeHint > 0L) { "Cannot open solid archive (size unknown): $cacheKey" }

            val engine = SolidExtractEngine(
                cacheKey = cacheKey,
                remoteSize = sizeHint,
            )
            // Disk-first: rebuild list from pages/ even when index.json is missing.
            engine.seedFromDiskIndex()

            // Lazy native open — half-cache can show page 0 without touching the network.
            // (No local fun: not allowed inside inline.)
            val solidOpen = AtomicBoolean(false)
            val solidOpenMutex = Mutex()
            val solidBridgeRef = AtomicReference<ArchiveStreamBridge?>(null)
            engine.nativeOpener = opener@{
                if (solidOpen.get()) return@opener
                solidOpenMutex.withLock {
                    if (solidOpen.get()) return@withLock
                    val bridge = ArchiveStreamBridge(source)
                    solidBridgeRef.set(bridge)
                    // maxScanBytes = 0: no scan limit
                    val opened = openSolidSequential(bridge, sizeHint, 0L)
                    check(opened > 0) { "Solid sequential open failed" }
                    if (needPassword() && archivePasswds.none(::providePassword)) {
                        archivePasswds += passwdProvider(::providePassword)
                    }
                    solidOpen.set(true)
                }
            }
            install({ }, { _, _ ->
                if (solidOpen.get()) {
                    runCatching { closeArchive() }
                }
                runCatching { solidBridgeRef.getAndSet(null)?.close() }
            })

            if (!engine.isKnownOnDisk(0)) {
                // Cold (or missing page 0): must open native and extract/list first page.
                engine.ensureThrough(0)
            }
            check(engine.listedCount() > 0) { "Solid archive has no playable images" }
            // Persist seed so next resume has index even if we only served disk pages.
            engine.persistIndex(complete = engine.isComplete, async = true)

            val pagePaths = ConcurrentHashMap<Int, Path>()
            // Pre-map half-cache pages so onRequest hits memory, not network/ensureThrough.
            for (i in engine.onDiskIndices()) {
                val ext = engine.extOf(i) ?: continue
                pagePaths[i] = SolidExtractCache.pagePath(cacheKey, i, ext)
            }
            val readyWaiters = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
            val extractJobs = ConcurrentHashMap<Int, Job>()
            val coverWritten = AtomicBoolean(false)
            val hostScope = this
            val prefetchN = Settings.preloadImage.value.coerceAtLeast(1)

            /** High-water extract target; advanced as the user moves so list grows past init+prefetch. */
            val extractTarget = AtomicInteger((startPage + prefetchN).coerceAtLeast(0))
            val bgExtractJob = AtomicReference<Job?>(null)

            val loader = install(
                object : PageLoader(
                    hostScope,
                    info,
                    startPage.coerceIn(0, (engine.listedCount() - 1).coerceAtLeast(0)),
                    engine.listedCount(),
                    hasAds,
                ) {
                    override val title by lazy { info?.title ?: titleHint }

                    init {
                        val self = this
                        engine.onListed = { count -> self.growTo(count) }
                        // Progressive UI: decode each page as it hits disk, not after the
                        // whole ensureThrough(target) batch (avoids long spinner then pop-in).
                        engine.onPageReady = { index -> self.markPageExtracted(index) }
                    }

                    override fun getImageExtension(index: Int) = engine.extOf(index)

                    override fun save(index: Int, file: Path): Boolean = runCatching {
                        val ext = engine.extOf(index) ?: return@runCatching false
                        // Prefer in-memory map (no File.stat on caller thread).
                        val path = pagePaths[index]
                            ?: SolidExtractCache.pagePath(cacheKey, index, ext)
                                .takeIf { SolidExtractCache.isCachedFile(it) }
                            ?: error("Not cached")
                        File(path.toString()).copyTo(File(file.toString()), overwrite = true)
                        true
                    }.getOrDefault(false)

                    override fun openSource(index: Int): ImageSource {
                        val ext = engine.extOf(index) ?: "bin"
                        // notifySourceReady only after markPageExtracted mapped the path —
                        // do not File.length() here (decode may run on main-ish scope).
                        val path = pagePaths[index]
                            ?: error("Solid page $index not extracted")
                        return object : PathSource {
                            override val source: Path = path
                            override val type: String = ext
                            override fun close() = Unit
                        }
                    }

                    override fun prefetchPages(pages: List<Int>, bounds: IntRange) {
                        pages.forEach { ensureExtract(it, interactive = false) }
                        pages.maxOrNull()?.let { bumpTarget(it) }
                    }

                    override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) {
                        ensureExtract(index, interactive = true) {
                            notifySourceReady(index, orgImg)
                        }
                    }

                    override fun onNavigation(demand: ReaderDemand) {
                        bumpTarget(demand.progressiveDiscoveryTarget(prefetchN))
                    }

                    override fun close() {
                        // Abort sequential extract immediately so ArchiveAccess can hand off
                        // to the next reader (exit / double-tap prev-next).
                        engine.abort()
                        bgExtractJob.getAndSet(null)?.cancel()
                        // Snapshot first: cancel handlers remove from extractJobs concurrently.
                        // Live CHM.values iteration on main (Compose dispose) → NoSuchElementException.
                        extractJobs.values.toList().forEach { it.cancel() }
                        extractJobs.clear()
                        readyWaiters.clear()
                        // Sync-enough flush via cache scope (outlives hostScope cancel).
                        engine.persistIndex(complete = engine.isComplete, async = true)
                        super.close()
                    }

                    private fun bumpTarget(index: Int) {
                        extractTarget.updateAndGet { cur -> maxOf(cur, index) }
                    }

                    /** In-memory only — safe on main / onRequest. */
                    private fun isPageMapped(index: Int): Boolean = pagePaths.containsKey(index)

                    /** Disk probe; call only from [Dispatchers.IO]. Prefers engine readdir set. */
                    private fun probePageOnDisk(index: Int): Boolean {
                        if (pagePaths.containsKey(index)) return true
                        val ext = engine.extOf(index) ?: return false
                        val p = SolidExtractCache.pagePath(cacheKey, index, ext)
                        if (engine.isKnownOnDisk(index) || SolidExtractCache.isCachedFile(p)) {
                            pagePaths[index] = p
                            return true
                        }
                        return false
                    }

                    /**
                     * Page [index] is ready — map path, grow list, optional cover, fire decode
                     * waiters. Does **not** File.stat (StrictMode); callers already extracted
                     * or [probePageOnDisk]'d on IO.
                     */
                    fun markPageExtracted(index: Int) {
                        val ext = engine.extOf(index) ?: return
                        val path = SolidExtractCache.pagePath(cacheKey, index, ext)
                        pagePaths[index] = path
                        growTo(engine.listedCount())
                        if (index == 0 && coverWritten.compareAndSet(false, true)) {
                            hostScope.launch(Dispatchers.IO) {
                                runCatching {
                                    ArchiveCoverCache.writeCoverFromExtractedPage(cacheKey, path)
                                }.onFailure { logcat("SolidExtract", it) }
                            }
                        }
                        readyWaiters.remove(index)?.forEach { runCatching { it() } }
                    }

                    private fun ensureExtract(
                        index: Int,
                        interactive: Boolean,
                        onReady: (() -> Unit)? = null,
                    ) {
                        if (index < 0) return
                        if (onReady != null) {
                            readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(onReady)
                            // Already mapped (bg extract finished) — decode now, no disk I/O.
                            if (isPageMapped(index)) {
                                markPageExtracted(index)
                                return
                            }
                        } else if (isPageMapped(index)) {
                            return
                        }
                        val existing = extractJobs[index]
                        if (existing != null && existing.isActive) return
                        // Always pull through index so this page is on disk; grow target separately.
                        val job = hostScope.launch(Dispatchers.IO) {
                            try {
                                if (probePageOnDisk(index)) {
                                    markPageExtracted(index)
                                    return@launch
                                }
                                // May run behind bg ensureThrough(target); onPageReady fires
                                // waiters for intermediate pages as each lands — do not wait
                                // for the whole batch before decoding the visible page.
                                engine.ensureThrough(index)
                                if (probePageOnDisk(index) || isPageMapped(index)) {
                                    markPageExtracted(index)
                                } else {
                                    val waiters = readyWaiters.remove(index).orEmpty()
                                    if (waiters.isNotEmpty()) {
                                        notifyPageFailed(index, "Extract incomplete")
                                    }
                                }
                            } catch (e: CancellationException) {
                                // Re-queue only for in-session job races (lost putIfAbsent).
                                // On reader exit / archive preempt, hostScope is cancelled — do not restart.
                                if (hostScope.isActive &&
                                    (
                                        extractJobs[index] == coroutineContext[Job] ||
                                            extractJobs[index] == null
                                        )
                                ) {
                                    val waiters = readyWaiters.remove(index).orEmpty()
                                    waiters.forEach {
                                        readyWaiters.getOrPut(index) { CopyOnWriteArrayList() }.add(it)
                                    }
                                    if (waiters.isNotEmpty()) {
                                        hostScope.launch(Dispatchers.IO) {
                                            ensureExtract(index, interactive = true)
                                        }
                                    }
                                }
                                throw e
                            } catch (e: Throwable) {
                                // Leave mid-flight: abort + source.close → quiet closed/IO; do not
                                // log or fail pages that are already going away with the reader.
                                if (engine.isAborted || e is ArchiveStreamClosedException) {
                                    readyWaiters.remove(index)
                                } else {
                                    logcat("SolidExtract", e)
                                    val waiters = readyWaiters.remove(index).orEmpty()
                                    if (waiters.isNotEmpty() || interactive) {
                                        notifyPageFailed(index, e.message)
                                    }
                                }
                            } finally {
                                extractJobs.remove(index, coroutineContext[Job])
                            }
                        }
                        val prev = extractJobs.putIfAbsent(index, job)
                        if (prev != null) {
                            if (prev.isActive) job.cancel() else extractJobs[index] = job
                        }
                    }
                },
            )

            // Sequential extract ahead of [extractTarget]. User advances raise the target so
            // the lazy list grows past the initial start+prefetch window within one session.
            // Cancelled from [PageLoader.close] / ArchiveAccess preempt so next archive can start.
            bgExtractJob.set(
                hostScope.launch(Dispatchers.IO) {
                    try {
                        var lastTarget = -1
                        while (isActive && !engine.isComplete && !engine.isAborted) {
                            val target = extractTarget.get()
                            if (target != lastTarget) {
                                engine.ensureThrough(target)
                                loader.growTo(engine.listedCount())
                                lastTarget = target
                            } else {
                                delay(50)
                            }
                            if (engine.isComplete) {
                                loader.growTo(engine.listedCount())
                                break
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Mid-exit close under solid extract is expected (not a real fault).
                        if (!engine.isAborted && e !is ArchiveStreamClosedException) {
                            logcat("SolidExtract", e)
                        }
                    }
                },
            )

            try {
                block(loader)
            } finally {
                // Reader exited or session preempted: stop extract + release network/JNI ASAP.
                engine.abort()
                val background = bgExtractJob.getAndSet(null)
                val jobs = extractJobs.values.toSet().toList()
                background?.cancel()
                jobs.forEach { it.cancel() }
                extractJobs.clear()
                // Mark bridge closed **before** tearing down the source so concurrent
                // nativeRead does not log "archive read error" (expected mid-exit).
                runCatching { solidBridgeRef.get()?.close() }
                // Close transport so a blocking libarchive callback can return, then
                // wait for every native child before AutoCloseScope invokes closeArchive.
                runCatching { source.close() }
                withContext(NonCancellable) {
                    (jobs + listOfNotNull(background)).joinAll()
                }
            }
        }
    }
}

/** Cached complete solid extract — folder-like pages from disk, no native open. */
fun cachedSolidLoader(
    scope: CoroutineScope,
    cacheKey: String,
    index: SolidExtractCache.Index,
    titleHint: String,
    info: GalleryInfo?,
    startPage: Int,
    hasAds: Boolean,
): PageLoader {
    val members = index.members.sortedBy { it.i }
    val exts = members.associate { it.i to it.ext }
    return object : PageLoader(
        scope,
        info,
        startPage.coerceIn(0, (members.size - 1).coerceAtLeast(0)),
        members.size,
        hasAds,
    ) {
        override val title by lazy { info?.title ?: titleHint }

        override fun getImageExtension(index: Int) = exts[index]

        override fun save(index: Int, file: Path): Boolean = runCatching {
            val ext = exts[index] ?: return@runCatching false
            val path = SolidExtractCache.pagePath(cacheKey, index, ext)
            File(path.toString()).copyTo(File(file.toString()), overwrite = true)
            true
        }.getOrDefault(false)

        override fun openSource(index: Int): ImageSource {
            // Paths are known from index — no File.stat (open-path O(n) was killing resume).
            val ext = exts[index] ?: "bin"
            val path = SolidExtractCache.pagePath(cacheKey, index, ext)
            return object : PathSource {
                override val source: Path = path
                override val type: String = ext
                override fun close() = Unit
            }
        }

        override fun prefetchPages(pages: List<Int>, bounds: IntRange) = Unit

        override fun onRequest(index: Int, force: Boolean, orgImg: Boolean) = notifySourceReady(index, orgImg)
    }
}

/**
 * Single-flight sequential extract cursor. [ensureThrough] advances until [index]
 * is on disk (or EOF). Listed members grow on each header; bodies always written
 * so seek-bar targets are extractable.
 *
 * **Half-cache resume:** seed from `pages/` + index **without** native open; [onDisk]
 * serves cached pages. Native open is deferred via [nativeOpener] until an uncached
 * page is requested, then skip-write advances the solid cursor past cached members.
 *
 * [abort] stops further members so reader exit / prev-next can release [ArchiveAccess]
 * without finishing the rest of the archive.
 */
class SolidExtractEngine(
    private val cacheKey: String,
    private val remoteSize: Long,
) {
    private val mutex = Mutex()
    private val members = CopyOnWriteArrayList<SolidExtractCache.Member>()

    /** O(1) ext lookup — avoid linear scan + [SolidExtractCache.extensionFor] disk. */
    private val memberExt = ConcurrentHashMap<Int, String>()
    private val memberUnc = ConcurrentHashMap<Int, Long>()

    /**
     * Pages known present under `pages/` (readdir once at seed + updated on extract).
     * Skip path trusts this — no File.stat per member.
     */
    private val onDisk = ConcurrentHashMap.newKeySet<Int>()

    /** Highest known page index (avoids iterating ConcurrentHashMap keys on main). */
    private val maxKnownIndex = AtomicInteger(-1)
    private val complete = AtomicBoolean(false)
    private val aborted = AtomicBoolean(false)
    private val error = AtomicReferenceError()
    var onListed: ((Int) -> Unit)? = null

    /**
     * Invoked after each playable member is on disk (extract or skip-write).
     * Used so the reader can decode page N as soon as it lands, not after
     * [ensureThrough] finishes the entire high-water target batch.
     */
    var onPageReady: ((Int) -> Unit)? = null

    /**
     * Opens libarchive solid sequential session (network). Invoked only when an
     * uncached page must be extracted/skipped-to. Null after open failure is fatal.
     */
    var nativeOpener: (suspend () -> Unit)? = null

    val isComplete: Boolean get() = complete.get()
    val isAborted: Boolean get() = aborted.get()

    /**
     * Page count for the reader: max known index + 1 (solid indices are 0..n-1).
     * Not [members.size] — half-cache from readdir must allow seek to the highest cached page.
     * O(1) — never iterate ConcurrentHashMap keys (Android EntryIterator races).
     */
    fun listedCount(): Int = maxKnownIndex.get() + 1

    fun extOf(index: Int): String? = memberExt[index]

    /** True if page was found at seed readdir or written this session (no File.stat). */
    fun isKnownOnDisk(index: Int): Boolean = index in onDisk

    /** Snapshot of pages already on disk (for pre-mapping into the page loader). */
    fun onDiskIndices(): Set<Int> = onDisk.toSet()

    /** Stop extract ASAP (reader closed or session preempted). */
    fun abort() {
        aborted.set(true)
    }

    private fun noteIndex(i: Int) {
        maxKnownIndex.updateAndGet { cur -> maxOf(cur, i) }
    }

    /**
     * Restore partial list from `index.json` **and** `pages/` readdir.
     * Disk pages alone are enough to resume (index may be missing after a kill).
     */
    fun seedFromDiskIndex() {
        // Hard purge if remote was replaced; never seed stale members/pages.
        if (SolidExtractCache.invalidateIfRemoteSizeMismatch(cacheKey, remoteSize)) return
        members.clear()
        memberExt.clear()
        memberUnc.clear()
        onDisk.clear()
        maxKnownIndex.set(-1)

        val idx = SolidExtractCache.loadIndex(cacheKey)
        if (idx != null) {
            for (m in idx.members.sortedBy { it.i }) {
                rememberMember(m.i, m.name, m.ext, m.uncSize, notify = false)
            }
        }

        // Always merge real files on disk (source of truth for half-cache).
        val disk = SolidExtractCache.listCachedPages(cacheKey)
        for ((i, ext) in disk) {
            onDisk.add(i)
            noteIndex(i)
            if (!memberExt.containsKey(i)) {
                rememberMember(i, name = "", ext = ext, unc = 0L, notify = false)
            }
        }

        if (idx?.complete == true &&
            idx.members.isNotEmpty() &&
            idx.members.all { it.i in onDisk }
        ) {
            complete.set(true)
        }
        onListed?.invoke(listedCount())
    }

    suspend fun ensureThrough(index: Int) {
        // Fast path: already cached — no native open, no lock contention with skip walks.
        if (index in onDisk) return
        // Need solid session only when extracting or skip-walking past cache.
        nativeOpener?.invoke()
        mutex.withLock {
            error.throwIfAny()
            throwIfAborted()
            currentCoroutineContext().ensureActive()
            if (index in onDisk) return
            if (complete.get() && index >= listedCount()) {
                error("Page $index past end (${listedCount()})")
            }
            var extractedAny = false
            var listedDirty = false
            while (index !in onDisk && !complete.get()) {
                throwIfAborted()
                currentCoroutineContext().ensureActive()
                error.throwIfAny()
                val n = solidNextPlayable()
                when {
                    n < 0 -> {
                        if (n == -1) {
                            complete.set(true)
                            persistIndex(complete = true)
                        } else {
                            error.fail(IllegalStateException("solidNextPlayable failed ($n)"))
                            error.throwIfAny()
                        }
                        break
                    }
                    n in onDisk -> {
                        // Already extracted last session — advance solid cursor only.
                        if (!memberExt.containsKey(n)) {
                            val ext = solidCurrentExtension().ifBlank { "bin" }
                            rememberMember(
                                n,
                                name = solidCurrentName(),
                                ext = ext,
                                unc = solidCurrentUncSize(),
                            )
                            listedDirty = true
                        }
                        if (!solidSkipCurrent()) {
                            error.fail(IllegalStateException("solidSkipCurrent failed at $n"))
                            error.throwIfAny()
                        }
                        runCatching { onPageReady?.invoke(n) }
                    }
                    else -> {
                        val ext = solidCurrentExtension().ifBlank { "bin" }
                        val name = solidCurrentName()
                        val unc = solidCurrentUncSize()
                        if (!memberExt.containsKey(n)) {
                            rememberMember(n, name, ext, unc)
                            listedDirty = true
                        }
                        extractCurrentToCache(n, ext, expectedSize = unc)
                        onDisk.add(n)
                        noteIndex(n)
                        extractedAny = true
                        runCatching { onPageReady?.invoke(n) }
                        // Keep index.json current so kill/exit still resumes half-cache.
                        if (n % 8 == 0) {
                            persistIndex(complete = false, async = true)
                        }
                    }
                }
            }
            if (aborted.get()) throw CancellationException("Solid extract aborted")
            if (index !in onDisk && complete.get()) {
                error("Page $index not in archive")
            }
            if (extractedAny || listedDirty || complete.get()) {
                persistIndex(complete = complete.get(), async = !complete.get())
            }
        } // mutex.withLock
    }

    private fun rememberMember(
        i: Int,
        name: String,
        ext: String,
        unc: Long,
        notify: Boolean = true,
    ) {
        val m = SolidExtractCache.Member(i = i, name = name, ext = ext, uncSize = unc)
        // Replace prior entry for same i if any (seed + solid rediscovery).
        members.removeAll { it.i == i }
        members.add(m)
        memberExt[i] = ext
        if (unc > 0L) memberUnc[i] = unc
        noteIndex(i)
        if (notify) onListed?.invoke(listedCount())
    }

    private fun throwIfAborted() {
        if (aborted.get()) throw CancellationException("Solid extract aborted")
    }

    fun persistIndex(complete: Boolean, async: Boolean = false) {
        // Snapshot ConcurrentHashMaps — live key iteration races with extract/seed
        // and has thrown NoSuchElementException on Android EntryIterator.
        val extSnap = HashMap(memberExt)
        val uncSnap = HashMap(memberUnc)
        val memberSnap = members.toList()
        val list = extSnap.keys.sorted().map { i ->
            memberSnap.firstOrNull { it.i == i }
                ?: SolidExtractCache.Member(
                    i = i,
                    name = "",
                    ext = extSnap[i] ?: "bin",
                    uncSize = uncSnap[i] ?: 0L,
                )
        }
        val index = SolidExtractCache.Index(
            cacheKey = cacheKey,
            remoteSize = remoteSize,
            format = "solid",
            complete = complete,
            members = list,
        )
        runCatching {
            if (async) {
                SolidExtractCache.saveIndexAsync(index)
            } else {
                SolidExtractCache.saveIndex(index)
            }
        }.onFailure { logcat(it) }
    }

    /**
     * Extract current solid member to a temp file; publish only if complete.
     * Reader exit closes the network mid-[solidExtractCurrentToFd] — native may still
     * return success with a truncated body; we refuse to rename half images into cache.
     */
    private fun extractCurrentToCache(index: Int, ext: String, expectedSize: Long) {
        throwIfAborted()
        val dest = SolidExtractCache.pagePath(cacheKey, index, ext)
        val destFile = File(dest.toString())
        File(dest.parent!!.toString()).mkdirs()
        val tmp = File("$dest.tmp.${System.nanoTime()}")
        try {
            ParcelFileDescriptor.open(
                tmp,
                ParcelFileDescriptor.MODE_WRITE_ONLY or
                    ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE,
            ).use { pfd ->
                val ok = try {
                    solidExtractCurrentToFd(pfd.fd)
                } catch (e: ArchiveStreamClosedException) {
                    throw CancellationException("Solid extract aborted during page $index", e)
                } catch (e: IOException) {
                    // source.close under JNI often surfaces as generic read error before
                    // closed flag is visible; abort still maps to cancel.
                    if (aborted.get()) {
                        throw CancellationException("Solid extract aborted during page $index", e)
                    }
                    throw e
                }
                // Abort / source.close() during extract: discard even if JNI returned true.
                if (aborted.get()) {
                    throw CancellationException("Solid extract aborted during page $index")
                }
                if (!ok) error("solidExtractCurrentToFd failed at $index")
            }
            if (aborted.get()) {
                throw CancellationException("Solid extract aborted during page $index")
            }
            val expect = expectedSize.takeIf { it > 0L }
                ?: memberUnc[index]?.takeIf { it > 0L }
                ?: 0L
            if (!CachePagePublish.publishTmp(
                    tmp = tmp,
                    dest = destFile,
                    expectedSize = expect,
                    ext = ext,
                )
            ) {
                destFile.delete()
                error("Incomplete solid page $index (truncated or bad header)")
            }
        } catch (e: Throwable) {
            // Never leave a partial final path after cancel / failure.
            if (destFile.exists() &&
                !CachePagePublish.isCompleteCachedFile(
                    destFile,
                    expectedSize = expectedSize,
                    ext = ext,
                )
            ) {
                destFile.delete()
            }
            if (tmp.exists()) tmp.delete()
            throw e
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}

private class AtomicReferenceError {
    private val ref = AtomicReference<Throwable?>(null)
    fun fail(t: Throwable) {
        ref.compareAndSet(null, t)
    }
    fun throwIfAny() {
        ref.get()?.let { throw it }
    }
}
