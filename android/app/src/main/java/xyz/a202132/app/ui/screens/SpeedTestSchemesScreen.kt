package xyz.a202132.app.ui.screens

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
import androidx.compose.material3.AlertDialog
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
import xyz.a202132.app.data.model.SpeedTestScheme
import xyz.a202132.app.data.model.SpeedTestScope
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SpeedTestSchemesScreen(
    viewModel: MainViewModel,
    onOpenEditor: (String) -> Unit,
    onBack: () -> Unit
) {
    val schemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val clickId by viewModel.homeClickSpeedTestSchemeId.collectAsStateWithLifecycle()
    val longPressId by viewModel.homeLongPressSpeedTestSchemeId.collectAsStateWithLifecycle()
    val swipeUpId by viewModel.homeSwipeUpSpeedTestSchemeId.collectAsStateWithLifecycle()
    val swipeDownId by viewModel.homeSwipeDownSpeedTestSchemeId.collectAsStateWithLifecycle()
    val swipeLeftId by viewModel.homeSwipeLeftSpeedTestSchemeId.collectAsStateWithLifecycle()
    val swipeRightId by viewModel.homeSwipeRightSpeedTestSchemeId.collectAsStateWithLifecycle()
    val clickEnabled by viewModel.homeClickSpeedTestEnabled.collectAsStateWithLifecycle()
    val longPressEnabled by viewModel.homeLongPressSpeedTestEnabled.collectAsStateWithLifecycle()
    val swipeUpEnabled by viewModel.homeSwipeUpSpeedTestEnabled.collectAsStateWithLifecycle()
    val swipeDownEnabled by viewModel.homeSwipeDownSpeedTestEnabled.collectAsStateWithLifecycle()
    val swipeLeftEnabled by viewModel.homeSwipeLeftSpeedTestEnabled.collectAsStateWithLifecycle()
    val swipeRightEnabled by viewModel.homeSwipeRightSpeedTestEnabled.collectAsStateWithLifecycle()
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
                text = "主页图标的点击、长按及上下左右滑动都可以绑定测速方案，并可分别启用或关闭。点击关闭时直接连接已选节点；测速按 1 TCPing、2 204 URL、3 带宽测速顺序执行。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            HomeSchemeBinding(
                title = "点击主页图标",
                selectedId = clickId,
                schemes = schemes,
                enabled = clickEnabled,
                onSelected = viewModel::setHomeClickSpeedTestScheme,
                onEnabledChange = viewModel::setHomeClickSpeedTestEnabled
            )
            HomeSchemeBinding(
                title = "长按主页图标",
                selectedId = longPressId,
                schemes = schemes,
                enabled = longPressEnabled,
                onSelected = viewModel::setHomeLongPressSpeedTestScheme,
                onEnabledChange = viewModel::setHomeLongPressSpeedTestEnabled
            )
            HomeSchemeBinding(
                title = "上滑主页图标",
                selectedId = swipeUpId,
                schemes = schemes,
                enabled = swipeUpEnabled,
                onSelected = viewModel::setHomeSwipeUpSpeedTestScheme,
                onEnabledChange = viewModel::setHomeSwipeUpSpeedTestEnabled
            )
            HomeSchemeBinding(
                title = "下滑主页图标",
                selectedId = swipeDownId,
                schemes = schemes,
                enabled = swipeDownEnabled,
                onSelected = viewModel::setHomeSwipeDownSpeedTestScheme,
                onEnabledChange = viewModel::setHomeSwipeDownSpeedTestEnabled
            )
            HomeSchemeBinding(
                title = "左滑主页图标",
                selectedId = swipeLeftId,
                schemes = schemes,
                enabled = swipeLeftEnabled,
                onSelected = viewModel::setHomeSwipeLeftSpeedTestScheme,
                onEnabledChange = viewModel::setHomeSwipeLeftSpeedTestEnabled
            )
            HomeSchemeBinding(
                title = "右滑主页图标",
                selectedId = swipeRightId,
                schemes = schemes,
                enabled = swipeRightEnabled,
                onSelected = viewModel::setHomeSwipeRightSpeedTestScheme,
                onEnabledChange = viewModel::setHomeSwipeRightSpeedTestEnabled
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
                    assignedToClick = clickEnabled && scheme.id == clickId,
                    assignedToLongPress = longPressEnabled && scheme.id == longPressId,
                    assignedToSwipeUp = swipeUpEnabled && scheme.id == swipeUpId,
                    assignedToSwipeDown = swipeDownEnabled && scheme.id == swipeDownId,
                    assignedToSwipeLeft = swipeLeftEnabled && scheme.id == swipeLeftId,
                    assignedToSwipeRight = swipeRightEnabled && scheme.id == swipeRightId,
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
@OptIn(ExperimentalMaterial3Api::class)
private fun HomeSchemeBinding(
    title: String,
    selectedId: String,
    schemes: List<SpeedTestScheme>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit
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
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }

    if (showSelectionDialog) {
        AlertDialog(
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
    assignedToClick: Boolean,
    assignedToLongPress: Boolean,
    assignedToSwipeUp: Boolean,
    assignedToSwipeDown: Boolean,
    assignedToSwipeLeft: Boolean,
    assignedToSwipeRight: Boolean,
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
        border = if (
            assignedToClick || assignedToLongPress || assignedToSwipeUp || assignedToSwipeDown ||
            assignedToSwipeLeft || assignedToSwipeRight
        ) {
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
                        if (!scheme.tcpingEnabled && !scheme.urlTestEnabled && !scheme.bandwidthEnabled) {
                            append("不测速，直接连接已选节点或当前组第一个节点")
                        } else {
                            append(
                                when (scheme.scope) {
                                    SpeedTestScope.CURRENT_GROUP -> "当前组"
                                    SpeedTestScope.SPECIFIED_GROUPS ->
                                        "指定组（${scheme.specifiedGroupIds.size} 个）"
                                    SpeedTestScope.ALL_GROUPS -> "所有组"
                                }
                            )
                            if (scheme.tcpingEnabled) append(" · 1 TCPing ${scheme.tcpingPercent}%")
                            if (scheme.urlTestEnabled) append(" · 2 204 URL ${scheme.urlTestPercent}%")
                            if (scheme.bandwidthEnabled) append(" · 3 带宽 ${scheme.bandwidthPercent}%")
                        }
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (assignedToClick) BindingBadge("点击")
                    if (assignedToLongPress) BindingBadge("长按")
                    if (assignedToSwipeUp) BindingBadge("上滑")
                    if (assignedToSwipeDown) BindingBadge("下滑")
                    if (assignedToSwipeLeft) BindingBadge("左滑")
                    if (assignedToSwipeRight) BindingBadge("右滑")
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
