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
import xyz.a202132.app.data.model.SpeedTestGroupOption
import xyz.a202132.app.data.model.SpeedTestScope
import xyz.a202132.app.data.model.UrlTestTargets
import xyz.a202132.app.network.UnlockTestCatalog
import xyz.a202132.app.network.UnlockTestCategory
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

    val speedTestGroups by viewModel.speedTestGroupOptions.collectAsStateWithLifecycle()
    var name by remember(scheme.id, scheme.name) { mutableStateOf(scheme.name) }
    var scope by remember(scheme.id, scheme.scope) { mutableStateOf(scheme.scope) }
    var specifiedGroupIds by remember(scheme.id, scheme.specifiedGroupIds) {
        mutableStateOf(scheme.specifiedGroupIds)
    }
    var tcpingEnabled by remember(scheme.id, scheme.tcpingEnabled) { mutableStateOf(scheme.tcpingEnabled) }
    var urlTestEnabled by remember(scheme.id, scheme.urlTestEnabled) { mutableStateOf(scheme.urlTestEnabled) }
    var bandwidthEnabled by remember(scheme.id, scheme.bandwidthEnabled) { mutableStateOf(scheme.bandwidthEnabled) }
    var unlockTestsEnabled by remember(scheme.id, scheme.unlockTestsEnabled) {
        mutableStateOf(scheme.unlockTestsEnabled)
    }
    var unlockTestIds by remember(scheme.id, scheme.unlockTestIds) {
        mutableStateOf(scheme.unlockTestIds)
    }
    var tcpingPercent by remember(scheme.id, scheme.tcpingPercent) { mutableStateOf(scheme.tcpingPercent.toString()) }
    var urlPercent by remember(scheme.id, scheme.urlTestPercent) { mutableStateOf(scheme.urlTestPercent.toString()) }
    var bandwidthPercent by remember(scheme.id, scheme.bandwidthPercent) { mutableStateOf(scheme.bandwidthPercent.toString()) }
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
    var repositoryGroupsExpanded by remember(scheme.id) { mutableStateOf(true) }
    var regionGroupsExpanded by remember(scheme.id) { mutableStateOf(true) }

    val repositoryGroups = speedTestGroups.filterNot { it.id.startsWith("country:") }
    val regionGroups = speedTestGroups.filter { it.id.startsWith("country:") }

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
                Text("启动前测速作用域", fontWeight = FontWeight.SemiBold)
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
                    description = "仅测试下面选中的一个或多个节点组",
                    selected = scope == SpeedTestScope.SPECIFIED_GROUPS,
                    onClick = { scope = SpeedTestScope.SPECIFIED_GROUPS }
                )
                ScopeOption(
                    title = "所有组",
                    description = "测试全部节点组",
                    selected = scope == SpeedTestScope.ALL_GROUPS,
                    onClick = { scope = SpeedTestScope.ALL_GROUPS }
                )
                if (scope == SpeedTestScope.SPECIFIED_GROUPS) {
                    Text(
                        "选择指定组（可多选）",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (speedTestGroups.isEmpty()) {
                        Text(
                            "暂无可用节点组，请先添加订阅或节点组。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    CollapsibleGroupSection(
                        title = "仓库组",
                        count = repositoryGroups.size,
                        expanded = repositoryGroupsExpanded,
                        onToggle = { repositoryGroupsExpanded = !repositoryGroupsExpanded }
                    ) {
                        SpeedTestGroupCheckboxes(
                            groups = repositoryGroups,
                            selectedGroupIds = specifiedGroupIds,
                            onCheckedChange = { groupId, selected ->
                                specifiedGroupIds = if (selected) {
                                    specifiedGroupIds + groupId
                                } else {
                                    specifiedGroupIds - groupId
                                }
                            }
                        )
                    }
                    CollapsibleGroupSection(
                        title = "地区组",
                        count = regionGroups.size,
                        expanded = regionGroupsExpanded,
                        onToggle = { regionGroupsExpanded = !regionGroupsExpanded }
                    ) {
                        SpeedTestGroupCheckboxes(
                            groups = regionGroups,
                            selectedGroupIds = specifiedGroupIds,
                            onCheckedChange = { groupId, selected ->
                                specifiedGroupIds = if (selected) {
                                    specifiedGroupIds + groupId
                                } else {
                                    specifiedGroupIds - groupId
                                }
                            }
                        )
                    }
                }
            }

            SchemeCard(
                title = "1. TCPing（可达性测试）",
                enabled = tcpingEnabled,
                onEnabledChange = { tcpingEnabled = it }
            ) {
                NumberInput("候选比例 (%)", tcpingPercent, enabled = tcpingEnabled) { tcpingPercent = it }
                NumberInput("超时 (ms)", tcpingTimeout, enabled = tcpingEnabled) { tcpingTimeout = it }
                NumberInput("并发", tcpingConcurrency, enabled = tcpingEnabled) { tcpingConcurrency = it }
            }

            SchemeCard(
                title = "2. 204 URL（连通性测试）",
                enabled = urlTestEnabled,
                onEnabledChange = { urlTestEnabled = it }
            ) {
                NumberInput("候选比例 (%)", urlPercent, enabled = urlTestEnabled) { urlPercent = it }
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

            SchemeCard(
                title = "4. 流媒体与 AI 解锁检测",
                enabled = unlockTestsEnabled,
                onEnabledChange = { unlockTestsEnabled = it }
            ) {
                Text(
                    "完成前面的节点筛选并连接后，通过当前节点检测所选服务。",
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
                                    Text(definition.name, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                    }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    val normalizedUrl = UrlTestTargets.normalize(urlTestUrl)
                    val normalizedBackupUrl = UrlTestTargets.normalizeOptional(urlTestBackupUrl)
                    val normalizedBandwidthUrl = BandwidthTestTargets.normalize(bandwidthUrl)
                    if (name.trim().isBlank()) {
                        error = "请输入方案名称"
                    } else if (scope == SpeedTestScope.SPECIFIED_GROUPS && specifiedGroupIds.isEmpty()) {
                        error = "请至少选择一个指定组"
                    } else if ((urlTestEnabled && (normalizedUrl == null || normalizedBackupUrl == null)) ||
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
                                unlockTestsEnabled = unlockTestsEnabled,
                                unlockTestIds = unlockTestIds,
                                tcpingPercent = tcpingPercent.toIntOrNull() ?: scheme.tcpingPercent,
                                urlTestPercent = urlPercent.toIntOrNull() ?: scheme.urlTestPercent,
                                bandwidthPercent = bandwidthPercent.toIntOrNull() ?: scheme.bandwidthPercent,
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
private fun CollapsibleGroupSection(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                "（$count）",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (expanded) "收起" else "展开",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun SpeedTestGroupCheckboxes(
    groups: List<SpeedTestGroupOption>,
    selectedGroupIds: Set<String>,
    onCheckedChange: (String, Boolean) -> Unit
) {
    groups.forEach { group ->
        val checked = group.id in selectedGroupIds
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(group.id, !checked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { selected -> onCheckedChange(group.id, selected) }
            )
            Text(group.name, color = MaterialTheme.colorScheme.onSurface)
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
