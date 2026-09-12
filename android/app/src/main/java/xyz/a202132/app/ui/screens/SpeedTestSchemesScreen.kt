package xyz.a202132.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import xyz.a202132.app.data.model.SpeedTestScheme
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun SpeedTestSchemesScreen(
    viewModel: MainViewModel,
    onOpenEditor: (String) -> Unit,
    onBack: () -> Unit
) {
    val schemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val clickId by viewModel.homeClickSpeedTestSchemeId.collectAsStateWithLifecycle()
    val longPressId by viewModel.homeLongPressSpeedTestSchemeId.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var newSchemeName by remember { mutableStateOf("") }
    var schemeToDelete by remember { mutableStateOf<SpeedTestScheme?>(null) }

    AppScreenScaffold(title = "测速方案", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "主页图标的点击和长按都可以绑定一个方案。方案会按 TCPing、204 URL 和带宽测速的结果选择节点。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            HomeSchemeBinding(
                title = "点击主页图标",
                selectedId = clickId,
                schemes = schemes,
                onSelected = viewModel::setHomeClickSpeedTestScheme
            )
            HomeSchemeBinding(
                title = "长按主页图标",
                selectedId = longPressId,
                schemes = schemes,
                onSelected = viewModel::setHomeLongPressSpeedTestScheme
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
                    assignedToClick = scheme.id == clickId,
                    assignedToLongPress = scheme.id == longPressId,
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
        AlertDialog(
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
        AlertDialog(
            onDismissRequest = { schemeToDelete = null },
            title = { Text("删除测速方案") },
            text = {
                Text(
                    if (scheme.builtIn) "内置方案不能删除，可以进入编辑页修改。"
                    else "确定删除“${scheme.name}”吗？主页绑定会自动切换到内置方案。"
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
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun HomeSchemeBinding(
    title: String,
    selectedId: String,
    schemes: List<SpeedTestScheme>,
    onSelected: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text(
                    text = schemes.firstOrNull { it.id == selectedId }?.name ?: "测速方案",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                schemes.forEach { scheme ->
                    FilterChip(
                        selected = scheme.id == selectedId,
                        onClick = { onSelected(scheme.id) },
                        label = { Text(scheme.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = if (scheme.id == selectedId) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedTestSchemeCard(
    scheme: SpeedTestScheme,
    assignedToClick: Boolean,
    assignedToLongPress: Boolean,
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
        border = if (assignedToClick || assignedToLongPress) {
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
                        append(if (scheme.testAllNodes) "测试所有节点" else "测试当前节点组")
                        append(" · TCPing ${scheme.tcpingPercent}%")
                        append(" · URL ${scheme.urlTestPercent}%")
                        append(" · 带宽 ${scheme.bandwidthPercent}%")
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (assignedToClick) BindingBadge("点击")
                    if (assignedToLongPress) BindingBadge("长按")
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
