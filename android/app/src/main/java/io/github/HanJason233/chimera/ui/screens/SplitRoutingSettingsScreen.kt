package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold

@Composable
fun SplitRoutingSettingsScreen(
    onBack: () -> Unit,
    onOpenPerAppInbound: (ProxyMode) -> Unit,
    onOpenRoutingRules: (ProxyMode) -> Unit
) {
    var selectedMode by rememberSaveable { mutableStateOf<ProxyMode?>(null) }
    val mode = selectedMode

    // 切换内外层时重建 Scaffold，避免内部返回后仍保留离场遮罩状态。
    key(mode) {
        AppScreenScaffold(
            title = when (mode) {
                ProxyMode.GLOBAL -> "全局模式设定"
                ProxyMode.SMART -> "智能模式设定"
                null -> "分流设置"
            },
            onBack = {
                if (mode == null) onBack() else selectedMode = null
            },
            contentPadding = PaddingValues(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == null) {
                    Text(
                        text = "选择要配置的分流模式",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SplitRoutingEntry(
                        title = "全局模式设定",
                        description = "配置全局模式下的 VPN 作用范围和路由规则",
                        icon = Icons.Outlined.Public,
                        onClick = { selectedMode = ProxyMode.GLOBAL }
                    )
                    SplitRoutingEntry(
                        title = "智能模式设定",
                        description = "配置智能模式下的 VPN 作用范围和路由规则",
                        icon = Icons.Outlined.Route,
                        onClick = { selectedMode = ProxyMode.SMART }
                    )
                } else {
                    Text(
                        text = "此处的设置仅作用于当前模式。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SplitRoutingEntry(
                        title = "VPN 作用范围",
                        description = "选择哪些应用的流量进入 VPN",
                        icon = Icons.Outlined.Android,
                        onClick = { onOpenPerAppInbound(mode) }
                    )
                    SplitRoutingEntry(
                        title = "路由规则",
                        description = "设置此模式下的域名、IP 与流量规则",
                        icon = Icons.Outlined.Route,
                        onClick = { onOpenRoutingRules(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SplitRoutingEntry(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
