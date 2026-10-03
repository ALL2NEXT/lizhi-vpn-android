package io.github.HanJason233.chimera.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subscriptions
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.NodeGroupSummary
import io.github.HanJason233.chimera.data.model.SubscriptionGroup
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.util.TrafficUsageFormatter
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class GroupActionMode {
    UPDATE,
    EDIT,
    DELETE
}

private enum class SubscriptionTrafficStatus {
    UNKNOWN,
    SAFE,
    WARNING,
    CRITICAL,
    EXHAUSTED
}

private data class SubscriptionTrafficInfo(
    val text: String,
    val status: SubscriptionTrafficStatus,
    val progress: Float?
)

@Composable
internal fun SubscriptionGroupSelectionDestination(
    viewModel: MainViewModel,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onUserGroupSelected: (NodeGroupSummary) -> Unit,
    onViewUserGroupDetails: (NodeGroupSummary) -> Unit,
    onCreateSubscriptionGroup: (String, String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    scannedSubscriptionText: String?,
    onScannedSubscriptionTextConsumed: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 16.dp
) {
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val userGroups by viewModel.userSubscriptionGroups.collectAsStateWithLifecycle()
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val nodeCounts = allSubscriptionNodes
        .asSequence()
        .mapNotNull(Node::subscriptionSourceId)
        .groupingBy { it }
        .eachCount()

    SubscriptionGroupSelectionScreen(
        subscriptionGroups = subscriptionGroups,
        userGroups = userGroups,
        selectedNodeGroupId = selectedNodeGroupId,
        nodeCounts = nodeCounts,
        isLoading = isLoading,
        onSubscriptionSelected = onSubscriptionSelected,
        onViewSubscriptionDetails = onViewSubscriptionDetails,
        onUserGroupSelected = onUserGroupSelected,
        onViewUserGroupDetails = onViewUserGroupDetails,
        onCreateSubscriptionGroup = onCreateSubscriptionGroup,
        onUpdateSubscriptionGroup = onUpdateSubscriptionGroup,
        onDeleteSubscriptionGroup = onDeleteSubscriptionGroup,
        onRefreshOfficialSubscription = { sourceId, onResult ->
            viewModel.refreshOfficialSubscription(sourceId, onResult)
        },
        onRefreshUserGroup = { groupId, onResult ->
            viewModel.refreshUserSubscriptionGroup(groupId, onResult)
        },
        onDeleteOfficialSubscription = { sourceId, onResult ->
            viewModel.deleteOfficialSubscription(sourceId, onResult)
        },
        onScanQrCode = onScanQrCode,
        scannedSubscriptionText = scannedSubscriptionText,
        onScannedSubscriptionTextConsumed = onScannedSubscriptionTextConsumed,
        onBack = onBack,
        bottomContentPadding = bottomContentPadding
    )
}

@Composable
private fun SubscriptionGroupSelectionScreen(
    subscriptionGroups: List<SubscriptionGroup>,
    userGroups: List<NodeGroupSummary>,
    selectedNodeGroupId: String?,
    nodeCounts: Map<String, Int>,
    isLoading: Boolean,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onUserGroupSelected: (NodeGroupSummary) -> Unit,
    onViewUserGroupDetails: (NodeGroupSummary) -> Unit,
    onCreateSubscriptionGroup: (String, String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onRefreshOfficialSubscription: (String, (Boolean) -> Unit) -> Unit,
    onRefreshUserGroup: (String, (MainViewModel.SubscriptionGroupRefreshResult) -> Unit) -> Unit,
    onDeleteOfficialSubscription: (String, (Boolean) -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    scannedSubscriptionText: String?,
    onScannedSubscriptionTextConsumed: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp
) {
    val context = LocalContext.current
    var addDialogVisible by rememberSaveable { mutableStateOf(false) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var subscriptionInput by rememberSaveable { mutableStateOf("") }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var editDialogVisible by rememberSaveable { mutableStateOf(false) }
    var deleteDialogVisible by rememberSaveable { mutableStateOf(false) }
    var editingGroupId by rememberSaveable { mutableStateOf("") }
    var deletingOfficialSubscription by rememberSaveable { mutableStateOf(false) }
    var groupActionMode by rememberSaveable { mutableStateOf<GroupActionMode?>(null) }

    LaunchedEffect(scannedSubscriptionText) {
        scannedSubscriptionText?.let { text ->
            subscriptionInput = text.trim()
            addDialogVisible = true
            onScannedSubscriptionTextConsumed()
        }
    }

    fun importClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?.trim()
        if (text.isNullOrBlank()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
        } else {
            subscriptionInput = text
            addDialogVisible = true
        }
    }

    fun editGroup(group: NodeGroupSummary) {
        editingGroupId = group.id.removePrefix("imported:")
        groupName = group.name
        subscriptionInput = group.subscriptionUrl.orEmpty()
        groupActionMode = null
        editDialogVisible = true
    }

    fun confirmDeleteGroup(group: NodeGroupSummary) {
        editingGroupId = group.id.removePrefix("imported:")
        deletingOfficialSubscription = false
        deleteDialogVisible = true
    }

    fun confirmDeleteOfficialSubscription(group: SubscriptionGroup) {
        editingGroupId = group.sourceId
        deletingOfficialSubscription = true
        deleteDialogVisible = true
    }

    AppScreenScaffold(
        title = "仓库源",
        onBack = onBack,
        showBackNavigation = false,
        actions = {
            IconButton(
                onClick = {
                    groupActionMode = if (groupActionMode == GroupActionMode.UPDATE) {
                        null
                    } else {
                        GroupActionMode.UPDATE
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = if (groupActionMode == GroupActionMode.UPDATE) {
                        "退出更新仓库源模式"
                    } else {
                        "进入更新仓库源模式"
                    },
                    tint = if (groupActionMode == GroupActionMode.UPDATE) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = {
                    groupActionMode = null
                    groupName = ""
                    subscriptionInput = ""
                    addDialogVisible = true
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加私人源"
                )
            }
            IconButton(
                onClick = {
                    groupActionMode = if (groupActionMode == GroupActionMode.EDIT) {
                        null
                    } else {
                        GroupActionMode.EDIT
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = if (groupActionMode == GroupActionMode.EDIT) {
                        "退出编辑分组模式"
                    } else {
                        "进入编辑分组模式"
                    },
                    tint = if (groupActionMode == GroupActionMode.EDIT) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(
                onClick = {
                    groupActionMode = if (groupActionMode == GroupActionMode.DELETE) {
                        null
                    } else {
                        GroupActionMode.DELETE
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = if (groupActionMode == GroupActionMode.DELETE) {
                        "退出删除分组模式"
                    } else {
                        "进入删除分组模式"
                    },
                    tint = if (groupActionMode == GroupActionMode.DELETE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 16.dp,
            end = 20.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                subscriptionGroups.isNotEmpty() || userGroups.isNotEmpty() ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (subscriptionGroups.isNotEmpty()) {
                            item(key = "official-groups-header") { GroupTypeHeader("官方源") }
                            items(
                                items = subscriptionGroups,
                                key = { "subscription:${it.sourceId}" }
                            ) { group ->
                                SubscriptionGroupCard(
                                    group = group,
                                    nodeCount = nodeCounts[group.sourceId] ?: 0,
                                    selected = selectedNodeGroupId ==
                                        NodeGroupKey.Subscription(group.sourceId).value,
                                    actionMode = groupActionMode,
                                    onClick = { onSubscriptionSelected(group) },
                                    onViewDetails = { onViewSubscriptionDetails(group) },
                                    onRefresh = {
                                        onRefreshOfficialSubscription(group.sourceId) { success ->
                                            Toast.makeText(
                                                context,
                                                if (success) "官方源已更新" else "官方源更新失败或当前操作繁忙",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    onDelete = { confirmDeleteOfficialSubscription(group) }
                                )
                            }
                        }
                        if (userGroups.isNotEmpty()) {
                            item(key = "personal-groups-header") { GroupTypeHeader("私人源") }
                            items(
                                items = userGroups,
                                key = NodeGroupSummary::id
                            ) { group ->
                                UserSubscriptionGroupCard(
                                    group = group,
                                    selected = selectedNodeGroupId == group.id,
                                    actionMode = groupActionMode,
                                    onClick = { onUserGroupSelected(group) },
                                    onViewDetails = { onViewUserGroupDetails(group) },
                                    onEdit = { editGroup(group) },
                                    onDelete = { confirmDeleteGroup(group) },
                                    onRefresh = {
                                        onRefreshUserGroup(group.id.removePrefix("imported:")) { result ->
                                            val message = when (result) {
                                                MainViewModel.SubscriptionGroupRefreshResult.REFRESHED ->
                                                    "私人源已更新"
                                                MainViewModel.SubscriptionGroupRefreshResult.INVALID ->
                                                    "订阅内容中未找到可用节点"
                                                MainViewModel.SubscriptionGroupRefreshResult.FETCH_FAILED ->
                                                    "订阅链接获取失败"
                                                MainViewModel.SubscriptionGroupRefreshResult.NOT_FOUND ->
                                                    "私人源不存在"
                                                MainViewModel.SubscriptionGroupRefreshResult.BUSY ->
                                                    "当前操作繁忙，请稍后重试"
                                            }
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }

                isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }

                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomContentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无仓库源",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (addDialogVisible) {
        AppAlertDialog(
            onDismissRequest = { if (!isSubmitting) addDialogVisible = false },
            title = { Text("添加私人源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("组名") },
                        placeholder = { Text("例如：我的机场") },
                        enabled = !isSubmitting
                    )
                    OutlinedTextField(
                        value = subscriptionInput,
                        onValueChange = { subscriptionInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("订阅连接（可选）") },
                        placeholder = { Text("输入订阅链接或节点内容") },
                        enabled = !isSubmitting
                    )
                    Text(
                        text = "也可以使用下面的图标从剪贴板或二维码填充",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = ::importClipboard,
                                enabled = !isSubmitting
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = "剪贴板导入"
                                )
                            }
                            Text("剪贴板", fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = onScanQrCode,
                                enabled = !isSubmitting
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCodeScanner,
                                    contentDescription = "二维码导入"
                                )
                            }
                            Text("二维码", fontSize = 12.sp)
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { addDialogVisible = false },
                    enabled = !isSubmitting
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSubmitting = true
                        onCreateSubscriptionGroup(
                            groupName,
                            subscriptionInput.takeIf(String::isNotBlank)
                        ) { success ->
                            isSubmitting = false
                            if (success) addDialogVisible = false
                        }
                    },
                    enabled = groupName.isNotBlank() && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("添加")
                    }
                }
            }
        )
    }

    if (editDialogVisible && !deleteDialogVisible) {
        AppAlertDialog(
            onDismissRequest = { if (!isSubmitting) editDialogVisible = false },
            title = { Text("编辑私人源") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("组名") },
                        enabled = !isSubmitting
                    )
                    OutlinedTextField(
                        value = subscriptionInput,
                        onValueChange = { subscriptionInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        label = { Text("订阅连接（可选）") },
                        placeholder = { Text("留空则保留当前节点和链接") },
                        enabled = !isSubmitting
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { editDialogVisible = false },
                    enabled = !isSubmitting
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSubmitting = true
                        onUpdateSubscriptionGroup(
                            editingGroupId,
                            groupName,
                            subscriptionInput.takeIf(String::isNotBlank)
                        ) { success ->
                            isSubmitting = false
                            if (success) {
                                editDialogVisible = false
                            }
                        }
                    },
                    enabled = groupName.isNotBlank() && !isSubmitting
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("保存")
                    }
                }
            }
        )
    }

    if (deleteDialogVisible) {
        AppAlertDialog(
            onDismissRequest = { if (!isSubmitting) deleteDialogVisible = false },
            title = { Text(if (deletingOfficialSubscription) "移除官方源？" else "删除私人源？") },
            text = {
                Text(
                    if (deletingOfficialSubscription) {
                        "将从本机移除此官方订阅的节点；下次刷新后订阅可能重新出现。"
                    } else {
                        "删除后，该组及其中的节点都会被移除，且无法恢复。"
                    }
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteDialogVisible = false },
                    enabled = !isSubmitting
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isSubmitting = true
                        val onComplete: (Boolean) -> Unit = { success ->
                            isSubmitting = false
                            if (success) {
                                deleteDialogVisible = false
                                editDialogVisible = false
                                groupActionMode = null
                                deletingOfficialSubscription = false
                            }
                        }
                        if (deletingOfficialSubscription) {
                            onDeleteOfficialSubscription(editingGroupId, onComplete)
                        } else {
                            onDeleteSubscriptionGroup(editingGroupId, onComplete)
                        }
                    },
                    enabled = !isSubmitting
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }
}

@Composable
private fun SubscriptionGroupCard(
    group: SubscriptionGroup,
    nodeCount: Int,
    selected: Boolean,
    actionMode: GroupActionMode?,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val subscriptionActionMode = actionMode.takeIf {
        it == GroupActionMode.UPDATE || it == GroupActionMode.DELETE
    }
    GroupCard(
        name = group.name,
        nodeCount = nodeCount,
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        actionMode = subscriptionActionMode,
        onGroupAction = when (subscriptionActionMode) {
            GroupActionMode.UPDATE -> onRefresh
            GroupActionMode.DELETE -> onDelete
            else -> onViewDetails
        }
    )
}

@Composable
private fun UserSubscriptionGroupCard(
    group: NodeGroupSummary,
    selected: Boolean,
    actionMode: GroupActionMode?,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    val supportsRefresh = !group.subscriptionUrl.isNullOrBlank()
    GroupCard(
        name = group.name,
        nodeCount = group.nodeCount,
        traffic = personalSubscriptionTraffic(group),
        details = personalSubscriptionDetails(group),
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        actionMode = actionMode,
        onGroupAction = when (actionMode) {
            GroupActionMode.UPDATE -> onRefresh
            GroupActionMode.EDIT -> onEdit
            GroupActionMode.DELETE -> onDelete
            null -> onViewDetails
        },
        actionEnabled = actionMode != GroupActionMode.UPDATE || supportsRefresh
    )
}

@Composable
private fun GroupTypeHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun GroupCard(
    name: String,
    nodeCount: Int,
    traffic: SubscriptionTrafficInfo? = null,
    details: List<String> = emptyList(),
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    actionMode: GroupActionMode? = null,
    onGroupAction: () -> Unit = onViewDetails,
    actionEnabled: Boolean = true
) {
    val trailingIcon = when (actionMode) {
        GroupActionMode.UPDATE -> Icons.Rounded.Refresh
        GroupActionMode.EDIT -> Icons.Rounded.Edit
        GroupActionMode.DELETE -> Icons.Rounded.Delete
        null -> Icons.Rounded.ChevronRight
    }
    val trailingDescription = when (actionMode) {
        GroupActionMode.UPDATE -> "更新仓库源"
        GroupActionMode.EDIT -> "编辑私人源"
        GroupActionMode.DELETE -> "删除仓库源"
        null -> "查看线路信息"
    }
    val trailingTint = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        actionMode == GroupActionMode.DELETE -> MaterialTheme.colorScheme.error
        actionMode == GroupActionMode.EDIT || actionMode == GroupActionMode.UPDATE ->
            MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 按剩余流量比例填充卡片背景，模拟手机电量的显示效果。
            traffic?.progress?.let { progress ->
                Box(
                    modifier = Modifier.matchParentSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .align(Alignment.CenterStart)
                            .background(
                                subscriptionTrafficColor(traffic.status).copy(
                                    alpha = if (traffic.status == SubscriptionTrafficStatus.EXHAUSTED) {
                                        0.22f
                                    } else {
                                        0.26f
                                    }
                                )
                            )
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            Icon(
                imageVector = Icons.Rounded.Subscriptions,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(34.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = name,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                traffic?.let { trafficInfo ->
                    Text(
                        text = trafficInfo.text,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                details.forEach { detail ->
                    Text(
                        text = detail,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "$nodeCount 个节点",
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                maxLines = 1
            )
                IconButton(onClick = onGroupAction, enabled = actionEnabled) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = trailingDescription,
                    tint = trailingTint
                    )
                }
            }
    }
}

}

private val subscriptionExpireFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun personalSubscriptionTraffic(group: NodeGroupSummary): SubscriptionTrafficInfo? {
    val hasTraffic = group.subscriptionUploadBytes != null ||
        group.subscriptionDownloadBytes != null ||
        group.subscriptionTotalBytes != null
    if (!hasTraffic) return null

    val upload = group.subscriptionUploadBytes?.coerceAtLeast(0L) ?: 0L
    val download = group.subscriptionDownloadBytes?.coerceAtLeast(0L) ?: 0L
    val used = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    val total = group.subscriptionTotalBytes?.coerceAtLeast(0L)
        ?: return SubscriptionTrafficInfo(
            text = "流量：已用 ${TrafficUsageFormatter.formatBytes(used)}",
            status = SubscriptionTrafficStatus.UNKNOWN,
            progress = null
        )

    val remaining = total - used
    // 余额小于等于 0 时视为已用完，卡片整张使用黑色填充。
    if (remaining <= 0L) {
        return SubscriptionTrafficInfo(
            text = "流量已用完",
            status = SubscriptionTrafficStatus.EXHAUSTED,
            progress = 1f
        )
    }

    val remainingRatio = (remaining.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    val percentage = (remainingRatio * 100.0)
        .roundToInt()
        .coerceIn(1, 100)
    // 剩余流量分级：50% 以上绿色，10%～50% 黄色，10% 以下红色。
    val status = when {
        remainingRatio >= 0.5 -> SubscriptionTrafficStatus.SAFE
        remainingRatio >= 0.1 -> SubscriptionTrafficStatus.WARNING
        else -> SubscriptionTrafficStatus.CRITICAL
    }

    return SubscriptionTrafficInfo(
        text = "剩余流量：${TrafficUsageFormatter.formatBytes(remaining)}（$percentage%）",
        status = status,
        progress = remainingRatio.toFloat()
    )
}

private fun personalSubscriptionDetails(group: NodeGroupSummary): List<String> = buildList {
    group.subscriptionExpireAt?.takeIf { it > 0L }?.let { expireAt ->
        runCatching {
            Instant.ofEpochMilli(expireAt)
                .atZone(ZoneId.systemDefault())
                .format(subscriptionExpireFormatter)
        }.getOrNull()?.let { formatted ->
            add(if (expireAt <= System.currentTimeMillis()) "已过期：$formatted" else "到期：$formatted")
        }
    }
}

private fun subscriptionTrafficColor(
    status: SubscriptionTrafficStatus
): Color = when (status) {
    SubscriptionTrafficStatus.SAFE -> Color(0xFF2E7D32)
    SubscriptionTrafficStatus.WARNING -> Color(0xFFF9A825)
    SubscriptionTrafficStatus.CRITICAL -> Color(0xFFC62828)
    SubscriptionTrafficStatus.EXHAUSTED -> Color.Black
    SubscriptionTrafficStatus.UNKNOWN -> Color.Transparent
}
