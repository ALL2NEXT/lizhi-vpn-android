package io.github.HanJason233.chimera.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
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
import io.github.HanJason233.chimera.data.model.PersonalSourceIconMode
import io.github.HanJason233.chimera.data.model.SubscriptionGroup
import io.github.HanJason233.chimera.feature.nodegrouping.domain.CountryGroupingStrategy
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryOption
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.SubscriptionFaviconLoader
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.PersonalSourceIconStore
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
    onCreateSubscriptionGroup: (String?, (Boolean) -> Unit) -> Unit,
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
        onUpdateUserGroupIcon = viewModel::updatePersonalSourceIcon,
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
    onCreateSubscriptionGroup: (String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onUpdateUserGroupIcon: (String, String, String?, String?, (Boolean) -> Unit) -> Unit,
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
    var importStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var countryDialogVisible by rememberSaveable { mutableStateOf(false) }
    var draftCountryCodes by remember { mutableStateOf(emptySet<String>()) }
    var iconDialogGroup by remember { mutableStateOf<NodeGroupSummary?>(null) }
    var editingIconMode by rememberSaveable { mutableStateOf(PersonalSourceIconMode.AUTO_REMOTE.name) }
    var editingCustomIconUrl by rememberSaveable { mutableStateOf("") }
    var stagedLocalIconFile by remember { mutableStateOf<String?>(null) }
    var iconUrlError by remember { mutableStateOf<String?>(null) }
    var isSavingIconSettings by remember { mutableStateOf(false) }
    var isImportingLocalIcon by remember { mutableStateOf(false) }
    val importScope = rememberCoroutineScope()
    val availableCountryCodes = remember(countryOptions) {
        countryOptions.mapTo(mutableSetOf()) { it.code }
    }
    val selectedCodes = selectedCountryCodes
    val isAllCountriesSelected = selectedCodes == null ||
        (availableCountryCodes.isNotEmpty() &&
            selectedCodes.containsAll(availableCountryCodes))

    fun discardStagedLocalIcon() {
        val group = iconDialogGroup
        stagedLocalIconFile
            ?.takeIf { it != group?.localIconFile }
            ?.let { PersonalSourceIconStore.delete(context, it) }
        stagedLocalIconFile = null
    }

    fun closeIconSettings() {
        if (isSavingIconSettings || isImportingLocalIcon) return
        discardStagedLocalIcon()
        iconDialogGroup = null
        iconUrlError = null
    }

    fun openIconSettings(group: NodeGroupSummary) {
        discardStagedLocalIcon()
        iconDialogGroup = group
        editingIconMode = PersonalSourceIconMode.fromStorage(group.iconMode).name
        editingCustomIconUrl = group.customIconUrl.orEmpty()
        stagedLocalIconFile = null
        iconUrlError = null
    }

    fun saveIconSettings() {
        val group = iconDialogGroup ?: return
        val mode = PersonalSourceIconMode.fromStorage(editingIconMode)
        val remoteUrl = if (mode == PersonalSourceIconMode.CUSTOM_REMOTE) {
            SubscriptionFaviconLoader.customImageUrl(editingCustomIconUrl)
        } else {
            null
        }
        if (mode == PersonalSourceIconMode.CUSTOM_REMOTE && remoteUrl == null) {
            iconUrlError = "请输入有效的 HTTP 或 HTTPS 图片链接"
            return
        }
        val localFile = if (mode == PersonalSourceIconMode.LOCAL) {
            stagedLocalIconFile ?: group.localIconFile
        } else {
            null
        }
        if (mode == PersonalSourceIconMode.LOCAL && localFile == null) return

        isSavingIconSettings = true
        onUpdateUserGroupIcon(
            group.id.removePrefix("imported:"),
            mode.name,
            remoteUrl,
            localFile
        ) { success ->
            isSavingIconSettings = false
            if (success) {
                if (group.localIconFile != localFile) {
                    PersonalSourceIconStore.delete(context, group.localIconFile)
                }
                stagedLocalIconFile
                    ?.takeIf { it != localFile }
                    ?.let { PersonalSourceIconStore.delete(context, it) }
                stagedLocalIconFile = null
                iconDialogGroup = null
                iconUrlError = null
            } else {
                Toast.makeText(context, "图标设置保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val localIconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val group = iconDialogGroup ?: return@rememberLauncherForActivityResult
        isImportingLocalIcon = true
        importScope.launch {
            val fileName = withContext(Dispatchers.IO) {
                PersonalSourceIconStore.importImage(
                    context.applicationContext,
                    group.id.removePrefix("imported:"),
                    uri
                )
            }
            isImportingLocalIcon = false
            if (fileName == null) {
                Toast.makeText(context, "无法读取图片，请选择有效的图片文件", Toast.LENGTH_SHORT).show()
            } else {
                stagedLocalIconFile
                    ?.takeIf { it != group.localIconFile }
                    ?.let { PersonalSourceIconStore.delete(context, it) }
                stagedLocalIconFile = fileName
                editingIconMode = PersonalSourceIconMode.LOCAL.name
                iconUrlError = null
            }
        }
    }

    val subscriptionFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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
                    subscriptionInput = ""
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
                                    onConfigureIcon = { openIconSettings(group) },
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
                                        text = "${option.marker} ${option.name}",
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
                        value = subscriptionInput,
                        onValueChange = {
                            subscriptionInput = it
                            importStatus = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 5,
                        label = { Text("智能识别") },
                        placeholder = {
                            Text(
                                "1. 支持 Clash 系订阅\n" +
                                    "2. 支持 URI，以及 URI 订阅链接\n" +
                                    "3. 支持混合类型，支持多个订阅链接\n" +
                                    "4. 支持智能命名"
                            )
                        },
                        enabled = !isSubmitting
                    )
                    Text(
                        text = "填入方式",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SubscriptionImportOption(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.ContentPaste,
                            contentDescription = "从剪贴板导入",
                            enabled = !isSubmitting,
                            onClick = ::importClipboard
                        )
                        SubscriptionImportOption(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.QrCodeScanner,
                            contentDescription = "扫描二维码导入",
                            enabled = !isSubmitting,
                            onClick = {
                                importStatus = null
                                onScanQrCode()
                            }
                        )
                        SubscriptionImportOption(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.InsertDriveFile,
                            contentDescription = "从文件导入",
                            enabled = !isSubmitting,
                            onClick = {
                                importStatus = null
                                subscriptionFilePicker.launch(arrayOf("*/*"))
                            }
                        )
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
                            subscriptionInput.takeIf(String::isNotBlank)
                        ) { success ->
                            isSubmitting = false
                            if (success) addDialogVisible = false
                        }
                    },
                    enabled = !isSubmitting
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

    iconDialogGroup?.let { group ->
        val selectedMode = PersonalSourceIconMode.fromStorage(editingIconMode)
        val selectedLocalIcon = stagedLocalIconFile ?: group.localIconFile
        val canSaveIconSettings = when (selectedMode) {
            PersonalSourceIconMode.DEFAULT,
            PersonalSourceIconMode.AUTO_REMOTE -> true
            PersonalSourceIconMode.CUSTOM_REMOTE -> editingCustomIconUrl.isNotBlank()
            PersonalSourceIconMode.LOCAL -> selectedLocalIcon != null
        }
        AppAlertDialog(
            onDismissRequest = { closeIconSettings() },
            title = { Text("${group.name}的图标") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PersonalIconModeOption(
                        title = "默认图标",
                        mode = PersonalSourceIconMode.DEFAULT,
                        selectedMode = selectedMode,
                        enabled = !isSavingIconSettings && !isImportingLocalIcon,
                        onSelect = { editingIconMode = it.name }
                    )
                    PersonalIconModeOption(
                        title = "远程自动获取（根据订阅地址）",
                        mode = PersonalSourceIconMode.AUTO_REMOTE,
                        selectedMode = selectedMode,
                        enabled = !isSavingIconSettings && !isImportingLocalIcon,
                        onSelect = { editingIconMode = it.name }
                    )
                    PersonalIconModeOption(
                        title = "远程指定图标链接",
                        mode = PersonalSourceIconMode.CUSTOM_REMOTE,
                        selectedMode = selectedMode,
                        enabled = !isSavingIconSettings && !isImportingLocalIcon,
                        onSelect = {
                            editingIconMode = it.name
                            iconUrlError = null
                        }
                    )
                    if (selectedMode == PersonalSourceIconMode.CUSTOM_REMOTE) {
                        OutlinedTextField(
                            value = editingCustomIconUrl,
                            onValueChange = {
                                editingCustomIconUrl = it
                                iconUrlError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("图标图片链接") },
                            placeholder = { Text("https://example.com/icon.png") },
                            isError = iconUrlError != null,
                            supportingText = iconUrlError?.let { message ->
                                { Text(message) }
                            },
                            enabled = !isSavingIconSettings && !isImportingLocalIcon
                        )
                    }
                    PersonalIconModeOption(
                        title = "本地选择图标",
                        mode = PersonalSourceIconMode.LOCAL,
                        selectedMode = selectedMode,
                        enabled = !isSavingIconSettings && !isImportingLocalIcon,
                        onSelect = { editingIconMode = it.name }
                    )
                    if (selectedMode == PersonalSourceIconMode.LOCAL) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (selectedLocalIcon == null) {
                                    "尚未选择图片"
                                } else {
                                    "已选择本地图片"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(
                                onClick = { localIconPicker.launch(arrayOf("image/*")) },
                                enabled = !isSavingIconSettings && !isImportingLocalIcon
                            ) {
                                if (isImportingLocalIcon) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    Text(if (selectedLocalIcon == null) "选择图片" else "更换图片")
                                }
                            }
                        }
                    }
                    Text(
                        text = "远程图标不可用时会继续显示默认图标。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { closeIconSettings() },
                    enabled = !isSavingIconSettings && !isImportingLocalIcon
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = { saveIconSettings() },
                    enabled = canSaveIconSettings &&
                        !isSavingIconSettings && !isImportingLocalIcon
                ) {
                    if (isSavingIconSettings) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("保存")
                    }
                }
            }
        )
    }
}

@Composable
private fun PersonalIconModeOption(
    title: String,
    mode: PersonalSourceIconMode,
    selectedMode: PersonalSourceIconMode,
    enabled: Boolean,
    onSelect: (PersonalSourceIconMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onSelect(mode) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selectedMode == mode,
            onClick = { onSelect(mode) },
            enabled = enabled
        )
        Text(
            text = title,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
            style = MaterialTheme.typography.bodyMedium
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
    onConfigureIcon: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    val supportsRefresh = !group.subscriptionUrl.isNullOrBlank()
    GroupCard(
        name = group.name,
        subscriptionUrl = group.subscriptionUrl,
        iconMode = group.iconMode,
        customIconUrl = group.customIconUrl,
        localIconFile = group.localIconFile,
        nodeCount = nodeCount,
        traffic = personalSubscriptionTrafficV2(group),
        updatedAt = group.subscriptionUpdatedAt,
        status = personalSubscriptionStatus(group),
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        onConfigureIcon = onConfigureIcon,
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
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun GroupCard(
    name: String,
    subscriptionUrl: String? = null,
    iconMode: String = PersonalSourceIconMode.AUTO_REMOTE.name,
    customIconUrl: String? = null,
    localIconFile: String? = null,
    nodeCount: Int,
    traffic: SubscriptionTrafficInfo? = null,
    updatedAt: Long? = null,
    status: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    onConfigureIcon: (() -> Unit)? = null,
    actionMode: GroupActionMode? = null,
    onGroupAction: () -> Unit = onViewDetails,
    actionEnabled: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    ModernGroupCard(
        name = name,
        subscriptionUrl = subscriptionUrl,
        iconMode = iconMode,
        customIconUrl = customIconUrl,
        localIconFile = localIconFile,
        nodeCount = nodeCount,
        traffic = traffic,
        updatedAt = updatedAt,
        status = status,
        selected = selected,
        onClick = onClick,
        onViewDetails = onViewDetails,
        onConfigureIcon = onConfigureIcon,
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
    subscriptionUrl: String?,
    iconMode: String,
    customIconUrl: String?,
    localIconFile: String?,
    nodeCount: Int,
    traffic: SubscriptionTrafficInfo?,
    updatedAt: Long?,
    status: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onViewDetails: () -> Unit,
    onConfigureIcon: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    actionMode: GroupActionMode?,
    onGroupAction: () -> Unit,
    actionEnabled: Boolean
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    val parsedIconMode = remember(iconMode) {
        PersonalSourceIconMode.fromStorage(iconMode)
    }
    val remoteIconUrl = when (parsedIconMode) {
        PersonalSourceIconMode.AUTO_REMOTE ->
            SubscriptionFaviconLoader.faviconUrl(subscriptionUrl)
        PersonalSourceIconMode.CUSTOM_REMOTE ->
            SubscriptionFaviconLoader.customImageUrl(customIconUrl)
        PersonalSourceIconMode.DEFAULT,
        PersonalSourceIconMode.LOCAL -> null
    }
    val iconLoadKey = when (parsedIconMode) {
        PersonalSourceIconMode.DEFAULT -> "default"
        PersonalSourceIconMode.AUTO_REMOTE -> "auto:$remoteIconUrl"
        PersonalSourceIconMode.CUSTOM_REMOTE -> "remote:$remoteIconUrl"
        PersonalSourceIconMode.LOCAL -> "local:$localIconFile"
    }
    val appContext = LocalContext.current.applicationContext
    val groupIcon by produceState<ImageBitmap?>(initialValue = null, key1 = iconLoadKey) {
        value = when (parsedIconMode) {
            PersonalSourceIconMode.DEFAULT -> null
            PersonalSourceIconMode.AUTO_REMOTE,
            PersonalSourceIconMode.CUSTOM_REMOTE -> remoteIconUrl?.let { url ->
                withContext(Dispatchers.IO) {
                    SubscriptionFaviconLoader.load(appContext, url)?.asImageBitmap()
                }
            }
            PersonalSourceIconMode.LOCAL -> withContext(Dispatchers.IO) {
                PersonalSourceIconStore.load(appContext, localIconFile)?.asImageBitmap()
            }
        }
    }
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
                val loadedIcon = groupIcon
                if (loadedIcon != null) {
                    Image(
                        bitmap = loadedIcon,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
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
                }
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
                        onConfigureIcon?.let { configureIcon ->
                            DropdownMenuItem(
                                text = { Text("设置图标") },
                                leadingIcon = {
                                    Icon(Icons.Rounded.InsertDriveFile, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    configureIcon()
                                }
                            )
                        }
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
