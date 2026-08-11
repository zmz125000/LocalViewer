package com.hippo.ehviewer.provider

import android.content.Context
import android.os.Process
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.webdav.WebDavClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import splitties.init.appCtx

/**
 * Battery vs reliability knobs for external Fuse **and** loopback HTTP streaming.
 *
 * **Streamdoc (Fuse):** live proxy FDs need sticky + FGS while open; tokens age out separately.
 *
 * **HTTP loopback:** session map is a **stateless token** (resume via Range anytime before prune).
 * Warm SMB is optional with **idle timeout** (open/close every Range is costly) — not required
 * for correctness. FGS wake lock only while a body transfer is in flight; idle FGS (grace) is
 * only process rank so the token stays in RAM + self-shutdown — no other CPU work.
 *
 * Limited (default): shorter token age + drop sticky on screen off / FGS stop.
 * Unlimited (Advanced): longer token age; streamdoc may keep sticky across screen off.
 *
 * **Recents swipe:** [shutdownAndKillProcess] — FGS alone would keep the process; user kill means die.
 */
object StreamKeepAlivePolicy {
    /** Idle token age when limited. */
    const val LIMITED_TOKEN_MAX_AGE_MS = 20L * 60L * 1000L

    /** Idle token age when unlimited (previous default). */
    const val UNLIMITED_TOKEN_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    /**
     * After last proxy FD closes, keep FGS briefly so reopen after seek/rebuffer works.
     * Same in both modes — not the long movie budget.
     */
    const val FGS_STOP_DELAY_MS = 3L * 60L * 1000L

    const val LIMITED_WAKE_LOCK_MS = 20L * 60L * 1000L
    const val UNLIMITED_WAKE_LOCK_MS = 6L * 60L * 60L * 1000L

    fun unlimited(): Boolean = Settings.streamKeepAliveUnlimited.value

    fun tokenMaxAgeMs(): Long = if (unlimited()) UNLIMITED_TOKEN_MAX_AGE_MS else LIMITED_TOKEN_MAX_AGE_MS

    fun wakeLockTimeoutMs(): Long = if (unlimited()) UNLIMITED_WAKE_LOCK_MS else LIMITED_WAKE_LOCK_MS

    fun fgsStopDelayMs(): Long = FGS_STOP_DELAY_MS

    fun dropNetworkOnScreenOff(): Boolean = !unlimited()

    /**
     * One-line dump of stream keep-alive / SMB sticky residual work.
     * Use on screen-off and before process kill to see what is still alive.
     */
    fun runtimeSnapshot(): String {
        val stickyInUse = SmbGateway.httpStickyPoolSize() - SmbGateway.httpStickyPoolAvailable()
        return buildString {
            append("unlimited=").append(unlimited())
            append(" fgs=").append(StreamKeepAliveService.isRunning())
            append(" wake=").append(StreamKeepAliveService.hasWakeLock())
            append(" streamdocFds=").append(StreamDocumentRegistry.networkOpenCount())
            append(" streamdocTokens=").append(StreamDocumentRegistry.tokenCount())
            append(" httpTransfers=").append(ExternalHttpStreamServer.networkActivityCount())
            append(" httpSessions=").append(ExternalHttpStreamServer.sessionCount())
            append(" httpWarm=").append(ExternalHttpStreamServer.warmBodyCount())
            append(" httpLiveSess=").append(ExternalHttpStreamServer.liveSocketCount())
            append(" smbStickyTcp=").append(SmbGateway.stickyConnectionCount())
            append(" smbHttpPool=").append(stickyInUse).append('/').append(SmbGateway.httpStickyPoolSize())
            append(" smbBrowseHosts=").append(SmbGateway.browsePoolHostCount())
        }
    }

    /**
     * Close sticky SMB/WebDAV transports. Live proxy FDs / in-flight HTTP Ranges stay open
     * at the app layer; next read reconnects.
     *
     * Idle HTTP warm video bodies are dropped first so dual-sticky pool permits free
     * immediately — otherwise KeepOpen can sit up to ~45s in idle-ping on a dead
     * DiskShare after [SmbGateway.dropStickySessions] and log "already been closed".
     */
    fun dropStickyNetwork(reason: String) {
        logcat("StreamKeepAlive") { "drop sticky network ($reason)" }
        runCatching { ExternalHttpStreamServer.evictAllIdleSmbBackends() }
        runCatching { SmbGateway.dropStickySessions(reason) }
        runCatching { WebDavClient.resetStickyClient() }
    }

    fun onScreenOff() {
        logcat("StreamKeepAlive") { "screen_off before: ${runtimeSnapshot()}" }
        if (dropNetworkOnScreenOff()) {
            dropStickyNetwork("screen_off")
            StreamKeepAliveService.onScreenOffConservePower()
        } else {
            // Unlimited: keep sticky TCP for external players; still log residuals.
            logcat("StreamKeepAlive") {
                "screen_off: unlimited — sticky kept; wake lock only if active transfer/FD"
            }
            // Drop wake lock when nothing is actively reading (conserve); keep FGS rank if any.
            StreamKeepAliveService.onNetworkActivityChanged()
        }
        logcat("StreamKeepAlive") { "screen_off after: ${runtimeSnapshot()}" }
    }

    fun onScreenOn(context: Context) {
        logcat("StreamKeepAlive") { "screen_on: ${runtimeSnapshot()}" }
        // Re-arm FGS / wake lock only if something is actively reading (FD or HTTP body).
        // Idle sessions alone do not restart FGS — next player Range starts it again.
        if (StreamDocumentRegistry.networkOpenCount() > 0 ||
            ExternalHttpStreamServer.networkActivityCount() > 0
        ) {
            StreamKeepAliveService.start(context)
        }
    }

    private val processExiting = AtomicBoolean(false)

    /**
     * User swiped the app away from Recents. Tear down stream infrastructure and **kill the
     * process** so FGS / loopback HTTP / sticky SMB cannot keep LocalViewer alive in the
     * background after an explicit dismiss.
     */
    fun shutdownAndKillProcess(reason: String) {
        if (!processExiting.compareAndSet(false, true)) return
        logcat("StreamKeepAlive") { "shutdownAndKillProcess ($reason) ${runtimeSnapshot()}" }
        runCatching { StreamKeepAliveService.stop(appCtx) }
        runCatching { ExternalHttpStreamServer.shutdown(reason) }
        runCatching { StreamDocumentRegistry.clearAll(reason) }
        runCatching { dropStickyNetwork(reason) }
        runCatching { SmbGateway.onAppBackgrounded() }
        runCatching { WebDavClient.resetClient() }
        runCatching { WebDavClient.resetStickyClient() }
        // Hard exit: non-daemon coroutine pools would otherwise keep the process.
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
