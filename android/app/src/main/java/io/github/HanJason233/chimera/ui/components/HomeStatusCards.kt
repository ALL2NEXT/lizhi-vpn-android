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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** 链路数据、IP 检测和运行进度统一展示在首页顶部的测试卡片中。 */
@Composable
internal fun HomeTestsCard(
    node: Node?,
    isTesting: Boolean,
    progress: SpeedTestProgress?,
    ipTestState: UnlockTestUiState
) {
    HomeInfoCard(
        title = "测试结果",
        icon = Icons.Outlined.Assessment,
        badge = if (isTesting || ipTestState.isRunning) "测试中" else null
    ) {
        if (isTesting && !ipTestState.isRunning) {
            HomeTestProgress(progress)
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        Text("链路测试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val tcping = node?.tcpingLatency ?: -1
            val urlTest = node?.urlTestLatency ?: -1
            HomeLinkMetric("TCPing", tcping.homeLatency(), Modifier.weight(1f), tcping.latencyColor())
            HomeLinkMetric("URLTest", urlTest.homeLatency(), Modifier.weight(1f), urlTest.latencyColor())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeLinkMetric("下载", (node?.downloadMbps ?: 0f).homeSpeed(), Modifier.weight(1f))
            HomeLinkMetric("上传", (node?.uploadMbps ?: 0f).homeSpeed(), Modifier.weight(1f))
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("IP 测试", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            if (ipTestState.items.isNotEmpty()) {
                Text("${ipTestState.completedCount}/${ipTestState.items.size}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ipTestState.items.isEmpty()) {
            Text("尚未运行 IP 测试", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            if (ipTestState.message.isNotBlank()) {
                Text(ipTestState.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ipTestState.isRunning) {
                LinearProgressIndicator(
                    progress = (ipTestState.completedCount.toFloat() / ipTestState.items.size).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                )
            }
            HomeIpTestGrid(ipTestState.items)
        }
    }
}

@Composable
private fun HomeTestProgress(progress: SpeedTestProgress?) {
    Text(progress?.title ?: "节点测试", style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun HomeIpTestGrid(items: List<UnlockTestResult>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 宽屏增加列数，大字体减少列数，保证项目名称和状态可读。
        val columns = (maxWidth / (92.dp * fontScale)).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { result -> HomeIpTestItem(result, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun HomeIpTestItem(result: UnlockTestResult, modifier: Modifier) {
    val color = when (result.state) {
        UnlockTestState.UNLOCKED -> MaterialTheme.colorScheme.primary
        UnlockTestState.BLOCKED, UnlockTestState.ERROR -> MaterialTheme.colorScheme.error
        UnlockTestState.PARTIAL, UnlockTestState.WARNING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 服务图标暂用名称首字占位，后续可在此替换为真实图片。
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(16.dp),
            color = color.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(result.definition.name.trim().take(1).uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
                if (result.state == UnlockTestState.TESTING) {
                    CircularProgressIndicator(Modifier.align(Alignment.BottomEnd).padding(3.dp).size(12.dp),
                        strokeWidth = 2.dp, color = color)
                }
            }
        }
        Text(result.definition.name, style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(result.statusText, style = MaterialTheme.typography.labelSmall, color = color,
            textAlign = TextAlign.Center)
        if (result.region.isNotBlank()) {
            Text(result.region, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth(),
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
private fun HomeLinkMetric(
    label: String,
    value: String,
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color)
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
