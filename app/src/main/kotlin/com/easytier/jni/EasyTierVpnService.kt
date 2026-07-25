package com.easytier.jni

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.ehviewer.core.util.logcat
import java.io.IOException
import logcat.LogPriority

/**
 * Split-tunnel VpnService for EasyTier.
 *
 * Only the app package is allowed through the TUN ([Builder.addAllowedApplication]), and
 * routes are limited to the virtual subnet + peer proxy CIDRs — not a full-device tunnel.
 * Android still permits only one active VpnService system-wide.
 */
class EasyTierVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var isRunning = false
    private var instanceName: String? = null
    private var vpnThread: Thread? = null

    private val stopVpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == stopAction(context)) {
                logcat(TAG) { "Stop broadcast received; tearing down VPN" }
                cleanupAndStop()
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(stopAction(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopVpnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopVpnReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == stopAction(this)) {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        vpnThread = Thread({
            try {
                val ipv4Address = intent.getStringExtra(EXTRA_IPV4_ADDRESS)
                val proxyCidrs = intent.getStringArrayListExtra(EXTRA_PROXY_CIDRS)
                instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME)

                if (ipv4Address == null || instanceName == null) {
                    cleanupAndStop()
                    return@Thread
                }

                setupVpnInterface(ipv4Address, proxyCidrs.orEmpty())
            } catch (t: Throwable) {
                logcat(TAG, LogPriority.ERROR) { "VPN setup thread failed: ${t.message}" }
                cleanupAndStop()
            }
        }, "EasyTierVpnSetup")

        vpnThread!!.start()
        return START_NOT_STICKY
    }

    private fun setupVpnInterface(ipv4Address: String, proxyCidrs: List<String>) {
        try {
            val addressInfo = parseCidr(ipv4Address, defaultPrefix = 24)

            val builder = Builder()
                .setSession("EasyTier")
                .setMtu(1400)
                .addAddress(addressInfo.ip, addressInfo.networkLength)
                .addDnsServer("223.5.5.5")

            // Route the virtual subnet so peer virtual IPs hit the TUN (needed for SMB).
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

            // Only this app — other apps keep normal networking (still occupies the VPN slot).
            try {
                builder.addAllowedApplication(packageName)
            } catch (e: Exception) {
                logcat(TAG, LogPriority.WARN) { "addAllowedApplication failed: ${e.message}" }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                logcat(TAG, LogPriority.ERROR) { "establish() returned null" }
                return
            }
            isRunning = true

            val name = instanceName!!
            val fd = vpnInterface!!.fd
            if (!EasyTierJNI.ensureLoaded()) {
                logcat(TAG, LogPriority.ERROR) { "Native lib not loaded: ${EasyTierJNI.libraryLoadError()}" }
                return
            }
            val rc = EasyTierJNI.setTunFd(name, fd)
            logcat(TAG) { "setTunFd($name, $fd) = $rc" }

            while (isRunning) {
                try {
                    Thread.sleep(Long.MAX_VALUE)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        } catch (t: Throwable) {
            logcat(TAG, LogPriority.ERROR) { "VPN interface error: ${t.message}" }
        } finally {
            cleanup()
        }
    }

    private fun cleanupAndStop() {
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        if (!isRunning && vpnInterface == null) return
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            logcat(TAG, LogPriority.ERROR) { "Close VPN interface: ${e.message}" }
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(stopVpnReceiver)
        } catch (_: IllegalArgumentException) {
        }
        cleanup()
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
        const val EXTRA_IPV4_ADDRESS = "ipv4_address"
        const val EXTRA_PROXY_CIDRS = "proxy_cidrs"
        const val EXTRA_INSTANCE_NAME = "instance_name"

        fun stopAction(context: Context): String =
            "${context.packageName}.easytier.ACTION_STOP_VPN"

        fun start(
            context: Context,
            ipv4: String,
            proxyCidrs: List<String>,
            instanceName: String,
        ) {
            val intent = Intent(context, EasyTierVpnService::class.java).apply {
                putExtra(EXTRA_IPV4_ADDRESS, ipv4)
                putStringArrayListExtra(EXTRA_PROXY_CIDRS, ArrayList(proxyCidrs))
                putExtra(EXTRA_INSTANCE_NAME, instanceName)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.sendBroadcast(Intent(stopAction(context)))
        }
    }
}
