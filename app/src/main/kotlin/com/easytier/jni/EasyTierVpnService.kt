package com.easytier.jni

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.ehviewer.core.util.logcat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import logcat.LogPriority

/**
 * Split-tunnel VpnService for EasyTier.
 *
 * Only the app package is allowed through the TUN ([Builder.addAllowedApplication]), and
 * routes are limited to the virtual subnet + peer proxy CIDRs — not a full-device tunnel.
 * Android still permits only one active VpnService system-wide.
 *
 * Stop path must close the [ParcelFileDescriptor] (and [stopSelf]); otherwise the system
 * VPN indicator stays on even after EasyTier is stopped in-app.
 */
class EasyTierVpnService : VpnService() {

    private val lock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var instanceName: String? = null

    /** Generation token so a superseded setup thread cannot keep / re-open the TUN. */
    private var setupGeneration = 0
    private val keepAlive = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            logcat(TAG) { "Stop command; tearing down VPN" }
            teardownAndStopSelf()
            return START_NOT_STICKY
        }

        val ipv4Address = intent.getStringExtra(EXTRA_IPV4_ADDRESS)
        val proxyCidrs = intent.getStringArrayListExtra(EXTRA_PROXY_CIDRS).orEmpty()
        val name = intent.getStringExtra(EXTRA_INSTANCE_NAME)
        if (ipv4Address == null || name == null) {
            logcat(TAG, LogPriority.ERROR) { "Missing ipv4/instance; stop" }
            teardownAndStopSelf()
            return START_NOT_STICKY
        }

        val generation: Int
        synchronized(lock) {
            // Drop any previous TUN before establishing a new one.
            closeTunLocked()
            keepAlive.set(false)
            vpnThread?.interrupt()
            vpnThread = null
            setupGeneration += 1
            generation = setupGeneration
            instanceName = name
            keepAlive.set(true)
        }

        val thread = Thread({
            setupVpnInterface(generation, ipv4Address, proxyCidrs, name)
        }, "EasyTierVpnSetup-$generation")

        synchronized(lock) {
            if (generation != setupGeneration) {
                // Superseded before start.
                return START_NOT_STICKY
            }
            vpnThread = thread
        }
        thread.start()
        return START_NOT_STICKY
    }

    private fun setupVpnInterface(
        generation: Int,
        ipv4Address: String,
        proxyCidrs: List<String>,
        name: String,
    ) {
        var established: ParcelFileDescriptor? = null
        try {
            if (!isCurrentGeneration(generation) || !keepAlive.get()) return

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

            if (!isCurrentGeneration(generation) || !keepAlive.get()) return

            established = builder.establish()
            if (established == null) {
                logcat(TAG, LogPriority.ERROR) { "establish() returned null" }
                return
            }

            synchronized(lock) {
                if (generation != setupGeneration || !keepAlive.get()) {
                    // Stop won the race — discard this TUN immediately.
                    closeQuietly(established)
                    established = null
                    return
                }
                vpnInterface = established
            }

            if (!EasyTierJNI.ensureLoaded()) {
                logcat(TAG, LogPriority.ERROR) { "Native lib not loaded: ${EasyTierJNI.libraryLoadError()}" }
                return
            }
            val fd = established.fd
            val rc = EasyTierJNI.setTunFd(name, fd)
            logcat(TAG) { "setTunFd($name, $fd) = $rc (gen=$generation)" }

            // Hold the service alive while this generation owns the TUN.
            while (keepAlive.get() && isCurrentGeneration(generation)) {
                try {
                    Thread.sleep(60_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (t: Throwable) {
            logcat(TAG, LogPriority.ERROR) { "VPN interface error: ${t.message}" }
        } finally {
            synchronized(lock) {
                if (generation == setupGeneration) {
                    closeTunLocked()
                    if (!keepAlive.get()) {
                        stopSelf()
                    }
                } else {
                    // Older generation — only close the fd we may still hold locally.
                    if (established != null && vpnInterface !== established) {
                        closeQuietly(established)
                    }
                }
            }
            logcat(TAG) { "Setup thread exit gen=$generation" }
        }
    }

    private fun isCurrentGeneration(generation: Int): Boolean =
        synchronized(lock) { generation == setupGeneration }

    private fun teardownAndStopSelf() {
        synchronized(lock) {
            keepAlive.set(false)
            setupGeneration += 1 // invalidate any in-flight setup
            vpnThread?.interrupt()
            vpnThread = null
            closeTunLocked()
        }
        stopSelf()
        logcat(TAG) { "VPN torn down and stopSelf()" }
    }

    /** Caller must hold [lock]. Closing the PFD is what clears the system VPN status. */
    private fun closeTunLocked() {
        val iface = vpnInterface
        vpnInterface = null
        if (iface != null) {
            closeQuietly(iface)
            logcat(TAG) { "TUN closed" }
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (e: IOException) {
            logcat(TAG, LogPriority.ERROR) { "Close VPN interface: ${e.message}" }
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            keepAlive.set(false)
            setupGeneration += 1
            vpnThread?.interrupt()
            vpnThread = null
            closeTunLocked()
        }
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
                putExtra(EXTRA_IPV4_ADDRESS, ipv4)
                putStringArrayListExtra(EXTRA_PROXY_CIDRS, ArrayList(proxyCidrs))
                putExtra(EXTRA_INSTANCE_NAME, instanceName)
            }
            app.startService(intent)
        }

        /**
         * Tear down the system VPN: deliver a stop command to the service (closes TUN fd)
         * and request [Context.stopService] so the service is destroyed.
         */
        fun stop(context: Context) {
            val app = context.applicationContext
            val stopIntent = Intent(app, EasyTierVpnService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                // Preferred: onStartCommand(ACTION_STOP) closes the TUN then stopSelf().
                app.startService(stopIntent)
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "startService(STOP) failed: ${e.message}" }
            }
            try {
                // Ensures service destruction if startService path is a no-op (not running).
                app.stopService(Intent(app, EasyTierVpnService::class.java))
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "stopService failed: ${e.message}" }
            }
        }
    }
}
