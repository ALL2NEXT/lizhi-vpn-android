package xyz.a202132.app.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeGroupKey
import xyz.a202132.app.data.model.NodeGroupSummary
import xyz.a202132.app.data.model.SubscriptionGroup
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.viewmodel.MainViewModel

private enum class GroupActionMode {
    EDIT,
    DELETE
}

@Composable
internal fun SubscriptionGroupSelectionDestination(
    viewModel: MainViewModel,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onUserGroupSelected: (NodeGroupSummary) -> Unit,
    onViewUserGroupDetails: (NodeGroupSummary) -> Unit,
    onFavoritesSelected: () -> Unit,
    onViewFavoritesDetails: () -> Unit,
    onCreateSubscriptionGroup: (String, String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    scannedSubscriptionText: String?,
    onScannedSubscriptionTextConsumed: () -> Unit,
    onSwitchToCountries: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 108.dp
) {
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val userGroups by viewModel.userSubscriptionGroups.collectAsStateWithLifecycle()
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val favoriteNodes by viewModel.favoriteNodes.collectAsStateWithLifecycle()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsStateWithLifecycle()
    val hideFavoriteNodeGroup by viewModel.hideFavoriteNodeGroup.collectAsStateWithLifecycle()
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
        favoriteNodeCount = favoriteNodes.size,
        hideFavoriteNodeGroup = hideFavoriteNodeGroup,
        isLoading = isLoading,
        onSubscriptionSelected = onSubscriptionSelected,
        onViewSubscriptionDetails = onViewSubscriptionDetails,
        onUserGroupSelected = onUserGroupSelected,
        onViewUserGroupDetails = onViewUserGroupDetails,
        onFavoritesSelected = onFavoritesSelected,
        onViewFavoritesDetails = onViewFavoritesDetails,
        onCreateSubscriptionGroup = onCreateSubscriptionGroup,
        onUpdateSubscriptionGroup = onUpdateSubscriptionGroup,
        onDeleteSubscriptionGroup = onDeleteSubscriptionGroup,
        onScanQrCode = onScanQrCode,
        scannedSubscriptionText = scannedSubscriptionText,
        onScannedSubscriptionTextConsumed = onScannedSubscriptionTextConsumed,
        onSwitchToCountries = onSwitchToCountries,
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
    favoriteNodeCount: Int,
    hideFavoriteNodeGroup: Boolean,
    isLoading: Boolean,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onUserGroupSelected: (NodeGroupSummary) -> Unit,
    onViewUserGroupDetails: (NodeGroupSummary) -> Unit,
    onFavoritesSelected: () -> Unit,
    onViewFavoritesDetails: () -> Unit,
    onCreateSubscriptionGroup: (String, String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    scannedSubscriptionText: String?,
    onScannedSubscriptionTextConsumed: () -> Unit,
    onSwitchToCountries: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp
) {
    val favoriteSelected = selectedNodeGroupId == NodeGroupKey.Favorites.value
    val context = LocalContext.current
    var addDialogVisible by rememberSaveable { mutableStateOf(false) }
    var groupName by rememberSaveable { mutableStateOf("") }
    var subscriptionInput by rememberSaveable { mutableStateOf("") }
    var isSubmitting by rememberSaveable { mutableStateOf(false) }
    var editDialogVisible by rememberSaveable { mutableStateOf(false) }
    var deleteDialogVisible by rememberSaveable { mutableStateOf(false) }
    var editingGroupId by rememberSaveable { mutableStateOf("") }
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
        deleteDialogVisible = true
    }

    AppScreenScaffold(
        title = "按订阅仓库分组",
        onBack = onBack,
        showBackNavigation = false,
        actions = {
            IconButton(onClick = onSwitchToCountries) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = "切换为按地区分组"
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
                subscriptionGroups.isNotEmpty() || userGroups.isNotEmpty() || !hideFavoriteNodeGroup ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = subscriptionGroups,
                            key = { "subscription:${it.sourceId}" }
                        ) { group ->
                            SubscriptionGroupCard(
                                group = group,
                                nodeCount = nodeCounts[group.sourceId] ?: 0,
                                selected = selectedNodeGroupId ==
                                    NodeGroupKey.Subscription(group.sourceId).value,
                                onClick = { onSubscriptionSelected(group) },
                                onViewDetails = { onViewSubscriptionDetails(group) }
                            )
                        }
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
                                onDelete = { confirmDeleteGroup(group) }
                            )
                        }
                        if (!hideFavoriteNodeGroup) {
                            item(key = "favorites") {
                                FavoriteGroupCard(
                                    nodeCount = favoriteNodeCount,
                                    selected = favoriteSelected,
                                    onClick = onFavoritesSelected,
                                    onViewDetails = onViewFavoritesDetails
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
                        text = "暂无可用订阅线路",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 操作栏默认收纳在屏幕侧边，展开后依次显示新增、编辑和删除按钮。
            SideExpandableActionMenu(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = bottomContentPadding),
                actions = listOf(
                    SideMenuAction(Icons.Default.Add, "添加订阅组") {
                        groupActionMode = null
                        groupName = ""
                        subscriptionInput = ""
                        addDialogVisible = true
                    },
                    SideMenuAction(
                        Icons.Rounded.Edit,
                        if (groupActionMode == GroupActionMode.EDIT) {
                            "退出编辑分组模式"
                        } else {
                            "进入编辑分组模式"
                        }
                    ) {
                        groupActionMode = if (groupActionMode == GroupActionMode.EDIT) {
                            null
                        } else {
                            GroupActionMode.EDIT
                        }
                    },
                    SideMenuAction(
                        Icons.Rounded.Delete,
                        if (groupActionMode == GroupActionMode.DELETE) {
                            "退出删除分组模式"
                        } else {
                            "进入删除分组模式"
                        }
                    ) {
                        groupActionMode = if (groupActionMode == GroupActionMode.DELETE) {
                            null
                        } else {
                            GroupActionMode.DELETE
                        }
                    }
                )
            )
        }
    }

    if (addDialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) addDialogVisible = false },
            title = { Text("添加订阅组") },
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
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) editDialogVisible = false },
            title = { Text("编辑订阅组") },
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
        AlertDialog(
            onDismissRequest = { if (!isSubmitting) deleteDialogVisible = false },
            title = { Text("删除订阅组？") },
            text = { Text("删除后，该组及其中的节点都会被移除，且无法恢复。") },
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
                        onDeleteSubscriptionGroup(editingGroupId) { success ->
                            isSubmitting = false
                            if (success) {
                                deleteDialogVisible = false
                                editDialogVisible = false
                                groupActionMode = null
                            }
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
private fun FavoriteGroupCard(
    nodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit
) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = "收藏节点",
                modifier = Modifier.weight(1f),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
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
            IconButton(onClick = onViewDetails) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "查看收藏节点",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun SubscriptionGroupCard(
    group: SubscriptionGroup,
    nodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit
) {
    GroupCard(
        name = group.name,
        nodeCount = nodeCount,
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails
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
    onDelete: () -> Unit
) {
    GroupCard(
        name = group.name,
        nodeCount = group.nodeCount,
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        actionMode = actionMode,
        onGroupAction = when (actionMode) {
            GroupActionMode.EDIT -> onEdit
            GroupActionMode.DELETE -> onDelete
            null -> onViewDetails
        }
    )
}

@Composable
private fun GroupCard(
    name: String,
    nodeCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    actionMode: GroupActionMode? = null,
    onGroupAction: () -> Unit = onViewDetails
) {
    val trailingIcon = when (actionMode) {
        GroupActionMode.EDIT -> Icons.Rounded.Edit
        GroupActionMode.DELETE -> Icons.Rounded.Delete
        null -> Icons.Rounded.ChevronRight
    }
    val trailingDescription = when (actionMode) {
        GroupActionMode.EDIT -> "编辑订阅组"
        GroupActionMode.DELETE -> "删除订阅组"
        null -> "查看线路信息"
    }
    val trailingTint = when {
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        actionMode == GroupActionMode.DELETE -> MaterialTheme.colorScheme.error
        actionMode == GroupActionMode.EDIT -> MaterialTheme.colorScheme.primary
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
            Text(
                text = name,
                modifier = Modifier.weight(1f),
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
            IconButton(onClick = onGroupAction) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = trailingDescription,
                    tint = trailingTint
                )
            }
        }
    }
}
