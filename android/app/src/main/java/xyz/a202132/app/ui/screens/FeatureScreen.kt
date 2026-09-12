package xyz.a202132.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.BuildConfig
import xyz.a202132.app.data.model.NodeType
import xyz.a202132.app.data.model.ProtocolCoreSettings
import xyz.a202132.app.data.model.ProxyCore
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FeatureScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val protocolCoreSettings by viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val nodeDisplaySettings by viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()

    AppScreenScaffold(title = "特色功能", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            FeatureSection("节点标签显示", initiallyExpanded = false) {
                Text(
                    text = "协议、安全层、传输方式和核心可独立显示，默认全部关闭；全部关闭后隐藏节点标签。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                FeatureSwitchRow("显示协议", nodeDisplaySettings.showProtocol) {
                    viewModel.setNodeLabelShowProtocol(it)
                }
                FeatureSwitchRow("显示安全层", nodeDisplaySettings.showSecurity) {
                    viewModel.setNodeLabelShowSecurity(it)
                }
                FeatureSwitchRow("显示传输方式", nodeDisplaySettings.showTransport) {
                    viewModel.setNodeLabelShowTransport(it)
                }
                FeatureSwitchRow("显示核心", nodeDisplaySettings.showCore) {
                    viewModel.setNodeLabelShowCore(it)
                }
            }

            FeatureSection("协议核心", initiallyExpanded = false) {
                Text(
                    text = "可为支持的协议选择 sing-box 或 Xray，修改后会在下次连接时生效。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                ProtocolCoreSettings.DISPLAY_PROTOCOLS.forEach { nodeType ->
                    ProtocolCoreRow(
                        label = nodeType.displayName(),
                        selectedCore = protocolCoreSettings.coreFor(nodeType),
                        availableCores = xyz.a202132.app.core.CoreBackends.registry.availableFor(nodeType),
                        onSelected = { core -> viewModel.setProtocolCore(nodeType, core) }
                    )
                }
                Text(
                    text = "内置 Xray ${BuildConfig.XRAY_CORE_VERSION}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun ProtocolCoreRow(
    label: String,
    selectedCore: ProxyCore,
    availableCores: List<ProxyCore>,
    onSelected: (ProxyCore) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(
            modifier = Modifier.weight(2f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            availableCores.forEach { core ->
                FilterChip(
                    selected = selectedCore == core,
                    onClick = { onSelected(core) },
                    label = { Text(core.displayName) }
                )
            }
        }
    }
}

private fun NodeType.displayName(): String = when (this) {
    NodeType.VLESS -> "VLESS"
    NodeType.VMESS -> "VMess"
    NodeType.TROJAN -> "Trojan"
    NodeType.HYSTERIA2 -> "Hysteria2"
    NodeType.ANYTLS -> "AnyTLS"
    NodeType.TUIC -> "TUIC"
    NodeType.NAIVE -> "Naive"
    NodeType.WIREGUARD -> "WireGuard"
    NodeType.SHADOWSOCKS -> "Shadowsocks"
    NodeType.SOCKS -> "SOCKS"
    NodeType.HTTP -> "HTTP"
    NodeType.UNKNOWN -> "Unknown"
}

@Composable
private fun FeatureSection(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "折叠$title" else "展开$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun FeatureSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}
