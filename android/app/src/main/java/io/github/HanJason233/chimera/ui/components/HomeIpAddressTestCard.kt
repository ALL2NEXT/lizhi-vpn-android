package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.IpAddressDetectionState
import io.github.HanJason233.chimera.data.model.IpAddressProbeResult
import io.github.HanJason233.chimera.data.model.IpAddressTestSettings
import io.github.HanJason233.chimera.ui.theme.MonetThemeOverlay

/** 首页窄版 IP 出口卡片，仅显示两种地址和小型测试图标。 */
@Composable
internal fun HomeIpAddressTestCard(
    state: IpAddressDetectionState,
    settings: IpAddressTestSettings,
    onDetect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ipv4 = if (settings.ipv4Enabled) state.ipv4Results.uniqueAddresses() else emptyList()
    val ipv6 = if (settings.ipv6Enabled) state.ipv6Results.uniqueAddresses() else emptyList()

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MonetThemeOverlay.homePanelSurface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IpAddressLine("IPv4", ipv4)
                    IpAddressLine("IPv6", ipv6)
                }
                IconButton(
                    onClick = onDetect,
                    enabled = !state.isChecking,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (state.isChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "开始测试",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IpAddressLine(family: String, addresses: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = family,
            modifier = Modifier.width(32.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (addresses.isEmpty()) {
                Text("—", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
            } else {
                addresses.forEach { address ->
                    Text(
                        text = address,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                    )
                }
            }
        }
    }
}

private fun List<IpAddressProbeResult>.uniqueAddresses(): List<String> =
    mapNotNull { result -> result.address?.let { result.normalizedAddress to it } }
        .distinctBy { it.first }
        .map { it.second }
