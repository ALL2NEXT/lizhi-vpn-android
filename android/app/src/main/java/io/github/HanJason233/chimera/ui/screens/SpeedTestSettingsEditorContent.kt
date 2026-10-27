package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import io.github.HanJason233.chimera.data.model.BandwidthTestTargets
import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.SpeedTestSettings
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCatalog
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCategory
import io.github.HanJason233.chimera.ui.components.unlockTestIconResource

@Composable
internal fun SpeedTestSettingsEditorContent(
    settings: SpeedTestSettings,
    isHomeTab: Boolean,
    modifier: Modifier = Modifier,
    onSave: (SpeedTestSettings) -> Unit
) {
    var tcpingEnabled by remember(settings.tcpingEnabled) { mutableStateOf(settings.tcpingEnabled) }
    var urlTestEnabled by remember(settings.urlTestEnabled) { mutableStateOf(settings.urlTestEnabled) }
    var bandwidthEnabled by remember(settings.bandwidthEnabled) { mutableStateOf(settings.bandwidthEnabled) }
    var linkTypeTestEnabled by remember(settings.linkTypeTestEnabled) {
        mutableStateOf(settings.linkTypeTestEnabled)
    }
    var unlockTestsEnabled by remember(settings.unlockTestsEnabled) {
        mutableStateOf(settings.unlockTestsEnabled)
    }
    var unlockTestIds by remember(settings.unlockTestIds) {
        mutableStateOf(settings.unlockTestIds)
    }
    var tcpingSelectionMode by remember(settings.tcpingSelectionMode) {
        mutableStateOf(settings.tcpingSelectionMode)
    }
    var tcpingSelectionValue by remember(settings.tcpingSelectionValue) {
        mutableStateOf(settings.tcpingSelectionValue.toString())
    }
    var urlSelectionMode by remember(settings.urlTestSelectionMode) {
        mutableStateOf(settings.urlTestSelectionMode)
    }
    var urlSelectionValue by remember(settings.urlTestSelectionValue) {
        mutableStateOf(settings.urlTestSelectionValue.toString())
    }
    var bandwidthSelectionMode by remember(settings.bandwidthSelectionMode) {
        mutableStateOf(settings.bandwidthSelectionMode)
    }
    var bandwidthSelectionValue by remember(settings.bandwidthSelectionValue) {
        mutableStateOf(settings.bandwidthSelectionValue.toString())
    }
    var tcpingAutoDeleteTimeoutNodes by remember(settings.tcpingAutoDeleteTimeoutNodes) {
        mutableStateOf(settings.tcpingAutoDeleteTimeoutNodes)
    }
    var urlTestAutoDeleteTimeoutNodes by remember(settings.urlTestAutoDeleteTimeoutNodes) {
        mutableStateOf(settings.urlTestAutoDeleteTimeoutNodes)
    }
    var bandwidthAutoDeleteLowSpeedNodes by remember(settings.bandwidthAutoDeleteLowSpeedNodes) {
        mutableStateOf(settings.bandwidthAutoDeleteLowSpeedNodes)
    }
    var bandwidthAutoDeleteThreshold by remember(settings.bandwidthAutoDeleteThresholdMbps) {
        mutableStateOf(settings.bandwidthAutoDeleteThresholdMbps.toString())
    }
    var tcpingTimeout by remember(settings.tcpingTimeoutMs) { mutableStateOf(settings.tcpingTimeoutMs.toString()) }
    var urlWarmupTimeout by remember(settings.urlTestWarmupTimeoutMs) {
        mutableStateOf(settings.urlTestWarmupTimeoutMs.toString())
    }
    var urlMeasurementTimeout by remember(settings.urlTestMeasurementTimeoutMs) {
        mutableStateOf(settings.urlTestMeasurementTimeoutMs.toString())
    }
    var bandwidthDuration by remember(settings.bandwidthTestDurationMs) { mutableStateOf(settings.bandwidthTestDurationMs.toString()) }
    var tcpingConcurrencyMode by remember(settings.tcpingConcurrencyMode) {
        mutableStateOf(settings.tcpingConcurrencyMode)
    }
    var tcpingConcurrency by remember(settings.tcpingConcurrency) { mutableStateOf(settings.tcpingConcurrency.toString()) }
    var urlConcurrencyMode by remember(settings.urlTestConcurrencyMode) {
        mutableStateOf(settings.urlTestConcurrencyMode)
    }
    var urlConcurrency by remember(settings.urlTestConcurrency) { mutableStateOf(settings.urlTestConcurrency.toString()) }
    var bandwidthConcurrencyMode by remember(settings.bandwidthTestConcurrencyMode) {
        mutableStateOf(settings.bandwidthTestConcurrencyMode)
    }
    var bandwidthConcurrency by remember(settings.bandwidthTestConcurrency) { mutableStateOf(settings.bandwidthTestConcurrency.toString()) }
    var urlTestUrl by remember(settings.urlTestUrl) { mutableStateOf(settings.urlTestUrl) }
    var urlTestBackupUrl by remember(settings.urlTestBackupUrl) {
        mutableStateOf(settings.urlTestBackupUrl)
    }
    var bandwidthUrl by remember(settings.bandwidthTestUrl) { mutableStateOf(settings.bandwidthTestUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TestPhaseLabel("连接前测试")

            SpeedTestStageCard(
                title = "${SpeedTestStage.TCPING.number}. ${SpeedTestStage.TCPING.title}",
                enabled = tcpingEnabled,
                onEnabledChange = { tcpingEnabled = it }
            ) {
                NumberInput("超时 (ms)", tcpingTimeout, enabled = tcpingEnabled) { tcpingTimeout = it }
                ConcurrencyInput(
                    mode = tcpingConcurrencyMode,
                    value = tcpingConcurrency,
                    enabled = tcpingEnabled,
                    onModeChange = { tcpingConcurrencyMode = it },
                    onValueChange = { tcpingConcurrency = it }
                )
                AutoDeleteSwitchRow(
                    label = "自动删除 TCPing 超时节点",
                    checked = tcpingAutoDeleteTimeoutNodes,
                    enabled = tcpingEnabled,
                    onCheckedChange = { tcpingAutoDeleteTimeoutNodes = it }
                )
                Text(
                    "仅删除本项测试中超时的节点；当前正在连接的节点会保留。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ResultRetentionInput(
                    mode = tcpingSelectionMode,
                    value = tcpingSelectionValue,
                    enabled = tcpingEnabled,
                    onModeChange = { tcpingSelectionMode = it },
                    onValueChange = { tcpingSelectionValue = it }
                )
            }

            SpeedTestStageCard(
                title = "${SpeedTestStage.URL_TEST.number}. ${SpeedTestStage.URL_TEST.title}",
                enabled = urlTestEnabled,
                onEnabledChange = { urlTestEnabled = it }
            ) {
                NumberInput("预热建连超时 (ms)", urlWarmupTimeout, enabled = urlTestEnabled) {
                    urlWarmupTimeout = it
                }
                NumberInput("延迟测量超时 (ms)", urlMeasurementTimeout, enabled = urlTestEnabled) {
                    urlMeasurementTimeout = it
                }
                Text(
                    "预热超时限制首次请求的建连和响应；测量超时只限制第二次暖连接请求。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ConcurrencyInput(
                    mode = urlConcurrencyMode,
                    value = urlConcurrency,
                    enabled = urlTestEnabled,
                    onModeChange = { urlConcurrencyMode = it },
                    onValueChange = { urlConcurrency = it }
                )
                OutlinedTextField(
                    value = urlTestUrl,
                    onValueChange = { urlTestUrl = it },
                    label = { Text("204 URL 地址") },
                    singleLine = true,
                    enabled = urlTestEnabled,
                    isError = urlTestEnabled && UrlTestTargets.normalize(urlTestUrl) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = urlTestBackupUrl,
                    onValueChange = { urlTestBackupUrl = it },
                    label = { Text("备用 204 URL（可留空）") },
                    singleLine = true,
                    enabled = urlTestEnabled,
                    isError = urlTestEnabled &&
                        UrlTestTargets.normalizeOptional(urlTestBackupUrl) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "主地址超时或遇到临时连接错误时尝试备用地址；留空则不使用备用地址。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AutoDeleteSwitchRow(
                    label = "自动删除 URL Test 超时节点",
                    checked = urlTestAutoDeleteTimeoutNodes,
                    enabled = urlTestEnabled,
                    onCheckedChange = { urlTestAutoDeleteTimeoutNodes = it }
                )
                Text(
                    "仅删除本项测试中超时的节点；当前正在连接的节点会保留。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ResultRetentionInput(
                    mode = urlSelectionMode,
                    value = urlSelectionValue,
                    enabled = urlTestEnabled,
                    onModeChange = { urlSelectionMode = it },
                    onValueChange = { urlSelectionValue = it }
                )
            }

            SpeedTestStageCard(
                title = "${SpeedTestStage.BANDWIDTH.number}. ${SpeedTestStage.BANDWIDTH.title}",
                enabled = bandwidthEnabled,
                onEnabledChange = { bandwidthEnabled = it }
            ) {
                NumberInput("测速时长 (ms)", bandwidthDuration, enabled = bandwidthEnabled) { bandwidthDuration = it }
                ConcurrencyInput(
                    mode = bandwidthConcurrencyMode,
                    value = bandwidthConcurrency,
                    enabled = bandwidthEnabled,
                    onModeChange = { bandwidthConcurrencyMode = it },
                    onValueChange = { bandwidthConcurrency = it }
                )
                OutlinedTextField(
                    value = bandwidthUrl,
                    onValueChange = { bandwidthUrl = it },
                    label = { Text("带宽下载地址") },
                    singleLine = true,
                    enabled = bandwidthEnabled,
                    isError = bandwidthEnabled && BandwidthTestTargets.normalize(bandwidthUrl) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "带宽地址应指向可下载内容。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AutoDeleteSwitchRow(
                    label = "自动删除低带宽节点",
                    checked = bandwidthAutoDeleteLowSpeedNodes,
                    enabled = bandwidthEnabled,
                    onCheckedChange = { bandwidthAutoDeleteLowSpeedNodes = it }
                )
                OutlinedTextField(
                    value = bandwidthAutoDeleteThreshold,
                    onValueChange = { bandwidthAutoDeleteThreshold = it.filter(Char::isDigit) },
                    label = { Text("自动删除阈值 (Mbps)") },
                    singleLine = true,
                    enabled = bandwidthEnabled && bandwidthAutoDeleteLowSpeedNodes,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "低于阈值的节点会在本项测试后删除；当前正在连接的节点会保留。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ResultRetentionInput(
                    mode = bandwidthSelectionMode,
                    value = bandwidthSelectionValue,
                    enabled = bandwidthEnabled,
                    onModeChange = { bandwidthSelectionMode = it },
                    onValueChange = { bandwidthSelectionValue = it }
                )
            }

            TestPhaseLabel("连接后测试")

            if (isHomeTab) {
                SpeedTestStageCard(
                    title = "${SpeedTestStage.LINK_TYPE.number}. ${SpeedTestStage.LINK_TYPE.title}",
                    enabled = linkTypeTestEnabled,
                    onEnabledChange = { linkTypeTestEnabled = it }
                ) {
                    Text(
                        "直连显示落地点 IP；中转显示中转点与落地点 IP，并识别中转点的 CDN。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SpeedTestStageCard(
                    title = "${SpeedTestStage.IP_QUALITY.number}. ${SpeedTestStage.IP_QUALITY.title}",
                    enabled = unlockTestsEnabled,
                    onEnabledChange = { unlockTestsEnabled = it }
                ) {
                    Text(
                        if (unlockTestIds.isEmpty()) "未选择检测项目" else "已选 ${unlockTestIds.size} 项",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "检测当前节点出口 IP 的流媒体与 AI 解锁能力。检测会在主页测速连接节点后执行。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    UnlockTestCategory.entries.forEach { category ->
                    Text(
                        text = category.title,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    UnlockTestCatalog.definitions
                        .filter { it.category == category }
                        .forEach { definition ->
                            val checked = definition.id in unlockTestIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = unlockTestsEnabled) {
                                        unlockTestIds = if (checked) {
                                            unlockTestIds - definition.id
                                        } else {
                                            unlockTestIds + definition.id
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = unlockTestsEnabled,
                                    onCheckedChange = { selected ->
                                        unlockTestIds = if (selected) {
                                            unlockTestIds + definition.id
                                        } else {
                                            unlockTestIds - definition.id
                                        }
                                    }
                                )
                                Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color.White
                                ) {
                                    Image(
                                        painter = painterResource(unlockTestIconResource(definition.id)),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                Text(definition.name, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            } else {
                SpeedTestStageCard(title = "${SpeedTestStage.LINK_TYPE.number}. ${SpeedTestStage.LINK_TYPE.title}") {
                    Text(
                        "此检测在主页测速连接后执行，手动测速不运行此项。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SpeedTestStageCard(title = "${SpeedTestStage.IP_QUALITY.number}. ${SpeedTestStage.IP_QUALITY.title}") {
                    Text(
                        "此检测在主页测速连接后执行，手动测速不运行此项。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    val normalizedUrl = UrlTestTargets.normalize(urlTestUrl)
                    val normalizedBackupUrl = UrlTestTargets.normalizeOptional(urlTestBackupUrl)
                    val normalizedBandwidthUrl = BandwidthTestTargets.normalize(bandwidthUrl)
                    if ((urlTestEnabled && (normalizedUrl == null || normalizedBackupUrl == null)) ||
                        (bandwidthEnabled && normalizedBandwidthUrl == null)
                    ) {
                        error = "请检查测速地址"
                    } else {
                        onSave(
                            settings.copy(
                                tcpingEnabled = tcpingEnabled,
                                urlTestEnabled = urlTestEnabled,
                                bandwidthEnabled = bandwidthEnabled,
                                linkTypeTestEnabled = linkTypeTestEnabled,
                                unlockTestsEnabled = unlockTestsEnabled,
                                unlockTestIds = unlockTestIds,
                                tcpingSelectionMode = tcpingSelectionMode,
                                tcpingSelectionValue = tcpingSelectionValue.toIntOrNull()
                                    ?: settings.tcpingSelectionValue,
                                urlTestSelectionMode = urlSelectionMode,
                                urlTestSelectionValue = urlSelectionValue.toIntOrNull()
                                    ?: settings.urlTestSelectionValue,
                                bandwidthSelectionMode = bandwidthSelectionMode,
                                bandwidthSelectionValue = bandwidthSelectionValue.toIntOrNull()
                                    ?: settings.bandwidthSelectionValue,
                                tcpingAutoDeleteTimeoutNodes = tcpingAutoDeleteTimeoutNodes,
                                urlTestAutoDeleteTimeoutNodes = urlTestAutoDeleteTimeoutNodes,
                                bandwidthAutoDeleteLowSpeedNodes = bandwidthAutoDeleteLowSpeedNodes,
                                bandwidthAutoDeleteThresholdMbps = bandwidthAutoDeleteThreshold.toIntOrNull()
                                    ?.coerceIn(0, 100_000)
                                    ?: settings.bandwidthAutoDeleteThresholdMbps,
                                tcpingTimeoutMs = tcpingTimeout.toLongOrNull() ?: settings.tcpingTimeoutMs,
                                urlTestWarmupTimeoutMs = urlWarmupTimeout.toLongOrNull()
                                    ?: settings.urlTestWarmupTimeoutMs,
                                urlTestMeasurementTimeoutMs = urlMeasurementTimeout.toLongOrNull()
                                    ?: settings.urlTestMeasurementTimeoutMs,
                                bandwidthTestDurationMs = bandwidthDuration.toLongOrNull()
                                    ?: settings.bandwidthTestDurationMs,
                                tcpingConcurrencyMode = tcpingConcurrencyMode,
                                tcpingConcurrency = tcpingConcurrency.toIntOrNull()
                                    ?: settings.tcpingConcurrency,
                                urlTestConcurrencyMode = urlConcurrencyMode,
                                urlTestConcurrency = urlConcurrency.toIntOrNull()
                                    ?: settings.urlTestConcurrency,
                                bandwidthTestConcurrencyMode = bandwidthConcurrencyMode,
                                bandwidthTestConcurrency = bandwidthConcurrency.toIntOrNull()
                                    ?: settings.bandwidthTestConcurrency,
                                urlTestUrl = normalizedUrl ?: settings.urlTestUrl,
                                urlTestBackupUrl = normalizedBackupUrl ?: settings.urlTestBackupUrl,
                                bandwidthTestUrl = normalizedBandwidthUrl ?: settings.bandwidthTestUrl
                            )
                        )
                        error = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isHomeTab) "保存主页测速设置" else "保存手动测速设置")
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
}

@Composable
private fun TestPhaseLabel(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun AutoDeleteSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SpeedTestStageCard(
    title: String,
    enabled: Boolean? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (enabled != null && onEnabledChange != null) {
                    Switch(checked = enabled, onCheckedChange = onEnabledChange,
                        modifier = Modifier.semantics { contentDescription = "$title 开关" })
                }
            }
            content()
        }
    }
}

@Composable
private fun ConcurrencyInput(
    mode: SpeedTestSelectionMode,
    value: String,
    enabled: Boolean,
    onModeChange: (SpeedTestSelectionMode) -> Unit,
    onValueChange: (String) -> Unit
) {
    ResultRetentionInput(
        label = "并发",
        mode = mode,
        value = value,
        enabled = enabled,
        onModeChange = onModeChange,
        onValueChange = onValueChange
    )
    Text(
        text = "百分比按本项待测试节点数计算",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ResultRetentionInput(
    label: String = "输出给下一项",
    mode: SpeedTestSelectionMode,
    value: String,
    enabled: Boolean,
    onModeChange: (SpeedTestSelectionMode) -> Unit,
    onValueChange: (String) -> Unit
) {
    var unitMenuExpanded by remember { mutableStateOf(false) }
    val unit = if (mode == SpeedTestSelectionMode.PERCENT) "%" else "个"

    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            Box {
                Text(
                    text = unit,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = enabled) { unitMenuExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
                DropdownMenu(
                    expanded = unitMenuExpanded,
                    onDismissRequest = { unitMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("%") },
                        onClick = {
                            unitMenuExpanded = false
                            onModeChange(SpeedTestSelectionMode.PERCENT)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("个") },
                        onClick = {
                            unitMenuExpanded = false
                            onModeChange(SpeedTestSelectionMode.COUNT)
                        }
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NumberInput(
    label: String,
    value: String,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
