package xyz.a202132.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.BandwidthTestTargets
import xyz.a202132.app.data.model.SpeedTestScheme
import xyz.a202132.app.data.model.SpeedTestScope
import xyz.a202132.app.data.model.UrlTestTargets
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

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

    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    var name by remember(scheme.id, scheme.name) { mutableStateOf(scheme.name) }
    var scope by remember(scheme.id, scheme.scope) { mutableStateOf(scheme.scope) }
    var specifiedGroupIds by remember(scheme.id, scheme.specifiedGroupIds) {
        mutableStateOf(scheme.specifiedGroupIds)
    }
    var tcpingEnabled by remember(scheme.id, scheme.tcpingEnabled) { mutableStateOf(scheme.tcpingEnabled) }
    var urlTestEnabled by remember(scheme.id, scheme.urlTestEnabled) { mutableStateOf(scheme.urlTestEnabled) }
    var bandwidthEnabled by remember(scheme.id, scheme.bandwidthEnabled) { mutableStateOf(scheme.bandwidthEnabled) }
    var tcpingPercent by remember(scheme.id, scheme.tcpingPercent) { mutableStateOf(scheme.tcpingPercent.toString()) }
    var urlPercent by remember(scheme.id, scheme.urlTestPercent) { mutableStateOf(scheme.urlTestPercent.toString()) }
    var bandwidthPercent by remember(scheme.id, scheme.bandwidthPercent) { mutableStateOf(scheme.bandwidthPercent.toString()) }
    var tcpingTimeout by remember(scheme.id, scheme.tcpingTimeoutMs) { mutableStateOf(scheme.tcpingTimeoutMs.toString()) }
    var urlTimeout by remember(scheme.id, scheme.urlTestTimeoutMs) { mutableStateOf(scheme.urlTestTimeoutMs.toString()) }
    var bandwidthDuration by remember(scheme.id, scheme.bandwidthTestDurationMs) { mutableStateOf(scheme.bandwidthTestDurationMs.toString()) }
    var tcpingConcurrency by remember(scheme.id, scheme.tcpingConcurrency) { mutableStateOf(scheme.tcpingConcurrency.toString()) }
    var urlConcurrency by remember(scheme.id, scheme.urlTestConcurrency) { mutableStateOf(scheme.urlTestConcurrency.toString()) }
    var bandwidthConcurrency by remember(scheme.id, scheme.bandwidthTestConcurrency) { mutableStateOf(scheme.bandwidthTestConcurrency.toString()) }
    var urlTestUrl by remember(scheme.id, scheme.urlTestUrl) { mutableStateOf(scheme.urlTestUrl) }
    var bandwidthUrl by remember(scheme.id, scheme.bandwidthTestUrl) { mutableStateOf(scheme.bandwidthTestUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    AppScreenScaffold(title = "编辑测速方案", subtitle = scheme.name, onBack = onBack) {
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
                Spacer(Modifier.height(6.dp))
                Text("测试作用域", fontWeight = FontWeight.SemiBold)
                Text(
                    "测速节点范围按以下顺序选择，指定组支持多选。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ScopeOption(
                    title = "当前组",
                    description = "跟随主页当前选中的节点组",
                    selected = scope == SpeedTestScope.CURRENT_GROUP,
                    onClick = { scope = SpeedTestScope.CURRENT_GROUP }
                )
                ScopeOption(
                    title = "指定组",
                    description = "仅测试下面选中的一个或多个订阅组",
                    selected = scope == SpeedTestScope.SPECIFIED_GROUPS,
                    onClick = { scope = SpeedTestScope.SPECIFIED_GROUPS }
                )
                ScopeOption(
                    title = "所有组",
                    description = "测试全部订阅组",
                    selected = scope == SpeedTestScope.ALL_GROUPS,
                    onClick = { scope = SpeedTestScope.ALL_GROUPS }
                )
                if (scope == SpeedTestScope.SPECIFIED_GROUPS) {
                    Text(
                        "选择指定组（可多选）",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (subscriptionGroups.isEmpty()) {
                        Text(
                            "暂无可用订阅组，请先添加订阅。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    subscriptionGroups.forEach { group ->
                        val checked = group.sourceId in specifiedGroupIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    specifiedGroupIds = if (checked) {
                                        specifiedGroupIds - group.sourceId
                                    } else {
                                        specifiedGroupIds + group.sourceId
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { selected ->
                                    specifiedGroupIds = if (selected) {
                                        specifiedGroupIds + group.sourceId
                                    } else {
                                        specifiedGroupIds - group.sourceId
                                    }
                                }
                            )
                            Text(group.name, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            SchemeCard(
                title = "1. TCPing",
                enabled = tcpingEnabled,
                onEnabledChange = { tcpingEnabled = it }
            ) {
                NumberInput("候选比例 (%)", tcpingPercent, enabled = tcpingEnabled) { tcpingPercent = it }
                NumberInput("超时 (ms)", tcpingTimeout, enabled = tcpingEnabled) { tcpingTimeout = it }
                NumberInput("并发", tcpingConcurrency, enabled = tcpingEnabled) { tcpingConcurrency = it }
            }

            SchemeCard(
                title = "2. 204 URL",
                enabled = urlTestEnabled,
                onEnabledChange = { urlTestEnabled = it }
            ) {
                NumberInput("候选比例 (%)", urlPercent, enabled = urlTestEnabled) { urlPercent = it }
                NumberInput("超时 (ms)", urlTimeout, enabled = urlTestEnabled) { urlTimeout = it }
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
                Text(
                    "用于检测节点通过 204 URL 请求的延迟。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SchemeCard(
                title = "3. 带宽测速",
                enabled = bandwidthEnabled,
                onEnabledChange = { bandwidthEnabled = it }
            ) {
                NumberInput("候选比例 (%)", bandwidthPercent, enabled = bandwidthEnabled) { bandwidthPercent = it }
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
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    val normalizedUrl = UrlTestTargets.normalize(urlTestUrl)
                    val normalizedBandwidthUrl = BandwidthTestTargets.normalize(bandwidthUrl)
                    if (name.trim().isBlank()) {
                        error = "请输入方案名称"
                    } else if (scope == SpeedTestScope.SPECIFIED_GROUPS && specifiedGroupIds.isEmpty()) {
                        error = "请至少选择一个指定组"
                    } else if ((urlTestEnabled && normalizedUrl == null) ||
                        (bandwidthEnabled && normalizedBandwidthUrl == null)
                    ) {
                        error = "请检查测速地址"
                    } else {
                        viewModel.upsertSpeedTestScheme(
                            scheme.copy(
                                name = name.trim(),
                                scope = scope,
                                specifiedGroupIds = specifiedGroupIds,
                                tcpingEnabled = tcpingEnabled,
                                urlTestEnabled = urlTestEnabled,
                                bandwidthEnabled = bandwidthEnabled,
                                tcpingPercent = tcpingPercent.toIntOrNull() ?: scheme.tcpingPercent,
                                urlTestPercent = urlPercent.toIntOrNull() ?: scheme.urlTestPercent,
                                bandwidthPercent = bandwidthPercent.toIntOrNull() ?: scheme.bandwidthPercent,
                                tcpingTimeoutMs = tcpingTimeout.toLongOrNull() ?: scheme.tcpingTimeoutMs,
                                urlTestTimeoutMs = urlTimeout.toLongOrNull() ?: scheme.urlTestTimeoutMs,
                                bandwidthTestDurationMs = bandwidthDuration.toLongOrNull()
                                    ?: scheme.bandwidthTestDurationMs,
                                tcpingConcurrency = tcpingConcurrency.toIntOrNull()
                                    ?: scheme.tcpingConcurrency,
                                urlTestConcurrency = urlConcurrency.toIntOrNull()
                                    ?: scheme.urlTestConcurrency,
                                bandwidthTestConcurrency = bandwidthConcurrency.toIntOrNull()
                                    ?: scheme.bandwidthTestConcurrency,
                                urlTestUrl = normalizedUrl ?: scheme.urlTestUrl,
                                bandwidthTestUrl = normalizedBandwidthUrl ?: scheme.bandwidthTestUrl
                            )
                        )
                        error = null
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存方案")
            }
        }
    }
}

@Composable
private fun ScopeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (enabled != null && onEnabledChange != null) {
                    Spacer(Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
            }
            content()
        }
    }
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
