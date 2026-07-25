package com.easytier.jni

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.ehviewer.core.util.logcat
import java.io.IOException
import logcat.LogPriority

/**
 * Split-tunnel VpnService for EasyTier.
 *
 * Only this app is allowed on the TUN; routes are virtual subnet + proxy CIDRs.
 *
 * Lifecycle notes (regression fixes):
 * - Topology updates must **replace the TUN in-process** without [stopSelf], otherwise a
 *   racing [stopSelf]/ [Context.stopService] drops the system VPN a few seconds after
 *   connect (when peers / proxy_cidrs first appear).
 * - User stop uses explicit [ACTION_STOP] + [stopSelf] with the matching startId so a
 *   later START is not cancelled. Closing the [ParcelFileDescriptor] clears the system badge.
 */
class EasyTierVpnService : VpnService() {

    private val lock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    /** Bumped to cancel an in-flight setup thread without destroying the service. */
    private var sessionId = 0

    @Volatile
    private var userStopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            logcat(TAG) { "ACTION_STOP startId=$startId" }
            userStopping = true
            cancelSessionAndCloseTun()
            // stopSelf(startId): only stops if no newer startService arrived after this STOP.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val ipv4Address = intent.getStringExtra(EXTRA_IPV4_ADDRESS)
        val proxyCidrs = intent.getStringArrayListExtra(EXTRA_PROXY_CIDRS).orEmpty()
        val instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME)
        if (ipv4Address.isNullOrBlank() || instanceName.isNullOrBlank()) {
            logcat(TAG, LogPriority.ERROR) { "Missing ipv4/instance; ignoring" }
            return START_NOT_STICKY
        }

        userStopping = false
        val mySession: Int
        synchronized(lock) {
            // Invalidate previous setup thread; keep service alive.
            sessionId += 1
            mySession = sessionId
            vpnThread?.interrupt()
            vpnThread = null
            // Close old TUN only after we are about to build a new one on the worker thread
            // so the system badge does not flicker off if establish fails later — still
            // close before establish to avoid two concurrent interfaces.
            closeTunLocked()
        }

        val thread = Thread({
            runSession(mySession, ipv4Address, proxyCidrs, instanceName)
        }, "EasyTierVpn-$mySession")

        synchronized(lock) {
            if (mySession != sessionId || userStopping) return START_NOT_STICKY
            vpnThread = thread
        }
        thread.start()
        // Sticky is unnecessary; EasyTierManager re-starts VPN when topology is known.
        return START_NOT_STICKY
    }

    private fun runSession(
        mySession: Int,
        ipv4Address: String,
        proxyCidrs: List<String>,
        instanceName: String,
    ) {
        try {
            if (!isActiveSession(mySession)) return

            val addressInfo = parseCidr(ipv4Address, defaultPrefix = 24)
            val builder = Builder()
                .setSession("EasyTier")
                .setMtu(1400)
                .addAddress(addressInfo.ip, addressInfo.networkLength)
                .addDnsServer("223.5.5.5")

            val networkBase = networkAddress(addressInfo.ip, addressInfo.networkLength)
            builder.addRoute(networkBase, addressInfo.networkLength)
            logcat(TAG) { "Virtual route $networkBase/${addressInfo.networkLength}" }

            try {
                builder.addAddress("fd00::1", 128)
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "IPv6 address failed: ${e.message}" }
            }

            for (cidr in proxyCidrs) {
                try {
                    val routeInfo = parseCidr(cidr, defaultPrefix = 24)
                    builder.addRoute(routeInfo.ip, routeInfo.networkLength)
                    logcat(TAG) { "Proxy route ${routeInfo.ip}/${routeInfo.networkLength}" }
                } catch (e: Exception) {
                    logcat(TAG, LogPriority.WARN) { "Bad proxy CIDR $cidr: ${e.message}" }
                }
            }

            try {
                builder.addAllowedApplication(packageName)
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "addAllowedApplication failed: ${e.message}" }
            }

            if (!isActiveSession(mySession)) return

            val established = builder.establish()
            if (established == null) {
                logcat(TAG, LogPriority.ERROR) { "establish() returned null" }
                return
            }

            synchronized(lock) {
                if (mySession != sessionId || userStopping) {
                    closeQuietly(established)
                    return
                }
                // Replace any residual interface (should already be null).
                closeTunLocked()
                vpnInterface = established
            }

            if (!EasyTierJNI.ensureLoaded()) {
                logcat(TAG, LogPriority.ERROR) { "Native lib not loaded: ${EasyTierJNI.libraryLoadError()}" }
                synchronized(lock) {
                    if (mySession == sessionId) closeTunLocked()
                }
                return
            }

            val fd = established.fd
            val rc = EasyTierJNI.setTunFd(instanceName, fd)
            logcat(TAG) { "setTunFd($instanceName, $fd) = $rc session=$mySession" }

            // Hold this session until cancelled (topology replace or user stop).
            while (isActiveSession(mySession)) {
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (t: Throwable) {
            logcat(TAG, LogPriority.ERROR) { "VPN session error: ${t.message}" }
        } finally {
            // Only the active session closes the TUN here. A superseded session must not
            // close a newer session's interface.
            synchronized(lock) {
                if (mySession == sessionId) {
                    closeTunLocked()
                    vpnThread = null
                }
            }
            logcat(TAG) { "Session $mySession exited" }
        }
    }

    private fun isActiveSession(mySession: Int): Boolean =
        synchronized(lock) { mySession == sessionId && !userStopping }

    private fun cancelSessionAndCloseTun() {
        synchronized(lock) {
            sessionId += 1
            vpnThread?.interrupt()
            vpnThread = null
            closeTunLocked()
        }
    }

    private fun closeTunLocked() {
        val iface = vpnInterface ?: return
        vpnInterface = null
        closeQuietly(iface)
        logcat(TAG) { "TUN closed (system VPN should drop if no other iface)" }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (e: IOException) {
            logcat(TAG, LogPriority.ERROR) { "Close TUN: ${e.message}" }
        }
    }

    override fun onDestroy() {
        userStopping = true
        cancelSessionAndCloseTun()
        super.onDestroy()
        logcat(TAG) { "onDestroy" }
    }

    private data class IpAddressInfo(val ip: String, val networkLength: Int)

    private fun parseCidr(cidr: String, defaultPrefix: Int): IpAddressInfo {
        val parts = cidr.split("/")
        val ip = parts[0].trim()
        val prefix = if (parts.size > 1) parts[1].toInt() else defaultPrefix
        require(parts.size <= 2) { "Invalid CIDR: $cidr" }
        return IpAddressInfo(ip, prefix)
    }

    private fun networkAddress(ip: String, prefix: Int): String {
        val parts = ip.split(".").map { it.toInt() }
        require(parts.size == 4) { "Invalid IPv4: $ip" }
        var addr = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        addr = addr and mask
        return "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
    }

    companion object {
        private const val TAG = "EasyTierVpnService"
        const val ACTION_STOP = "com.easytier.jni.EasyTierVpnService.STOP"
        const val EXTRA_IPV4_ADDRESS = "ipv4_address"
        const val EXTRA_PROXY_CIDRS = "proxy_cidrs"
        const val EXTRA_INSTANCE_NAME = "instance_name"

        fun start(
            context: Context,
            ipv4: String,
            proxyCidrs: List<String>,
            instanceName: String,
        ) {
            val app = context.applicationContext
            val intent = Intent(app, EasyTierVpnService::class.java).apply {
                // Explicit non-STOP action so a sticky redelivery is not treated as stop.
                action = ACTION_START
                putExtra(EXTRA_IPV4_ADDRESS, ipv4)
                putStringArrayListExtra(EXTRA_PROXY_CIDRS, ArrayList(proxyCidrs))
                putExtra(EXTRA_INSTANCE_NAME, instanceName)
            }
            app.startService(intent)
        }

        /**
         * User / manager stop: deliver ACTION_STOP so the service closes the TUN (system
         * badge clears) and stops. Do **not** also call [Context.stopService] here — that
         * races with a follow-up [start] on topology change and drops a fresh VPN session.
         */
        fun stop(context: Context) {
            val app = context.applicationContext
            val stopIntent = Intent(app, EasyTierVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                app.startService(stopIntent)
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "startService(STOP) failed: ${e.message}" }
            }
        }

        private const val ACTION_START = "com.easytier.jni.EasyTierVpnService.START"
    }
}
