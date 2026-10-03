package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.network.UnlockTestResult
import io.github.HanJason233.chimera.network.UnlockTestState
import io.github.HanJason233.chimera.viewmodel.SpeedTestProgress
import io.github.HanJason233.chimera.viewmodel.UnlockTestUiState
import java.util.Locale

@Composable
internal fun HomeGroupCard(name: String, groupType: String, onClick: () -> Unit) {
    HomeInfoCard(
        title = "选中组",
        icon = Icons.Outlined.FolderOpen,
        badge = groupType,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text("切换", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun HomeNodeCard(node: Node?, regionName: String, vpnState: VpnState) {
    val status = when (vpnState) {
        VpnState.CONNECTED -> "已连接"
        VpnState.CONNECTING -> "连接中"
        VpnState.DISCONNECTING -> "断开中"
        VpnState.DISCONNECTED -> "未连接"
    }
    HomeInfoCard(
        title = if (vpnState == VpnState.CONNECTED) "连接节点" else "选中节点",
        icon = Icons.Outlined.Dns,
        badge = status
    ) {
        if (node == null) {
            Text("未选择节点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("选择分组后，可选择节点或运行测速方案。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                text = "${node.getFlagEmoji()} ${node.getDisplayName()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$regionName · ${node.type.protocol.uppercase(Locale.ROOT)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "仓库源：${node.getConnectionGroupName()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
internal fun HomeTestProgressCard(progress: SpeedTestProgress?) {
    HomeInfoCard("测试进度", Icons.Outlined.Speed,
        badge = if (progress?.isManualGroupTest == true) "组内测试" else "测速方案") {
        Text(progress?.title ?: "节点测试", style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(progress?.stage ?: "准备测试", modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (progress != null && progress.total > 0) {
                Text("${progress.completed}/${progress.total}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(
                progress = (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        }
    }
}

@Composable
internal fun HomeTestResultsCard(node: Node?) {
    HomeInfoCard("测试结果", Icons.Outlined.Assessment) {
        if (node == null) {
            Text("节点的延迟和带宽结果会显示在这里。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMetric("延迟", node.latency.homeLatency(), Modifier.weight(1f), node.latency.latencyColor())
                HomeMetric("TCPing", node.tcpingLatency.homeLatency(), Modifier.weight(1f), node.tcpingLatency.latencyColor())
                HomeMetric("URLTest", node.urlTestLatency.homeLatency(), Modifier.weight(1f), node.urlTestLatency.latencyColor())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeMetric("下载", node.downloadMbps.homeSpeed(), Modifier.weight(1f))
                HomeMetric("上传", node.uploadMbps.homeSpeed(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun HomeUnlockResultsCard(state: UnlockTestUiState) {
    HomeInfoCard("流媒体与 AI", Icons.Outlined.Public,
        badge = if (state.isRunning) "${state.completedCount}/${state.items.size}" else "检测结果") {
        if (state.message.isNotBlank()) {
            Text(state.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.isRunning && state.items.isNotEmpty()) {
            LinearProgressIndicator(progress = state.completedCount.toFloat() / state.items.size,
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        }
        state.items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { result -> HomeUnlockResult(result, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeUnlockResult(result: UnlockTestResult, modifier: Modifier) {
    val color = when (result.state) {
        UnlockTestState.UNLOCKED -> MaterialTheme.colorScheme.primary
        UnlockTestState.BLOCKED, UnlockTestState.ERROR -> MaterialTheme.colorScheme.error
        UnlockTestState.PARTIAL, UnlockTestState.WARNING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.07f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (result.state == UnlockTestState.TESTING) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = color)
                } else {
                    Icon(
                        imageVector = when (result.state) {
                            UnlockTestState.UNLOCKED -> Icons.Rounded.CheckCircle
                            UnlockTestState.IDLE, UnlockTestState.NOT_CONNECTED -> Icons.Rounded.Schedule
                            else -> Icons.Rounded.ErrorOutline
                        },
                        contentDescription = null, modifier = Modifier.size(16.dp), tint = color
                    )
                }
                Text(result.definition.name, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(result.statusText, style = MaterialTheme.typography.bodySmall, color = color)
            if (result.region.isNotBlank()) {
                Text(result.region, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HomeInfoCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                badge?.let {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)) {
                        Text(it, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun HomeMetric(
    label: String,
    value: String,
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

private fun Int.homeLatency(): String = when {
    this >= 0 -> "${this} ms"
    this == -2 -> "超时"
    else -> "未测速"
}

@Composable
private fun Int.latencyColor(): Color = when {
    this == -2 -> MaterialTheme.colorScheme.error
    this < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

private fun Float.homeSpeed(): String =
    if (isFinite() && this > 0f) "${"%.2f".format(Locale.ROOT, this)} Mbps" else "未测速"
