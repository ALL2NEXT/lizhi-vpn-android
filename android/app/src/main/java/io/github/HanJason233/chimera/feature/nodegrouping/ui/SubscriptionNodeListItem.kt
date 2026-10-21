package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.ui.theme.LatencyBad
import io.github.HanJason233.chimera.ui.theme.LatencyGood
import io.github.HanJason233.chimera.ui.theme.LatencyMedium
import io.github.HanJason233.chimera.util.protocolDisplayLabel

@Composable
internal fun NodeListItem(
    node: Node,
    isSelected: Boolean,
    enabled: Boolean,
    displaySettings: NodeDisplaySettings,
    coreSettings: ProtocolCoreSettings,
    actionMode: NodeActionMode?,
    onClick: () -> Unit,
    onEditNode: () -> Unit,
    onDeleteNode: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val protocolDisplay = remember(node.type, node.rawLink, displaySettings, coreSettings) {
        node.protocolDisplayLabel(displaySettings, coreSettings)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = node.getRegionMarker(),
                fontSize = 34.sp,
                lineHeight = 38.sp,
                modifier = Modifier
                    .offset(x = (-8).dp)
                    .width(48.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (protocolDisplay.isNotEmpty()) {
                        NodeProtocolDetailsBadge(
                            label = protocolDisplay,
                            modifier = Modifier.padding(start = 8.dp, end = 4.dp)
                        )
                    }

                    when (actionMode) {
                        null -> Unit
                        NodeActionMode.EDIT -> IconButton(
                            onClick = onEditNode,
                            enabled = enabled,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "编辑节点",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        NodeActionMode.DELETE -> IconButton(
                            onClick = onDeleteNode,
                            enabled = enabled,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "删除节点",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

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
private fun NodeProtocolDetailsBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
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
