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
    val tcpingTestTimeoutMs by viewModel.tcpingTestTimeoutMs.collectAsStateWithLifecycle()
    val urlTestTimeoutMs by viewModel.urlTestTimeoutMs.collectAsStateWithLifecycle()
    val speedTestDownloadTimeoutMs by viewModel.speedTestDownloadTimeoutMs.collectAsStateWithLifecycle()
    val tcpingConcurrency by viewModel.tcpingConcurrency.collectAsStateWithLifecycle()
    val urlTestConcurrency by viewModel.urlTestConcurrency.collectAsStateWithLifecycle()
    val bandwidthTestConcurrency by viewModel.bandwidthTestConcurrency.collectAsStateWithLifecycle()
    val autoDeleteTimeoutNodes by viewModel.autoDeleteTimeoutNodes.collectAsStateWithLifecycle()
    val vpnMtu by viewModel.vpnMtu.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val rememberLastSelectedNodeEnabled by viewModel.rememberLastSelectedNodeEnabled.collectAsStateWithLifecycle()
    val ipv6RoutingMode by viewModel.ipv6RoutingMode.collectAsStateWithLifecycle()
    val tunStack by viewModel.tunStack.collectAsStateWithLifecycle()
    val simplifiedNodeTestDisplayEnabled by
        viewModel.simplifiedNodeTestDisplayEnabled.collectAsStateWithLifecycle()

    var tcpingTimeoutInput by remember(tcpingTestTimeoutMs) { mutableStateOf(tcpingTestTimeoutMs.toString()) }
    var urlTestTimeoutInput by remember(urlTestTimeoutMs) { mutableStateOf(urlTestTimeoutMs.toString()) }
    var speedTestDownloadTimeoutInput by remember(speedTestDownloadTimeoutMs) { mutableStateOf(speedTestDownloadTimeoutMs.toString()) }
    var tcpingConcurrencyInput by remember(tcpingConcurrency) { mutableStateOf(tcpingConcurrency.toString()) }
    var urlTestConcurrencyInput by remember(urlTestConcurrency) { mutableStateOf(urlTestConcurrency.toString()) }
    var bandwidthTestConcurrencyInput by remember(bandwidthTestConcurrency) { mutableStateOf(bandwidthTestConcurrency.toString()) }
    var mtuInput by remember(vpnMtu) { mutableStateOf(vpnMtu.toString()) }

    LaunchedEffect(
        tcpingTestTimeoutMs,
        urlTestTimeoutMs,
        speedTestDownloadTimeoutMs,
        tcpingConcurrency,
        urlTestConcurrency,
        bandwidthTestConcurrency,
        vpnMtu
    ) {
        tcpingTimeoutInput = tcpingTestTimeoutMs.toString()
        urlTestTimeoutInput = urlTestTimeoutMs.toString()
        speedTestDownloadTimeoutInput = speedTestDownloadTimeoutMs.toString()
        tcpingConcurrencyInput = tcpingConcurrency.toString()
        urlTestConcurrencyInput = urlTestConcurrency.toString()
        bandwidthTestConcurrencyInput = bandwidthTestConcurrency.toString()
        mtuInput = vpnMtu.toString()
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

            }

            OtherConfigSection(
                title = "节点清理",
                description = "开启后，TCPing 或 URL Test 超时的节点会在测试完成后从本地删除；默认关闭。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "自动删除超时节点",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "仅处理测试结果为超时的节点，不影响未测试节点。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoDeleteTimeoutNodes,
                        onCheckedChange = { viewModel.setAutoDeleteTimeoutNodes(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "节点列表显示",
                description = "开启后，将 TCPing、204 URLTest 和带宽测试结果显示为三段横向状态色块。按延迟或带宽分级，超时和未测速显示为灰色。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "简化测速数据",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "仅显示绿、黄、红、灰四种状态色块，减少节点列表信息量。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = simplifiedNodeTestDisplayEnabled,
                        onCheckedChange = { viewModel.setSimplifiedNodeTestDisplayEnabled(it) },
                        colors = visibleSwitchColors()
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
