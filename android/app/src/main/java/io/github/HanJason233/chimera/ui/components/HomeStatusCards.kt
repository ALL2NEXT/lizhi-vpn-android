package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.VpnState
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestResult
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestState
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestResult
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestState
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkIpAddress
import io.github.HanJason233.chimera.viewmodel.SpeedTestProgress
import io.github.HanJason233.chimera.viewmodel.UnlockTestUiState
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@Composable
internal fun HomeSelectionCard(
    name: String,
    groupType: String,
    node: Node?,
    vpnState: VpnState,
    onGroupClick: () -> Unit,
    onNodeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    HomeInfoCard(compact = true, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onGroupClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "选中组：",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
                ) {
                    Text(
                        text = groupType,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Text("切换", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Divider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                thickness = 0.5.dp
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onNodeClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (vpnState == VpnState.CONNECTED) "连接节点" else "选中节点",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = node?.let { "${it.getRegionMarker()} ${it.getDisplayName()}" } ?: "未选择节点",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("切换", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** 链路数据、IP 检测和运行进度统一展示在首页顶部的测试卡片中。 */
@Composable
internal fun HomeTestsCard(
    node: Node?,
    isTesting: Boolean,
    isHysteriaBandwidthAdapting: Boolean,
    progressFlow: StateFlow<SpeedTestProgress?>,
    ipTestState: UnlockTestUiState,
    linkTypeResult: LinkTypeTestResult,
    onInterruptTests: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    val activeTestLabel = when {
        ipTestState.isRunning -> ipTestState.message.ifBlank { "IP 与服务测试进行中" }
        linkTypeResult.state == LinkTypeTestState.TESTING ->
            linkTypeResult.message.ifBlank { "链路类型检测中" }
        isHysteriaBandwidthAdapting -> "Hy2 带宽自适应测试中"
        isTesting -> progress?.title ?: "节点测试中"
        else -> null
    }
    HomeInfoCard(
        compact = true,
        modifier = modifier
    ) {
        if (activeTestLabel != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = activeTestLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = onInterruptTests,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("强制中断", maxLines = 1)
                }
            }
            if (isTesting && !ipTestState.isRunning &&
                linkTypeResult.state != LinkTypeTestState.TESTING
            ) {
                HomeTestProgress(progress)
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tcping = node?.tcpingLatency ?: -1
            val urlTest = node?.urlTestLatency ?: -1
            HomeLinkMetric("TCPing", tcping.homeLatency(), Modifier.weight(1f), tcping.latencyColor())
            HomeLinkMetric("URLTest", urlTest.homeLatency(), Modifier.weight(1f), urlTest.latencyColor())
            HomeLinkMetric("下载", (node?.downloadMbps ?: 0f).homeSpeed(), Modifier.weight(1f))
        }
        if (linkTypeResult.state != LinkTypeTestState.IDLE) {
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            HomeLinkTypeResult(linkTypeResult)
        }
        if (ipTestState.items.isNotEmpty()) {
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
private fun HomeLinkTypeResult(result: LinkTypeTestResult) {
    val color = when (result.state) {
        LinkTypeTestState.ERROR, LinkTypeTestState.NOT_CONNECTED -> MaterialTheme.colorScheme.error
        LinkTypeTestState.TESTING -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("链路类型", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(result.state.title, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = color)
    }
    if (result.state == LinkTypeTestState.RELAY || result.state == LinkTypeTestState.CDN_RELAY) {
        if (LinkIpAddress.parse(result.serverAddress) == null) {
            Text("中转地址：${result.serverAddress}", style = MaterialTheme.typography.bodySmall)
        }
        result.entryIps.forEach { ip ->
            val provider = result.entryCdnProviders[ip]
            Text("中转点：$ip${provider?.let { "（$it）" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (result.exitIp.isNotBlank()) {
        Text("落地点 IP：${result.exitIp}", style = MaterialTheme.typography.bodySmall)
    }
    if (result.state == LinkTypeTestState.RELAY) {
        Text("CDN：未命中已知 CDN 地址段", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (result.message.isNotBlank()) {
        Text(result.message, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun HomeTestProgress(progress: SpeedTestProgress?) {
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
        // 品牌图标随 APK 本地打包，检测状态由外围颜色表示。
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(16.dp),
            color = color.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(unlockTestIconResource(result.definition.id)),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
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

internal fun unlockTestIconResource(id: String): Int = when (id) {
    "google_cn" -> R.drawable.ic_unlock_google
    "chatgpt" -> R.drawable.ic_unlock_chatgpt
    "claude" -> R.drawable.ic_unlock_anthropic
    "gemini" -> R.drawable.ic_unlock_gemini
    "netflix" -> R.drawable.ic_unlock_netflix
    "disney" -> R.drawable.ic_unlock_disney_plus
    "max" -> R.drawable.ic_unlock_max
    "prime" -> R.drawable.ic_unlock_prime_video
    "youtube" -> R.drawable.ic_unlock_youtube
    "tiktok" -> R.drawable.ic_unlock_tiktok
    "spotify" -> R.drawable.ic_unlock_spotify
    "wikipedia" -> R.drawable.ic_unlock_wikipedia
    else -> R.drawable.ic_unlock_google
}

@Composable
private fun HomeInfoCard(
    title: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    badge: String? = null,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(
            Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
        ) {
            if (title != null && icon != null) {
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
