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
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.ProxyMode
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.ui.components.ProxyModeToggle
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun SplitRoutingSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenPerAppInbound: (ProxyMode) -> Unit,
    onOpenRoutingRules: (ProxyMode) -> Unit
) {
    val activeMode by viewModel.proxyMode.collectAsStateWithLifecycle()
    // 默认跟随首页正在使用的模式，切换模式只改变当前编辑分组。
    var editingMode by rememberSaveable { mutableStateOf<ProxyMode?>(null) }
    val mode = editingMode ?: activeMode

    AppScreenScaffold(
        title = "分流设置",
        onBack = onBack,
        contentPadding = PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "配置模式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                ProxyModeToggle(
                    isGlobalMode = mode == ProxyMode.GLOBAL,
                    onModeChange = { isGlobal ->
                        editingMode = if (isGlobal) ProxyMode.GLOBAL else ProxyMode.SMART
                    }
                )
                Text(
                    text = if (mode == ProxyMode.GLOBAL) {
                        "全局模式的 VPN 作用范围和路由规则独立保存。"
                    } else {
                        "智能模式的 VPN 作用范围和路由规则独立保存。"
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
