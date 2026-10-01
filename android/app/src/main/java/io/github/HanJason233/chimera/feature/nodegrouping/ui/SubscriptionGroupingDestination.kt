package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.ManualGroupTestStage
import io.github.HanJason233.chimera.data.model.SpeedTestScheme
import io.github.HanJason233.chimera.viewmodel.MainViewModel

/** 按订阅仓库分组页面的状态绑定入口。 */
@Composable
internal fun SubscriptionGroupingDestination(
    viewModel: MainViewModel,
    onNodeSelected: (Node) -> Unit,
    onBack: () -> Unit,
    showBackNavigation: Boolean = true,
    bottomContentPadding: Dp = 16.dp,
    nodesOverride: List<Node>? = null,
    screenTitle: String = "按订阅仓库分组",
    importTargetGroupId: String? = null
) {
    val viewModelNodes by viewModel.nodes.collectAsStateWithLifecycle()
    val resultSortMode by viewModel.nodeTestResultSortMode.collectAsStateWithLifecycle()
    val sourceNodes = nodesOverride ?: viewModelNodes
    val nodes = remember(sourceNodes, resultSortMode) {
        viewModel.sortNodesByTestResult(sourceNodes, resultSortMode)
    }
    val selectedNodeId by viewModel.selectedNodeId.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val isRefreshingAllSubscriptions by
        viewModel.isRefreshingAllSubscriptions.collectAsStateWithLifecycle()
    val speedTestSchemes by viewModel.speedTestSchemes.collectAsStateWithLifecycle()
    val groupManualSpeedTestSchemeId by
        viewModel.groupManualSpeedTestSchemeId.collectAsStateWithLifecycle()
    val groupManualSpeedTestEnabled by
        viewModel.groupManualSpeedTestEnabled.collectAsStateWithLifecycle()
    val groupManualSpeedTestScheme = speedTestSchemes.firstOrNull {
        it.id == groupManualSpeedTestSchemeId
    } ?: speedTestSchemes.firstOrNull { it.id == SpeedTestScheme.DEFAULT_ID }
    val nodeDisplaySettings by
        viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()
    val protocolCoreSettings by
        viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val onImportNodeUris: ((String, (String, Boolean) -> Unit) -> Unit)? =
        importTargetGroupId?.let { groupId ->
            { input, onComplete ->
                viewModel.importNodeUrisIntoSubscriptionGroup(groupId, input) { result, count ->
                    val (message, shouldClose) = when (result) {
                        MainViewModel.SubscriptionGroupNodeImportResult.IMPORTED ->
                            "已导入 $count 个节点" to true
                        MainViewModel.SubscriptionGroupNodeImportResult.ALREADY_EXISTS ->
                            "这些节点已在当前分组中" to true
                        MainViewModel.SubscriptionGroupNodeImportResult.INVALID ->
                            "未找到可导入节点，请检查 URI" to false
                        MainViewModel.SubscriptionGroupNodeImportResult.FETCH_FAILED ->
                            "订阅链接获取失败，请检查地址和网络" to false
                        MainViewModel.SubscriptionGroupNodeImportResult.NOT_FOUND ->
                            "当前分组不存在" to false
                    }
                    onComplete(message, shouldClose)
                }
            }
        }
    val onUpdateLocalNode:
        ((Node, String, String, (String, Boolean) -> Unit) -> Unit)? =
        importTargetGroupId?.let { groupId ->
            { node, name, rawLink, onComplete ->
                viewModel.updateLocalGroupNode(groupId, node.id, name, rawLink) { result ->
                    val (message, shouldClose) = when (result) {
                        MainViewModel.LocalGroupNodeEditResult.UPDATED -> "节点已更新" to true
                        MainViewModel.LocalGroupNodeEditResult.INVALID ->
                            "节点 URI 无效或与当前内核不兼容" to false
                        MainViewModel.LocalGroupNodeEditResult.NOT_FOUND -> "节点不存在" to false
                    }
                    onComplete(message, shouldClose)
                }
            }
        }
    val onDeleteLocalNode: ((Node, (String, Boolean) -> Unit) -> Unit)? =
        importTargetGroupId?.let { groupId ->
            { node, onComplete ->
                viewModel.deleteLocalGroupNode(groupId, node.id) { result ->
                    val (message, shouldClose) = when (result) {
                        MainViewModel.LocalGroupNodeDeleteResult.DELETED -> "节点已删除" to true
                        MainViewModel.LocalGroupNodeDeleteResult.NOT_FOUND -> "节点不存在" to false
                        MainViewModel.LocalGroupNodeDeleteResult.IN_USE ->
                            "当前正在使用该节点，无法删除" to false
                    }
                    onComplete(message, shouldClose)
                }
            }
        }

    SubscriptionGroupingScreen(
        nodes = nodes,
        selectedNodeId = selectedNodeId,
        isTesting = isTesting,
        isRefreshingAllSubscriptions = isRefreshingAllSubscriptions,
        nodeDisplaySettings = nodeDisplaySettings,
        protocolCoreSettings = protocolCoreSettings,
        groupManualSpeedTestScheme = groupManualSpeedTestScheme,
        groupManualSpeedTestEnabled = groupManualSpeedTestEnabled,
        onRunManualGroupTest = { stage ->
            viewModel.runManualGroupTest(nodes, stage)
        },
        onNodeSelected = onNodeSelected,
        onBack = onBack,
        showBackNavigation = showBackNavigation,
        bottomContentPadding = bottomContentPadding,
        screenTitle = screenTitle,
        onImportNodeUris = onImportNodeUris,
        onUpdateNode = onUpdateLocalNode,
        onDeleteNode = onDeleteLocalNode
    )
}
