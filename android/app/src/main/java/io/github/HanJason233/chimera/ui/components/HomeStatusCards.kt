package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    var detailsDialog by remember { mutableStateOf<HomeTestDetails?>(null) }
    val hasActiveTest = isTesting || isHysteriaBandwidthAdapting || ipTestState.isRunning ||
        linkTypeResult.state == LinkTypeTestState.TESTING
    val tcping = node?.tcpingLatency ?: -1
    val urlTest = node?.urlTestLatency ?: -1
    val progressStage = progress?.stage
    val isLinkTypeTesting = linkTypeResult.state == LinkTypeTestState.TESTING ||
        (isTesting && (progressStage == "第 4 项链路类型检测" || progressStage == "第 4、5 项筛选线路"))
    val isUnlockTesting = ipTestState.isRunning ||
        ipTestState.items.any { it.state == UnlockTestState.TESTING } ||
        (isTesting && (progressStage == "第 5 项流媒体与 AI 解锁检测" || progressStage == "第 4、5 项筛选线路"))
    val linkTypeColor = when (linkTypeResult.state) {
        LinkTypeTestState.ERROR, LinkTypeTestState.NOT_CONNECTED -> MaterialTheme.colorScheme.error
        LinkTypeTestState.TESTING, LinkTypeTestState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val unlockSummary = if (ipTestState.items.isEmpty()) {
        "未测"
    } else {
        "${ipTestState.items.count { it.state == UnlockTestState.UNLOCKED }}/${ipTestState.items.size}"
    }
    val linkTypeSummary = when (linkTypeResult.state) {
        LinkTypeTestState.IDLE -> "未测"
        LinkTypeTestState.TESTING -> "检测中"
        LinkTypeTestState.DIRECT -> "直连"
        LinkTypeTestState.RELAY -> "中转"
        LinkTypeTestState.CDN_RELAY -> "CDN中转"
        LinkTypeTestState.NOT_CONNECTED -> "未连接"
        LinkTypeTestState.ERROR -> "失败"
    }
    HomeInfoCard(
        compact = true,
        modifier = modifier
    ) {
        // 五项测试并排显示；链路类型和解锁结果可点击查看详情。
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeTestTableCell(
                label = "TCPing",
                value = tcping.homeLatency(),
                modifier = Modifier.weight(1f),
                color = tcping.latencyColor(),
                isTesting = isTesting && progress?.stage == "TCPing"
            )
            HomeTestTableDivider()
            HomeTestTableCell(
                label = "URLTest",
                value = urlTest.homeLatency(),
                modifier = Modifier.weight(1f),
                color = urlTest.latencyColor(),
                isTesting = isTesting && progress?.stage == "URL Test"
            )
            HomeTestTableDivider()
            HomeTestTableCell(
                label = "下载",
                value = (node?.downloadMbps ?: 0f).homeSpeedCompact(),
                modifier = Modifier.weight(1f),
                isTesting = isHysteriaBandwidthAdapting ||
                    (isTesting && progress?.stage == "带宽测速")
            )
            HomeTestTableDivider()
            HomeTestTableCell(
                label = "链路",
                value = linkTypeSummary,
                modifier = Modifier.weight(1f),
                color = linkTypeColor,
                isTesting = isLinkTypeTesting,
                onClick = { detailsDialog = HomeTestDetails.LINK_TYPE }
            )
            HomeTestTableDivider()
            HomeTestTableCell(
                label = "解锁",
                value = unlockSummary,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primary,
                isTesting = isUnlockTesting,
                onClick = { detailsDialog = HomeTestDetails.UNLOCK }
            )
        }
        // 仅在测试运行期间显示强制中断按钮。
        if (hasActiveTest) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onInterruptTests) {
                    Icon(
                        imageVector = Icons.Outlined.StopCircle,
                        contentDescription = "强制中断测试",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    when (detailsDialog) {
        HomeTestDetails.LINK_TYPE -> AlertDialog(
            onDismissRequest = { detailsDialog = null },
            confirmButton = {
                TextButton(onClick = { detailsDialog = null }) { Text("关闭") }
            },
            title = { Text("链路类型详情") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when {
                        linkTypeResult.state != LinkTypeTestState.IDLE -> HomeLinkTypeDetails(linkTypeResult)
                        isLinkTypeTesting -> Text("正在检测链路类型…")
                        else -> Text("当前节点暂无链路类型检测结果。")
                    }
                }
            }
        )
        HomeTestDetails.UNLOCK -> AlertDialog(
            onDismissRequest = { detailsDialog = null },
            confirmButton = {
                TextButton(onClick = { detailsDialog = null }) { Text("关闭") }
            },
            title = { Text("解锁检测详情") },
            text = {
                if (ipTestState.items.isEmpty()) {
                    Text(if (isUnlockTesting) "正在检测流媒体与 AI 服务…" else "当前节点暂无解锁检测结果。")
                } else {
                    HomeUnlockTestDetails(
                        items = ipTestState.items,
                        isRunning = ipTestState.isRunning,
                        completedCount = ipTestState.completedCount
                    )
                }
            }
        )
        null -> Unit
    }
}

@Composable
private fun HomeLinkTypeDetails(result: LinkTypeTestResult) {
    val color = when (result.state) {
        LinkTypeTestState.ERROR, LinkTypeTestState.NOT_CONNECTED -> MaterialTheme.colorScheme.error
        LinkTypeTestState.TESTING -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("链路类型", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (result.state == LinkTypeTestState.TESTING) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color)
            Spacer(Modifier.width(6.dp))
        }
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

private enum class HomeTestDetails {
    LINK_TYPE,
    UNLOCK
}

@Composable
private fun HomeTestTableDivider() {
    Box(
        Modifier.width(1.dp).height(38.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    )
}

@Composable
private fun HomeTestTableCell(
    label: String,
    value: String,
    modifier: Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isTesting: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier.fillMaxHeight().then(clickableModifier).padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "查看详情",
                    modifier = Modifier.padding(start = 2.dp).size(11.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeUnlockTestDetails(
    items: List<UnlockTestResult>,
    isRunning: Boolean,
    completedCount: Int
) {
    Column(
        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isRunning) {
            LinearProgressIndicator(
                progress = (completedCount.toFloat() / items.size).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )
        }
        items.forEachIndexed { index, result ->
            val color = when (result.state) {
                UnlockTestState.UNLOCKED -> MaterialTheme.colorScheme.primary
                UnlockTestState.BLOCKED, UnlockTestState.ERROR -> MaterialTheme.colorScheme.error
                UnlockTestState.PARTIAL, UnlockTestState.WARNING -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                HomeUnlockTestIcon(result.definition.id)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = result.definition.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(result.statusText, style = MaterialTheme.typography.labelSmall, color = color)
                    }
                    Text(result.description, style = MaterialTheme.typography.bodySmall)
                    val metadata = listOfNotNull(
                        result.definition.category.title,
                        result.region.takeIf { it.isNotBlank() }?.let { "地区：$it" },
                        result.delayMs?.let { "延迟：${it} ms" }
                    ).joinToString(" · ")
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index < items.lastIndex) {
                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

// 测试选项列表与结果明细共用同一套品牌图标。
internal fun unlockTestIconResource(id: String): Int = when (id) {
    "google_cn" -> R.drawable.ic_unlock_google
    "chatgpt" -> R.drawable.ic_unlock_chatgpt
    "copilot" -> R.drawable.ic_unlock_copilot
    "sora" -> R.drawable.ic_unlock_chatgpt
    "grok" -> R.drawable.ic_unlock_grok
    "llama" -> R.drawable.ic_unlock_meta
    "perplexity" -> R.drawable.ic_unlock_perplexity
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
    "bilibili" -> R.drawable.ic_unlock_bilibili
    "dazn" -> R.drawable.ic_unlock_dazn
    "abema" -> R.drawable.ic_unlock_abema
    "bahamut" -> R.drawable.ic_unlock_bahamut
    "steam" -> R.drawable.ic_unlock_steam
    else -> R.drawable.ic_vpn_key
}

@Composable
private fun HomeUnlockTestIcon(id: String) {
    Image(
        painter = painterResource(unlockTestIconResource(id)),
        contentDescription = null,
        modifier = Modifier.size(32.dp),
        contentScale = ContentScale.Fit
    )
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

private fun Float.homeSpeedCompact(): String =
    if (isFinite() && this > 0f) "${"%.1f".format(Locale.ROOT, this)}M" else "未测"
