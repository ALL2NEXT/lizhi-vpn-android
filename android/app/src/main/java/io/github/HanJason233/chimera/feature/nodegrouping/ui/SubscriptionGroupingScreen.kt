package io.github.HanJason233.chimera.feature.nodegrouping.ui

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.data.model.formatSpeedTestSelection
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.SpeedTestProgress

internal enum class NodeActionMode {
    EDIT,
    DELETE
}

@Composable
internal fun SubscriptionGroupingScreen(
    nodes: List<Node>,
    selectedNodeId: String?,
    isTesting: Boolean,
    testProgress: SpeedTestProgress?,
    isRefreshingAllSubscriptions: Boolean,
    nodeDisplaySettings: NodeDisplaySettings,
    protocolCoreSettings: ProtocolCoreSettings,
    groupManualSpeedTestScheme: SpeedTestScheme?,
    groupManualSpeedTestEnabled: Boolean,
    onRunManualGroupTest: (ManualGroupTestStage) -> Unit,
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
    var actionMode by rememberSaveable { mutableStateOf<NodeActionMode?>(null) }
    var addNodeDialogVisible by rememberSaveable { mutableStateOf(false) }
    var nodeUris by rememberSaveable { mutableStateOf("") }
    var editingNode by remember { mutableStateOf<Node?>(null) }
    var editingNodeName by rememberSaveable { mutableStateOf("") }
    var editingNodeUri by rememberSaveable { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<Node?>(null) }
    var isManagingNodes by rememberSaveable { mutableStateOf(false) }
    var manualTestMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var manualTestDialogHidden by rememberSaveable { mutableStateOf(false) }

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
        subtitle = if (isRefreshingAllSubscriptions) {
            "节点刷新中..."
        } else {
            null
        },
        onBack = handleBack,
        backEnabled = !isClosing,
        showBackNavigation = showBackNavigation,
        actions = {
            val testScheme = groupManualSpeedTestScheme
            if (groupManualSpeedTestEnabled && testScheme != null &&
                (testScheme.tcpingEnabled || testScheme.urlTestEnabled || testScheme.bandwidthEnabled)
            ) {
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
                        if (testScheme.tcpingEnabled) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "TCPing · 前${formatSpeedTestSelection(testScheme.tcpingSelectionMode, testScheme.tcpingSelectionValue)}"
                                    )
                                },
                                onClick = {
                                    manualTestMenuExpanded = false
                                    onRunManualGroupTest(ManualGroupTestStage.TCPING)
                                },
                                enabled = !isTesting
                            )
                        }
                        if (testScheme.urlTestEnabled) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "URL Test · 前${formatSpeedTestSelection(testScheme.urlTestSelectionMode, testScheme.urlTestSelectionValue)}"
                                    )
                                },
                                onClick = {
                                    manualTestMenuExpanded = false
                                    onRunManualGroupTest(ManualGroupTestStage.URL_TEST)
                                },
                                enabled = !isTesting
                            )
                        }
                        if (testScheme.bandwidthEnabled) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "带宽测速 · 前${formatSpeedTestSelection(testScheme.bandwidthSelectionMode, testScheme.bandwidthSelectionValue)}"
                                    )
                                },
                                onClick = {
                                    manualTestMenuExpanded = false
                                    onRunManualGroupTest(ManualGroupTestStage.BANDWIDTH)
                                },
                                enabled = !isTesting
                            )
                        }
                    }
                }
            }
            // 私人源详情沿用仓库源页面的顶部操作栏，只保留添加、编辑和删除。
            if (canManageNodes) {
                IconButton(
                    onClick = {
                        actionMode = null
                        nodeUris = ""
                        addNodeDialogVisible = true
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加节点"
                    )
                }
                IconButton(
                    onClick = {
                        actionMode = if (actionMode == NodeActionMode.EDIT) {
                            null
                        } else {
                            NodeActionMode.EDIT
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = if (actionMode == NodeActionMode.EDIT) {
                            "退出编辑节点模式"
                        } else {
                            "进入编辑节点模式"
                        },
                        tint = if (actionMode == NodeActionMode.EDIT) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                IconButton(
                    onClick = {
                        actionMode = if (actionMode == NodeActionMode.DELETE) {
                            null
                        } else {
                            NodeActionMode.DELETE
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = if (actionMode == NodeActionMode.DELETE) {
                            "退出删除节点模式"
                        } else {
                            "进入删除节点模式"
                        },
                        tint = if (actionMode == NodeActionMode.DELETE) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
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
            NodeListContent(
                nodes = nodes,
                selectedNodeId = selectedNodeId,
                isTesting = isTesting,
                nodeDisplaySettings = nodeDisplaySettings,
                protocolCoreSettings = protocolCoreSettings,
                onNodeSelected = handleNodeSelected,
                interactionEnabled = !isClosing,
                bottomContentPadding = bottomContentPadding,
                actionMode = actionMode,
                onEditNode = { node ->
                    editingNode = node
                    editingNodeName = node.name
                    editingNodeUri = node.rawLink
                    actionMode = null
                },
                onDeleteNode = { node -> deleteCandidate = node }
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
                        label = { Text("节点 URI 或订阅链接") },
                        placeholder = { Text("可粘贴一个或多个节点 URI") },
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

    deleteCandidate?.let { node ->
        if (onDeleteNode != null) {
            AppAlertDialog(
                onDismissRequest = { if (!isManagingNodes) deleteCandidate = null },
                title = { Text("删除节点？") },
                text = { Text("确认从当前私人源中删除“${node.getDisplayName()}”吗？") },
                dismissButton = {
                    TextButton(
                        onClick = { deleteCandidate = null },
                        enabled = !isManagingNodes
                    ) {
                        Text("取消")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            isManagingNodes = true
                            onDeleteNode(node) { message, success ->
                                isManagingNodes = false
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                if (success) {
                                    deleteCandidate = null
                                    actionMode = null
                                }
                            }
                        },
                        enabled = !isManagingNodes
                    ) {
                        if (isManagingNodes) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text("删除", color = MaterialTheme.colorScheme.error)
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
    selectedNodeId: String?,
    isTesting: Boolean,
    nodeDisplaySettings: NodeDisplaySettings,
    protocolCoreSettings: ProtocolCoreSettings,
    onNodeSelected: (Node) -> Unit,
    interactionEnabled: Boolean,
    bottomContentPadding: Dp,
    actionMode: NodeActionMode?,
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

    Column(modifier = Modifier.fillMaxSize()) {
        when {
            displayNodes.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无可用节点",
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = displayNodes,
                            key = { it.id }
                        ) { node ->
                            NodeListItem(
                                node = node,
                                isSelected = node.id == selectedNodeId,
                                enabled = interactionEnabled,
                                displaySettings = nodeDisplaySettings,
                                coreSettings = protocolCoreSettings,
                                onClick = { onNodeSelected(node) },
                                actionMode = actionMode,
                                onEditNode = { onEditNode(node) },
                                onDeleteNode = { onDeleteNode(node) }
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
