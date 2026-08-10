package com.hippo.ehviewer.provider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ehviewer.core.i18n.R as I18nR
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.R
import com.hippo.ehviewer.ui.MainActivity

/**
 * Keeps LocalViewer unfrozen while a network [StreamDocumentProvider] proxy FD is open.
 *
 * External players stream SMB/WebDAV over Fuse in **this** process. Without a foreground
 * service the process is cached after ON_STOP, frozen after a few minutes, and reads
 * stall — playback dies and resume fails because the in-memory token / sticky session
 * is gone. Start when the first network proxy is retained; stop when the last is released.
 *
 * Android may time out a `dataSync` FGS. A timed-out service must stop promptly; the
 * existing proxy FD then fails/reopens normally instead of risking RemoteServiceException.
 */
class StreamKeepAliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        if (!promoteForeground()) {
            releaseWakeLock()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService() is asynchronous: a very short-lived external FD may
        // already be released before this callback runs. Preserve the FGS reopen grace,
        // but only keep the CPU awake while a proxy is actually open.
        if (StreamDocumentRegistry.networkOpenCount() > 0 ||
            ExternalHttpStreamServer.networkActivityCount() > 0
        ) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
        // The registry and network source are process-local. Restarting only this service
        // after process death cannot restore the URI token or an external player's FD.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val stillOpen = StreamDocumentRegistry.networkOpenCount() +
            ExternalHttpStreamServer.networkActivityCount()
        logcat("StreamKeepAlive") {
            "Foreground service timed out (type=$fgsType) openFds=$stillOpen"
        }
        // Android 15+ requires a timed-out dataSync service to stop within a few seconds.
        // Re-promotion does not reset the quota and can crash the app.
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseWakeLock()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun promoteForeground(): Boolean {
        val notification = buildNotification()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            true
        } catch (e: Throwable) {
            logcat("StreamKeepAlive", e)
            // Do not leave a startForegroundService() instance waiting for promotion.
            // That becomes a foreground-service timeout/ANR a few seconds later.
            false
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocalViewer:StreamKeepAlive",
            ).apply {
                setReferenceCounted(false)
                acquire(StreamKeepAlivePolicy.wakeLockTimeoutMs())
            }
            wakeLock = lock
        } catch (e: Throwable) {
            logcat("StreamKeepAlive", e)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) {
            runCatching { lock.release() }
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(I18nR.string.stream_keepalive_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(I18nR.string.stream_keepalive_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play_arrow_108dp)
            .setContentTitle(getString(I18nR.string.stream_keepalive_title))
            .setContentText(getString(I18nR.string.stream_keepalive_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "stream_keepalive"
        private const val NOTIFICATION_ID = 0x535444 // "STD"

        /** Live instance while FGS is running (for screen-off wake-lock release). */
        @Volatile
        private var instance: StreamKeepAliveService? = null

        /** Start or refresh the keep-alive for a live network proxy FD. */
        fun start(context: Context) {
            val app = context.applicationContext
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, StreamKeepAliveService::class.java),
                )
            } catch (e: Throwable) {
                // A viewer can reopen the URI after LocalViewer has gone to the background,
                // where Android may reject a new FGS start. Keep proxy I/O working and log it;
                // a still-running grace-period service is unaffected.
                logcat("StreamKeepAlive", e)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.stopService(Intent(app, StreamKeepAliveService::class.java))
            }.onFailure { logcat("StreamKeepAlive", it) }
        }

        /** Keep the FGS grace period, but never hold the CPU awake with no live proxy FD. */
        fun onNetworkIdle() {
            instance?.releaseWakeLock()
        }

        /**
         * Limited mode + screen off: drop partial wake lock so the device can sleep.
         * FGS notification stays if FDs are open so process rank stays elevated for Fuse.
         * [start] re-acquires on screen on / next retain.
         */
        fun onScreenOffConservePower() {
            instance?.releaseWakeLock()
        }
    }
}
