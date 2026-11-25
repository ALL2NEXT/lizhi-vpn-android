package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.BuildConfig
import io.github.HanJason233.chimera.data.model.NodeType
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.model.ProxyCore
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.ui.components.SettingsListChoiceRow
import io.github.HanJason233.chimera.ui.components.SettingsListRow
import io.github.HanJason233.chimera.ui.components.SettingsListSectionHeader
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun OtherConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val serviceRequestTimeoutMs by viewModel.serviceRequestTimeoutMs.collectAsStateWithLifecycle()
    var serviceRequestTimeoutSecondsInput by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(serviceRequestTimeoutMs) {
        serviceRequestTimeoutSecondsInput = (serviceRequestTimeoutMs / 1000L).toString()
    }

    AppScreenScaffold(
        title = "设置",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            RoutingConnectionSettings(viewModel = viewModel)

            OtherConfigSection(title = "服务请求超时") {
                Text(
                    text = "设置本机请求后台接口和订阅源的最长等待时间。默认 25 秒，范围 5–60 秒。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = serviceRequestTimeoutSecondsInput,
                        onValueChange = { input ->
                            serviceRequestTimeoutSecondsInput = input.filter(Char::isDigit).take(2)
                        },
                        label = { Text("超时时间（秒）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    val timeoutSeconds = serviceRequestTimeoutSecondsInput.toLongOrNull()
                    TextButton(
                        enabled = timeoutSeconds?.let {
                            it in 5L..60L && it * 1000L != serviceRequestTimeoutMs
                        } == true,
                        onClick = {
                            timeoutSeconds?.let { viewModel.setServiceRequestTimeoutMs(it * 1000L) }
                        }
                    ) {
                        Text("保存")
                    }
                }
            }

        }
    }
}

@Composable
fun ProtocolCoreSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val protocolCoreSettings by viewModel.protocolCoreSettings.collectAsStateWithLifecycle()

    AppScreenScaffold(
        title = "协议核心",
        onBack = onBack,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsListSectionHeader("按协议选择核心")
            Text(
                text = "可为支持的协议选择 sing-box 或 Xray。VLESS、VMess 和 Cloudflare WARP MASQUE 默认使用 Xray；XHTTP、VLESS encryption 等扩展会使用 Xray，不支持的扩展参数会在连接前提示。",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            ProtocolCoreSettings.DISPLAY_PROTOCOLS.forEach { nodeType ->
                ProtocolCoreListRow(
                    nodeType = nodeType,
                    selectedCore = protocolCoreSettings.coreFor(nodeType),
                    availableCores = io.github.HanJason233.chimera.core.CoreBackends.registry.availableFor(nodeType),
                    onSelected = { core -> viewModel.setProtocolCore(nodeType, core) }
                )
            }
            SettingsListRow(
                icon = Icons.Outlined.Memory,
                title = "内置 sing-box 核心",
                subtitle = "版本 ${BuildConfig.SING_BOX_CORE_VERSION}"
            )
            SettingsListRow(
                icon = Icons.Outlined.Memory,
                title = "内置 Xray 核心",
                subtitle = "版本 ${BuildConfig.XRAY_CORE_VERSION}"
            )
        }
    }
}

@Composable
private fun ProtocolCoreListRow(
    nodeType: NodeType,
    selectedCore: ProxyCore,
    availableCores: List<ProxyCore>,
    onSelected: (ProxyCore) -> Unit
) {
    val subtitle = when {
        availableCores.isEmpty() -> "当前版本没有可用核心"
        availableCores.size == 1 -> "仅支持 ${availableCores.single().displayName}"
        else -> "当前使用 ${selectedCore.displayName} · 点击切换"
    }

    if (availableCores.size > 1) {
        SettingsListChoiceRow(
            icon = nodeType.settingsIcon(),
            title = nodeType.displayName(),
            subtitle = subtitle,
            options = availableCores,
            selected = selectedCore,
            optionLabel = ProxyCore::displayName,
            onSelected = onSelected
        )
    } else {
        SettingsListRow(
            icon = nodeType.settingsIcon(),
            title = nodeType.displayName(),
            subtitle = subtitle
        )
    }
}

private fun NodeType.settingsIcon(): ImageVector = when (this) {
    NodeType.VLESS,
    NodeType.VMESS,
    NodeType.TROJAN,
    NodeType.ANYTLS -> Icons.Outlined.VpnKey

    NodeType.HYSTERIA2,
    NodeType.TUIC -> Icons.Outlined.FlashOn

    NodeType.NAIVE,
    NodeType.HTTP -> Icons.Outlined.Language

    NodeType.WIREGUARD,
    NodeType.MASQUE,
    NodeType.SHADOWSOCKS,
    NodeType.SOCKS -> Icons.Outlined.Route

    NodeType.UNKNOWN -> Icons.Outlined.Memory
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
    NodeType.MASQUE -> "MASQUE"
    NodeType.SHADOWSOCKS -> "Shadowsocks"
    NodeType.SOCKS -> "SOCKS"
    NodeType.HTTP -> "HTTP"
    NodeType.UNKNOWN -> "Unknown"
}

@Composable
private fun ConfigSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = visibleSwitchColors()
        )
    }
}

@Composable
private fun OtherConfigSection(
    title: String? = null,
    titleAction: (@Composable () -> Unit)? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title ?: "") { mutableStateOf(initiallyExpanded) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (collapsible) 48.dp else 0.dp)
                    .then(
                        if (collapsible) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                titleAction?.invoke()
                if (collapsible) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) "折叠$title" else "展开$title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!collapsible || expanded) content()
    }
}

@Composable
private fun visibleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)
