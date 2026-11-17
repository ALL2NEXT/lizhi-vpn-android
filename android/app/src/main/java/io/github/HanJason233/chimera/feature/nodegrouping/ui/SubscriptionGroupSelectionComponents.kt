package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.NodeGroupSummary
import io.github.HanJason233.chimera.data.model.PersonalSourceIconMode
import io.github.HanJason233.chimera.data.model.PersonalSourceUserAgentMode
import io.github.HanJason233.chimera.data.model.SubscriptionGroup
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.SubscriptionFaviconLoader
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.PersonalSourceIconStore
import io.github.HanJason233.chimera.util.TrafficUsageFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SubscriptionTrafficStatus {
    UNKNOWN,
    SAFE,
    WARNING,
    CRITICAL,
    EXHAUSTED
}

private data class SubscriptionTrafficInfo(
    val text: String,
    val status: SubscriptionTrafficStatus,
    val progress: Float?
)

@Composable
internal fun PersonalIconModeOption(
    title: String,
    mode: PersonalSourceIconMode,
    selectedMode: PersonalSourceIconMode,
    enabled: Boolean,
    onSelect: (PersonalSourceIconMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(mode) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selectedMode == mode,
            onClick = { onSelect(mode) },
            enabled = enabled
        )
        Text(
            text = title,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun AutoRefreshOnStartupRow(
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "每次重新启动软件后立即更新",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "应用冷启动或进程被系统结束后重新打开时更新此源。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
internal fun PersonalSourceUserAgentOption(
    title: String,
    mode: PersonalSourceUserAgentMode,
    selectedMode: PersonalSourceUserAgentMode,
    enabled: Boolean,
    onSelect: (PersonalSourceUserAgentMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(mode) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selectedMode == mode,
            onClick = { onSelect(mode) },
            enabled = enabled
        )
        Text(
            text = title,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun SubscriptionGroupCard(
    modifier: Modifier = Modifier,
    group: SubscriptionGroup,
    nodeCount: Int,
    isRefreshing: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    GroupCard(
        modifier = modifier,
        name = group.name,
        nodeCount = nodeCount,
        isRefreshing = isRefreshing,
        updatedAt = group.updatedAt,
        status = "长期有效",
        selected = selected,
        onClick = onClick
    )
}

@Composable
internal fun UserSubscriptionGroupCard(
    modifier: Modifier = Modifier,
    group: NodeGroupSummary,
    nodeCount: Int,
    isRefreshing: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    GroupCard(
        modifier = modifier,
        name = group.name,
        subscriptionUrl = group.subscriptionUrl,
        iconMode = group.iconMode,
        customIconUrl = group.customIconUrl,
        localIconFile = group.localIconFile,
        nodeCount = nodeCount,
        isRefreshing = isRefreshing,
        traffic = personalSubscriptionTrafficV2(group),
        updatedAt = group.subscriptionUpdatedAt,
        status = personalSubscriptionStatus(group),
        selected = selected,
        onClick = onClick
    )
}

@Composable
internal fun GroupTypeHeader(
    title: String,
    expanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            imageVector = if (expanded) {
                Icons.Default.KeyboardArrowUp
            } else {
                Icons.Default.KeyboardArrowDown
            },
            contentDescription = if (expanded) "收起$title" else "展开$title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun SubscriptionImportOption(
    modifier: Modifier,
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun GroupCard(
    modifier: Modifier = Modifier,
    name: String,
    subscriptionUrl: String? = null,
    iconMode: String = PersonalSourceIconMode.AUTO_REMOTE.name,
    customIconUrl: String? = null,
    localIconFile: String? = null,
    nodeCount: Int,
    isRefreshing: Boolean = false,
    traffic: SubscriptionTrafficInfo? = null,
    updatedAt: Long? = null,
    status: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    ModernGroupCard(
        modifier = modifier,
        name = name,
        subscriptionUrl = subscriptionUrl,
        iconMode = iconMode,
        customIconUrl = customIconUrl,
        localIconFile = localIconFile,
        nodeCount = nodeCount,
        isRefreshing = isRefreshing,
        traffic = traffic,
        updatedAt = updatedAt,
        status = status,
        selected = selected,
        onClick = onClick
    )
}

@Composable
private fun ModernGroupCard(
    modifier: Modifier = Modifier,
    name: String,
    subscriptionUrl: String?,
    iconMode: String,
    customIconUrl: String?,
    localIconFile: String?,
    nodeCount: Int,
    isRefreshing: Boolean,
    traffic: SubscriptionTrafficInfo?,
    updatedAt: Long?,
    status: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val parsedIconMode = remember(iconMode) {
        PersonalSourceIconMode.fromStorage(iconMode)
    }
    val remoteIconUrl = when (parsedIconMode) {
        PersonalSourceIconMode.AUTO_REMOTE ->
            SubscriptionFaviconLoader.faviconUrl(subscriptionUrl)
        PersonalSourceIconMode.CUSTOM_REMOTE ->
            SubscriptionFaviconLoader.customImageUrl(customIconUrl)
        PersonalSourceIconMode.DEFAULT,
        PersonalSourceIconMode.LOCAL -> null
    }
    val iconLoadKey = when (parsedIconMode) {
        PersonalSourceIconMode.DEFAULT -> "default"
        PersonalSourceIconMode.AUTO_REMOTE -> "auto:$remoteIconUrl"
        PersonalSourceIconMode.CUSTOM_REMOTE -> "remote:$remoteIconUrl"
        PersonalSourceIconMode.LOCAL -> "local:$localIconFile"
    }
    val iconCacheRevision by SubscriptionFaviconLoader.cacheRevision
        .collectAsStateWithLifecycle()
    val iconLoadStateKey = "$iconLoadKey:$iconCacheRevision"
    val appContext = LocalContext.current.applicationContext
    val groupIcon by produceState<ImageBitmap?>(initialValue = null, key1 = iconLoadStateKey) {
        value = when (parsedIconMode) {
            PersonalSourceIconMode.DEFAULT -> null
            PersonalSourceIconMode.AUTO_REMOTE,
            PersonalSourceIconMode.CUSTOM_REMOTE -> remoteIconUrl?.let { url ->
                withContext(Dispatchers.IO) {
                    SubscriptionFaviconLoader.loadCached(appContext, url)?.asImageBitmap()
                }
            }
            PersonalSourceIconMode.LOCAL -> withContext(Dispatchers.IO) {
                PersonalSourceIconStore.load(appContext, localIconFile)?.asImageBitmap()
            }
        }
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val mutedColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusColor = if (status?.startsWith("已过期") == true) {
        MaterialTheme.colorScheme.error
    } else {
        mutedColor
    }
    val progress = traffic?.progress?.coerceIn(0f, 1f)
    val progressColor = when (traffic?.status) {
        SubscriptionTrafficStatus.SAFE -> Color(0xFF2E7D32)
        SubscriptionTrafficStatus.WARNING -> Color(0xFFF9A825)
        SubscriptionTrafficStatus.CRITICAL -> MaterialTheme.colorScheme.error
        SubscriptionTrafficStatus.EXHAUSTED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val progressTrackColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    }
    val progressModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 6.dp, max = 6.dp)
        .clip(RoundedCornerShape(50))
        .background(progressTrackColor)
        .then(
            if (progress != null) {
                Modifier.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f, 0)
                }
            } else {
                Modifier
            }
        )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 34.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .offset(x = (-8).dp)
                    .width(48.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                val loadedIcon = groupIcon
                if (isRefreshing) {
                    RefreshingSubscriptionGroupIcon(
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                } else if (loadedIcon != null) {
                    Image(
                        bitmap = loadedIcon,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Subscriptions,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = name,
                        modifier = Modifier.weight(1f),
                        color = contentColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    status?.let { label ->
                        Text(
                            text = if (label == "长期有效") {
                                label
                            } else {
                                "到期时间: $label"
                            },
                            modifier = Modifier.padding(start = 8.dp),
                            color = statusColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Box(modifier = progressModifier) {
                    progress?.let { value ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(value)
                                .fillMaxHeight()
                                .background(progressColor)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = traffic?.text ?: "流量数据不可用",
                        modifier = Modifier.weight(1f),
                        color = mutedColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    formatRelativeUpdate(updatedAt)?.let { updatedText ->
                        Text(
                            text = "· $updatedText",
                            modifier = Modifier.padding(start = 6.dp),
                            color = mutedColor,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$nodeCount 个节点",
                        color = mutedColor,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun RefreshingSubscriptionGroupIcon(tint: Color) {
    val transition = rememberInfiniteTransition(label = "subscription_group_refresh")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "subscription_group_refresh_rotation"
    )
    Icon(
        imageVector = Icons.Rounded.Refresh,
        contentDescription = "正在刷新节点",
        tint = tint,
        modifier = Modifier
            .size(36.dp)
            .rotate(rotation)
    )
}

private val subscriptionExpireFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatRelativeUpdate(updatedAt: Long?): String? {
    val timestamp = updatedAt?.takeIf { it > 0L } ?: return null
    val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> "刚刚"
        elapsed < 3_600_000L -> "${elapsed / 60_000L}分钟前"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L}小时前"
        elapsed < 604_800_000L -> "${elapsed / 86_400_000L}天前"
        else -> runCatching {
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(subscriptionExpireFormatter)
        }.getOrNull() ?: "更新时间未知"
    }
}

private fun personalSubscriptionTrafficV2(group: NodeGroupSummary): SubscriptionTrafficInfo? {
    val hasTraffic = group.subscriptionUploadBytes != null ||
        group.subscriptionDownloadBytes != null ||
        group.subscriptionTotalBytes != null
    if (!hasTraffic) return null

    val upload = group.subscriptionUploadBytes?.coerceAtLeast(0L) ?: 0L
    val download = group.subscriptionDownloadBytes?.coerceAtLeast(0L) ?: 0L
    val used = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    val total = group.subscriptionTotalBytes?.coerceAtLeast(0L)
        ?: return SubscriptionTrafficInfo(
            text = "已用 ${TrafficUsageFormatter.formatBytes(used)}",
            status = SubscriptionTrafficStatus.UNKNOWN,
            progress = null
        )

    if (total <= 0L) {
        return SubscriptionTrafficInfo(
            text = "总量 ${TrafficUsageFormatter.formatBytes(total)} / 已用 ${TrafficUsageFormatter.formatBytes(used)}",
            status = SubscriptionTrafficStatus.UNKNOWN,
            progress = 0f
        )
    }

    val usedRatio = used.toDouble() / total.toDouble()
    val remainingRatio = 1.0 - usedRatio
    val status = when {
        remainingRatio < 0.0 -> SubscriptionTrafficStatus.EXHAUSTED
        remainingRatio >= 0.5 -> SubscriptionTrafficStatus.SAFE
        remainingRatio >= 0.1 -> SubscriptionTrafficStatus.WARNING
        else -> SubscriptionTrafficStatus.CRITICAL
    }

    return SubscriptionTrafficInfo(
        text = "总量 ${TrafficUsageFormatter.formatBytes(total)} / 已用 ${TrafficUsageFormatter.formatBytes(used)}",
        status = status,
        progress = remainingRatio.coerceIn(0.0, 1.0).toFloat()
    )
}

private fun personalSubscriptionStatus(group: NodeGroupSummary): String {
    val expireAt = group.subscriptionExpireAt?.takeIf { it > 0L }
        ?: return "长期有效"
    val formatted = runCatching {
        Instant.ofEpochMilli(expireAt)
            .atZone(ZoneId.systemDefault())
            .format(subscriptionExpireFormatter)
    }.getOrNull() ?: return "有效期未知"
    return if (expireAt <= System.currentTimeMillis()) {
        "已过期 $formatted"
    } else {
        formatted
    }
}
