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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
    stage: SpeedTestStage,
    isHomeTab: Boolean,
    modifier: Modifier = Modifier,
    onSettingsChange: (SpeedTestSettings) -> Unit
) {
    var draftSettings by remember(settings) { mutableStateOf(settings) }
    fun persistSettings(update: (SpeedTestSettings) -> SpeedTestSettings) {
        // 每次有效改动立即保存，未完成的数字输入暂存在编辑态。
        val updatedSettings = update(draftSettings)
        draftSettings = updatedSettings
        onSettingsChange(updatedSettings)
    }

    var tcpingEnabled by remember(settings.tcpingEnabled) { mutableStateOf(settings.tcpingEnabled) }
    var urlTestEnabled by remember(settings.urlTestEnabled) { mutableStateOf(settings.urlTestEnabled) }
    var bandwidthEnabled by remember(settings.bandwidthEnabled) { mutableStateOf(settings.bandwidthEnabled) }
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

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (stage == SpeedTestStage.TCPING) {
            SpeedTestStageCard(
                title = "${SpeedTestStage.TCPING.number}. ${SpeedTestStage.TCPING.title}"
            ) {
                NumberInput("超时 (ms)", tcpingTimeout, enabled = tcpingEnabled) {
                    tcpingTimeout = it
                    it.toLongOrNull()?.takeIf { value -> value >= 500L }?.let { value ->
                        persistSettings { settings -> settings.copy(tcpingTimeoutMs = value) }
                    }
                }
                ConcurrencyInput(
                    mode = tcpingConcurrencyMode,
                    value = tcpingConcurrency,
                    enabled = tcpingEnabled,
                    onModeChange = { mode ->
                        tcpingConcurrencyMode = mode
                        persistSettings { settings -> settings.copy(tcpingConcurrencyMode = mode) }
                    },
                    onValueChange = { text ->
                        tcpingConcurrency = text
                        val max = if (tcpingConcurrencyMode == SpeedTestSelectionMode.PERCENT) 100 else 512
                        text.toIntOrNull()?.takeIf { value -> value in 1..max }?.let { value ->
                            persistSettings { settings -> settings.copy(tcpingConcurrency = value) }
                        }
                    }
                )
                AutoDeleteSwitchRow(
                    label = "自动删除 TCPing 超时节点",
                    checked = tcpingAutoDeleteTimeoutNodes,
                    enabled = tcpingEnabled,
                    onCheckedChange = { checked ->
                        tcpingAutoDeleteTimeoutNodes = checked
                        persistSettings { settings -> settings.copy(tcpingAutoDeleteTimeoutNodes = checked) }
                    }
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
                    onModeChange = { mode ->
                        tcpingSelectionMode = mode
                        persistSettings { settings -> settings.copy(tcpingSelectionMode = mode) }
                    },
                    onValueChange = { text ->
                        tcpingSelectionValue = text
                        text.toIntOrNull()?.takeIf { value -> value >= 1 && (tcpingSelectionMode != SpeedTestSelectionMode.PERCENT || value <= 100) }?.let { value ->
                            persistSettings { settings -> settings.copy(tcpingSelectionValue = value) }
                        }
                    }
                )
            }
        }

        if (stage == SpeedTestStage.URL_TEST) {
            SpeedTestStageCard(
                title = "${SpeedTestStage.URL_TEST.number}. ${SpeedTestStage.URL_TEST.title}"
            ) {
                NumberInput("预热建连超时 (ms)", urlWarmupTimeout, enabled = urlTestEnabled) {
                    urlWarmupTimeout = it
                    it.toLongOrNull()?.takeIf { value -> value in 500L..60_000L }?.let { value ->
                        persistSettings { settings -> settings.copy(urlTestWarmupTimeoutMs = value) }
                    }
                }
                NumberInput("延迟测量超时 (ms)", urlMeasurementTimeout, enabled = urlTestEnabled) {
                    urlMeasurementTimeout = it
                    it.toLongOrNull()?.takeIf { value -> value in 500L..60_000L }?.let { value ->
                        persistSettings { settings -> settings.copy(urlTestMeasurementTimeoutMs = value) }
                    }
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
                    onModeChange = { mode ->
                        urlConcurrencyMode = mode
                        persistSettings { settings -> settings.copy(urlTestConcurrencyMode = mode) }
                    },
                    onValueChange = { text ->
                        urlConcurrency = text
                        val max = if (urlConcurrencyMode == SpeedTestSelectionMode.PERCENT) 100 else 128
                        text.toIntOrNull()?.takeIf { value -> value in 1..max }?.let { value ->
                            persistSettings { settings -> settings.copy(urlTestConcurrency = value) }
                        }
                    }
                )
                OutlinedTextField(
                    value = urlTestUrl,
                    onValueChange = { text ->
                        urlTestUrl = text
                        UrlTestTargets.normalize(text)?.let { value ->
                            persistSettings { settings -> settings.copy(urlTestUrl = value) }
                        }
                    },
                    label = { Text("204 URL 地址") },
                    singleLine = true,
                    enabled = urlTestEnabled,
                    isError = urlTestEnabled && UrlTestTargets.normalize(urlTestUrl) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = urlTestBackupUrl,
                    onValueChange = { text ->
                        urlTestBackupUrl = text
                        UrlTestTargets.normalizeOptional(text)?.let { value ->
                            persistSettings { settings -> settings.copy(urlTestBackupUrl = value) }
                        }
                    },
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
                    onCheckedChange = { checked ->
                        urlTestAutoDeleteTimeoutNodes = checked
                        persistSettings { settings -> settings.copy(urlTestAutoDeleteTimeoutNodes = checked) }
                    }
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
                    onModeChange = { mode ->
                        urlSelectionMode = mode
                        persistSettings { settings -> settings.copy(urlTestSelectionMode = mode) }
                    },
                    onValueChange = { text ->
                        urlSelectionValue = text
                        text.toIntOrNull()?.takeIf { value -> value >= 1 && (urlSelectionMode != SpeedTestSelectionMode.PERCENT || value <= 100) }?.let { value ->
                            persistSettings { settings -> settings.copy(urlTestSelectionValue = value) }
                        }
                    }
                )
            }
        }

        if (stage == SpeedTestStage.BANDWIDTH) {
            SpeedTestStageCard(
                title = "${SpeedTestStage.BANDWIDTH.number}. ${SpeedTestStage.BANDWIDTH.title}"
            ) {
                NumberInput("测速时长 (ms)", bandwidthDuration, enabled = bandwidthEnabled) {
                    bandwidthDuration = it
                    it.toLongOrNull()?.takeIf { value -> value >= 1_000L }?.let { value ->
                        persistSettings { settings -> settings.copy(bandwidthTestDurationMs = value) }
                    }
                }
                ConcurrencyInput(
                    mode = bandwidthConcurrencyMode,
                    value = bandwidthConcurrency,
                    enabled = bandwidthEnabled,
                    onModeChange = { mode ->
                        bandwidthConcurrencyMode = mode
                        persistSettings { settings -> settings.copy(bandwidthTestConcurrencyMode = mode) }
                    },
                    onValueChange = { text ->
                        bandwidthConcurrency = text
                        val max = if (bandwidthConcurrencyMode == SpeedTestSelectionMode.PERCENT) 100 else 3
                        text.toIntOrNull()?.takeIf { value -> value in 1..max }?.let { value ->
                            persistSettings { settings -> settings.copy(bandwidthTestConcurrency = value) }
                        }
                    }
                )
                OutlinedTextField(
                    value = bandwidthUrl,
                    onValueChange = { text ->
                        bandwidthUrl = text
                        BandwidthTestTargets.normalize(text)?.let { value ->
                            persistSettings { settings -> settings.copy(bandwidthTestUrl = value) }
                        }
                    },
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
                    onCheckedChange = { checked ->
                        bandwidthAutoDeleteLowSpeedNodes = checked
                        persistSettings { settings -> settings.copy(bandwidthAutoDeleteLowSpeedNodes = checked) }
                    }
                )
                OutlinedTextField(
                    value = bandwidthAutoDeleteThreshold,
                    onValueChange = { text ->
                        val valueText = text.filter(Char::isDigit)
                        bandwidthAutoDeleteThreshold = valueText
                        valueText.toIntOrNull()?.takeIf { value -> value in 0..100_000 }?.let { value ->
                            persistSettings { settings -> settings.copy(bandwidthAutoDeleteThresholdMbps = value) }
                        }
                    },
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
                    onModeChange = { mode ->
                        bandwidthSelectionMode = mode
                        persistSettings { settings -> settings.copy(bandwidthSelectionMode = mode) }
                    },
                    onValueChange = { text ->
                        bandwidthSelectionValue = text
                        text.toIntOrNull()?.takeIf { value -> value >= 1 && (bandwidthSelectionMode != SpeedTestSelectionMode.PERCENT || value <= 100) }?.let { value ->
                            persistSettings { settings -> settings.copy(bandwidthSelectionValue = value) }
                        }
                    }
                )
            }
        }

        if (isHomeTab && stage == SpeedTestStage.LINK_TYPE) {
                SpeedTestStageCard(
                    title = "${SpeedTestStage.LINK_TYPE.number}. ${SpeedTestStage.LINK_TYPE.title}"
                ) {
                    Text(
                        "直连显示落地点 IP；中转显示中转点与落地点 IP，并识别中转点的 CDN。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

        }

        if (isHomeTab && stage == SpeedTestStage.IP_QUALITY) {
                SpeedTestStageCard(
                    title = "${SpeedTestStage.IP_QUALITY.number}. ${SpeedTestStage.IP_QUALITY.title}"
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
                                        val updatedIds = if (checked) {
                                            unlockTestIds - definition.id
                                        } else {
                                            unlockTestIds + definition.id
                                        }
                                        unlockTestIds = updatedIds
                                        persistSettings { settings -> settings.copy(unlockTestIds = updatedIds) }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = unlockTestsEnabled,
                                    onCheckedChange = { selected ->
                                        val updatedIds = if (selected) {
                                            unlockTestIds + definition.id
                                        } else {
                                            unlockTestIds - definition.id
                                        }
                                        unlockTestIds = updatedIds
                                        persistSettings { settings -> settings.copy(unlockTestIds = updatedIds) }
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
        }

        if (!isHomeTab && stage == SpeedTestStage.LINK_TYPE) {
                SpeedTestStageCard(title = "${SpeedTestStage.LINK_TYPE.number}. ${SpeedTestStage.LINK_TYPE.title}") {
                    Text(
                        "此检测在主页测速连接后执行，手动测速不运行此项。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
        }

        if (!isHomeTab && stage == SpeedTestStage.IP_QUALITY) {
                SpeedTestStageCard(title = "${SpeedTestStage.IP_QUALITY.number}. ${SpeedTestStage.IP_QUALITY.title}") {
                    Text(
                        "此检测在主页测速连接后执行，手动测速不运行此项。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
        }

        }
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
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
