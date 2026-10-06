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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.BandwidthTestTargets
import io.github.HanJason233.chimera.data.model.SpeedTestSelectionMode
import io.github.HanJason233.chimera.data.model.SpeedTestStage
import io.github.HanJason233.chimera.data.model.UrlTestTargets
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCatalog
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestCategory
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.ui.components.unlockTestIconResource
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
fun SpeedTestSchemeEditorScreen(
    viewModel: MainViewModel,
    schemeId: String,
    onBack: () -> Unit
) {
    val schemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val scheme = schemes.firstOrNull { it.id == schemeId }
    if (scheme == null) {
        AppScreenScaffold(title = "编辑测速方案", onBack = onBack) {
            Text("正在加载方案…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var name by remember(scheme.id, scheme.name) { mutableStateOf(scheme.name) }
    var tcpingEnabled by remember(scheme.id, scheme.tcpingEnabled) { mutableStateOf(scheme.tcpingEnabled) }
    var urlTestEnabled by remember(scheme.id, scheme.urlTestEnabled) { mutableStateOf(scheme.urlTestEnabled) }
    var bandwidthEnabled by remember(scheme.id, scheme.bandwidthEnabled) { mutableStateOf(scheme.bandwidthEnabled) }
    var linkTypeTestEnabled by remember(scheme.id, scheme.linkTypeTestEnabled) {
        mutableStateOf(scheme.linkTypeTestEnabled)
    }
    var unlockTestsEnabled by remember(scheme.id, scheme.unlockTestsEnabled) {
        mutableStateOf(scheme.unlockTestsEnabled)
    }
    var unlockTestIds by remember(scheme.id, scheme.unlockTestIds) {
        mutableStateOf(scheme.unlockTestIds)
    }
    var tcpingSelectionMode by remember(scheme.id, scheme.tcpingSelectionMode) {
        mutableStateOf(scheme.tcpingSelectionMode)
    }
    var tcpingSelectionValue by remember(scheme.id, scheme.tcpingSelectionValue) {
        mutableStateOf(scheme.tcpingSelectionValue.toString())
    }
    var urlSelectionMode by remember(scheme.id, scheme.urlTestSelectionMode) {
        mutableStateOf(scheme.urlTestSelectionMode)
    }
    var urlSelectionValue by remember(scheme.id, scheme.urlTestSelectionValue) {
        mutableStateOf(scheme.urlTestSelectionValue.toString())
    }
    var bandwidthSelectionMode by remember(scheme.id, scheme.bandwidthSelectionMode) {
        mutableStateOf(scheme.bandwidthSelectionMode)
    }
    var bandwidthSelectionValue by remember(scheme.id, scheme.bandwidthSelectionValue) {
        mutableStateOf(scheme.bandwidthSelectionValue.toString())
    }
    var tcpingAutoDeleteTimeoutNodes by remember(scheme.id, scheme.tcpingAutoDeleteTimeoutNodes) {
        mutableStateOf(scheme.tcpingAutoDeleteTimeoutNodes)
    }
    var urlTestAutoDeleteTimeoutNodes by remember(scheme.id, scheme.urlTestAutoDeleteTimeoutNodes) {
        mutableStateOf(scheme.urlTestAutoDeleteTimeoutNodes)
    }
    var bandwidthAutoDeleteLowSpeedNodes by remember(scheme.id, scheme.bandwidthAutoDeleteLowSpeedNodes) {
        mutableStateOf(scheme.bandwidthAutoDeleteLowSpeedNodes)
    }
    var bandwidthAutoDeleteThreshold by remember(scheme.id, scheme.bandwidthAutoDeleteThresholdMbps) {
        mutableStateOf(scheme.bandwidthAutoDeleteThresholdMbps.toString())
    }
    var tcpingTimeout by remember(scheme.id, scheme.tcpingTimeoutMs) { mutableStateOf(scheme.tcpingTimeoutMs.toString()) }
    var urlWarmupTimeout by remember(scheme.id, scheme.urlTestWarmupTimeoutMs) {
        mutableStateOf(scheme.urlTestWarmupTimeoutMs.toString())
    }
    var urlMeasurementTimeout by remember(scheme.id, scheme.urlTestMeasurementTimeoutMs) {
        mutableStateOf(scheme.urlTestMeasurementTimeoutMs.toString())
    }
    var bandwidthDuration by remember(scheme.id, scheme.bandwidthTestDurationMs) { mutableStateOf(scheme.bandwidthTestDurationMs.toString()) }
    var tcpingConcurrency by remember(scheme.id, scheme.tcpingConcurrency) { mutableStateOf(scheme.tcpingConcurrency.toString()) }
    var urlConcurrency by remember(scheme.id, scheme.urlTestConcurrency) { mutableStateOf(scheme.urlTestConcurrency.toString()) }
    var bandwidthConcurrency by remember(scheme.id, scheme.bandwidthTestConcurrency) { mutableStateOf(scheme.bandwidthTestConcurrency.toString()) }
    var urlTestUrl by remember(scheme.id, scheme.urlTestUrl) { mutableStateOf(scheme.urlTestUrl) }
    var urlTestBackupUrl by remember(scheme.id, scheme.urlTestBackupUrl) {
        mutableStateOf(scheme.urlTestBackupUrl)
    }
    var bandwidthUrl by remember(scheme.id, scheme.bandwidthTestUrl) { mutableStateOf(scheme.bandwidthTestUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    AppScreenScaffold(
        title = "编辑测速方案",
        subtitle = scheme.name,
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    val normalizedUrl = UrlTestTargets.normalize(urlTestUrl)
                    val normalizedBackupUrl = UrlTestTargets.normalizeOptional(urlTestBackupUrl)
                    val normalizedBandwidthUrl = BandwidthTestTargets.normalize(bandwidthUrl)
                    if (name.trim().isBlank()) {
                        error = "请输入方案名称"
                    } else if ((urlTestEnabled && (normalizedUrl == null || normalizedBackupUrl == null)) ||
                        (bandwidthEnabled && normalizedBandwidthUrl == null)
                    ) {
                        error = "请检查测速地址"
                    } else {
                        viewModel.upsertSpeedTestScheme(
                            scheme.copy(
                                name = name.trim(),
                                tcpingEnabled = tcpingEnabled,
                                urlTestEnabled = urlTestEnabled,
                                bandwidthEnabled = bandwidthEnabled,
                                linkTypeTestEnabled = linkTypeTestEnabled,
                                unlockTestsEnabled = unlockTestsEnabled,
                                unlockTestIds = unlockTestIds,
                                tcpingSelectionMode = tcpingSelectionMode,
                                tcpingSelectionValue = tcpingSelectionValue.toIntOrNull()
                                    ?: scheme.tcpingSelectionValue,
                                urlTestSelectionMode = urlSelectionMode,
                                urlTestSelectionValue = urlSelectionValue.toIntOrNull()
                                    ?: scheme.urlTestSelectionValue,
                                bandwidthSelectionMode = bandwidthSelectionMode,
                                bandwidthSelectionValue = bandwidthSelectionValue.toIntOrNull()
                                    ?: scheme.bandwidthSelectionValue,
                                tcpingAutoDeleteTimeoutNodes = tcpingAutoDeleteTimeoutNodes,
                                urlTestAutoDeleteTimeoutNodes = urlTestAutoDeleteTimeoutNodes,
                                bandwidthAutoDeleteLowSpeedNodes = bandwidthAutoDeleteLowSpeedNodes,
                                bandwidthAutoDeleteThresholdMbps = bandwidthAutoDeleteThreshold.toIntOrNull()
                                    ?.coerceIn(0, 100_000)
                                    ?: scheme.bandwidthAutoDeleteThresholdMbps,
                                tcpingTimeoutMs = tcpingTimeout.toLongOrNull() ?: scheme.tcpingTimeoutMs,
                                urlTestWarmupTimeoutMs = urlWarmupTimeout.toLongOrNull()
                                    ?: scheme.urlTestWarmupTimeoutMs,
                                urlTestMeasurementTimeoutMs = urlMeasurementTimeout.toLongOrNull()
                                    ?: scheme.urlTestMeasurementTimeoutMs,
                                bandwidthTestDurationMs = bandwidthDuration.toLongOrNull()
                                    ?: scheme.bandwidthTestDurationMs,
                                tcpingConcurrency = tcpingConcurrency.toIntOrNull()
                                    ?: scheme.tcpingConcurrency,
                                urlTestConcurrency = urlConcurrency.toIntOrNull()
                                    ?: scheme.urlTestConcurrency,
                                bandwidthTestConcurrency = bandwidthConcurrency.toIntOrNull()
                                    ?: scheme.bandwidthTestConcurrency,
                                urlTestUrl = normalizedUrl ?: scheme.urlTestUrl,
                                urlTestBackupUrl = normalizedBackupUrl ?: scheme.urlTestBackupUrl,
                                bandwidthTestUrl = normalizedBandwidthUrl ?: scheme.bandwidthTestUrl
                            )
                        )
                        error = null
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Save,
                    contentDescription = "保存方案"
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SchemeCard(title = "方案信息") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("方案名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            TestPhaseLabel("连接前测试")

            SchemeCard(
                title = "${SpeedTestStage.TCPING.number}. ${SpeedTestStage.TCPING.title}",
                enabled = tcpingEnabled,
                onEnabledChange = { tcpingEnabled = it }
            ) {
                NumberInput("超时 (ms)", tcpingTimeout, enabled = tcpingEnabled) { tcpingTimeout = it }
                NumberInput("并发", tcpingConcurrency, enabled = tcpingEnabled) { tcpingConcurrency = it }
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

            SchemeCard(
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
                NumberInput("并发", urlConcurrency, enabled = urlTestEnabled) { urlConcurrency = it }
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

            SchemeCard(
                title = "${SpeedTestStage.BANDWIDTH.number}. ${SpeedTestStage.BANDWIDTH.title}",
                enabled = bandwidthEnabled,
                onEnabledChange = { bandwidthEnabled = it }
            ) {
                NumberInput("测速时长 (ms)", bandwidthDuration, enabled = bandwidthEnabled) { bandwidthDuration = it }
                NumberInput("并发", bandwidthConcurrency, enabled = bandwidthEnabled) { bandwidthConcurrency = it }
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

            SchemeCard(
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

            SchemeCard(
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
                    "检测当前节点出口 IP 的流媒体与 AI 解锁能力。只启用连接后检测时复用当前连接；未连接时连接已选节点或当前组首个节点。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                UnlockTestCategory.entries.forEach { category ->
                    Text(
                        text = category.title,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    UnlockTestCatalog.schemeDefinitions
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

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
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
private fun SchemeCard(
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
private fun ResultRetentionInput(
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
        label = { Text("输出给下一项") },
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
