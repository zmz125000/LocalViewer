package com.hippo.ehviewer.easytier

data class EasyTierConfigUiState(
    val networkName: String = "",
    val networkSecret: String = "",
    val ipv4: String = "",
    val listeners: String = "",
    val peers: String = "",
    val useSmoltcp: Boolean = false,
    val latencyFirst: Boolean = false,
    val disableP2p: Boolean = false,
    val privateMode: Boolean = false,
    val disableIpv6: Boolean = false,
    val enableKcpProxy: Boolean = false,
    val disableKcpInput: Boolean = false,
    val enableQuicProxy: Boolean = false,
    val disableQuicInput: Boolean = false,
    val proxyForwardBySystem: Boolean = false,
    val disableEncryption: Boolean = false,
    val disableUdpHolePunching: Boolean = false,
    val disableSymHolePunching: Boolean = false,
)

data class EasyTierPeerInfo(
    val hostname: String,
    val virtualIp: String?,
    val isDirectConnection: Boolean,
    val isInSameSubnet: Boolean,
    val connectionDetails: String?,
    val latency: String?,
    val traffic: String?,
    val version: String?,
    val natType: String?,
)

data class EasyTierDisplayInfo(
    val hostname: String? = null,
    val version: String? = null,
    val virtualIp: String? = null,
    val publicIp: String? = null,
    val natType: String? = null,
    val peers: List<EasyTierPeerInfo> = emptyList(),
    val error: String? = null,
)

enum class EasyTierUiTab {
    STATUS,
    CONFIG,
}
