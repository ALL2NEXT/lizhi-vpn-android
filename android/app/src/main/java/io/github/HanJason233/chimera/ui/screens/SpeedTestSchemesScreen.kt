package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.enabledQualityStages
import io.github.HanJason233.chimera.data.model.selectionFor
import io.github.HanJason233.chimera.data.model.formatSpeedTestSelection
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SpeedTestSchemesScreen(
    viewModel: MainViewModel,
    onOpenEditor: (String) -> Unit,
    onBack: () -> Unit
) {
    val schemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val longPressId by viewModel.homeLongPressSpeedTestSchemeId.collectAsStateWithLifecycle()
    val groupManualId by viewModel.groupManualSpeedTestSchemeId.collectAsStateWithLifecycle()
    val groupManualEnabled by viewModel.groupManualSpeedTestEnabled.collectAsStateWithLifecycle()
    val longPressEnabled by viewModel.homeLongPressSpeedTestEnabled.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var newSchemeName by remember { mutableStateOf("") }
    var schemeToDelete by remember { mutableStateOf<SpeedTestScheme?>(null) }

    AppScreenScaffold(title = "启动前节点选择/测速", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "前三项为连接前测试，每项按结果保留前 X 个或百分比供后续阶段使用；后两项在连接后检测当前节点。线路详情手动测试单项会测试当前分组全部节点。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            SpeedTestSchemeBinding(
                title = "长按主页图标",
                selectedId = longPressId,
                schemes = schemes,
                enabled = longPressEnabled,
                onSelected = viewModel::setHomeLongPressSpeedTestScheme,
                onEnabledChange = viewModel::setHomeLongPressSpeedTestEnabled
            )
            SpeedTestSchemeBinding(
                title = "分组内手动测试",
                selectedId = groupManualId,
                schemes = schemes,
                enabled = groupManualEnabled,
                onSelected = viewModel::setGroupManualSpeedTestScheme,
                onEnabledChange = viewModel::setGroupManualSpeedTestEnabled
            )

            Text(
                text = "方案列表",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            schemes.forEach { scheme ->
                SpeedTestSchemeCard(
                    scheme = scheme,
                    assignedToLongPress = longPressEnabled && scheme.id == longPressId,
                    assignedToGroupManual = groupManualEnabled && scheme.id == groupManualId,
                    onEdit = { onOpenEditor(scheme.id) },
                    onDelete = { schemeToDelete = scheme }
                )
            }
            OutlinedButton(
                onClick = {
                    newSchemeName = ""
                    showAddDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("新增方案")
            }
        }
    }

    if (showAddDialog) {
        AppAlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增测速方案") },
            text = {
                OutlinedTextField(
                    value = newSchemeName,
                    onValueChange = { newSchemeName = it },
                    label = { Text("方案名称") },
                    placeholder = { Text("例如：低延迟") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
            confirmButton = {
                Button(
                    enabled = newSchemeName.trim().isNotEmpty(),
                    onClick = {
                        val scheme = SpeedTestScheme.create(newSchemeName)
                        viewModel.upsertSpeedTestScheme(scheme)
                        showAddDialog = false
                        onOpenEditor(scheme.id)
                    }
                ) { Text("继续设置") }
            }
        )
    }

    schemeToDelete?.let { scheme ->
        AppAlertDialog(
            onDismissRequest = { schemeToDelete = null },
            title = { Text("删除测速方案") },
            text = {
                Text(
                    if (scheme.builtIn) "内置方案不能删除，可以进入编辑页修改。"
                    else "确定删除“${scheme.name}”吗？主页和分组手动测试的绑定会自动切换到内置方案。"
                )
            },
            dismissButton = {
                TextButton(onClick = { schemeToDelete = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = !scheme.builtIn,
                    onClick = {
                        viewModel.deleteSpeedTestScheme(scheme.id)
                        schemeToDelete = null
                    }
                ) { Text("删除") }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SpeedTestSchemeBinding(
    title: String,
    selectedId: String,
    schemes: List<SpeedTestScheme>,
    enabled: Boolean? = null,
    onSelected: (String) -> Unit,
    onEnabledChange: ((Boolean) -> Unit)? = null
) {
    var showSelectionDialog by remember { mutableStateOf(false) }
    val selectedScheme = schemes.firstOrNull { it.id == selectedId }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                modifier = Modifier
                    .widthIn(max = 150.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showSelectionDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedScheme?.name ?: "测速方案",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "选择测速方案",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (enabled != null && onEnabledChange != null) {
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
        }
    }

    if (showSelectionDialog) {
        AppAlertDialog(
            onDismissRequest = { showSelectionDialog = false },
            title = { Text(title) },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(schemes, key = { it.id }) { scheme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    onSelected(scheme.id)
                                    showSelectionDialog = false
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = scheme.id == selectedId,
                                onClick = {
                                    onSelected(scheme.id)
                                    showSelectionDialog = false
                                }
                            )
                            Text(
                                text = scheme.name,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (scheme.id == selectedId) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSelectionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun SpeedTestSchemeCard(
    scheme: SpeedTestScheme,
    assignedToLongPress: Boolean,
    assignedToGroupManual: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (assignedToLongPress || assignedToGroupManual) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = scheme.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (scheme.builtIn) {
                        Spacer(Modifier.size(6.dp))
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "内置方案",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = buildString {
                        if (scheme.enabledQualityStages.isEmpty()) {
                            append(if (scheme.linkTypeTestEnabled ||
                                (scheme.unlockTestsEnabled && scheme.unlockTestIds.isNotEmpty()))
                                "检测当前连接；未连接时连接已选节点或当前组第一个节点"
                            else "不测速，直接连接已选节点或当前组第一个节点")
                        } else {
                            append("起始节点组：当前组")
                            append("\n连接前测试保留结果：")
                            append(scheme.enabledQualityStages.joinToString(" → ") { stage ->
                                val selection = scheme.selectionFor(stage)
                                "${stage.shortTitle} 保留前${formatSpeedTestSelection(selection.mode, selection.value)}"
                            })
                        }
                        if (scheme.linkTypeTestEnabled) append("\n链路类型：连接后的当前节点")
                        if (scheme.unlockTestsEnabled && scheme.unlockTestIds.isNotEmpty()) {
                            append("\nIP 质量：连接后的当前节点 · ${scheme.unlockTestIds.size} 项")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (assignedToLongPress) BindingBadge("长按")
                    if (assignedToGroupManual) BindingBadge("分组手动")
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑方案", tint = MaterialTheme.colorScheme.primary)
            }
            if (scheme.builtIn) {
                Icon(Icons.Outlined.Lock, contentDescription = "内置方案不可删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除方案", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BindingBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
