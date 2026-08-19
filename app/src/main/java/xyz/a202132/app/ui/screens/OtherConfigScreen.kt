package xyz.a202132.app.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.IPv6RoutingMode
import xyz.a202132.app.data.model.TunStack
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
        title = "基础设定",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            OtherConfigSection(
                title = "网络配置"
            ) {
                Text(
                    text = "IPV6路由",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                MultiPositionSwitch(
                    options = listOf("仅", "优先", "启用", "禁用"),
                    selectedIndex = when (ipv6RoutingMode) {
                        IPv6RoutingMode.ONLY -> 0
                        IPv6RoutingMode.PREFER -> 1
                        IPv6RoutingMode.ENABLED -> 2
                        IPv6RoutingMode.DISABLED -> 3
                    },
                    onOptionSelected = { index ->
                        viewModel.setIPv6RoutingMode(
                            when (index) {
                                0 -> IPv6RoutingMode.ONLY
                                1 -> IPv6RoutingMode.PREFER
                                2 -> IPv6RoutingMode.ENABLED
                                else -> IPv6RoutingMode.DISABLED
                            }
                        )
                    }
                )
            }

            OtherConfigSection(
                title = "TUN实现"
            ) {
                val tunStacks = TunStack.values()
                MultiPositionSwitch(
                    options = tunStacks.map(TunStack::configValue),
                    selectedIndex = tunStacks.indexOf(tunStack),
                    onOptionSelected = { index -> viewModel.setTunStack(tunStacks[index]) }
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
                    }
                    Switch(
                        checked = rememberLastSelectedNodeEnabled,
                        onCheckedChange = { viewModel.setRememberLastSelectedNodeEnabled(it) },
                        colors = visibleSwitchColors()
                    )
                }

            }

            OtherConfigSection(
                title = "自动删除超时节点"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = autoDeleteTimeoutNodes,
                        onCheckedChange = { viewModel.setAutoDeleteTimeoutNodes(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "简化测速数据"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = simplifiedNodeTestDisplayEnabled,
                        onCheckedChange = { viewModel.setSimplifiedNodeTestDisplayEnabled(it) },
                        colors = visibleSwitchColors()
                    )
                }
            }

            OtherConfigSection(
                title = "测试超时"
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
                title = "并发与网络"
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
            }
        }
    }
}

@Composable
private fun OtherConfigSection(
    title: String? = null,
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
        content()
    }
}

@Composable
private fun MultiPositionSwitch(
    options: List<String>,
    selectedIndex: Int,
    onOptionSelected: (Int) -> Unit
) {
    if (options.isEmpty()) return

    val safeSelectedIndex = selectedIndex.coerceIn(options.indices)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        val segmentWidth = maxWidth / options.size
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * safeSelectedIndex,
            label = "multi-position-switch"
        )

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(Primary)
        )

        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onOptionSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (index == safeSelectedIndex) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
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
