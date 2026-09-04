package com.hippo.ehviewer.provider

import android.content.Context
import android.os.Process
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.webdav.WebDavClient
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * Slim foreground service for external Fuse **and** loopback HTTP.
 *
 * FGS is process rank only: HTTP listener + session/token map stay in RAM. No wake lock,
 * no idle-ping, no sticky TCP while idle. Resume is a new Range / URI open — HTTP or
 * streamdoc starts SMB then.
 *
 * **Playing** (proxy FD or HTTP body): keep the process so the connection can work.
 * **Idle grants:** keep FGS so loopback + session survive freeze.
 * **Screen off:** drop sticky unless something is playing (background playback);
 * always drop **browse** SMB/WebDAV pools so keep-alive does not chatter through
 * VPN/EasyTier while the display is off.
 *
 * Limited (default): idle grants age out after 20 minutes, then FGS stops.
 * Unlimited (Advanced): grants stay; FGS stays until Recents swipe.
 *
 * **Recents swipe:** [shutdownAndKillProcess] — user kill means die.
 */
object StreamKeepAlivePolicy {
    /** Idle token / HTTP session age when limited. */
    const val LIMITED_TOKEN_MAX_AGE_MS = 20L * 60L * 1000L

    /** Brief pause so session replace does not flicker the notification. */
    const val FGS_STOP_GRACE_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconcileLock = Any()
    private var stopJob: Job? = null

    fun unlimited(): Boolean = Settings.streamKeepAliveUnlimited.value

    /**
     * Idle grant lifetime. Null = never age out (unlimited).
     * Callers must skip age-prune when this is null.
     */
    fun tokenMaxAgeMs(): Long? = if (unlimited()) null else LIMITED_TOKEN_MAX_AGE_MS

    fun isPlaying(): Boolean = StreamDocumentRegistry.networkOpenCount() > 0 ||
        ExternalHttpStreamServer.networkActivityCount() > 0

    fun hasIdleGrant(): Boolean = StreamDocumentRegistry.networkTokenCount() > 0 ||
        ExternalHttpStreamServer.networkSessionCount() > 0

    fun shouldHoldFgs(): Boolean = isPlaying() || hasIdleGrant()

    fun onUnlimitedChanged() {
        StreamDocumentRegistry.pruneStale()
        ExternalHttpStreamServer.pruneStale()
        reconcileFgs(appCtx)
    }

    /** Start FGS while a grant or transfer exists; stop shortly after the last one is gone. */
    fun reconcileFgs(context: Context = appCtx) {
        val app = context.applicationContext
        synchronized(reconcileLock) {
            if (shouldHoldFgs()) {
                stopJob?.cancel()
                stopJob = null
                StreamKeepAliveService.start(app)
            } else {
                stopJob?.cancel()
                stopJob = scope.launch {
                    delay(FGS_STOP_GRACE_MS)
                    synchronized(reconcileLock) {
                        if (!shouldHoldFgs()) {
                            StreamKeepAliveService.stop(app)
                            stopJob = null
                        }
                    }
                }
            }
        }
    }

    /**
     * One-line dump of stream keep-alive / SMB sticky residual work.
     * Use on screen-off and before process kill to see what is still alive.
     */
    fun runtimeSnapshot(): String {
        val stickyInUse = SmbGateway.httpStickyPoolSize() - SmbGateway.httpStickyPoolAvailable()
        return buildString {
            append("unlimited=").append(unlimited())
            append(" fgs=").append(StreamKeepAliveService.isRunning())
            append(" playing=").append(isPlaying())
            append(" streamdocFds=").append(StreamDocumentRegistry.networkOpenCount())
            append(" streamdocTokens=").append(StreamDocumentRegistry.networkTokenCount())
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
        runCatching { ExternalHttpStreamServer.relieveSmbPoolPressure() }
        runCatching { SmbGateway.dropStickySessions(reason) }
        runCatching { WebDavClient.resetStickyClient() }
    }

    fun onScreenOff() {
        logcat("StreamKeepAlive") { "screen_off before: ${runtimeSnapshot()}" }
        if (isPlaying()) {
            // Background playback: leave the live sticky SMB/WebDAV handle alone.
            logcat("StreamKeepAlive") { "screen_off: playing — sticky kept" }
        } else {
            dropStickyNetwork("screen_off")
        }
        // Browse keep-alive is for interactive listing/reader only. Drop it on screen-off
        // even while playing so idle host pools do not ping through EasyTier/VPN.
        runCatching { SmbGateway.dropBrowseSessions("screen_off") }
        runCatching { WebDavClient.dropBrowseClient("screen_off") }
        logcat("StreamKeepAlive") { "screen_off after: ${runtimeSnapshot()}" }
    }

    fun onScreenOn(context: Context) {
        logcat("StreamKeepAlive") { "screen_on: ${runtimeSnapshot()}" }
        if (shouldHoldFgs()) StreamKeepAliveService.start(context)
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
        runCatching { SmbGateway.dropBrowseSessions("recents") }
        runCatching { WebDavClient.resetClient() }
        runCatching { WebDavClient.resetStickyClient() }
        // Hard exit: non-daemon coroutine pools would otherwise keep the process.
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
