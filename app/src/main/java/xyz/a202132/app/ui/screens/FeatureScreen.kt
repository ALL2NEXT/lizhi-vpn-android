package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.ui.theme.Primary
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
fun FeatureScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val smartConnectEnabled by viewModel.smartConnectEnabled.collectAsState()
    val smartConnectTcpingPercent by viewModel.smartConnectTcpingPercent.collectAsState()
    val smartConnectUrlTestPercent by viewModel.smartConnectUrlTestPercent.collectAsState()
    val smartConnectSpeedTestPercent by viewModel.smartConnectSpeedTestPercent.collectAsState()
    val hysteriaBandwidthEnabled by viewModel.hysteriaBandwidthEnabled.collectAsState()
    val hysteriaUpMbps by viewModel.hysteriaUpMbps.collectAsState()
    val hysteriaDownMbps by viewModel.hysteriaDownMbps.collectAsState()
    val isHysteriaBandwidthAdapting by viewModel.isHysteriaBandwidthAdapting.collectAsState()
    val hysteriaBandwidthAdaptiveStatus by viewModel.hysteriaBandwidthAdaptiveStatus.collectAsState()

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
                title = "智能连接",
                description = "关闭后，主页连接会直接使用已选节点；未选择节点时使用当前列表第一个节点。"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "启用智能连接",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "按 TCPing → URL Test 204 → 5MB 下载测速逐步筛选节点",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = smartConnectEnabled,
                        onCheckedChange = { viewModel.setSmartConnectEnabled(it) },
                        colors = featureSwitchColors()
                    )
                }
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
                title = "Hy2 加速",
                description = "设置 Hysteria2 节点的最大上下行带宽；自适应使用直连网络测速，并按 10 Mbps 向上取整。",
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
                hysteriaBandwidthAdaptiveStatus?.let { status ->
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureSection(
    title: String,
    description: String,
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
        if (description.isNotBlank()) {
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
private fun featureSwitchColors() = SwitchDefaults.colors(
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
private fun FeatureNumberField(
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
