package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
fun FeatureScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val smartConnectTcpingPercent by viewModel.smartConnectTcpingPercent.collectAsStateWithLifecycle()
    val smartConnectUrlTestPercent by viewModel.smartConnectUrlTestPercent.collectAsStateWithLifecycle()
    val smartConnectSpeedTestPercent by viewModel.smartConnectSpeedTestPercent.collectAsStateWithLifecycle()
    val disconnectRecoveryEnabled by viewModel.disconnectRecoveryEnabled.collectAsStateWithLifecycle()
    val hysteriaBandwidthEnabled by viewModel.hysteriaBandwidthEnabled.collectAsStateWithLifecycle()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsStateWithLifecycle()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsStateWithLifecycle()
    val isHysteriaBandwidthAdapting by viewModel.isHysteriaBandwidthAdapting.collectAsStateWithLifecycle()
    val protocolCoreSettings by viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val nodeDisplaySettings by viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()

    var tcpingPercentInput by remember(smartConnectTcpingPercent) {
        mutableStateOf(smartConnectTcpingPercent.toString())
    }
    var urlTestPercentInput by remember(smartConnectUrlTestPercent) {
        mutableStateOf(smartConnectUrlTestPercent.toString())
    }
    var speedTestPercentInput by remember(smartConnectSpeedTestPercent) {
        mutableStateOf(smartConnectSpeedTestPercent.toString())
    }
    var hysteriaUpMbpsInput by remember(hysteriaUpMbps) {
        mutableStateOf(hysteriaUpMbps.toString())
    }
    var hysteriaDownMbpsInput by remember(hysteriaDownMbps) {
        mutableStateOf(hysteriaDownMbps.toString())
    }

    LaunchedEffect(
        smartConnectTcpingPercent,
        smartConnectUrlTestPercent,
        smartConnectSpeedTestPercent,
        hysteriaUpMbps,
        hysteriaDownMbps
    ) {
        tcpingPercentInput = smartConnectTcpingPercent.toString()
        urlTestPercentInput = smartConnectUrlTestPercent.toString()
        speedTestPercentInput = smartConnectSpeedTestPercent.toString()
        hysteriaUpMbpsInput = hysteriaUpMbps.toString()
        hysteriaDownMbpsInput = hysteriaDownMbps.toString()
    }

    AppScreenScaffold(
        title = "特色功能",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            FeatureSection(
                title = "节点标签显示"
            ) {
                Text(
                    text = "协议、安全层、传输方式和核心可独立显示，默认全部关闭；全部关闭后隐藏节点标签。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                FeatureSwitchRow(
                    label = "显示协议",
                    checked = nodeDisplaySettings.showProtocol,
                    onCheckedChange = viewModel::setNodeLabelShowProtocol
                )
                FeatureSwitchRow(
                    label = "显示安全层",
                    checked = nodeDisplaySettings.showSecurity,
                    onCheckedChange = viewModel::setNodeLabelShowSecurity
                )
                FeatureSwitchRow(
                    label = "显示传输方式",
                    checked = nodeDisplaySettings.showTransport,
                    onCheckedChange = viewModel::setNodeLabelShowTransport
                )
                FeatureSwitchRow(
                    label = "显示核心",
                    checked = nodeDisplaySettings.showCore,
                    onCheckedChange = viewModel::setNodeLabelShowCore
                )
            }

            FeatureSection(
                title = "协议核心"
            ) {
                Text(
                    text = "全部协议默认使用 sing-box；当前有 8 种可切换为 Xray。灰色 Xray 表示核心不支持；SOCKS 的 Xray 选项仅适用于 SOCKS5。修改后会自动重连。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                ProtocolCoreSettings.DISPLAY_PROTOCOLS.forEach { nodeType ->
                    ProtocolCoreRow(
                        label = nodeType.displayName(),
                        selectedCore = protocolCoreSettings.coreFor(nodeType),
                        xrayAvailable = nodeType in ProtocolCoreSettings.XRAY_CAPABLE_PROTOCOLS,
                        onSelected = { core -> viewModel.setProtocolCore(nodeType, core) }
                    )
                }
                Text(
                    text = "内置 Xray ${BuildConfig.XRAY_CORE_VERSION}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            FeatureSection(
                title = "智能连接"
            ) {
                FeatureNumberField(
                    label = "TCPing 节点比例 (%)",
                    value = tcpingPercentInput,
                    onValueChange = {
                        tcpingPercentInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setSmartConnectTcpingPercent(value.coerceIn(1, 100))
                        }
                    }
                )
                FeatureNumberField(
                    label = "URL Test 候选比例 (%)",
                    value = urlTestPercentInput,
                    onValueChange = {
                        urlTestPercentInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setSmartConnectUrlTestPercent(value.coerceIn(1, 100))
                        }
                    }
                )
                FeatureNumberField(
                    label = "5MB 测速候选比例 (%)",
                    value = speedTestPercentInput,
                    onValueChange = {
                        speedTestPercentInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setSmartConnectSpeedTestPercent(value.coerceIn(1, 100))
                        }
                    }
                )
            }

            FeatureSection(
                title = "断线恢复",
                titleAction = {
                    Switch(
                        checked = disconnectRecoveryEnabled,
                        onCheckedChange = { viewModel.setDisconnectRecoveryEnabled(it) },
                        colors = featureSwitchColors()
                    )
                }
            ) {}

            FeatureSection(
                title = "Hy2 加速",
                titleAction = {
                    Switch(
                        checked = hysteriaBandwidthEnabled,
                        onCheckedChange = { viewModel.setHysteriaBandwidthEnabled(it) },
                        colors = featureSwitchColors()
                    )
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FeatureNumberField(
                        label = "Up",
                        value = hysteriaUpMbpsInput,
                        onValueChange = {
                            hysteriaUpMbpsInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setHysteriaUpMbps(value.coerceIn(0, 100_000))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hysteriaBandwidthEnabled
                    )
                    FeatureNumberField(
                        label = "Dw",
                        value = hysteriaDownMbpsInput,
                        onValueChange = {
                            hysteriaDownMbpsInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setHysteriaDownMbps(value.coerceIn(0, 100_000))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = hysteriaBandwidthEnabled
                    )
                    TextButton(
                        onClick = { viewModel.adaptHysteriaBandwidth() },
                        enabled = hysteriaBandwidthEnabled && !isHysteriaBandwidthAdapting
                    ) {
                        Text(if (isHysteriaBandwidthAdapting) "测试中..." else "自适应")
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProtocolCoreRow(
    label: String,
    selectedCore: ProxyCore,
    xrayAvailable: Boolean,
    onSelected: (ProxyCore) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        FilterChip(
            selected = selectedCore == ProxyCore.SING_BOX,
            onClick = { onSelected(ProxyCore.SING_BOX) },
            label = { Text("sing-box") }
        )
        FilterChip(
            selected = selectedCore == ProxyCore.XRAY,
            onClick = { onSelected(ProxyCore.XRAY) },
            enabled = xrayAvailable,
            label = { Text("Xray") }
        )
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
    titleAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        }
        content()
    }
}

@Composable
private fun FeatureSwitchRow(
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
            colors = featureSwitchColors()
        )
    }
}

@Composable
private fun featureSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)

@Composable
private fun FeatureNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
