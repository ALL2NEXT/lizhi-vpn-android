package xyz.a202132.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeDisplaySettings
import xyz.a202132.app.data.model.ProtocolCoreSettings
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.theme.LatencyGood
import xyz.a202132.app.util.protocolDisplayLabel

/** 主页链路概览；只组合现有节点与连接状态，不发起额外网络请求。 */
@Composable
internal fun ConnectionRouteOverview(
    node: Node?,
    vpnState: VpnState,
    isTesting: Boolean,
    enabled: Boolean,
    displaySettings: NodeDisplaySettings,
    coreSettings: ProtocolCoreSettings,
    onNodeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayState = if (isTesting && vpnState == VpnState.DISCONNECTED) {
        VpnState.CONNECTING
    } else {
        vpnState
    }
    val routeColor = when (displayState) {
        VpnState.CONNECTED -> LatencyGood
        VpnState.CONNECTING, VpnState.DISCONNECTING -> MaterialTheme.colorScheme.primary
        VpnState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    val statusTitle = when (displayState) {
        VpnState.CONNECTED -> "连接稳定 · 流量已受保护"
        VpnState.CONNECTING -> "正在建立安全链路"
        VpnState.DISCONNECTING -> "正在断开安全链路"
        VpnState.DISCONNECTED -> "尚未连接 · 点击下方按钮"
    }
    val protocolLabel = node?.let {
        it.protocolDisplayLabel(displaySettings, coreSettings)
            .ifBlank { it.type.protocol }
            .replace("+", " · ")
    } ?: "等待选择"
    val nodeTitle = node?.let { current ->
        current.getDisplayName().ifBlank { current.server }
    } ?: "选择节点"

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            RouteEndpoint(
                icon = Icons.Outlined.PhoneAndroid,
                title = "此设备",
                activeColor = routeColor,
                modifier = Modifier.width(56.dp)
            )
            TunnelSegment(
                label = protocolLabel,
                tcping = formatLatency(node?.tcpingLatency),
                color = routeColor,
                modifier = Modifier.weight(1f)
            )
            RouteEndpoint(
                icon = Icons.Outlined.Dns,
                title = nodeTitle,
                activeColor = routeColor,
                trailingIcon = Icons.Rounded.ChevronRight,
                enabled = enabled,
                onClick = onNodeClick,
                modifier = Modifier.width(72.dp)
            )
            InternetSegment(color = routeColor)
            RouteEndpoint(
                icon = Icons.Outlined.Public,
                title = "互联网",
                activeColor = routeColor,
                modifier = Modifier.width(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = statusTitle,
            color = routeColor,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))
        ProxyRouteMetrics(
            urlLatency = formatLatency(node?.urlTestLatency),
            downloadBandwidth = formatBandwidth(node?.downloadMbps),
            color = routeColor
        )

        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = node?.let {
                    "订阅：${it.getConnectionGroupName()} · ${it.server}"
                } ?: "点击服务器图标选择节点",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RouteEndpoint(
    icon: ImageVector,
    title: String,
    activeColor: Color,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = false,
    onClick: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClick = onClick)
                        .padding(bottom = 4.dp)
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(46.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(23.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(7.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = "切换节点",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(activeColor, CircleShape)
        )
    }
}

@Composable
private fun TunnelSegment(
    label: String,
    tcping: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            contentAlignment = Alignment.Center
        ) {
            Divider(
                modifier = Modifier.fillMaxWidth(),
                color = color,
                thickness = 2.dp
            )
            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .widthIn(max = 150.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "加密隧道",
                        tint = color,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        InlineRouteMetric(label = "TCPing", value = tcping)
    }
}

@Composable
private fun InternetSegment(
    color: Color
) {
    Box(
        modifier = Modifier
            .width(24.dp)
            .height(46.dp),
        contentAlignment = Alignment.Center
    ) {
        Divider(color = color, thickness = 2.dp)
        Icon(
            imageVector = Icons.Outlined.MoreHoriz,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ProxyRouteMetrics(
    urlLatency: String,
    downloadBandwidth: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "经代理",
            color = color,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(8.dp))
        InlineRouteMetric(label = "204", value = urlLatency)
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.ArrowDownward,
            contentDescription = "下载带宽",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "下载 · $downloadBandwidth",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InlineRouteMetric(label: String, value: String) {
    Text(
        text = "$label · $value",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun formatLatency(value: Int?): String = when {
    value == null || value == -1 -> "--"
    value == -2 -> "失败"
    value > 0 -> "$value ms"
    else -> "--"
}

private fun formatBandwidth(value: Float?): String = if (value != null && value > 0f && value.isFinite()) {
    String.format(Locale.US, "%.1f Mbps", value)
} else {
    "--"
}
