package io.github.HanJason233.chimera.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeDisplaySettings
import io.github.HanJason233.chimera.data.model.ManualGroupTestStage
import io.github.HanJason233.chimera.data.model.ProtocolCoreSettings
import io.github.HanJason233.chimera.data.local.HomeNodeTestResultsEntity
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.feature.nodetest.model.SpeedTestProgress

@Composable
internal fun SubscriptionGroupingScreen(
    nodes: List<Node>,
    selectedNodeId: String?,
    isTesting: Boolean,
    testProgress: SpeedTestProgress?,
    testResults: Map<String, HomeNodeTestResultsEntity>,
    displayedTestStage: ManualGroupTestStage,
    testingNodeIds: Set<String>,
    nodeDisplaySettings: NodeDisplaySettings,
    protocolCoreSettings: ProtocolCoreSettings,
    onRunManualGroupTest: (ManualGroupTestStage) -> Unit,
    onRunManualNodeTest: (Node, ManualGroupTestStage) -> Unit,
    onSortByTestResults: () -> Unit,
    onClearTestResults: (List<Node>, (String, Boolean) -> Unit) -> Unit,
    onNodeSelected: (Node) -> Unit,
    onBack: () -> Unit,
    showBackNavigation: Boolean = true,
    bottomContentPadding: Dp = 16.dp,
    screenTitle: String = "仓库源",
    onImportNodeUris: ((String, (String, Boolean) -> Unit) -> Unit)? = null,
    onUpdateNode: ((Node, String, String, (String, Boolean) -> Unit) -> Unit)? = null,
    onDeleteNode: ((Node, (String, Boolean) -> Unit) -> Unit)? = null
) {
    var isClosing by remember { mutableStateOf(false) }
    val canManageNodes = onImportNodeUris != null && onUpdateNode != null && onDeleteNode != null
    val context = LocalContext.current
    var addNodeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var nodeUris by rememberSaveable { mutableStateOf("") }
    var editingNode by remember { mutableStateOf<Node?>(null) }
    var editingNodeName by rememberSaveable { mutableStateOf("") }
    var editingNodeUri by rememberSaveable { mutableStateOf("") }
    var isManagingNodes by rememberSaveable { mutableStateOf(false) }
    val canDeleteNodes = onDeleteNode != null
    var manualTestMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var manualTestDialogHidden by rememberSaveable { mutableStateOf(false) }
    var clearTestDataDialogVisible by rememberSaveable { mutableStateOf(false) }
    var isClearingTestData by rememberSaveable { mutableStateOf(false) }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(testProgress?.isManualGroupTest) {
        if (testProgress?.isManualGroupTest == true) {
            manualTestDialogHidden = false
        }
    }

    val handleBack = {
        if (!isClosing) {
            isClosing = true
            onBack()
        }
    }

    val handleNodeSelected: (Node) -> Unit = { node ->
        if (!isClosing) {
            isClosing = true
            onNodeSelected(node)
        }
    }

    AppScreenScaffold(
        title = screenTitle,
        subtitle = null,
        onBack = handleBack,
        backEnabled = !isClosing,
        showBackNavigation = showBackNavigation,
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        searchPlaceholder = "搜索节点名称或服务器地址",
        onSearchQueryChange = { searchQuery = it },
        onSearchClose = {
            isSearchActive = false
            searchQuery = ""
        },
        actions = {
            if (!isSearchActive) {
                IconButton(onClick = { isSearchActive = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "搜索节点"
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { manualTestMenuExpanded = true },
                    enabled = !isTesting && nodes.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Speed,
                        contentDescription = "分组内手动测试"
                    )
                }
                DropdownMenu(
                    expanded = manualTestMenuExpanded,
                    onDismissRequest = { manualTestMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("TCPing") },
                        onClick = {
                            manualTestMenuExpanded = false
                            onRunManualGroupTest(ManualGroupTestStage.TCPING)
                        },
                        enabled = !isTesting
                    )
                    DropdownMenuItem(
                        text = { Text("URL Test") },
                        onClick = {
                            manualTestMenuExpanded = false
                            onRunManualGroupTest(ManualGroupTestStage.URL_TEST)
                        },
                        enabled = !isTesting
                    )
                    DropdownMenuItem(
                        text = { Text("带宽测速") },
                        onClick = {
                            manualTestMenuExpanded = false
                            onRunManualGroupTest(ManualGroupTestStage.BANDWIDTH)
                        },
                        enabled = !isTesting
                    )
                    DropdownMenuItem(
                        text = { Text("链路类型检测") },
                        onClick = {
                            manualTestMenuExpanded = false
                            onRunManualGroupTest(ManualGroupTestStage.LINK_TYPE)
                        },
                        enabled = !isTesting
                    )
                    DropdownMenuItem(
                        text = { Text("流媒体与 AI 解锁") },
                        onClick = {
                            manualTestMenuExpanded = false
                            onRunManualGroupTest(ManualGroupTestStage.IP_QUALITY)
                        },
                        enabled = !isTesting
                    )
                    Divider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                "清理测试数据",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            manualTestMenuExpanded = false
                            clearTestDataDialogVisible = true
                        },
                        enabled = !isTesting
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("按照测试结果排序") },
                        onClick = {
                            manualTestMenuExpanded = false
                            onSortByTestResults()
                        },
                        enabled = !isTesting && nodes.isNotEmpty()
                    )
                }
            }
            // 节点编辑和删除已移到卡片滑动操作中；顶部只保留添加入口。
            if (canManageNodes) {
                IconButton(
                    onClick = {
                        nodeUris = ""
                        addNodeDialogVisible = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加节点"
                    )
                }
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
            NodeListContent(
                nodes = nodes,
                searchQuery = searchQuery,
                testResults = testResults,
                testStage = displayedTestStage,
                testingNodeIds = testingNodeIds,
                selectedNodeId = selectedNodeId,
                isTesting = isTesting,
                nodeDisplaySettings = nodeDisplaySettings,
                protocolCoreSettings = protocolCoreSettings,
                onNodeSelected = handleNodeSelected,
                onRunManualNodeTest = onRunManualNodeTest,
                onShareNode = { node ->
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, node.rawLink)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "分享节点链接"))
                },
                interactionEnabled = !isClosing,
                canStartTest = !isTesting && !isClosing,
                bottomContentPadding = bottomContentPadding,
                canManageNodes = canManageNodes,
                canDeleteNodes = canDeleteNodes,
                onEditNode = { node ->
                    editingNode = node
                    editingNodeName = node.name
                    editingNodeUri = node.rawLink
                },
                onDeleteNode = { node ->
                    if (!isManagingNodes) {
                        isManagingNodes = true
                        onDeleteNode?.invoke(node) { message, _ ->
                            isManagingNodes = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    val manualProgress = testProgress?.takeIf {
        it.isManualGroupTest && !manualTestDialogHidden
    }
    if (manualProgress != null) {
        val progressValue = if (manualProgress.total > 0) {
            (manualProgress.completed.toFloat() / manualProgress.total).coerceIn(0f, 1f)
        } else {
            null
        }
        AppAlertDialog(
            onDismissRequest = { manualTestDialogHidden = true },
            title = { Text(manualProgress.title) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (progressValue == null) {
                        CircularProgressIndicator()
                    } else {
                        CircularProgressIndicator(progress = progressValue)
                    }
                    Text(
                        text = manualProgress.stage,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (manualProgress.total > 0) {
                            "${manualProgress.completed} / ${manualProgress.total}"
                        } else {
                            "准备测试..."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            dismissButton = {
                TextButton(onClick = { manualTestDialogHidden = true }) {
                    Text("隐藏")
                }
            },
            confirmButton = {}
        )
    }

    if (clearTestDataDialogVisible) {
        AppAlertDialog(
            onDismissRequest = {
                if (!isClearingTestData) clearTestDataDialogVisible = false
            },
            title = { Text("清理测试数据？") },
            text = {
                Text(
                    "将清除当前列表中 ${nodes.size} 个节点的 TCPing、URL Test、带宽、链路类型和解锁结果。"
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { clearTestDataDialogVisible = false },
                    enabled = !isClearingTestData
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isClearingTestData = true
                        onClearTestResults(nodes) { message, success ->
                            isClearingTestData = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            if (success) clearTestDataDialogVisible = false
                        }
                    },
                    enabled = !isClearingTestData
                ) {
                    if (isClearingTestData) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("清理", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }

    if (addNodeDialogVisible && onImportNodeUris != null) {
        AppAlertDialog(
            onDismissRequest = { if (!isManagingNodes) addNodeDialogVisible = false },
            title = { Text("添加节点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nodeUris,
                        onValueChange = { nodeUris = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("节点 URI") },
                        placeholder = { Text("粘贴单个或多个节点 URI，每行一个") },
                        enabled = !isManagingNodes
                    )
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? ClipboardManager
                            val text = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.trim()
                            if (text.isNullOrBlank()) {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            } else {
                                nodeUris = text
                            }
                        },
                        enabled = !isManagingNodes
                    ) {
                        Text("从剪贴板粘贴")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { addNodeDialogVisible = false },
                    enabled = !isManagingNodes
                ) {
                    Text("取消")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isManagingNodes = true
                        onImportNodeUris(nodeUris) { message, shouldClose ->
                            isManagingNodes = false
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            if (shouldClose) {
                                addNodeDialogVisible = false
                                nodeUris = ""
                            }
                        }
                    },
                    enabled = nodeUris.isNotBlank() && !isManagingNodes
                ) {
                    if (isManagingNodes) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("添加")
                    }
                }
            }
        )
    }

    editingNode?.let { node ->
        if (onUpdateNode != null) {
            AppAlertDialog(
                onDismissRequest = { if (!isManagingNodes) editingNode = null },
                title = { Text("编辑节点") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = editingNodeName,
                            onValueChange = { editingNodeName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("节点名称") },
                            enabled = !isManagingNodes
                        )
                        OutlinedTextField(
                            value = editingNodeUri,
                            onValueChange = { editingNodeUri = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            label = { Text("节点 URI") },
                            enabled = !isManagingNodes
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { editingNode = null },
                        enabled = !isManagingNodes
                    ) {
                        Text("取消")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isManagingNodes = true
                            onUpdateNode(node, editingNodeName, editingNodeUri) { message, success ->
                                isManagingNodes = false
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                if (success) editingNode = null
                            }
                        },
                        enabled = editingNodeName.isNotBlank() && !isManagingNodes
                    ) {
                        if (isManagingNodes) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text("保存")
                        }
                    }
                }
            )
        }
    }

}

@Composable
private fun NodeListContent(
    nodes: List<Node>,
    searchQuery: String,
    testResults: Map<String, HomeNodeTestResultsEntity>,
    testStage: ManualGroupTestStage,
    testingNodeIds: Set<String>,
    selectedNodeId: String?,
    isTesting: Boolean,
    nodeDisplaySettings: NodeDisplaySettings,
    protocolCoreSettings: ProtocolCoreSettings,
    onNodeSelected: (Node) -> Unit,
    onRunManualNodeTest: (Node, ManualGroupTestStage) -> Unit,
    onShareNode: (Node) -> Unit,
    interactionEnabled: Boolean,
    canStartTest: Boolean,
    bottomContentPadding: Dp,
    canManageNodes: Boolean,
    canDeleteNodes: Boolean,
    onEditNode: (Node) -> Unit,
    onDeleteNode: (Node) -> Unit
) {
    var frozenNodeOrderIds by remember { mutableStateOf<List<String>?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(isTesting) {
        if (isTesting) {
            if (frozenNodeOrderIds == null) {
                frozenNodeOrderIds = nodes.map { it.id }
            }
        } else {
            frozenNodeOrderIds = null
        }
    }

    val displayNodes = remember(nodes, isTesting, frozenNodeOrderIds) {
        if (!isTesting || frozenNodeOrderIds == null) {
            nodes
        } else {
            val nodeMap = nodes.associateBy { it.id }
            val frozenNodes = frozenNodeOrderIds.orEmpty().mapNotNull(nodeMap::get)
            val newNodes = nodes.filterNot { it.id in frozenNodeOrderIds.orEmpty() }
            frozenNodes + newNodes
        }
    }
    val normalizedSearchQuery = searchQuery.trim()
    val visibleNodes = remember(displayNodes, normalizedSearchQuery) {
        if (normalizedSearchQuery.isEmpty()) {
            displayNodes
        } else {
            displayNodes.filter { node ->
                node.name.contains(normalizedSearchQuery, ignoreCase = true) ||
                    node.getDisplayName().contains(normalizedSearchQuery, ignoreCase = true) ||
                    node.server.contains(normalizedSearchQuery, ignoreCase = true) ||
                    node.type.protocol.contains(normalizedSearchQuery, ignoreCase = true) ||
                    node.subscriptionSourceName.orEmpty()
                        .contains(normalizedSearchQuery, ignoreCase = true)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            visibleNodes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (normalizedSearchQuery.isNotEmpty()) {
                            "没有匹配的节点"
                        } else {
                            "暂无可用节点"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 4.dp,
                            bottom = bottomContentPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(
                            items = visibleNodes,
                            key = { it.id }
                        ) { node ->
                            NodeListItem(
                                node = node,
                                isSelected = node.id == selectedNodeId,
                                enabled = interactionEnabled,
                                displaySettings = nodeDisplaySettings,
                                coreSettings = protocolCoreSettings,
                                testResult = testResults[node.id],
                                testStage = testStage,
                                isTestRunning = isTesting &&
                                    node.id in testingNodeIds,
                                canStartTest = canStartTest,
                                onTest = { stage -> onRunManualNodeTest(node, stage) },
                                onShare = if (node.subscriptionSourceId.isNullOrBlank()) {
                                    { onShareNode(node) }
                                } else {
                                    null
                                },
                                onEdit = if (canManageNodes) {
                                    { onEditNode(node) }
                                } else {
                                    null
                                },
                                onDelete = if (canDeleteNodes) {
                                    { onDeleteNode(node) }
                                } else {
                                    null
                                },
                                onClick = { onNodeSelected(node) }
                            )
                            Divider(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                    }
                }
            }
        }

        if (!interactionEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
            )
        }
    }
}
