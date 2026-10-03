package io.github.HanJason233.chimera.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subscriptions
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.data.model.NodeGroupSummary
import io.github.HanJason233.chimera.data.model.NodeSource
import io.github.HanJason233.chimera.data.model.SubscriptionGroup
import io.github.HanJason233.chimera.feature.nodegrouping.domain.CountryGroupingStrategy
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryOption
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.util.TrafficUsageFormatter
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class GroupActionMode {
    UPDATE,
    EDIT,
    DELETE
}

private enum class SubscriptionImportSource {
    REMOTE,
    CLIPBOARD,
    QR_CODE,
    FILE
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
    val allWarehouseNodes by viewModel.allWarehouseNodes.collectAsStateWithLifecycle()
    val selectedCountryCodes by viewModel.warehouseGroupingCountryCodes
        .collectAsStateWithLifecycle()
    val selectedNodeGroupId by viewModel.selectedNodeGroupId.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val countryOptions = remember(allWarehouseNodes) {
        CountryGroupingStrategy.groups(allWarehouseNodes)
    }
    val filteredWarehouseNodes = allWarehouseNodes.filter { node ->
        selectedCountryCodes == null ||
            selectedCountryCodes?.contains(node.getCountryCode()) == true
    }
    val nodeCounts = filteredWarehouseNodes
        .asSequence()
        .filter { it.source == NodeSource.SUBSCRIPTION }
        .mapNotNull(Node::subscriptionSourceId)
        .groupingBy { it }
        .eachCount()
    val personalNodeCounts = filteredWarehouseNodes
        .asSequence()
        .filter { it.source == NodeSource.LOCAL_GROUP }
        .mapNotNull(Node::subscriptionSourceId)
        .groupingBy { it }
        .eachCount()

    SubscriptionGroupSelectionScreen(
        subscriptionGroups = subscriptionGroups,
        userGroups = userGroups,
        selectedNodeGroupId = selectedNodeGroupId,
        nodeCounts = nodeCounts,
        personalNodeCounts = personalNodeCounts,
        countryOptions = countryOptions,
        selectedCountryCodes = selectedCountryCodes,
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
        onSaveCountryCodes = viewModel::setWarehouseGroupingCountryCodes,
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
    personalNodeCounts: Map<String, Int>,
    countryOptions: List<CountryOption>,
    selectedCountryCodes: Set<String>?,
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
    onSaveCountryCodes: (Set<String>?) -> Unit,
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
    var importSource by remember { mutableStateOf(SubscriptionImportSource.REMOTE) }
    var importStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var countryDialogVisible by rememberSaveable { mutableStateOf(false) }
    var draftCountryCodes by remember { mutableStateOf(emptySet<String>()) }
    val importScope = rememberCoroutineScope()
    val availableCountryCodes = remember(countryOptions) {
        countryOptions.mapTo(mutableSetOf()) { it.code }
    }
    val selectedCodes = selectedCountryCodes
    val isAllCountriesSelected = selectedCodes == null ||
        (availableCountryCodes.isNotEmpty() &&
            selectedCodes.containsAll(availableCountryCodes))

    val subscriptionFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importSource = SubscriptionImportSource.FILE
        importScope.launch {
            val content = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                }
            }.getOrNull()
            if (content.isNullOrBlank()) {
                Toast.makeText(context, "无法读取订阅文件", Toast.LENGTH_SHORT).show()
            } else {
                subscriptionInput = content
                importStatus = "已读取文件内容（${content.length} 字符）"
                addDialogVisible = true
            }
        }
    }

    LaunchedEffect(scannedSubscriptionText) {
        scannedSubscriptionText?.let { text ->
            importSource = SubscriptionImportSource.QR_CODE
            subscriptionInput = text.trim()
            importStatus = "已读取二维码内容（${text.trim().length} 字符）"
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
            importSource = SubscriptionImportSource.CLIPBOARD
            subscriptionInput = text
            importStatus = "已读取剪贴板内容（${text.length} 字符）"
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
                    draftCountryCodes = selectedCountryCodes
                        ?.intersect(availableCountryCodes)
                        ?: availableCountryCodes
                    countryDialogVisible = true
                }
            ) {
                Icon(
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = "选择仓库源显示的地区",
                    tint = if (isAllCountriesSelected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
            IconButton(
                onClick = {
                    groupName = ""
                    subscriptionInput = ""
                    importSource = SubscriptionImportSource.REMOTE
                    importStatus = null
                    addDialogVisible = true
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加私人源"
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
                                    nodeCount = personalNodeCounts[
                                        group.id.removePrefix("imported:")
                                    ] ?: 0,
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

    if (countryDialogVisible) {
        AppAlertDialog(
            onDismissRequest = { countryDialogVisible = false },
            title = { Text("选择仓库源地区") },
            text = {
                Column {
                    Text(
                        text = "仓库源中的节点只显示所选地区。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { draftCountryCodes = availableCountryCodes }) {
                            Text("全选")
                        }
                        TextButton(onClick = { draftCountryCodes = emptySet() }) {
                            Text("清空")
                        }
                    }
                    if (countryOptions.isEmpty()) {
                        Text(
                            text = "暂无可识别的地区",
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(countryOptions, key = CountryOption::code) { option ->
                                val checked = option.code in draftCountryCodes
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(value = checked, role = Role.Checkbox) {
                                            draftCountryCodes = if (checked) {
                                                draftCountryCodes - option.code
                                            } else {
                                                draftCountryCodes + option.code
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${option.flag} ${option.name}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${option.nodeCount} 个节点",
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Checkbox(checked = checked, onCheckedChange = null)
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { countryDialogVisible = false }) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val savedCodes = if (
                            availableCountryCodes.isNotEmpty() &&
                            draftCountryCodes.containsAll(availableCountryCodes)
                        ) {
                            null
                        } else {
                            draftCountryCodes
                        }
                        onSaveCountryCodes(savedCodes)
                        countryDialogVisible = false
                    }
                ) {
                    Text("应用")
                }
            }
        )
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
                    Text(
                        text = "导入方式",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SubscriptionImportOption(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.Link,
                                label = "网络订阅",
                                selected = importSource == SubscriptionImportSource.REMOTE,
                                enabled = !isSubmitting,
                                onClick = {
                                    importSource = SubscriptionImportSource.REMOTE
                                    subscriptionInput = ""
                                    importStatus = null
                                }
                            )
                            SubscriptionImportOption(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.ContentPaste,
                                label = "剪贴板",
                                selected = importSource == SubscriptionImportSource.CLIPBOARD,
                                enabled = !isSubmitting,
                                onClick = ::importClipboard
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SubscriptionImportOption(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Default.QrCodeScanner,
                                label = "二维码",
                                selected = importSource == SubscriptionImportSource.QR_CODE,
                                enabled = !isSubmitting,
                                onClick = {
                                    importSource = SubscriptionImportSource.QR_CODE
                                    subscriptionInput = ""
                                    importStatus = null
                                    onScanQrCode()
                                }
                            )
                            SubscriptionImportOption(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.InsertDriveFile,
                                label = "文件",
                                selected = importSource == SubscriptionImportSource.FILE,
                                enabled = !isSubmitting,
                                onClick = {
                                    importSource = SubscriptionImportSource.FILE
                                    subscriptionInput = ""
                                    importStatus = null
                                    subscriptionFilePicker.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                    if (importSource == SubscriptionImportSource.REMOTE) {
                        OutlinedTextField(
                            value = subscriptionInput,
                            onValueChange = { subscriptionInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("订阅链接") },
                            placeholder = { Text("输入远程订阅链接") },
                            enabled = !isSubmitting
                        )
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = importStatus ?: "请点击上方入口读取订阅内容",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
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
        updatedAt = group.updatedAt,
        status = "长期有效",
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        actionMode = subscriptionActionMode,
        onGroupAction = when (subscriptionActionMode) {
            GroupActionMode.UPDATE -> onRefresh
            GroupActionMode.DELETE -> onDelete
            else -> onViewDetails
        },
        onRefresh = onRefresh,
        onDelete = onDelete
    )
}

@Composable
private fun UserSubscriptionGroupCard(
    group: NodeGroupSummary,
    nodeCount: Int,
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
        nodeCount = nodeCount,
        traffic = personalSubscriptionTrafficV2(group),
        updatedAt = group.subscriptionUpdatedAt,
        status = personalSubscriptionStatus(group),
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
        actionEnabled = actionMode != GroupActionMode.UPDATE || supportsRefresh,
        onRefresh = onRefresh,
        onEdit = onEdit,
        onDelete = onDelete
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
private fun SubscriptionImportOption(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                modifier = Modifier.padding(start = 6.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GroupCard(
    name: String,
    nodeCount: Int,
    traffic: SubscriptionTrafficInfo? = null,
    updatedAt: Long? = null,
    status: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    actionMode: GroupActionMode? = null,
    onGroupAction: () -> Unit = onViewDetails,
    actionEnabled: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    ModernGroupCard(
        name = name,
        nodeCount = nodeCount,
        traffic = traffic,
        updatedAt = updatedAt,
        status = status,
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        onRefresh = onRefresh,
        onEdit = onEdit,
        onDelete = onDelete,
        actionMode = actionMode,
        onGroupAction = onGroupAction,
        actionEnabled = actionEnabled
    )
}

@Composable
private fun ModernGroupCard(
    name: String,
    nodeCount: Int,
    traffic: SubscriptionTrafficInfo?,
    updatedAt: Long?,
    status: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    onRefresh: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    actionMode: GroupActionMode?,
    onGroupAction: () -> Unit,
    actionEnabled: Boolean
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(16.dp)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val mutedColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusColor = if (status?.startsWith("已过期") == true) {
        MaterialTheme.colorScheme.error
    } else {
        mutedColor
    }
    val progress = traffic?.progress?.coerceIn(0f, 1f)
    val progressColor = when (traffic?.status) {
        SubscriptionTrafficStatus.SAFE -> Color(0xFF2E7D32)
        SubscriptionTrafficStatus.WARNING -> Color(0xFFF9A825)
        SubscriptionTrafficStatus.CRITICAL -> MaterialTheme.colorScheme.error
        SubscriptionTrafficStatus.EXHAUSTED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.primary
    }
    val progressTrackColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    }
    val progressModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 6.dp, max = 6.dp)
        .clip(RoundedCornerShape(50))
        .background(progressTrackColor)
        .then(
            if (progress != null) {
                Modifier.semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f, 0)
                }
            } else {
                Modifier
            }
        )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = cardShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.82f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Subscriptions,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    color = contentColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                status?.let { label ->
                    Text(
                        text = if (label == "长期有效") {
                            label
                        } else {
                            "到期时间: $label"
                        },
                        modifier = Modifier.padding(start = 8.dp),
                        color = statusColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    if (actionMode == null) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "更多操作",
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                        }
                        DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                        ) {
                        DropdownMenuItem(
                            text = { Text("查看详情") },
                            leadingIcon = {
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                            },
                            onClick = {
                                menuExpanded = false
                                onViewDetails()
                            }
                        )
                        onRefresh?.let { refresh ->
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    refresh()
                                }
                            )
                        }
                        onEdit?.let { edit ->
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Edit, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    edit()
                                }
                            )
                        }
                        onDelete?.let { delete ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "删除",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    delete()
                                }
                            )
                        }
                        }
                    } else {
                        val actionIcon = when (actionMode) {
                            GroupActionMode.UPDATE -> Icons.Rounded.Refresh
                            GroupActionMode.EDIT -> Icons.Rounded.Edit
                            GroupActionMode.DELETE -> Icons.Rounded.Delete
                            null -> Icons.Rounded.MoreVert
                        }
                        IconButton(
                            onClick = onGroupAction,
                            enabled = actionEnabled,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = actionIcon,
                                contentDescription = "仓库源操作",
                                tint = if (actionMode == GroupActionMode.DELETE) {
                                    MaterialTheme.colorScheme.error
                                } else if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }
            }

            Box(modifier = progressModifier) {
                progress?.let { value ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value)
                            .fillMaxHeight()
                            .background(progressColor)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = traffic?.text ?: "流量数据不可用",
                    modifier = Modifier.weight(1f),
                    color = mutedColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                formatRelativeUpdate(updatedAt)?.let { updatedText ->
                    Text(
                        text = "· $updatedText",
                        modifier = Modifier.padding(start = 6.dp),
                        color = mutedColor,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$nodeCount 个节点",
                    color = mutedColor,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
}

private val subscriptionExpireFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatRelativeUpdate(updatedAt: Long?): String? {
    val timestamp = updatedAt?.takeIf { it > 0L } ?: return null
    val elapsed = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    return when {
        elapsed < 60_000L -> "刚刚"
        elapsed < 3_600_000L -> "${elapsed / 60_000L}分钟前"
        elapsed < 86_400_000L -> "${elapsed / 3_600_000L}小时前"
        elapsed < 604_800_000L -> "${elapsed / 86_400_000L}天前"
        else -> runCatching {
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(subscriptionExpireFormatter)
        }.getOrNull() ?: "更新时间未知"
    }
}

private fun personalSubscriptionTrafficV2(group: NodeGroupSummary): SubscriptionTrafficInfo? {
    val hasTraffic = group.subscriptionUploadBytes != null ||
        group.subscriptionDownloadBytes != null ||
        group.subscriptionTotalBytes != null
    if (!hasTraffic) return null

    val upload = group.subscriptionUploadBytes?.coerceAtLeast(0L) ?: 0L
    val download = group.subscriptionDownloadBytes?.coerceAtLeast(0L) ?: 0L
    val used = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    val total = group.subscriptionTotalBytes?.coerceAtLeast(0L)
        ?: return SubscriptionTrafficInfo(
            text = "已用 ${TrafficUsageFormatter.formatBytes(used)}",
            status = SubscriptionTrafficStatus.UNKNOWN,
            progress = null
        )

    if (total <= 0L) {
        return SubscriptionTrafficInfo(
            text = "总量 ${TrafficUsageFormatter.formatBytes(total)} / 已用 ${TrafficUsageFormatter.formatBytes(used)}",
            status = SubscriptionTrafficStatus.UNKNOWN,
            progress = 0f
        )
    }

    val usedRatio = used.toDouble() / total.toDouble()
    val remainingRatio = 1.0 - usedRatio
    val status = when {
        remainingRatio < 0.0 -> SubscriptionTrafficStatus.EXHAUSTED
        remainingRatio >= 0.5 -> SubscriptionTrafficStatus.SAFE
        remainingRatio >= 0.1 -> SubscriptionTrafficStatus.WARNING
        else -> SubscriptionTrafficStatus.CRITICAL
    }

    return SubscriptionTrafficInfo(
        text = "总量 ${TrafficUsageFormatter.formatBytes(total)} / 已用 ${TrafficUsageFormatter.formatBytes(used)}",
        status = status,
        progress = remainingRatio.coerceIn(0.0, 1.0).toFloat()
    )
}

private fun personalSubscriptionStatus(group: NodeGroupSummary): String {
    val expireAt = group.subscriptionExpireAt?.takeIf { it > 0L }
        ?: return "长期有效"
    val formatted = runCatching {
        Instant.ofEpochMilli(expireAt)
            .atZone(ZoneId.systemDefault())
            .format(subscriptionExpireFormatter)
    }.getOrNull() ?: return "有效期未知"
    return if (expireAt <= System.currentTimeMillis()) {
        "已过期 $formatted"
    } else {
        formatted
    }
}
