package xyz.a202132.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import xyz.a202132.app.data.model.LatencyLevel
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.ui.theme.LatencyBad
import xyz.a202132.app.ui.theme.LatencyGood
import xyz.a202132.app.ui.theme.LatencyMedium
import xyz.a202132.app.ui.theme.Primary

@Composable
internal fun NodeListItem(
    node: Node,
    isSelected: Boolean,
    isFavorite: Boolean,
    simplifiedTestDisplay: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val backgroundColor =
        if (isSelected) Primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isSelected) Primary else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() },
        color = backgroundColor,
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) BorderStroke(2.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = node.getFlagEmoji(),
                        fontSize = 24.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )

                    Text(
                        text = node.getDisplayName(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (node.isAvailable) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (simplifiedTestDisplay) {
                    NodeMetricStatusStrip(
                        node = node,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }

                IconButton(
                    onClick = onToggleFavorite,
                    enabled = enabled,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "收藏节点",
                        tint = if (isFavorite) {
                            Primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (!simplifiedTestDisplay) {
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!simplifiedTestDisplay) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NodeMetric(
                    label = "TCPing",
                    value = formatNodeLatency(node.tcpingLatency),
                    valueColor = nodeMetricColor(node.tcpingLatency),
                    modifier = Modifier.weight(1f)
                )
                NodeMetric(
                    label = "204 URLTest",
                    value = formatNodeLatency(node.urlTestLatency),
                    valueColor = nodeMetricColor(node.urlTestLatency),
                    modifier = Modifier.weight(1f)
                )
                NodeMetric(
                    label = "带宽",
                    value = formatBandwidth(node.downloadMbps),
                    valueColor = if (node.downloadMbps > 0f) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                }
            }
        }
    }
}

@Composable
private fun NodeMetricStatusStrip(
    node: Node,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NodeStatusIndicator(
            color = nodeLatencyStatusColor(node.tcpingLatency, node.isAvailable),
            description = "TCPing 测试状态",
            modifier = Modifier.size(8.dp)
        )
        NodeStatusIndicator(
            color = nodeLatencyStatusColor(node.urlTestLatency, node.isAvailable),
            description = "204 URLTest 测试状态",
            modifier = Modifier.size(8.dp)
        )
        NodeStatusIndicator(
            color = nodeBandwidthStatusColor(node.downloadMbps),
            description = "带宽测试状态",
            modifier = Modifier.size(8.dp)
        )
    }
}

@Composable
private fun NodeStatusIndicator(
    color: Color,
    description: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .semantics { contentDescription = description },
        color = color,
        shape = RoundedCornerShape(50)
    ) {}
}

@Composable
private fun NodeMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatNodeLatency(latency: Int): String = when {
    latency > 0 -> "${latency}ms"
    latency == -2 -> "失败"
    else -> "未测试"
}

private fun formatBandwidth(downloadMbps: Float): String = if (downloadMbps > 0f) {
    String.format(Locale.US, "%.1f Mbps", downloadMbps)
} else {
    "未测试"
}

@Composable
private fun nodeMetricColor(latency: Int): Color = when {
    latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    latency <= 200 -> LatencyGood
    latency <= 400 -> LatencyMedium
    else -> LatencyBad
}

@Composable
private fun nodeLatencyStatusColor(latency: Int, isAvailable: Boolean): Color = when {
    !isAvailable || latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    latency <= 200 -> LatencyGood
    latency <= 400 -> LatencyMedium
    else -> LatencyBad
}

@Composable
private fun nodeBandwidthStatusColor(downloadMbps: Float): Color = when {
    !downloadMbps.isFinite() || downloadMbps <= 0f ->
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    downloadMbps >= 20f -> LatencyGood
    downloadMbps >= 5f -> LatencyMedium
    else -> LatencyBad
}

@Composable
fun LatencyBadge(node: Node, isTesting: Boolean = false) {
    val latencyColor = when (node.getLatencyLevel()) {
        LatencyLevel.GOOD -> LatencyGood
        LatencyLevel.MEDIUM -> LatencyMedium
        LatencyLevel.BAD -> LatencyBad
    }

    Surface(color = latencyColor.copy(alpha = 0.15f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text = if (isTesting && node.latency == -1) "测试中" else node.getLatencyText(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = latencyColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
