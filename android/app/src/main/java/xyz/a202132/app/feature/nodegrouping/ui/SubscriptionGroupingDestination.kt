package xyz.a202132.app.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.viewmodel.MainViewModel

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
    screenSubtitle: String? = null,
    testNodes: List<Node>? = null
) {
    val viewModelNodes by viewModel.nodes.collectAsStateWithLifecycle()
    val resultSortMode by viewModel.nodeTestResultSortMode.collectAsStateWithLifecycle()
    val sourceNodes = nodesOverride ?: viewModelNodes
    val nodes = remember(sourceNodes, resultSortMode) {
        viewModel.sortNodesByTestResult(sourceNodes, resultSortMode)
    }
    val selectedNodeId by viewModel.selectedNodeId.collectAsStateWithLifecycle()
    val favoriteSourceNodeIds by
        viewModel.favoriteSourceNodeIds.collectAsStateWithLifecycle()
    val skipFavoriteRemovalConfirmation by
        viewModel.skipFavoriteRemovalConfirmation.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val isRefreshingAllSubscriptions by
        viewModel.isRefreshingAllSubscriptions.collectAsStateWithLifecycle()
    val nodeDisplaySettings by
        viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()
    val protocolCoreSettings by
        viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val testingLabel by viewModel.testingLabel.collectAsStateWithLifecycle()

    SubscriptionGroupingScreen(
        nodes = nodes,
        selectedNodeId = selectedNodeId,
        favoriteSourceNodeIds = favoriteSourceNodeIds,
        skipFavoriteRemovalConfirmation = skipFavoriteRemovalConfirmation,
        isTesting = isTesting,
        isRefreshingAllSubscriptions = isRefreshingAllSubscriptions,
        nodeDisplaySettings = nodeDisplaySettings,
        protocolCoreSettings = protocolCoreSettings,
        testingLabel = testingLabel,
        onNodeSelected = onNodeSelected,
        onToggleFavorite = viewModel::toggleFavoriteNode,
        onSkipFavoriteRemovalConfirmationForSession =
            viewModel::skipFavoriteRemovalConfirmationForSession,
        onTcpingTest = { viewModel.testAllNodes(testNodes ?: nodes) },
        onUrlTest = { viewModel.urlTestAllNodes(testNodes ?: nodes) },
        onRefreshAll = viewModel::refreshNodes,
        onBack = onBack,
        showBackNavigation = showBackNavigation,
        bottomContentPadding = bottomContentPadding,
        screenTitle = screenTitle,
        screenSubtitle = screenSubtitle
    )
}
