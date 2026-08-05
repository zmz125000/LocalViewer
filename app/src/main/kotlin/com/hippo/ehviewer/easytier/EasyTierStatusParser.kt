package com.hippo.ehviewer.easytier

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject

object EasyTierStatusParser {
    private const val INSTANCE_NAME = "Default"

    fun parse(
        jsonString: String?,
        strings: StatusStrings,
    ): EasyTierDisplayInfo {
        if (jsonString.isNullOrEmpty()) {
            return EasyTierDisplayInfo()
        }
        return try {
            val root = JSONObject(jsonString)
            val instance = resolveInstanceInfo(root, INSTANCE_NAME)
                ?: return EasyTierDisplayInfo(error = strings.parseError)

            val myNode = instance.getJSONObject("my_node_info")
            var myIp: String? = null
            var myPrefix = 0
            val hostname = myNode.getString("hostname")
            val version = myNode.getString("version")

            val virtualIp = myNode.optJSONObject("virtual_ipv4")?.let { virtualIpv4 ->
                myPrefix = virtualIpv4.getInt("network_length")
                myIp = ipFromInt(virtualIpv4.getJSONObject("address").getInt("addr"))
                "$myIp/$myPrefix"
            } ?: strings.loading

            val stunInfo = myNode.getJSONObject("stun_info")
            val publicIps = stunInfo.optJSONArray("public_ip")
            val publicIp = if (publicIps != null && publicIps.length() > 0) {
                buildString {
                    for (i in 0 until publicIps.length()) {
                        if (i > 0) append('\n')
                        append(publicIps.getString(i))
                    }
                }
            } else {
                strings.unknown
            }
            val natType = parseNatType(stunInfo.getInt("udp_nat_type"), strings)

            val routesMap = parseRoutesToMap(instance.getJSONArray("routes"), strings)
            val peersMap = parsePeersToMap(instance.getJSONArray("peers"))

            val peerList = ArrayList<EasyTierPeerInfo>()
            for (route in routesMap.values) {
                var inSameSubnet = true
                val localIp = myIp
                if (localIp != null && myPrefix > 0 && route.virtualIp != strings.none) {
                    inSameSubnet = isInSameSubnet(localIp, route.virtualIp, myPrefix)
                }
                val peerConn = peersMap[route.peerId]
                if (peerConn != null) {
                    peerList.add(
                        EasyTierPeerInfo(
                            hostname = route.hostname,
                            virtualIp = route.virtualIp,
                            isDirectConnection = true,
                            isInSameSubnet = inSameSubnet,
                            connectionDetails = peerConn.physicalAddr,
                            latency = "${peerConn.latencyUs / 1000} ms",
                            traffic = "${formatBytes(peerConn.rxBytes)} / ${formatBytes(peerConn.txBytes)}",
                            version = route.version,
                            natType = route.natType,
                        ),
                    )
                } else {
                    val nextHop = routesMap[route.nextHopPeerId]
                    val nextHopHostname = nextHop?.hostname ?: strings.unknown
                    peerList.add(
                        EasyTierPeerInfo(
                            hostname = route.hostname,
                            virtualIp = route.virtualIp,
                            isDirectConnection = false,
                            isInSameSubnet = inSameSubnet,
                            connectionDetails = strings.viaPeer(nextHopHostname),
                            latency = strings.pathLatency(route.pathLatency),
                            traffic = strings.unknown,
                            version = route.version,
                            natType = route.natType,
                        ),
                    )
                }
            }
            peerList.sortBy { it.hostname }

            EasyTierDisplayInfo(
                hostname = hostname,
                version = version,
                virtualIp = virtualIp,
                publicIp = publicIp,
                natType = natType,
                peers = peerList,
            )
        } catch (e: Exception) {
            EasyTierDisplayInfo(error = e.message ?: strings.parseError)
        }
    }

    data class StatusStrings(
        val loading: String,
        val unknown: String,
        val none: String,
        val parseError: String,
        val viaPeer: (String) -> String,
        val pathLatency: (Int) -> String,
        val natUnknown: String,
        val natOpenInternet: String,
        val natNoPat: String,
        val natFullCone: String,
        val natRestrictedCone: String,
        val natPortRestricted: String,
        val natSymmetric: String,
        val natSymmetricUdpFirewall: String,
        val natSymmetricEasyInc: String,
        val natSymmetricEasyDec: String,
    )

    private data class RouteData(
        val peerId: Long,
        val hostname: String,
        val virtualIp: String,
        val nextHopPeerId: Long,
        val pathLatency: Int,
        val version: String,
        val natType: String,
    )

    private data class PeerConnectionData(
        val peerId: Long,
        val physicalAddr: String,
        val latencyUs: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    private fun resolveInstanceInfo(root: JSONObject, preferredName: String): JSONObject? {
        val instances = root.optJSONObject("map") ?: return null
        instances.optJSONObject(preferredName)?.let { return it }
        val keys = instances.keys()
        while (keys.hasNext()) {
            val fallbackName = keys.next()
            val fallback = instances.optJSONObject(fallbackName)
            if (fallback != null) return fallback
        }
        return null
    }

    private fun ipFromInt(addr: Int): String = "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / 1024.0.pow(exp.toDouble()), pre)
    }

    private fun parseNatType(typeCode: Int, strings: StatusStrings): String = when (typeCode) {
        0 -> strings.natUnknown
        1 -> strings.natOpenInternet
        2 -> strings.natNoPat
        3 -> strings.natFullCone
        4 -> strings.natRestrictedCone
        5 -> strings.natPortRestricted
        6 -> strings.natSymmetric
        7 -> strings.natSymmetricUdpFirewall
        8 -> strings.natSymmetricEasyInc
        9 -> strings.natSymmetricEasyDec
        else -> "Other ($typeCode)"
    }

    private fun isInSameSubnet(ip1: String, ip2: String, prefix: Int): Boolean = try {
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        (ipToInt(ip1) and mask) == (ipToInt(ip2) and mask)
    } catch (_: Exception) {
        false
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or
            (parts[1].toInt() shl 16) or
            (parts[2].toInt() shl 8) or
            parts[3].toInt()
    }

    private fun parseRoutesToMap(routesJson: JSONArray, strings: StatusStrings): Map<Long, RouteData> {
        val map = HashMap<Long, RouteData>()
        for (i in 0 until routesJson.length()) {
            val route = routesJson.getJSONObject(i)
            val peerId = route.getLong("peer_id")
            val ipv4AddrJson = route.optJSONObject("ipv4_addr")
            val virtualIp = if (ipv4AddrJson != null) {
                ipFromInt(ipv4AddrJson.getJSONObject("address").getInt("addr"))
            } else {
                strings.none
            }
            map[peerId] = RouteData(
                peerId = peerId,
                hostname = route.getString("hostname"),
                virtualIp = virtualIp,
                nextHopPeerId = route.getLong("next_hop_peer_id"),
                pathLatency = route.getInt("path_latency"),
                version = route.getString("version"),
                natType = parseNatType(route.getJSONObject("stun_info").getInt("udp_nat_type"), strings),
            )
        }
        return map
    }

    private fun parsePeersToMap(peersJson: JSONArray): Map<Long, PeerConnectionData> {
        val map = HashMap<Long, PeerConnectionData>()
        for (i in 0 until peersJson.length()) {
            val peer = peersJson.getJSONObject(i)
            val conns = peer.getJSONArray("conns")
            if (conns.length() > 0) {
                val conn = conns.getJSONObject(0)
                val peerId = conn.getLong("peer_id")
                map[peerId] = PeerConnectionData(
                    peerId = peerId,
                    physicalAddr = conn.getJSONObject("tunnel").getJSONObject("remote_addr").getString("url"),
                    latencyUs = conn.getJSONObject("stats").getLong("latency_us"),
                    rxBytes = conn.getJSONObject("stats").getLong("rx_bytes"),
                    txBytes = conn.getJSONObject("stats").getLong("tx_bytes"),
                )
            }
        }
        return map
    }
}
