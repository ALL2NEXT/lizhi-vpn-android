package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeSource
import io.github.HanJason233.chimera.data.model.ManualGroupTestStage
import io.github.HanJason233.chimera.network.nodetest.linktype.LinkTypeTestState
import io.github.HanJason233.chimera.network.nodetest.unlock.UnlockTestState
import io.github.HanJason233.chimera.viewmodel.HomeTestResultCodec
import io.github.HanJason233.chimera.viewmodel.MainViewModel

/** 仓库源详情页面的状态绑定入口。 */
@Composable
internal fun SubscriptionGroupingDestination(
    viewModel: MainViewModel,
    onNodeSelected: (Node) -> Unit,
    onBack: () -> Unit,
    showBackNavigation: Boolean = true,
    bottomContentPadding: Dp = 16.dp,
    nodesOverride: List<Node>? = null,
    screenTitle: String = "仓库源",
    importTargetGroupId: String? = null
) {
    val viewModelNodes by viewModel.nodes.collectAsStateWithLifecycle()
    val resultSortMode by viewModel.nodeTestResultSortMode.collectAsStateWithLifecycle()
    val storedNodeTestResults by viewModel.allNodeTestResults.collectAsStateWithLifecycle()
    val sourceNodes = nodesOverride ?: viewModelNodes
    val nodes = remember(sourceNodes, resultSortMode) {
        viewModel.sortNodesByTestResult(sourceNodes, resultSortMode)
    }
    val nodeTestResults = remember(storedNodeTestResults) {
        storedNodeTestResults.mapValues { (_, stored) ->
            val linkTypeResult = stored.linkTypeResultJson
                .takeIf(String::isNotBlank)
                ?.let(HomeTestResultCodec::decodeLinkType)
            val linkTypeState = linkTypeResult?.state?.takeUnless {
                it == LinkTypeTestState.IDLE || it == LinkTypeTestState.TESTING
            }
            val unlockItems = stored.unlockTestResultJson
                .takeIf(String::isNotBlank)
                ?.let(HomeTestResultCodec::decodeUnlockTests)
                ?.items
                .orEmpty()
            val unlockedCount = unlockItems.count { it.state == UnlockTestState.UNLOCKED }
            val unlockTone = when {
                unlockItems.isEmpty() -> NodeTestResultTone.NEUTRAL
                unlockItems.all { it.state == UnlockTestState.UNLOCKED } ->
                    NodeTestResultTone.POSITIVE
                unlockItems.any {
                    it.state == UnlockTestState.UNLOCKED ||
                        it.state == UnlockTestState.PARTIAL ||
                        it.state == UnlockTestState.WARNING
                } -> NodeTestResultTone.WARNING
                else -> NodeTestResultTone.ERROR
            }
            NodeListTestResultSummary(
                tcpingLatency = stored.tcpingLatency,
                urlTestLatency = stored.urlTestLatency,
                downloadMbps = stored.downloadMbps,
                linkType = when (linkTypeState) {
                    LinkTypeTestState.ERROR, LinkTypeTestState.NOT_CONNECTED -> "×"
                    else -> linkTypeState?.title
                },
                linkTypeTone = when (linkTypeState) {
                    LinkTypeTestState.ERROR, LinkTypeTestState.NOT_CONNECTED ->
                        NodeTestResultTone.ERROR
                    else -> NodeTestResultTone.NEUTRAL
                },
                unlockRatio = unlockItems.takeIf { it.isNotEmpty() }
                    ?.let { "$unlockedCount/${it.size}" },
                unlockTone = unlockTone
            )
        }
    }
    val selectedNodeId by viewModel.selectedNodeId.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val speedTestProgress by viewModel.speedTestProgress.collectAsStateWithLifecycle()
    val skinSettings by viewModel.skinSettings.collectAsStateWithLifecycle()
    val nodeDisplaySettings = skinSettings.nodeDisplaySettings
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
    val onDeleteNode: (Node, (String, Boolean) -> Unit) -> Unit = { node, onComplete ->
        when (node.source) {
            NodeSource.LOCAL_GROUP -> {
                val groupId = node.subscriptionSourceId
                    ?.takeIf(String::isNotBlank)
                    ?: importTargetGroupId
                if (groupId == null) {
                    onComplete("节点来源不存在", false)
                } else {
                    viewModel.deleteLocalGroupNode(groupId, node.id) { result ->
                        val (message, success) = when (result) {
                            MainViewModel.LocalGroupNodeDeleteResult.DELETED ->
                                "节点已删除" to true
                            MainViewModel.LocalGroupNodeDeleteResult.NOT_FOUND ->
                                "节点不存在" to false
                            MainViewModel.LocalGroupNodeDeleteResult.IN_USE ->
                                "当前正在使用该节点，无法删除" to false
                        }
                        onComplete(message, success)
                    }
                }
            }
            NodeSource.SUBSCRIPTION -> {
                viewModel.deleteSubscriptionNode(node) { result ->
                    val (message, success) = when (result) {
                        MainViewModel.SubscriptionNodeDeleteResult.DELETED ->
                            "节点已从官方源移除" to true
                        MainViewModel.SubscriptionNodeDeleteResult.NOT_FOUND ->
                            "节点不存在或已更新" to false
                        MainViewModel.SubscriptionNodeDeleteResult.IN_USE ->
                            "当前正在使用该节点，无法删除" to false
                    }
                    onComplete(message, success)
                }
            }
        }
    }

    SubscriptionGroupingScreen(
        nodes = nodes,
        selectedNodeId = selectedNodeId,
        isTesting = isTesting,
        testProgress = speedTestProgress,
        nodeDisplaySettings = nodeDisplaySettings,
        protocolCoreSettings = protocolCoreSettings,
        nodeTestResults = nodeTestResults,
        onRunManualGroupTest = { stage ->
            viewModel.runManualGroupTest(sourceNodes, stage)
        },
        onClearTestResults = { groupNodes, onComplete ->
            viewModel.clearNodeTestResults(groupNodes, onComplete)
        },
        onNodeSelected = onNodeSelected,
        onBack = onBack,
        showBackNavigation = showBackNavigation,
        bottomContentPadding = bottomContentPadding,
        screenTitle = screenTitle,
        onImportNodeUris = onImportNodeUris,
        onUpdateNode = onUpdateLocalNode,
        onDeleteNode = onDeleteNode
    )
}
