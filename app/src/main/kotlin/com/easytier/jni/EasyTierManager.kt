package com.easytier.jni

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ehviewer.core.util.logcat
import logcat.LogPriority
import org.json.JSONException
import org.json.JSONObject

/**
 * Runs an EasyTier network instance and keeps the [EasyTierVpnService] in sync with
 * virtual IP / proxy CIDR topology changes.
 */
class EasyTierManager(
    context: Context,
    private val instanceName: String,
    private val networkConfig: String,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isRunning = false

    @Volatile
    private var currentIpv4: String? = null

    @Volatile
    private var currentProxyCidrs: List<String> = emptyList()

    @Volatile
    var latestNetworkInfoJson: String? = null
        private set

    val running: Boolean
        get() = isRunning

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                monitorNetworkStatus()
                handler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }
    }

    fun start(): Boolean {
        if (isRunning) return true
        if (!EasyTierJNI.ensureLoaded()) {
            logcat(TAG, LogPriority.ERROR) {
                "Native load failed: ${EasyTierJNI.libraryLoadError()}"
            }
            return false
        }
        return try {
            val rc = EasyTierJNI.runNetworkInstance(networkConfig)
            if (rc == 0) {
                isRunning = true
                logcat(TAG) { "EasyTier instance started: $instanceName" }
                handler.post(monitorRunnable)
                true
            } else {
                logcat(TAG, LogPriority.ERROR) {
                    "runNetworkInstance failed ($rc): ${EasyTierJNI.getLastError()}"
                }
                false
            }
        } catch (e: Exception) {
            logcat(TAG, LogPriority.ERROR) { "start exception: ${e.message}" }
            false
        }
    }

    fun stop() {
        if (!isRunning && latestNetworkInfoJson == null) {
            // Still try to stop VPN / native in case of partial start.
        }
        isRunning = false
        handler.removeCallbacks(monitorRunnable)
        try {
            EasyTierVpnService.stop(appContext)
            if (EasyTierJNI.isLibraryLoaded()) {
                EasyTierJNI.stopAllInstances()
            }
            latestNetworkInfoJson = null
            currentIpv4 = null
            currentProxyCidrs = emptyList()
            logcat(TAG) { "EasyTier instance stopped: $instanceName" }
        } catch (e: Exception) {
            logcat(TAG, LogPriority.ERROR) { "stop exception: ${e.message}" }
        }
    }

    private fun monitorNetworkStatus() {
        try {
            val infosJson = EasyTierJNI.collectNetworkInfos()
            latestNetworkInfoJson = infosJson

            if (infosJson.isNullOrEmpty()) {
                if (currentIpv4 != null) {
                    logcat(TAG, LogPriority.WARN) { "Empty network info; stopping VPN" }
                    EasyTierVpnService.stop(appContext)
                    currentIpv4 = null
                    currentProxyCidrs = emptyList()
                }
                return
            }

            var newIpv4: String? = null
            val newProxyCidrs = ArrayList<String>()

            try {
                val root = JSONObject(infosJson)
                val instance = resolveInstanceInfo(root)
                if (instance == null) {
                    if (currentIpv4 != null) {
                        EasyTierVpnService.stop(appContext)
                        currentIpv4 = null
                        currentProxyCidrs = emptyList()
                    }
                    return
                }

                val myNodeInfo = instance.optJSONObject("my_node_info")
                if (myNodeInfo != null) {
                    val virtualIpv4 = myNodeInfo.optJSONObject("virtual_ipv4")
                    if (virtualIpv4 != null) {
                        val myAddrInt = virtualIpv4.getJSONObject("address").getInt("addr")
                        val myPrefix = virtualIpv4.getInt("network_length")
                        val myIp = ipFromInt(myAddrInt)
                        newIpv4 = "$myIp/$myPrefix"
                    }
                }

                val routes = instance.optJSONArray("routes")
                if (routes != null) {
                    for (i in 0 until routes.length()) {
                        val route = routes.getJSONObject(i)
                        val proxyCidrsArray = route.optJSONArray("proxy_cidrs")
                        if (proxyCidrsArray != null) {
                            for (j in 0 until proxyCidrsArray.length()) {
                                newProxyCidrs.add(proxyCidrsArray.getString(j))
                            }
                        }
                    }
                }
            } catch (e: JSONException) {
                logcat(TAG, LogPriority.ERROR) { "Parse network info failed: ${e.message}" }
                if (currentIpv4 != null) {
                    EasyTierVpnService.stop(appContext)
                    currentIpv4 = null
                    currentProxyCidrs = emptyList()
                }
                return
            }

            val ipv4Changed = currentIpv4 != newIpv4
            val proxyCidrsChanged = newProxyCidrs != currentProxyCidrs

            if (ipv4Changed || proxyCidrsChanged) {
                logcat(TAG) { "Topology change; restarting VPN ($newIpv4, ${newProxyCidrs.size} cidrs)" }
                currentIpv4 = newIpv4
                currentProxyCidrs = ArrayList(newProxyCidrs)
                if (newIpv4 != null) {
                    EasyTierVpnService.stop(appContext)
                    EasyTierVpnService.start(appContext, newIpv4, newProxyCidrs, instanceName)
                } else {
                    EasyTierVpnService.stop(appContext)
                }
            }
        } catch (e: Exception) {
            logcat(TAG, LogPriority.ERROR) { "Monitor error: ${e.message}" }
            latestNetworkInfoJson = null
            if (currentIpv4 != null) {
                EasyTierVpnService.stop(appContext)
                currentIpv4 = null
                currentProxyCidrs = emptyList()
            }
        }
    }

    private fun resolveInstanceInfo(root: JSONObject): JSONObject? {
        val instances = root.optJSONObject("map") ?: return null
        instances.optJSONObject(instanceName)?.let { return it }
        val keys = instances.keys()
        while (keys.hasNext()) {
            val fallbackName = keys.next()
            val fallback = instances.optJSONObject(fallbackName)
            if (fallback != null) {
                logcat(TAG, LogPriority.WARN) {
                    "Instance '$instanceName' missing; using '$fallbackName'"
                }
                return fallback
            }
        }
        return null
    }

    private fun ipFromInt(addr: Int): String =
        "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"

    companion object {
        private const val TAG = "EasyTierManager"
        private const val MONITOR_INTERVAL_MS = 3000L
    }
}
