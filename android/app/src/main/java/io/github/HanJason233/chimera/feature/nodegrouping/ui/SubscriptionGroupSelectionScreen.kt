package io.github.HanJason233.chimera.feature.nodegrouping.ui

import io.github.HanJason233.chimera.feature.nodegrouping.data.SubscriptionGroupRefreshResult
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
import io.github.HanJason233.chimera.data.model.PersonalSourceUserAgentMode
import io.github.HanJason233.chimera.data.model.SubscriptionGroup
import io.github.HanJason233.chimera.feature.nodegrouping.domain.CountryGroupingStrategy
import io.github.HanJason233.chimera.feature.nodegrouping.model.CountryOption
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.SubscriptionFaviconLoader
import io.github.HanJason233.chimera.feature.nodegrouping.data.icon.PersonalSourceIconStore
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SubscriptionGroupSelectionDestination(
    viewModel: MainViewModel,
    onSubscriptionSelected: (SubscriptionGroup) -> Unit,
    onViewSubscriptionDetails: (SubscriptionGroup) -> Unit,
    onUserGroupSelected: (NodeGroupSummary) -> Unit,
    onViewUserGroupDetails: (NodeGroupSummary) -> Unit,
    onCreateSubscriptionGroup: (String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (
        String,
        String,
        String?,
        PersonalSourceUserAgentMode,
        String?,
        (Boolean) -> Unit
    ) -> Unit,
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
    val refreshingSourceIds by viewModel.refreshingWarehouseGroupIds
        .collectAsStateWithLifecycle()
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
        refreshingSourceIds = refreshingSourceIds,
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
        onSetAutoRefreshOnStartup = viewModel::setAutoRefreshOnStartup,
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
    refreshingSourceIds: Set<String>,
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
    onUpdateSubscriptionGroup: (
        String,
        String,
        String?,
        PersonalSourceUserAgentMode,
        String?,
        (Boolean) -> Unit
    ) -> Unit,
    onUpdateUserGroupIcon: (String, String, String?, String?, (Boolean) -> Unit) -> Unit,
    onSetAutoRefreshOnStartup: (String, Boolean) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onRefreshOfficialSubscription: (String, (Boolean) -> Unit) -> Unit,
    onRefreshUserGroup: (String, (SubscriptionGroupRefreshResult) -> Unit) -> Unit,
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
    var importStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var countryDialogVisible by rememberSaveable { mutableStateOf(false) }
    var officialGroupsExpanded by rememberSaveable { mutableStateOf(true) }
    var personalGroupsExpanded by rememberSaveable { mutableStateOf(true) }
    var draftCountryCodes by remember { mutableStateOf(emptySet<String>()) }
    var editingUserGroup by remember { mutableStateOf<NodeGroupSummary?>(null) }
    var editingAutoRefreshOnStartup by rememberSaveable { mutableStateOf(false) }
    var editingIconMode by rememberSaveable { mutableStateOf(PersonalSourceIconMode.AUTO_REMOTE.name) }
    var editingCustomIconUrl by rememberSaveable { mutableStateOf("") }
    var editingUserAgentMode by rememberSaveable {
        mutableStateOf(PersonalSourceUserAgentMode.AUTO.name)
    }
    var editingCustomUserAgent by rememberSaveable { mutableStateOf("") }
    var userAgentError by remember { mutableStateOf<String?>(null) }
    var stagedLocalIconFile by remember { mutableStateOf<String?>(null) }
    var iconUrlError by remember { mutableStateOf<String?>(null) }
    var isImportingLocalIcon by remember { mutableStateOf(false) }
    var openSwipeCardId by rememberSaveable { mutableStateOf<String?>(null) }
    val importScope = rememberCoroutineScope()
    val availableCountryCodes = remember(countryOptions) {
        countryOptions.mapTo(mutableSetOf()) { it.code }
    }
    val selectedCodes = selectedCountryCodes
    val isAllCountriesSelected = selectedCodes == null ||
        (availableCountryCodes.isNotEmpty() &&
            selectedCodes.containsAll(availableCountryCodes))

    fun discardStagedLocalIcon() {
        val group = editingUserGroup
        stagedLocalIconFile
            ?.takeIf { it != group?.localIconFile }
            ?.let { PersonalSourceIconStore.delete(context, it) }
        stagedLocalIconFile = null
    }

    fun cancelEditGroup() {
        if (isSubmitting || isImportingLocalIcon) return
        discardStagedLocalIcon()
        editingUserGroup = null
        iconUrlError = null
        editDialogVisible = false
    }

    fun saveEditedGroup() {
        val group = editingUserGroup ?: return
        val mode = PersonalSourceIconMode.fromStorage(editingIconMode)
        val userAgentMode = PersonalSourceUserAgentMode.fromStorage(editingUserAgentMode)
        val customUserAgent = editingCustomUserAgent.trim()
            .takeIf { userAgentMode == PersonalSourceUserAgentMode.CUSTOM }
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
        if (userAgentMode == PersonalSourceUserAgentMode.CUSTOM && customUserAgent.isNullOrBlank()) {
            userAgentError = "固定 User-Agent 不能为空"
            return
        }

        isSubmitting = true
        onUpdateSubscriptionGroup(
            editingGroupId,
            groupName,
            subscriptionInput.takeIf(String::isNotBlank),
            userAgentMode,
            customUserAgent
        ) { groupSaved ->
            if (!groupSaved) {
                isSubmitting = false
            } else {
                onUpdateUserGroupIcon(
                    group.id.removePrefix("imported:"),
                    mode.name,
                    remoteUrl,
                    localFile
                ) { iconSaved ->
                    isSubmitting = false
                    if (iconSaved) {
                        if (group.localIconFile != localFile) {
                            PersonalSourceIconStore.delete(context, group.localIconFile)
                        }
                        stagedLocalIconFile
                            ?.takeIf { it != localFile }
                            ?.let { PersonalSourceIconStore.delete(context, it) }
                        stagedLocalIconFile = null
                        editingUserGroup = null
                        iconUrlError = null
                        editDialogVisible = false
                    } else {
                        Toast.makeText(
                            context,
                            "私人源已保存，但图标设置保存失败，请重试",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    fun editGroup(group: NodeGroupSummary) {
        discardStagedLocalIcon()
        editingUserGroup = group
        editingGroupId = group.id.removePrefix("imported:")
        groupName = group.name
        subscriptionInput = group.subscriptionUrl.orEmpty()
        editingIconMode = PersonalSourceIconMode.fromStorage(group.iconMode).name
        editingCustomIconUrl = group.customIconUrl.orEmpty()
        editingUserAgentMode = PersonalSourceUserAgentMode.fromStorage(group.userAgentMode).name
        editingCustomUserAgent = group.customUserAgent.orEmpty()
        editingAutoRefreshOnStartup = group.autoRefreshOnStartup
        userAgentError = null
        stagedLocalIconFile = null
        iconUrlError = null
        editDialogVisible = true
    }

    val localIconPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val group = editingUserGroup ?: return@rememberLauncherForActivityResult
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
        },
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 16.dp,
            end = 0.dp,
            bottom = 0.dp
        ),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                subscriptionGroups.isNotEmpty() || userGroups.isNotEmpty() ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottomContentPadding + 72.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        if (subscriptionGroups.isNotEmpty()) {
                            item(key = "official-groups-header") {
                                GroupTypeHeader(
                                    title = "官方源",
                                    expanded = officialGroupsExpanded,
                                    onToggle = {
                                        officialGroupsExpanded = !officialGroupsExpanded
                                    }
                                )
                            }
                            if (officialGroupsExpanded) {
                                items(
                                    items = subscriptionGroups,
                                    key = { "subscription:${it.sourceId}" }
                                ) { group ->
                                    val swipeKey = "subscription:${group.sourceId}"
                                    val refresh = {
                                        onRefreshOfficialSubscription(group.sourceId) { success ->
                                            Toast.makeText(
                                                context,
                                                if (success) "官方源已更新" else "官方源更新失败或当前操作繁忙",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    SwipeActionCard(
                                        itemKey = swipeKey,
                                        actions = listOf(
                                            SwipeCardAction(
                                                icon = Icons.Rounded.ChevronRight,
                                                label = "详情",
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                onClick = { onViewSubscriptionDetails(group) }
                                            ),
                                            SwipeCardAction(
                                                icon = Icons.Rounded.Refresh,
                                                label = "刷新",
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                onClick = refresh
                                            ),
                                        ),
                                        revealed = openSwipeCardId == swipeKey,
                                        onRevealedChange = { isRevealed ->
                                            openSwipeCardId = if (isRevealed) swipeKey else null
                                        },
                                        onSwipeRight = {
                                            openSwipeCardId = null
                                            confirmDeleteOfficialSubscription(group)
                                        }
                                    ) { swipeModifier ->
                                        SubscriptionGroupCard(
                                            modifier = swipeModifier,
                                            group = group,
                                            nodeCount = nodeCounts[group.sourceId] ?: 0,
                                            isRefreshing = group.sourceId in refreshingSourceIds,
                                            selected = selectedNodeGroupId ==
                                                NodeGroupKey.Subscription(group.sourceId).value,
                                            onClick = {
                                                openSwipeCardId = null
                                                onSubscriptionSelected(group)
                                            }
                                        )
                                    }
                                    Divider(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
                            }
                        }
                        if (userGroups.isNotEmpty()) {
                            item(key = "personal-groups-header") {
                                GroupTypeHeader(
                                    title = "私人源",
                                    expanded = personalGroupsExpanded,
                                    onToggle = {
                                        personalGroupsExpanded = !personalGroupsExpanded
                                    }
                                )
                            }
                            if (personalGroupsExpanded) {
                                items(
                                    items = userGroups,
                                    key = NodeGroupSummary::id
                                ) { group ->
                                    val swipeKey = group.id
                                    val refresh = {
                                        onRefreshUserGroup(group.id.removePrefix("imported:")) { result ->
                                            val message = when (result) {
                                                SubscriptionGroupRefreshResult.REFRESHED ->
                                                    "私人源已更新"
                                                SubscriptionGroupRefreshResult.INVALID ->
                                                    "订阅内容中未找到可用节点"
                                                SubscriptionGroupRefreshResult.FETCH_FAILED ->
                                                    "订阅链接获取失败"
                                                SubscriptionGroupRefreshResult.NOT_FOUND ->
                                                    "私人源不存在"
                                                SubscriptionGroupRefreshResult.BUSY ->
                                                    "当前操作繁忙，请稍后重试"
                                            }
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    val edit = { editGroup(group) }
                                    SwipeActionCard(
                                        itemKey = swipeKey,
                                        actions = listOf(
                                            SwipeCardAction(
                                                icon = Icons.Rounded.ChevronRight,
                                                label = "详情",
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                onClick = { onViewUserGroupDetails(group) }
                                            ),
                                            SwipeCardAction(
                                                icon = Icons.Rounded.Refresh,
                                                label = "刷新",
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                onClick = refresh
                                            ),
                                            SwipeCardAction(
                                                icon = Icons.Rounded.Edit,
                                                label = "编辑",
                                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                                onClick = edit
                                            )
                                        ),
                                        revealed = openSwipeCardId == swipeKey,
                                        onRevealedChange = { isRevealed ->
                                            openSwipeCardId = if (isRevealed) swipeKey else null
                                        },
                                        onSwipeRight = {
                                            openSwipeCardId = null
                                            confirmDeleteGroup(group)
                                        }
                                    ) { swipeModifier ->
                                        UserSubscriptionGroupCard(
                                            modifier = swipeModifier,
                                            group = group,
                                            nodeCount = personalNodeCounts[
                                                group.id.removePrefix("imported:")
                                            ] ?: 0,
                                            isRefreshing = group.id in refreshingSourceIds,
                                            selected = selectedNodeGroupId == group.id,
                                            onClick = {
                                                openSwipeCardId = null
                                                onUserGroupSelected(group)
                                            }
                                        )
                                    }
                                    Divider(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                }
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

            ExtendedFloatingActionButton(
                onClick = {
                    subscriptionInput = ""
                    importStatus = null
                    addDialogVisible = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 4.dp, bottom = bottomContentPadding),
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = { Text("智能导入") }
            )
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
        val group = editingUserGroup
        val selectedMode = PersonalSourceIconMode.fromStorage(editingIconMode)
        val selectedUserAgentMode = PersonalSourceUserAgentMode.fromStorage(editingUserAgentMode)
        val selectedLocalIcon = stagedLocalIconFile ?: group?.localIconFile
        val canSaveIconSettings = when (selectedMode) {
            PersonalSourceIconMode.DEFAULT,
            PersonalSourceIconMode.AUTO_REMOTE -> true
            PersonalSourceIconMode.CUSTOM_REMOTE -> editingCustomIconUrl.isNotBlank()
            PersonalSourceIconMode.LOCAL -> selectedLocalIcon != null
        }
        val canSaveUserAgent = selectedUserAgentMode == PersonalSourceUserAgentMode.AUTO ||
            editingCustomUserAgent.isNotBlank()
        AppAlertDialog(
            onDismissRequest = { cancelEditGroup() },
            title = { Text("编辑私人源") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                        label = { Text("订阅链接（可选）") },
                        placeholder = { Text("留空则保留当前节点和链接") },
                        enabled = !isSubmitting
                    )
                    if (
                        !group?.subscriptionUrl.isNullOrBlank() ||
                            subscriptionInput.isNotBlank()
                    ) {
                        AutoRefreshOnStartupRow(
                            checked = editingAutoRefreshOnStartup,
                            enabled = !isSubmitting,
                            onCheckedChange = { enabled ->
                                editingAutoRefreshOnStartup = enabled
                                group?.let {
                                    onSetAutoRefreshOnStartup(
                                        it.id.removePrefix("imported:"),
                                        enabled
                                    )
                                }
                            }
                        )
                    }
                    Text(
                        text = "订阅请求 User-Agent",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    PersonalSourceUserAgentOption(
                        title = "自动匹配（兼容 UA 重试）",
                        mode = PersonalSourceUserAgentMode.AUTO,
                        selectedMode = selectedUserAgentMode,
                        enabled = !isSubmitting && !isImportingLocalIcon,
                        onSelect = {
                            editingUserAgentMode = it.name
                            userAgentError = null
                        }
                    )
                    PersonalSourceUserAgentOption(
                        title = "固定 User-Agent",
                        mode = PersonalSourceUserAgentMode.CUSTOM,
                        selectedMode = selectedUserAgentMode,
                        enabled = !isSubmitting && !isImportingLocalIcon,
                        onSelect = {
                            editingUserAgentMode = it.name
                            userAgentError = null
                        }
                    )
                    if (selectedUserAgentMode == PersonalSourceUserAgentMode.CUSTOM) {
                        OutlinedTextField(
                            value = editingCustomUserAgent,
                            onValueChange = {
                                editingCustomUserAgent = it
                                userAgentError = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("固定 User-Agent") },
                            placeholder = { Text("例如：v2rayNG/2.3.7") },
                            isError = userAgentError != null,
                            supportingText = userAgentError?.let { message ->
                                { Text(message) }
                            },
                            enabled = !isSubmitting && !isImportingLocalIcon
                        )
                    } else {
                        Text(
                            text = "自动模式会按兼容 UA 顺序重试，成功解析到节点后停止。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        text = "图标设置",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    PersonalIconModeOption(
                        title = "默认图标",
                        mode = PersonalSourceIconMode.DEFAULT,
                        selectedMode = selectedMode,
                        enabled = !isSubmitting && !isImportingLocalIcon,
                        onSelect = { editingIconMode = it.name }
                    )
                    PersonalIconModeOption(
                        title = "远程自动获取（根据订阅地址）",
                        mode = PersonalSourceIconMode.AUTO_REMOTE,
                        selectedMode = selectedMode,
                        enabled = !isSubmitting && !isImportingLocalIcon,
                        onSelect = { editingIconMode = it.name }
                    )
                    PersonalIconModeOption(
                        title = "远程指定图标链接",
                        mode = PersonalSourceIconMode.CUSTOM_REMOTE,
                        selectedMode = selectedMode,
                        enabled = !isSubmitting && !isImportingLocalIcon,
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
                            enabled = !isSubmitting && !isImportingLocalIcon
                        )
                    }
                    PersonalIconModeOption(
                        title = "本地选择图标",
                        mode = PersonalSourceIconMode.LOCAL,
                        selectedMode = selectedMode,
                        enabled = !isSubmitting && !isImportingLocalIcon,
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
                                enabled = !isSubmitting && !isImportingLocalIcon
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
                    onClick = { cancelEditGroup() },
                    enabled = !isSubmitting && !isImportingLocalIcon
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = { saveEditedGroup() },
                    enabled = group != null && groupName.isNotBlank() &&
                        canSaveIconSettings && canSaveUserAgent &&
                        !isSubmitting && !isImportingLocalIcon
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
