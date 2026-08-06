package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.TunStack
import xyz.a202132.app.data.model.VpnState
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.theme.Primary
import xyz.a202132.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtherConfigScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val scheduledNodeUpdateEnabled by viewModel.scheduledNodeUpdateEnabled.collectAsState()
    val scheduledNodeUpdateHours by viewModel.scheduledNodeUpdateHours.collectAsState()
    val scheduledNodeUpdateMinutes by viewModel.scheduledNodeUpdateMinutes.collectAsState()
    val nodeAutoReconnect by viewModel.nodeAutoReconnect.collectAsState()
    val scheduledNodeUpdateToastEnabled by viewModel.scheduledNodeUpdateToastEnabled.collectAsState()
    val effectiveScheduledNodeUpdateSettings by viewModel.effectiveScheduledNodeUpdateSettings.collectAsState()
    val tcpingTestTimeoutMs by viewModel.tcpingTestTimeoutMs.collectAsState()
    val urlTestTimeoutMs by viewModel.urlTestTimeoutMs.collectAsState()
    val speedTestDownloadTimeoutMs by viewModel.speedTestDownloadTimeoutMs.collectAsState()
    val tcpingConcurrency by viewModel.tcpingConcurrency.collectAsState()
    val urlTestConcurrency by viewModel.urlTestConcurrency.collectAsState()
    val bandwidthTestConcurrency by viewModel.bandwidthTestConcurrency.collectAsState()
    val smartConnectTcpingPercent by viewModel.smartConnectTcpingPercent.collectAsState()
    val smartConnectUrlTestPercent by viewModel.smartConnectUrlTestPercent.collectAsState()
    val smartConnectSpeedTestPercent by viewModel.smartConnectSpeedTestPercent.collectAsState()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsState()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsState()
    val vpnMtu by viewModel.vpnMtu.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    val rememberLastSelectedNodeEnabled by viewModel.rememberLastSelectedNodeEnabled.collectAsState()
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsState()
    val tunStack by viewModel.tunStack.collectAsState()

    var scheduledNodeUpdateHoursInput by remember(scheduledNodeUpdateHours) { mutableStateOf(scheduledNodeUpdateHours.toString()) }
    var scheduledNodeUpdateMinutesInput by remember(scheduledNodeUpdateMinutes) { mutableStateOf(scheduledNodeUpdateMinutes.toString()) }
    var tcpingTimeoutInput by remember(tcpingTestTimeoutMs) { mutableStateOf(tcpingTestTimeoutMs.toString()) }
    var urlTestTimeoutInput by remember(urlTestTimeoutMs) { mutableStateOf(urlTestTimeoutMs.toString()) }
    var speedTestDownloadTimeoutInput by remember(speedTestDownloadTimeoutMs) { mutableStateOf(speedTestDownloadTimeoutMs.toString()) }
    var tcpingConcurrencyInput by remember(tcpingConcurrency) { mutableStateOf(tcpingConcurrency.toString()) }
    var urlTestConcurrencyInput by remember(urlTestConcurrency) { mutableStateOf(urlTestConcurrency.toString()) }
    var bandwidthTestConcurrencyInput by remember(bandwidthTestConcurrency) { mutableStateOf(bandwidthTestConcurrency.toString()) }
    var smartConnectTcpingPercentInput by remember(smartConnectTcpingPercent) { mutableStateOf(smartConnectTcpingPercent.toString()) }
    var smartConnectUrlTestPercentInput by remember(smartConnectUrlTestPercent) { mutableStateOf(smartConnectUrlTestPercent.toString()) }
    var smartConnectSpeedTestPercentInput by remember(smartConnectSpeedTestPercent) { mutableStateOf(smartConnectSpeedTestPercent.toString()) }
    var hysteriaUpMbpsInput by remember(hysteriaUpMbps) { mutableStateOf(hysteriaUpMbps.toString()) }
    var hysteriaDownMbpsInput by remember(hysteriaDownMbps) { mutableStateOf(hysteriaDownMbps.toString()) }
    var mtuInput by remember(vpnMtu) { mutableStateOf(vpnMtu.toString()) }

    LaunchedEffect(
        scheduledNodeUpdateHours,
        scheduledNodeUpdateMinutes,
        tcpingTestTimeoutMs,
        urlTestTimeoutMs,
        speedTestDownloadTimeoutMs,
        tcpingConcurrency,
        urlTestConcurrency,
        bandwidthTestConcurrency,
        smartConnectTcpingPercent,
        smartConnectUrlTestPercent,
        smartConnectSpeedTestPercent,
        hysteriaUpMbps,
        hysteriaDownMbps,
        vpnMtu
    ) {
        scheduledNodeUpdateHoursInput = scheduledNodeUpdateHours.toString()
        scheduledNodeUpdateMinutesInput = scheduledNodeUpdateMinutes.toString()
        tcpingTimeoutInput = tcpingTestTimeoutMs.toString()
        urlTestTimeoutInput = urlTestTimeoutMs.toString()
        speedTestDownloadTimeoutInput = speedTestDownloadTimeoutMs.toString()
        tcpingConcurrencyInput = tcpingConcurrency.toString()
        urlTestConcurrencyInput = urlTestConcurrency.toString()
        bandwidthTestConcurrencyInput = bandwidthTestConcurrency.toString()
        smartConnectTcpingPercentInput = smartConnectTcpingPercent.toString()
        smartConnectUrlTestPercentInput = smartConnectUrlTestPercent.toString()
        smartConnectSpeedTestPercentInput = smartConnectSpeedTestPercent.toString()
        hysteriaUpMbpsInput = hysteriaUpMbps.toString()
        hysteriaDownMbpsInput = hysteriaDownMbps.toString()
        mtuInput = vpnMtu.toString()
    }

    fun applyScheduledNodeUpdateSettings(
        enabledValue: Boolean = scheduledNodeUpdateEnabled,
        hoursValue: Int = scheduledNodeUpdateHoursInput.toIntOrNull() ?: scheduledNodeUpdateHours,
        minutesValue: Int = scheduledNodeUpdateMinutesInput.toIntOrNull() ?: scheduledNodeUpdateMinutes,
        autoReconnectValue: Boolean = nodeAutoReconnect,
        toastEnabledValue: Boolean = scheduledNodeUpdateToastEnabled
    ) {
        viewModel.setScheduledNodeUpdateSettings(
            enabled = enabledValue,
            hours = hoursValue.coerceIn(0, 168),
            minutes = minutesValue.coerceIn(0, 59),
            nodeAutoReconnect = autoReconnectValue,
            toastEnabled = toastEnabledValue
        )
    }

    AppScreenScaffold(
        title = "其他配置",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OtherConfigSection(
                title = "网络配置",
                description = ""
            ) {
                Text(
                    text = "IPV6路由",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = ipv6RoutingMode == IPv6RoutingMode.ONLY,
                        onClick = { viewModel.setIPv6RoutingMode(IPv6RoutingMode.ONLY) },
                        label = { Text("仅") }
                    )
                    FilterChip(
                        selected = ipv6RoutingMode == IPv6RoutingMode.PREFER,
                        onClick = { viewModel.setIPv6RoutingMode(IPv6RoutingMode.PREFER) },
                        label = { Text("优先") }
                    )
                    FilterChip(
                        selected = ipv6RoutingMode == IPv6RoutingMode.ENABLED,
                        onClick = { viewModel.setIPv6RoutingMode(IPv6RoutingMode.ENABLED) },
                        label = { Text("启用") }
                    )
                    FilterChip(
                        selected = ipv6RoutingMode == IPv6RoutingMode.DISABLED,
                        onClick = { viewModel.setIPv6RoutingMode(IPv6RoutingMode.DISABLED) },
                        label = { Text("禁用") }
                    )
                }

            }

            OtherConfigSection(
                title = "TUN实现",
                description = "gvisor兼容性最佳；system更省电；mixed为混合模式。修改后重连VPN生效。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TunStack.values().forEach { stack ->
                        FilterChip(
                            selected = tunStack == stack,
                            onClick = { viewModel.setTunStack(stack) },
                            label = { Text(stack.displayName, maxLines = 1) }
                        )
                    }
                }
                Text(
                    text = tunStack.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OtherConfigSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "记住上次选择的节点",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "APP 启动后自动恢复上次选择的节点；若节点已不存在则不恢复。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rememberLastSelectedNodeEnabled,
                        onCheckedChange = { viewModel.setRememberLastSelectedNodeEnabled(it) },
                        colors = visibleSwitchColors()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "定时更新节点信息",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "从上次请求节点完成后开始计时，自动请求订阅列表中的所有线路",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = if (effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice) {
                            effectiveScheduledNodeUpdateSettings.enabled
                        } else {
                            scheduledNodeUpdateEnabled
                        },
                        onCheckedChange = { applyScheduledNodeUpdateSettings(enabledValue = it) },
                        enabled = !effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice,
                        colors = visibleSwitchColors()
                    )
                }

                if (effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice) {
                    Text(
                        text = "notice 已覆盖定时更新：每隔 ${effectiveScheduledNodeUpdateSettings.intervalLabel}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OtherConfigNumberField(
                        label = "间隔小时",
                        value = if (effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice) {
                            effectiveScheduledNodeUpdateSettings.hours.toString()
                        } else {
                            scheduledNodeUpdateHoursInput
                        },
                        onValueChange = {
                            scheduledNodeUpdateHoursInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { hours ->
                                applyScheduledNodeUpdateSettings(hoursValue = hours)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice
                    )
                    OtherConfigNumberField(
                        label = "间隔分钟",
                        value = if (effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice) {
                            effectiveScheduledNodeUpdateSettings.minutes.toString()
                        } else {
                            scheduledNodeUpdateMinutesInput
                        },
                        onValueChange = {
                            scheduledNodeUpdateMinutesInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { minutes ->
                                applyScheduledNodeUpdateSettings(minutesValue = minutes)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "节点自动重连",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "节点更新后，如当前连接节点的 server + port 仍存在，则自动重连到新列表中的对应节点；不存在则保持原连接",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = if (effectiveScheduledNodeUpdateSettings.reconnectOverriddenByNotice) {
                            effectiveScheduledNodeUpdateSettings.nodeAutoReconnect
                        } else {
                            nodeAutoReconnect
                        },
                        onCheckedChange = { applyScheduledNodeUpdateSettings(autoReconnectValue = it) },
                        enabled = !effectiveScheduledNodeUpdateSettings.reconnectOverriddenByNotice,
                        colors = visibleSwitchColors()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "节点更新通知",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Switch(
                        checked = if (effectiveScheduledNodeUpdateSettings.toastOverriddenByNotice) {
                            effectiveScheduledNodeUpdateSettings.toastEnabled
                        } else {
                            scheduledNodeUpdateToastEnabled
                        },
                        onCheckedChange = { applyScheduledNodeUpdateSettings(toastEnabledValue = it) },
                        enabled = !effectiveScheduledNodeUpdateSettings.toastOverriddenByNotice,
                        colors = visibleSwitchColors()
                    )
                }

                if (effectiveScheduledNodeUpdateSettings.reconnectOverriddenByNotice) {
                    Text(
                        text = "notice 已覆盖节点自动重连：${if (effectiveScheduledNodeUpdateSettings.nodeAutoReconnect) "开启" else "关闭"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (effectiveScheduledNodeUpdateSettings.toastOverriddenByNotice) {
                    Text(
                        text = "notice 已覆盖节点更新通知：${if (effectiveScheduledNodeUpdateSettings.toastEnabled) "开启" else "关闭"}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (
                    effectiveScheduledNodeUpdateSettings.scheduledOverriddenByNotice ||
                    effectiveScheduledNodeUpdateSettings.reconnectOverriddenByNotice ||
                    effectiveScheduledNodeUpdateSettings.toastOverriddenByNotice
                ) {
                    Text(
                        text = "当前设置由 Worker 的 notice 配置覆盖，本地保存只在对应字段移除后生效。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OtherConfigSection(
                title = "智能连接",
                description = "按 TCPing → URL Test 204 → 5MB 下载测速逐步筛选，默认比例为 100% / 50% / 50%。"
            ) {
                OtherConfigNumberField(
                    label = "TCPing 节点比例 (%)",
                    value = smartConnectTcpingPercentInput,
                    onValueChange = {
                        smartConnectTcpingPercentInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setSmartConnectTcpingPercent(value.coerceIn(1, 100))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "URL Test 候选比例 (%)",
                    value = smartConnectUrlTestPercentInput,
                    onValueChange = {
                        smartConnectUrlTestPercentInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setSmartConnectUrlTestPercent(value.coerceIn(1, 100))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "5MB 测速候选比例 (%)",
                    value = smartConnectSpeedTestPercentInput,
                    onValueChange = {
                        smartConnectSpeedTestPercentInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setSmartConnectSpeedTestPercent(value.coerceIn(1, 100))
                        }
                    }
                )
            }

            OtherConfigSection(
                title = "Hysteria 最大带宽 (Up/Dw)",
                description = "单位 Mbps，仅对 Hysteria2 节点生效；修改后下次连接生效，输入 0 表示不设置。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OtherConfigNumberField(
                        label = "Up",
                        value = hysteriaUpMbpsInput,
                        onValueChange = {
                            hysteriaUpMbpsInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setHysteriaUpMbps(value.coerceIn(0, 100_000))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    OtherConfigNumberField(
                        label = "Dw",
                        value = hysteriaDownMbpsInput,
                        onValueChange = {
                            hysteriaDownMbpsInput = it.filter(Char::isDigit)
                            it.toIntOrNull()?.let { value ->
                                viewModel.setHysteriaDownMbps(value.coerceIn(0, 100_000))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OtherConfigSection(
                title = "测试超时",
                description = "单位均为毫秒。下载测速超时设为 0 表示不限制。"
            ) {
                OtherConfigNumberField(
                    label = "TCPing 超时",
                    value = tcpingTimeoutInput,
                    onValueChange = {
                        tcpingTimeoutInput = it.filter(Char::isDigit)
                        it.toLongOrNull()?.let { value ->
                            viewModel.setTcpingTestTimeoutMs(value.coerceAtLeast(500L))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "URL Test 超时",
                    value = urlTestTimeoutInput,
                    onValueChange = {
                        urlTestTimeoutInput = it.filter(Char::isDigit)
                        it.toLongOrNull()?.let { value ->
                            viewModel.setUrlTestTimeoutMs(value.coerceAtLeast(500L))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "单次下载测速超时",
                    value = speedTestDownloadTimeoutInput,
                    onValueChange = {
                        speedTestDownloadTimeoutInput = it.filter(Char::isDigit)
                        it.toLongOrNull()?.let { value ->
                            viewModel.setSpeedTestDownloadTimeoutMs(value.coerceAtLeast(0L))
                        }
                    }
                )
            }

            OtherConfigSection(
                title = "并发与网络",
                description = "并发配置对下一次测试生效；MTU需断开并重连VPN生效。"
            ) {
                OtherConfigNumberField(
                    label = "TCPing 并发数",
                    value = tcpingConcurrencyInput,
                    onValueChange = {
                        tcpingConcurrencyInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setTcpingConcurrency(value.coerceIn(1, 128))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "URL Test 并发数",
                    value = urlTestConcurrencyInput,
                    onValueChange = {
                        urlTestConcurrencyInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setUrlTestConcurrency(value.coerceIn(1, 128))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "带宽测试并发数",
                    value = bandwidthTestConcurrencyInput,
                    onValueChange = {
                        bandwidthTestConcurrencyInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setBandwidthTestConcurrency(value.coerceIn(1, 3))
                        }
                    }
                )
                OtherConfigNumberField(
                    label = "VPN MTU",
                    value = mtuInput,
                    onValueChange = {
                        mtuInput = it.filter(Char::isDigit)
                        it.toIntOrNull()?.let { value ->
                            viewModel.setVpnMtu(value.coerceIn(576, 9000))
                        }
                    }
                )
                if (vpnState == VpnState.CONNECTED) {
                    Text(
                        text = "当前VPN已连接，修改MTU后请断开并重新连接。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun OtherConfigSection(
    title: String? = null,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
private fun visibleSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Primary,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledUncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
)

@Composable
private fun OtherConfigNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
