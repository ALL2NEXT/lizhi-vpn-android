package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.IpAddressDetectionState
import io.github.HanJason233.chimera.data.model.IpAddressProbeResult
import io.github.HanJason233.chimera.data.model.IpAddressTestSettings
import io.github.HanJason233.chimera.ui.theme.MonetThemeOverlay

/** 首页独立展示的手动 IP 出口检测面板，不参与节点测速流程。 */
@Composable
internal fun HomeIpAddressTestCard(
    state: IpAddressDetectionState,
    settings: IpAddressTestSettings,
    onDetect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDetails by remember { mutableStateOf(false) }
    val enabledFamilies = buildList {
        if (settings.ipv4Enabled) add("IPv4")
        if (settings.ipv6Enabled) add("IPv6")
    }
    val probeCount = settings.enabledProbeSources.size * enabledFamilies.size

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MonetThemeOverlay.homePanelSurface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "IP 出口检测",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    enabled = !state.isChecking,
                    onClick = onDetect
                ) {
                    if (state.isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (state.hasChecked) "重新检测" else "检测")
                    }
                }
            }

            when {
                state.isChecking -> Text(
                    text = "正在并发查询 $probeCount 个探针…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                !state.hasChecked -> Text(
                    text = "手动查询当前出口的 ${enabledFamilies.joinToString(" 和 ")} 地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    IpAddressSummaryRow("IPv4", state.ipv4Results, settings.ipv4Enabled)
                    IpAddressSummaryRow("IPv6", state.ipv6Results, settings.ipv6Enabled)
                    TextButton(
                        onClick = { showDetails = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("探针详情")
                    }
                }
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("关闭") }
            },
            title = { Text("IP 出口探针结果") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IpAddressProbeDetails("IPv4", state.ipv4Results, settings.ipv4Enabled)
                    IpAddressProbeDetails("IPv6", state.ipv6Results, settings.ipv6Enabled)
                }
            }
        )
    }
}

@Composable
private fun IpAddressSummaryRow(
    family: String,
    results: List<IpAddressProbeResult>,
    enabled: Boolean
) {
    val addresses = if (enabled) results.uniqueAddresses() else emptyList()
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(family, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    !enabled -> "已关闭"
                    addresses.isEmpty() -> "未检测到"
                    else -> "${addresses.size} 个不同出口"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (enabled) addresses.joinToString("、").ifBlank { "无可用地址" } else "该地址族未启用",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IpAddressProbeDetails(
    family: String,
    results: List<IpAddressProbeResult>,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (!enabled) {
            Text("该地址族未启用", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            results.forEach { result ->
                Text(
                    text = "${result.source}：${result.address ?: "检测失败"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun List<IpAddressProbeResult>.uniqueAddresses(): List<String> =
    mapNotNull { result -> result.address?.let { result.normalizedAddress to it } }
        .distinctBy { it.first }
        .map { it.second }
