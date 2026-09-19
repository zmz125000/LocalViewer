package com.hippo.ehviewer.ui.main

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** IPv4 hosts other devices can reach for LAN HTTP share. */
object LanAddresses {
    fun preferredHost(): String? = preferredIpv4(enumerateIpv4())

    fun enumerateIpv4(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.toList() }
    }.getOrDefault(emptyList())

    fun preferredIpv4(candidates: List<InetAddress>): String? {
        val ipv4 = candidates.filterIsInstance<Inet4Address>().filter { addr ->
            !addr.isLoopbackAddress && !addr.isAnyLocalAddress && !addr.isLinkLocalAddress
        }
        return ipv4.firstOrNull { it.isSiteLocalAddress }?.hostAddress
            ?: ipv4.firstOrNull()?.hostAddress
    }
}
