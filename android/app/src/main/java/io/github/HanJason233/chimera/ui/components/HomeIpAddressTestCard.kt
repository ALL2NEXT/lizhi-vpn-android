package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** 首页窄版 IP 出口卡片，仅显示两种地址和开始按钮。 */
@Composable
internal fun HomeIpAddressTestCard(
    state: IpAddressDetectionState,
    settings: IpAddressTestSettings,
    onDetect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ipv4 = if (settings.ipv4Enabled) {
        state.ipv4Results.uniqueAddresses().joinToString(" · ").ifBlank { "—" }
    } else {
        "—"
    }
    val ipv6 = if (settings.ipv6Enabled) {
        state.ipv6Results.uniqueAddresses().joinToString(" · ").ifBlank { "—" }
    } else {
        "—"
    }

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
            IpAddressLine("IPv4", ipv4)
            IpAddressLine("IPv6", ipv6)
            Button(
                onClick = onDetect,
                enabled = !state.isChecking,
                modifier = Modifier.fillMaxWidth().height(34.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (state.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("开始测试", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun IpAddressLine(family: String, address: String) {
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
        Text(
            text = address,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun List<IpAddressProbeResult>.uniqueAddresses(): List<String> =
    mapNotNull { result -> result.address?.let { result.normalizedAddress to it } }
        .distinctBy { it.first }
        .map { it.second }
