package com.easytier.jni

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.ehviewer.core.util.logcat
import java.io.IOException
import logcat.LogPriority

/**
 * Split-tunnel VpnService for EasyTier (this app only; virtual subnet + proxy CIDRs).
 *
 * Stop must close the TUN [ParcelFileDescriptor] via [ACTION_STOP] — that is what clears
 * the system VPN indicator. Prefer [stopSelf] with startId so an older STOP cannot cancel
 * a newer START. Topology updates call [start] again to replace the TUN without a full
 * process-level [Context.stopService].
 */
class EasyTierVpnService : VpnService() {

    private val lock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null

    /** Bumped to cancel the current setup/hold thread. */
    private var sessionId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            logcat(TAG) { "STOP startId=$startId" }
            cancelSessionAndCloseTun()
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

        val mySession: Int
        synchronized(lock) {
            sessionId += 1
            mySession = sessionId
            vpnThread?.interrupt()
            vpnThread = null
            closeTunLocked()
        }

        val thread = Thread(
            { runSession(mySession, ipv4Address, proxyCidrs, instanceName) },
            "EasyTierVpn-$mySession",
        )
        synchronized(lock) {
            if (mySession != sessionId) return START_NOT_STICKY
            vpnThread = thread
        }
        thread.start()
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
                if (mySession != sessionId) {
                    closeQuietly(established)
                    return
                }
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
            // Superseded sessions must not close a newer TUN.
            synchronized(lock) {
                if (mySession == sessionId) {
                    closeTunLocked()
                    if (vpnThread === Thread.currentThread()) vpnThread = null
                }
            }
        }
    }

    private fun isActiveSession(mySession: Int): Boolean =
        synchronized(lock) { mySession == sessionId }

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
        logcat(TAG) { "TUN closed" }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (e: IOException) {
            logcat(TAG, LogPriority.ERROR) { "Close TUN: ${e.message}" }
        }
    }

    override fun onDestroy() {
        cancelSessionAndCloseTun()
        super.onDestroy()
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
        private const val ACTION_START = "com.easytier.jni.EasyTierVpnService.START"
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
            app.startService(
                Intent(app, EasyTierVpnService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_IPV4_ADDRESS, ipv4)
                    putStringArrayListExtra(EXTRA_PROXY_CIDRS, ArrayList(proxyCidrs))
                    putExtra(EXTRA_INSTANCE_NAME, instanceName)
                },
            )
        }

        /** Closes the TUN (clears system VPN) and stops the service. */
        fun stop(context: Context) {
            val app = context.applicationContext
            try {
                app.startService(
                    Intent(app, EasyTierVpnService::class.java).apply { action = ACTION_STOP },
                )
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "startService(STOP) failed: ${e.message}" }
            }
        }
    }
}
