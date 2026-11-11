package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.FailoverDetectionSettings
import io.github.HanJason233.chimera.data.model.FailoverProbeStepSettings
import io.github.HanJason233.chimera.data.model.FailoverRuntimeStatus
import io.github.HanJason233.chimera.data.model.FailoverTransferSettings
import io.github.HanJason233.chimera.data.model.FailoverTransferStrategy
import io.github.HanJason233.chimera.viewmodel.MainViewModel

private data class FailoverProbeDraft(
    val probeUrl: String,
    val intervalSeconds: String,
    val timeoutMs: String
)

private fun FailoverProbeStepSettings.toDraft() = FailoverProbeDraft(
    probeUrl = probeUrl,
    intervalSeconds = intervalSeconds.toString(),
    timeoutMs = timeoutMs.toString()
)

private fun FailoverProbeDraft.toSettings(): FailoverProbeStepSettings? {
    val normalizedUrl = normalizeFailoverProbeUrlInput(probeUrl) ?: return null
    val interval = intervalSeconds.toIntOrNull()?.takeIf {
        it in FailoverDetectionSettings.MIN_PROBE_INTERVAL_SECONDS..
            FailoverDetectionSettings.MAX_PROBE_INTERVAL_SECONDS
    } ?: return null
    val timeout = timeoutMs.toLongOrNull()?.takeIf {
        it in FailoverDetectionSettings.MIN_PROBE_TIMEOUT_MS..
            FailoverDetectionSettings.MAX_PROBE_TIMEOUT_MS
    } ?: return null
    return FailoverProbeStepSettings(normalizedUrl, interval, timeout)
}

@Composable
internal fun FailoverDetectionCard(
    settings: FailoverDetectionSettings,
    runtimeStatus: FailoverRuntimeStatus,
    onSave: (FailoverDetectionSettings) -> Unit
) {
    var firstProbe by remember(settings.firstProbe) { mutableStateOf(settings.firstProbe.toDraft()) }
    var secondProbe by remember(settings.secondProbe) { mutableStateOf(settings.secondProbe.toDraft()) }
    var thirdProbe by remember(settings.thirdProbe) { mutableStateOf(settings.thirdProbe.toDraft()) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "故障检测",
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = runtimeStatus.message,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (runtimeStatus.state.name in setOf("ERROR", "NO_BACKUP")) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
        Text(
            text = "正常时按第一次的间隔检测；失败后按第二、第三次各自设置的等待时间继续探测。连续三次未收到 204 时切换。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FailoverProbeEditor(
            title = "第一次 204 探测",
            intervalLabel = "正常探测间隔（秒）",
            draft = firstProbe,
            onDraftChange = { firstProbe = it; validationMessage = null }
        )
        FailoverProbeEditor(
            title = "第二次 204 探测",
            intervalLabel = "首次失败后等待（秒）",
            draft = secondProbe,
            onDraftChange = { secondProbe = it; validationMessage = null }
        )
        FailoverProbeEditor(
            title = "第三次 204 探测",
            intervalLabel = "二次失败后等待（秒）",
            draft = thirdProbe,
            onDraftChange = { thirdProbe = it; validationMessage = null }
        )
        validationMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = "首次失败即预检备用线路；切换时保持 VPN 隧道",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            onClick = {
                val first = firstProbe.toSettings()
                val second = secondProbe.toSettings()
                val third = thirdProbe.toSettings()
                if (first == null || second == null || third == null) {
                    validationMessage = "请检查三项 HTTPS 地址、间隔和超时；间隔需为 1–3600 秒，超时需为 250–30000 毫秒"
                } else {
                    onSave(
                        settings.copy(
                            firstProbe = first,
                            secondProbe = second,
                            thirdProbe = third
                        )
                    )
                    firstProbe = first.toDraft()
                    secondProbe = second.toDraft()
                    thirdProbe = third.toDraft()
                    validationMessage = null
                }
            }
        ) {
            Text("保存三次探测设置")
        }
    }
}

@Composable
private fun FailoverProbeEditor(
    title: String,
    intervalLabel: String,
    draft: FailoverProbeDraft,
    onDraftChange: (FailoverProbeDraft) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        OutlinedTextField(
            value = draft.probeUrl,
            onValueChange = { onDraftChange(draft.copy(probeUrl = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("测试地址") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.intervalSeconds,
                onValueChange = {
                    onDraftChange(draft.copy(intervalSeconds = it.filter(Char::isDigit).take(4)))
                },
                modifier = Modifier.weight(1f),
                label = { Text(intervalLabel) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = draft.timeoutMs,
                onValueChange = {
                    onDraftChange(draft.copy(timeoutMs = it.filter(Char::isDigit).take(5)))
                },
                modifier = Modifier.weight(1f),
                label = { Text("超时（毫秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }
    }
    Divider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

private fun normalizeFailoverProbeUrlInput(value: String): String? = runCatching {
    java.net.URI(value.trim())
}.getOrNull()?.takeIf { uri ->
    uri.scheme?.equals("https", ignoreCase = true) == true && !uri.host.isNullOrBlank()
}?.let { value.trim() }
internal data class FailoverGroupOption(
    val id: String,
    val name: String,
    val nodeCount: Int
)

private enum class FailoverTransferPickerMode {
    NODE,
    GROUP
}

@Composable
internal fun FailoverTransferCard(
    settings: FailoverTransferSettings,
    currentNodeId: String?,
    nodes: List<io.github.HanJason233.chimera.data.model.Node>,
    groups: List<FailoverGroupOption>,
    viewModel: MainViewModel,
    onChange: (FailoverTransferStrategy?) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var pickerMode by remember { mutableStateOf(FailoverTransferPickerMode.NODE) }
    var nodePickerGroupId by remember { mutableStateOf<String?>(null) }
    val strategyLabel = when (val strategy = settings.strategy) {
        is FailoverTransferStrategy.SpecifiedNode -> {
            val nodeName = nodes.firstOrNull { it.id == strategy.nodeId }?.name ?: "线路已删除"
            "指定线路：$nodeName"
        }
        is FailoverTransferStrategy.SpecifiedGroup -> {
            val groupName = groups.firstOrNull { it.id == strategy.groupId }?.name ?: "节点组已删除"
            "指定组：$groupName"
        }
        FailoverTransferStrategy.SecondBestTestResult -> "测试结果第二名"
        null -> "尚未选择转移策略"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "转移策略",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = strategyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FailoverTransferStrategyOption(
                title = "指定线路",
                selected = settings.strategy is FailoverTransferStrategy.SpecifiedNode,
                onClick = {
                    pickerMode = FailoverTransferPickerMode.NODE
                    nodePickerGroupId = null
                    showPicker = true
                }
            )
            FailoverTransferStrategyOption(
                title = "指定组",
                selected = settings.strategy is FailoverTransferStrategy.SpecifiedGroup,
                onClick = {
                    pickerMode = FailoverTransferPickerMode.GROUP
                    nodePickerGroupId = null
                    showPicker = true
                }
            )
            FailoverTransferStrategyOption(
                title = "测试结果第二名",
                selected = settings.strategy == FailoverTransferStrategy.SecondBestTestResult,
                onClick = { onChange(FailoverTransferStrategy.SecondBestTestResult) }
            )
            if (settings.strategy == FailoverTransferStrategy.SecondBestTestResult) {
                Text(
                    text = "按最近一次 URL 测速结果选择排名第二的可用线路；若当前线路排第二，则顺延一名。",
                    modifier = Modifier.padding(start = 48.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (settings.strategy != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onChange(null) }) { Text("清除") }
                }
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }

    if (showPicker) {
        val nodePickerGroup = groups.firstOrNull { it.id == nodePickerGroupId }
        AlertDialog(
            onDismissRequest = {
                showPicker = false
                nodePickerGroupId = null
            },
            title = {
                Text(
                    nodePickerGroup?.let { "选择${it.name}中的线路" }
                        ?: if (pickerMode == FailoverTransferPickerMode.GROUP) "选择转移组" else "选择线路所在分组"
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (nodePickerGroup == null) {
                        if (groups.isEmpty()) {
                            Text(
                                text = "当前没有可用节点组",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            groups.forEach { group ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            if (pickerMode == FailoverTransferPickerMode.GROUP) {
                                                onChange(FailoverTransferStrategy.SpecifiedGroup(group.id))
                                                showPicker = false
                                            } else {
                                                nodePickerGroupId = group.id
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = group.name,
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${group.nodeCount} 个",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (pickerMode == FailoverTransferPickerMode.NODE) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ChevronRight,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        TextButton(onClick = { nodePickerGroupId = null }) {
                            Text("返回分组")
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        FailoverGroupNodeOptions(
                            group = nodePickerGroup,
                            currentNodeId = currentNodeId,
                            viewModel = viewModel,
                            onSelect = { node ->
                                onChange(FailoverTransferStrategy.SpecifiedNode(node.id))
                                showPicker = false
                                nodePickerGroupId = null
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPicker = false
                    nodePickerGroupId = null
                }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun FailoverTransferStrategyOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FailoverGroupNodeOptions(
    group: FailoverGroupOption,
    currentNodeId: String?,
    viewModel: MainViewModel,
    onSelect: (io.github.HanJason233.chimera.data.model.Node) -> Unit
) {
    val groupNodesFlow = remember(group.id, viewModel) {
        viewModel.observeFailoverTargetGroupNodes(group.id)
    }
    val groupNodes by groupNodesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val eligibleNodes = remember(groupNodes, currentNodeId) {
        groupNodes.filter {
            it.id != currentNodeId &&
                it.type != io.github.HanJason233.chimera.data.model.NodeType.UNKNOWN &&
                it.server.isNotBlank() && it.port in 1..65_535
        }
    }

    if (eligibleNodes.isEmpty()) {
        Text(
            text = "该组没有可用的备用节点",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    } else {
        eligibleNodes.forEach { node ->
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelect(node) }
            ) {
                Text(
                    text = "${node.name} · ${node.type.protocol}",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** Hy2 bandwidth controls only affect the matching protocol outbound. */
@Composable
internal fun OutboundHysteriaCard(
    enabled: Boolean,
    upMbpsInput: String,
    downMbpsInput: String,
    isAdapting: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onUpMbpsChange: (String) -> Unit,
    onDownMbpsChange: (String) -> Unit,
    onAdapt: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hysteria2 出站加速",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = upMbpsInput,
                    onValueChange = onUpMbpsChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("上行 Mbps") },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = downMbpsInput,
                    onValueChange = onDownMbpsChange,
                    modifier = Modifier.weight(1f),
                    label = { Text("下行 Mbps") },
                    enabled = enabled,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                TextButton(
                    onClick = onAdapt,
                    enabled = enabled && !isAdapting
                ) {
                    Text(if (isAdapting) "测试中..." else "自适应")
                }
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
