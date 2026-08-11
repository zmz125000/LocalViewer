package com.hippo.ehviewer.provider

import android.content.Context
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.smb.SmbGateway
import com.hippo.ehviewer.webdav.WebDavClient

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
     * Close sticky SMB/WebDAV transports. Live proxy FDs stay open; next read reconnects.
     */
    fun dropStickyNetwork(reason: String) {
        logcat("StreamKeepAlive") { "drop sticky network ($reason)" }
        runCatching { SmbGateway.dropStickySessions(reason) }
        runCatching { WebDavClient.resetStickyClient() }
    }

    fun onScreenOff() {
        if (!dropNetworkOnScreenOff()) return
        dropStickyNetwork("screen_off")
        StreamKeepAliveService.onScreenOffConservePower()
    }

    fun onScreenOn(context: Context) {
        // Re-arm FGS / wake lock only if something is actively reading (FD or HTTP body).
        // Idle sessions alone do not restart FGS — next player Range starts it again.
        if (StreamDocumentRegistry.networkOpenCount() > 0 ||
            ExternalHttpStreamServer.networkActivityCount() > 0
        ) {
            StreamKeepAliveService.start(context)
        }
    }
}
