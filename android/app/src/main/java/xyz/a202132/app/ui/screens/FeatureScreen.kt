package xyz.a202132.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val smartConnectFullTestEnabled by
        viewModel.smartConnectFullTestEnabled.collectAsStateWithLifecycle()
    val publicNodePoolOptimizationEnabled by
        viewModel.publicNodePoolOptimizationEnabled.collectAsStateWithLifecycle()
    val publicNodePoolNodeThreshold by viewModel.publicNodePoolNodeThreshold.collectAsStateWithLifecycle()
    val publicNodePoolTcpingPercent by
        viewModel.publicNodePoolTcpingPercent.collectAsStateWithLifecycle()
    val publicNodePoolUrlTestPercent by
        viewModel.publicNodePoolUrlTestPercent.collectAsStateWithLifecycle()
    val publicNodePoolSpeedTestPercent by
        viewModel.publicNodePoolSpeedTestPercent.collectAsStateWithLifecycle()
    val publicNodePoolTcpingTestTimeoutMs by
        viewModel.publicNodePoolTcpingTestTimeoutMs.collectAsStateWithLifecycle()
    val publicNodePoolUrlTestTimeoutMs by
        viewModel.publicNodePoolUrlTestTimeoutMs.collectAsStateWithLifecycle()
    val publicNodePoolSpeedTestDurationMs by
        viewModel.publicNodePoolSpeedTestDurationMs.collectAsStateWithLifecycle()
    val publicNodePoolTcpingConcurrency by
        viewModel.publicNodePoolTcpingConcurrency.collectAsStateWithLifecycle()
    val publicNodePoolUrlTestConcurrency by
        viewModel.publicNodePoolUrlTestConcurrency.collectAsStateWithLifecycle()
    val publicNodePoolBandwidthTestConcurrency by
        viewModel.publicNodePoolBandwidthTestConcurrency.collectAsStateWithLifecycle()
    val tcpingTestTimeoutMs by viewModel.tcpingTestTimeoutMs.collectAsStateWithLifecycle()
    val urlTestTimeoutMs by viewModel.urlTestTimeoutMs.collectAsStateWithLifecycle()
    val speedTestDurationMs by viewModel.speedTestDurationMs.collectAsStateWithLifecycle()
    val tcpingConcurrency by viewModel.tcpingConcurrency.collectAsStateWithLifecycle()
    val urlTestConcurrency by viewModel.urlTestConcurrency.collectAsStateWithLifecycle()
    val bandwidthTestConcurrency by viewModel.bandwidthTestConcurrency.collectAsStateWithLifecycle()
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
    var tcpingTimeoutInput by remember(tcpingTestTimeoutMs) {
        mutableStateOf(tcpingTestTimeoutMs.toString())
    }
    var urlTestTimeoutInput by remember(urlTestTimeoutMs) {
        mutableStateOf(urlTestTimeoutMs.toString())
    }
    var speedTestDurationInput by remember(speedTestDurationMs) {
        mutableStateOf(speedTestDurationMs.toString())
    }
    var tcpingConcurrencyInput by remember(tcpingConcurrency) {
        mutableStateOf(tcpingConcurrency.toString())
    }
    var urlTestConcurrencyInput by remember(urlTestConcurrency) {
        mutableStateOf(urlTestConcurrency.toString())
    }
    var bandwidthTestConcurrencyInput by remember(bandwidthTestConcurrency) {
        mutableStateOf(bandwidthTestConcurrency.toString())
    }
    var publicNodePoolThresholdInput by remember(publicNodePoolNodeThreshold) {
        mutableStateOf(publicNodePoolNodeThreshold.toString())
    }
    var publicNodePoolTcpingPercentInput by remember(publicNodePoolTcpingPercent) {
        mutableStateOf(publicNodePoolTcpingPercent.toString())
    }
    var publicNodePoolUrlTestPercentInput by remember(publicNodePoolUrlTestPercent) {
        mutableStateOf(publicNodePoolUrlTestPercent.toString())
    }
    var publicNodePoolSpeedTestPercentInput by remember(publicNodePoolSpeedTestPercent) {
        mutableStateOf(publicNodePoolSpeedTestPercent.toString())
    }
    var publicNodePoolTcpingTimeoutInput by remember(publicNodePoolTcpingTestTimeoutMs) {
        mutableStateOf(publicNodePoolTcpingTestTimeoutMs.toString())
    }
    var publicNodePoolUrlTestTimeoutInput by remember(publicNodePoolUrlTestTimeoutMs) {
        mutableStateOf(publicNodePoolUrlTestTimeoutMs.toString())
    }
    var publicNodePoolSpeedTestDurationInput by remember(publicNodePoolSpeedTestDurationMs) {
        mutableStateOf(publicNodePoolSpeedTestDurationMs.toString())
    }
    var publicNodePoolTcpingConcurrencyInput by remember(publicNodePoolTcpingConcurrency) {
        mutableStateOf(publicNodePoolTcpingConcurrency.toString())
    }
    var publicNodePoolUrlTestConcurrencyInput by remember(publicNodePoolUrlTestConcurrency) {
        mutableStateOf(publicNodePoolUrlTestConcurrency.toString())
    }
    var publicNodePoolBandwidthTestConcurrencyInput by
        remember(publicNodePoolBandwidthTestConcurrency) {
            mutableStateOf(publicNodePoolBandwidthTestConcurrency.toString())
        }
    var testSettingsType by remember { mutableStateOf(0) }
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
        tcpingTestTimeoutMs,
        urlTestTimeoutMs,
        speedTestDurationMs,
        tcpingConcurrency,
        urlTestConcurrency,
        bandwidthTestConcurrency,
        publicNodePoolNodeThreshold,
        publicNodePoolTcpingPercent,
        publicNodePoolUrlTestPercent,
        publicNodePoolSpeedTestPercent,
        publicNodePoolTcpingTestTimeoutMs,
        publicNodePoolUrlTestTimeoutMs,
        publicNodePoolSpeedTestDurationMs,
        publicNodePoolTcpingConcurrency,
        publicNodePoolUrlTestConcurrency,
        publicNodePoolBandwidthTestConcurrency,
        hysteriaUpMbps,
        hysteriaDownMbps
    ) {
        tcpingPercentInput = smartConnectTcpingPercent.toString()
        urlTestPercentInput = smartConnectUrlTestPercent.toString()
        speedTestPercentInput = smartConnectSpeedTestPercent.toString()
        tcpingTimeoutInput = tcpingTestTimeoutMs.toString()
        urlTestTimeoutInput = urlTestTimeoutMs.toString()
        speedTestDurationInput = speedTestDurationMs.toString()
        tcpingConcurrencyInput = tcpingConcurrency.toString()
        urlTestConcurrencyInput = urlTestConcurrency.toString()
        bandwidthTestConcurrencyInput = bandwidthTestConcurrency.toString()
        publicNodePoolThresholdInput = publicNodePoolNodeThreshold.toString()
        publicNodePoolTcpingPercentInput = publicNodePoolTcpingPercent.toString()
        publicNodePoolUrlTestPercentInput = publicNodePoolUrlTestPercent.toString()
        publicNodePoolSpeedTestPercentInput = publicNodePoolSpeedTestPercent.toString()
        publicNodePoolTcpingTimeoutInput = publicNodePoolTcpingTestTimeoutMs.toString()
        publicNodePoolUrlTestTimeoutInput = publicNodePoolUrlTestTimeoutMs.toString()
        publicNodePoolSpeedTestDurationInput = publicNodePoolSpeedTestDurationMs.toString()
        publicNodePoolTcpingConcurrencyInput = publicNodePoolTcpingConcurrency.toString()
        publicNodePoolUrlTestConcurrencyInput = publicNodePoolUrlTestConcurrency.toString()
        publicNodePoolBandwidthTestConcurrencyInput = publicNodePoolBandwidthTestConcurrency.toString()
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
                title = "智能连接"
            ) {
                FeatureSwitchRow(
                    label = "全量测试",
                    checked = smartConnectFullTestEnabled,
                    onCheckedChange = viewModel::setSmartConnectFullTestEnabled
                )
                Text(
                    text = "开启后从全部普通订阅组中测试并选择最优节点；仍遵守下方候选比例。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                FeatureSwitchRow(
                    label = "聚合订阅优化",
                    checked = publicNodePoolOptimizationEnabled,
                    onCheckedChange = viewModel::setPublicNodePoolOptimizationEnabled
                )
                Text(
                    text = "订阅组节点数大于 $publicNodePoolNodeThreshold 时，智能连接改用独立的聚合订阅测试参数。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Text(
                    text = "测试配置",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                MultiPositionSwitch(
                    options = listOf("常规订阅", "聚合订阅"),
                    selectedIndex = testSettingsType,
                    onOptionSelected = { testSettingsType = it }
                )
                Text(
                    text = if (testSettingsType == 0) {
                        "常规订阅参数也用于手动节点测速；与聚合订阅参数分别保存。"
                    } else {
                        "聚合订阅参数仅在优化开启且节点数超过判定阈值时使用。"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Text(
                    text = "时间说明：TCPing 和 204 URL Test 的时间表示单个节点的超时时间；" +
                        "带宽测速的时间表示每个节点实际持续测试的时长，单位均为毫秒。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                if (testSettingsType == 1) {
                    FeatureNumberField(
                        label = "聚合订阅判定阈值（节点数）",
                        value = publicNodePoolThresholdInput,
                        onValueChange = {
                            publicNodePoolThresholdInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setPublicNodePoolNodeThreshold(value.coerceIn(1, 10_000))
                            }
                        }
                    )
                }
                SmartTestSettingsTable(
                    rows = if (testSettingsType == 0) {
                        listOf(
                            SmartTestSettingsRow(
                                label = "TCPing",
                                percentValue = tcpingPercentInput,
                                onPercentChange = {
                                    tcpingPercentInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setSmartConnectTcpingPercent(value.coerceIn(1, 100))
                                    }
                                },
                                timeValue = tcpingTimeoutInput,
                                onTimeChange = {
                                    tcpingTimeoutInput = it.filter(Char::isDigit)
                                    it.toLongOrNull()?.let { value ->
                                        viewModel.setTcpingTestTimeoutMs(value.coerceAtLeast(500L))
                                    }
                                },
                                concurrencyValue = tcpingConcurrencyInput,
                                onConcurrencyChange = {
                                    tcpingConcurrencyInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setTcpingConcurrency(value.coerceIn(1, 128))
                                    }
                                }
                            ),
                            SmartTestSettingsRow(
                                label = "204 URL",
                                percentValue = urlTestPercentInput,
                                onPercentChange = {
                                    urlTestPercentInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setSmartConnectUrlTestPercent(value.coerceIn(1, 100))
                                    }
                                },
                                timeValue = urlTestTimeoutInput,
                                onTimeChange = {
                                    urlTestTimeoutInput = it.filter(Char::isDigit)
                                    it.toLongOrNull()?.let { value ->
                                        viewModel.setUrlTestTimeoutMs(value.coerceAtLeast(500L))
                                    }
                                },
                                concurrencyValue = urlTestConcurrencyInput,
                                onConcurrencyChange = {
                                    urlTestConcurrencyInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setUrlTestConcurrency(value.coerceIn(1, 128))
                                    }
                                }
                            ),
                            SmartTestSettingsRow(
                                label = "带宽测速",
                                percentValue = speedTestPercentInput,
                                onPercentChange = {
                                    speedTestPercentInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setSmartConnectSpeedTestPercent(value.coerceIn(1, 100))
                                    }
                                },
                                timeValue = speedTestDurationInput,
                                onTimeChange = {
                                    speedTestDurationInput = it.filter(Char::isDigit)
                                    it.toLongOrNull()?.let { value ->
                                        viewModel.setSpeedTestDurationMs(value.coerceAtLeast(1_000L))
                                    }
                                },
                                concurrencyValue = bandwidthTestConcurrencyInput,
                                onConcurrencyChange = {
                                    bandwidthTestConcurrencyInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setBandwidthTestConcurrency(value.coerceIn(1, 3))
                                    }
                                }
                            )
                        )
                    } else {
                        listOf(
                            SmartTestSettingsRow(
                                label = "TCPing",
                                percentValue = publicNodePoolTcpingPercentInput,
                                onPercentChange = {
                                    publicNodePoolTcpingPercentInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolTcpingPercent(value.coerceIn(1, 100))
                                    }
                                },
                                timeValue = publicNodePoolTcpingTimeoutInput,
                                onTimeChange = {
                                    publicNodePoolTcpingTimeoutInput = it.filter(Char::isDigit)
                                    it.toLongOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolTcpingTestTimeoutMs(value.coerceAtLeast(500L))
                                    }
                                },
                                concurrencyValue = publicNodePoolTcpingConcurrencyInput,
                                onConcurrencyChange = {
                                    publicNodePoolTcpingConcurrencyInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolTcpingConcurrency(value.coerceIn(1, 512))
                                    }
                                }
                            ),
                            SmartTestSettingsRow(
                                label = "204 URL",
                                percentValue = publicNodePoolUrlTestPercentInput,
                                onPercentChange = {
                                    publicNodePoolUrlTestPercentInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolUrlTestPercent(value.coerceIn(1, 100))
                                    }
                                },
                                timeValue = publicNodePoolUrlTestTimeoutInput,
                                onTimeChange = {
                                    publicNodePoolUrlTestTimeoutInput = it.filter(Char::isDigit)
                                    it.toLongOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolUrlTestTimeoutMs(value.coerceAtLeast(500L))
                                    }
                                },
                                concurrencyValue = publicNodePoolUrlTestConcurrencyInput,
                                onConcurrencyChange = {
                                    publicNodePoolUrlTestConcurrencyInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolUrlTestConcurrency(value.coerceIn(1, 128))
                                    }
                                }
                            ),
                            SmartTestSettingsRow(
                                label = "带宽测速",
                                percentValue = publicNodePoolSpeedTestPercentInput,
                                onPercentChange = {
                                    publicNodePoolSpeedTestPercentInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolSpeedTestPercent(value.coerceIn(1, 100))
                                    }
                                },
                                timeValue = publicNodePoolSpeedTestDurationInput,
                                onTimeChange = {
                                    publicNodePoolSpeedTestDurationInput = it.filter(Char::isDigit)
                                    it.toLongOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolSpeedTestDurationMs(value.coerceAtLeast(1_000L))
                                    }
                                },
                                concurrencyValue = publicNodePoolBandwidthTestConcurrencyInput,
                                onConcurrencyChange = {
                                    publicNodePoolBandwidthTestConcurrencyInput = it.filter(Char::isDigit)
                                    it.toIntOrNull()?.let { value ->
                                        viewModel.setPublicNodePoolBandwidthTestConcurrency(value.coerceIn(1, 3))
                                    }
                                }
                            )
                        )
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

            FeatureSection(
                title = "节点标签显示",
                collapsible = true,
                initiallyExpanded = false
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
                title = "协议核心",
                collapsible = true,
                initiallyExpanded = false
            ) {
                Text(
                    text = "vless，vmess适用于xray生态，带有自定义证书，ech等特殊设置优先使用xray，其余使用singbox",
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(modifier = Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
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
    titleAction: (@Composable () -> Unit)? = null,
    collapsible: Boolean = false,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) {
                        Modifier
                            .heightIn(min = 48.dp)
                            .clickable { expanded = !expanded }
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
        if (!collapsible || expanded) {
            content()
        }
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

private data class SmartTestSettingsRow(
    val label: String,
    val percentValue: String,
    val onPercentChange: (String) -> Unit,
    val timeValue: String,
    val onTimeChange: (String) -> Unit,
    val concurrencyValue: String,
    val onConcurrencyChange: (String) -> Unit
)

@Composable
private fun SmartTestSettingsTable(rows: List<SmartTestSettingsRow>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SmartTestSettingsTableRow {
            Text(
                text = "类型",
                modifier = Modifier.width(SmartTestTypeColumnWidth),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            SmartTestTableHeader("比例 (%)", Modifier.weight(1f))
            SmartTestTableHeader("时间 (ms)", Modifier.weight(1f))
            SmartTestTableHeader("并发", Modifier.weight(1f))
        }
        rows.forEach { row ->
            SmartTestSettingsTableRow {
                Text(
                    text = row.label,
                    modifier = Modifier.width(SmartTestTypeColumnWidth),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                CompactFeatureNumberField(
                    value = row.percentValue,
                    onValueChange = row.onPercentChange,
                    modifier = Modifier.weight(1f)
                )
                CompactFeatureNumberField(
                    value = row.timeValue,
                    onValueChange = row.onTimeChange,
                    modifier = Modifier.weight(1f)
                )
                CompactFeatureNumberField(
                    value = row.concurrencyValue,
                    onValueChange = row.onConcurrencyChange,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SmartTestSettingsTableRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun SmartTestTableHeader(text: String, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

@Composable
private fun CompactFeatureNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

private val SmartTestTypeColumnWidth = 70.dp
