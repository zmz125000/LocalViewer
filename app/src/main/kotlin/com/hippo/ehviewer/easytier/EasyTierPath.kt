package com.hippo.ehviewer.easytier

import com.ehviewer.core.database.model.SmbSourceEntity

/**
 * Resolves the TCP endpoint when an optional EasyTier host is configured.
 *
 * - Tunnel up + non-blank [SmbSourceEntity.easytierHost] → use EasyTier address for connect only.
 * - Otherwise → regular [SmbSourceEntity.host].
 *
 * Disk caches and sourceConfigKey identity stay on the regular host so switching
 * path does not duplicate or invalidate content that is the same on the server.
 */
object EasyTierPath {
    /** True while EasyTier service is starting or has a live tunnel. */
    fun isActive(): Boolean {
        val s = EasyTierRuntime.state.value
        return s.connectingOrRunning || s.running
    }

    fun resolveHost(regularHost: String, easytierHost: String): String {
        val alt = easytierHost.trim()
        if (alt.isEmpty() || !isActive()) return regularHost
        return alt
    }

    fun smbConnectHost(source: SmbSourceEntity): String = resolveHost(source.host, source.easytierHost)
}
