package com.hippo.ehviewer.ui.easytier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.hippo.ehviewer.easytier.EasyTierConfigUiState
import com.hippo.ehviewer.easytier.EasyTierPeerInfo
import com.hippo.ehviewer.easytier.EasyTierStatusParser
import com.hippo.ehviewer.easytier.EasyTierUiTab

@Composable
fun rememberEasyTierStatusStrings(): EasyTierStatusParser.StatusStrings {
    val loading = stringResource(R.string.easytier_loading)
    val unknown = stringResource(R.string.easytier_unknown)
    val none = stringResource(R.string.easytier_none)
    val parseError = stringResource(R.string.easytier_parse_error)
    val viaPeerFmt = stringResource(R.string.easytier_via_peer)
    val pathLatencyFmt = stringResource(R.string.easytier_path_latency)
    val natUnknown = stringResource(R.string.easytier_nat_unknown)
    val natOpenInternet = stringResource(R.string.easytier_nat_open_internet)
    val natNoPat = stringResource(R.string.easytier_nat_no_pat)
    val natFullCone = stringResource(R.string.easytier_nat_full_cone)
    val natRestrictedCone = stringResource(R.string.easytier_nat_restricted_cone)
    val natPortRestricted = stringResource(R.string.easytier_nat_port_restricted)
    val natSymmetric = stringResource(R.string.easytier_nat_symmetric)
    val natSymmetricUdpFirewall = stringResource(R.string.easytier_nat_symmetric_udp_firewall)
    val natSymmetricEasyInc = stringResource(R.string.easytier_nat_symmetric_easy_inc)
    val natSymmetricEasyDec = stringResource(R.string.easytier_nat_symmetric_easy_dec)
    return remember(
        loading, unknown, none, parseError, viaPeerFmt, pathLatencyFmt,
        natUnknown, natOpenInternet, natNoPat, natFullCone, natRestrictedCone,
        natPortRestricted, natSymmetric, natSymmetricUdpFirewall,
        natSymmetricEasyInc, natSymmetricEasyDec,
    ) {
        EasyTierStatusParser.StatusStrings(
            loading = loading,
            unknown = unknown,
            none = none,
            parseError = parseError,
            viaPeer = { peer -> viaPeerFmt.format(peer) },
            pathLatency = { ms -> pathLatencyFmt.format(ms) },
            natUnknown = natUnknown,
            natOpenInternet = natOpenInternet,
            natNoPat = natNoPat,
            natFullCone = natFullCone,
            natRestrictedCone = natRestrictedCone,
            natPortRestricted = natPortRestricted,
            natSymmetric = natSymmetric,
            natSymmetricUdpFirewall = natSymmetricUdpFirewall,
            natSymmetricEasyInc = natSymmetricEasyInc,
            natSymmetricEasyDec = natSymmetricEasyDec,
        )
    }
}

@Composable
fun EasyTierTabRow(
    selected: EasyTierUiTab,
    onSelect: (EasyTierUiTab) -> Unit,
) {
    TabRow(selectedTabIndex = if (selected == EasyTierUiTab.STATUS) 0 else 1) {
        Tab(
            selected = selected == EasyTierUiTab.STATUS,
            onClick = { onSelect(EasyTierUiTab.STATUS) },
            text = { Text(stringResource(R.string.easytier_tab_status)) },
        )
        Tab(
            selected = selected == EasyTierUiTab.CONFIG,
            onClick = { onSelect(EasyTierUiTab.CONFIG) },
            text = { Text(stringResource(R.string.easytier_tab_config)) },
        )
    }
}

@Composable
fun EasyTierStatusContent(
    statusJson: String?,
    connectingOrRunning: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberEasyTierStatusStrings()
    val display = remember(statusJson, strings) {
        EasyTierStatusParser.parse(statusJson, strings)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.easytier_refresh_status))
        }
        if (statusJson.isNullOrEmpty()) {
            InfoCard {
                Text(
                    text = stringResource(R.string.easytier_service_not_running),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.easytier_refresh_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        if (display.error != null) {
            InfoCard {
                Text(display.error, color = MaterialTheme.colorScheme.error)
            }
            return@Column
        }
        Text(
            stringResource(R.string.easytier_local_info),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        InfoCard {
            InfoRow(stringResource(R.string.easytier_hostname), display.hostname)
            InfoRow(stringResource(R.string.easytier_virtual_ip), display.virtualIp)
            InfoRow(stringResource(R.string.easytier_public_ip), display.publicIp)
            InfoRow(stringResource(R.string.easytier_nat_type), display.natType)
        }
        Text(
            stringResource(R.string.easytier_peers_count, display.peers.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (display.peers.isEmpty()) {
            InfoCard {
                Text(
                    stringResource(R.string.easytier_no_peers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            display.peers.forEach { peer -> PeerCard(peer) }
        }
        @Suppress("UNUSED_EXPRESSION")
        connectingOrRunning
    }
}

@Composable
fun EasyTierConfigContent(
    config: EasyTierConfigUiState,
    advancedExpanded: Boolean,
    onConfigChange: (EasyTierConfigUiState) -> Unit,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showAdvanced: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = config.networkName,
            onValueChange = { onConfigChange(config.copy(networkName = it)) },
            label = { Text(stringResource(R.string.easytier_network_name)) },
            placeholder = { Text(stringResource(R.string.easytier_network_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.networkSecret,
            onValueChange = { onConfigChange(config.copy(networkSecret = it)) },
            label = { Text(stringResource(R.string.easytier_network_secret)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.ipv4,
            onValueChange = { onConfigChange(config.copy(ipv4 = it)) },
            label = { Text(stringResource(R.string.easytier_ipv4)) },
            placeholder = { Text(stringResource(R.string.easytier_ipv4_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.listeners,
            onValueChange = { onConfigChange(config.copy(listeners = it)) },
            label = { Text(stringResource(R.string.easytier_listeners)) },
            placeholder = { Text(stringResource(R.string.easytier_listeners_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.peers,
            onValueChange = { onConfigChange(config.copy(peers = it)) },
            label = { Text(stringResource(R.string.easytier_peers)) },
            placeholder = { Text(stringResource(R.string.easytier_peers_hint)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showAdvanced) {
            TextButton(
                onClick = { onAdvancedExpandedChange(!advancedExpanded) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (advancedExpanded) {
                            R.string.easytier_hide_advanced_flags
                        } else {
                            R.string.easytier_show_advanced_flags
                        },
                    ),
                )
            }
            if (advancedExpanded) {
                SectionTitle(stringResource(R.string.easytier_section_core))
                FlagRow(stringResource(R.string.easytier_use_smoltcp), config.useSmoltcp) {
                    onConfigChange(config.copy(useSmoltcp = it))
                }
                FlagRow(stringResource(R.string.easytier_latency_first), config.latencyFirst) {
                    onConfigChange(config.copy(latencyFirst = it))
                }
                FlagRow(stringResource(R.string.easytier_disable_p2p), config.disableP2p) {
                    onConfigChange(config.copy(disableP2p = it))
                }
                FlagRow(stringResource(R.string.easytier_private_mode), config.privateMode) {
                    onConfigChange(config.copy(privateMode = it))
                }
                FlagRow(stringResource(R.string.easytier_disable_ipv6), config.disableIpv6) {
                    onConfigChange(config.copy(disableIpv6 = it))
                }
                SectionTitle(stringResource(R.string.easytier_section_proxy))
                FlagRow(stringResource(R.string.easytier_enable_kcp_proxy), config.enableKcpProxy) {
                    onConfigChange(config.copy(enableKcpProxy = it))
                }
                FlagRow(stringResource(R.string.easytier_disable_kcp_input), config.disableKcpInput) {
                    onConfigChange(config.copy(disableKcpInput = it))
                }
                FlagRow(stringResource(R.string.easytier_enable_quic_proxy), config.enableQuicProxy) {
                    onConfigChange(config.copy(enableQuicProxy = it))
                }
                FlagRow(stringResource(R.string.easytier_disable_quic_input), config.disableQuicInput) {
                    onConfigChange(config.copy(disableQuicInput = it))
                }
                FlagRow(stringResource(R.string.easytier_proxy_forward_by_system), config.proxyForwardBySystem) {
                    onConfigChange(config.copy(proxyForwardBySystem = it))
                }
                SectionTitle(stringResource(R.string.easytier_section_security))
                FlagRow(stringResource(R.string.easytier_disable_encryption), config.disableEncryption) {
                    onConfigChange(config.copy(disableEncryption = it))
                }
                FlagRow(stringResource(R.string.easytier_disable_udp_hole_punching), config.disableUdpHolePunching) {
                    onConfigChange(config.copy(disableUdpHolePunching = it))
                }
                FlagRow(stringResource(R.string.easytier_disable_sym_hole_punching), config.disableSymHolePunching) {
                    onConfigChange(config.copy(disableSymHolePunching = it))
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: EasyTierPeerInfo) {
    val title = when {
        !peer.isInSameSubnet ->
            "${peer.hostname} (${stringResource(R.string.easytier_subnet_mismatch)})"
        !peer.isDirectConnection ->
            "${peer.hostname} (${stringResource(R.string.easytier_relayed)})"
        else -> peer.hostname
    }
    val titleColor = if (!peer.isInSameSubnet) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    InfoCard {
        Text(title, fontWeight = FontWeight.Bold, color = titleColor)
        Spacer(modifier = Modifier.height(6.dp))
        InfoRow(stringResource(R.string.easytier_virtual_ip), peer.virtualIp)
        InfoRow(stringResource(R.string.easytier_nat_type), peer.natType)
        InfoRow(
            stringResource(
                if (peer.isDirectConnection) {
                    R.string.easytier_physical_address
                } else {
                    R.string.easytier_next_hop
                },
            ),
            peer.connectionDetails,
        )
        InfoRow(stringResource(R.string.easytier_latency), peer.latency)
        InfoRow(stringResource(R.string.easytier_traffic), peer.traffic)
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), content = { content() })
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = value ?: stringResource(R.string.easytier_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun FlagRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
