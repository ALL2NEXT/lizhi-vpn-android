package xyz.a202132.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeListCategory
import xyz.a202132.app.ui.components.NodeListScreen
import xyz.a202132.app.viewmodel.MainViewModel

/** 滑动路由和底部导航“订阅”页共用的节点列表入口，避免重复维护状态绑定。 */
@Composable
fun NodeListDestination(
    viewModel: MainViewModel,
    onNodeSelected: (Node) -> Unit,
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onSwitchToCountries: (() -> Unit)? = null,
    onBack: () -> Unit,
    showBackNavigation: Boolean = true,
    bottomContentPadding: Dp = 16.dp
) {
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val selectedNodeId by viewModel.selectedNodeId.collectAsStateWithLifecycle()
    val nodeListCategory by viewModel.nodeListCategory.collectAsStateWithLifecycle()
    val subscriptionGroups by viewModel.subscriptionGroups.collectAsStateWithLifecycle()
    val selectedSubscriptionSourceId by
        viewModel.selectedSubscriptionSourceId.collectAsStateWithLifecycle()
    val favoriteSourceNodeIds by
        viewModel.favoriteSourceNodeIds.collectAsStateWithLifecycle()
    val skipFavoriteRemovalConfirmation by
        viewModel.skipFavoriteRemovalConfirmation.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshingAllSubscriptions by
        viewModel.isRefreshingAllSubscriptions.collectAsStateWithLifecycle()
    val hideFavoriteNodeGroup by
        viewModel.hideFavoriteNodeGroup.collectAsStateWithLifecycle()
    val nodeDisplaySettings by
        viewModel.nodeDisplaySettings.collectAsStateWithLifecycle()
    val protocolCoreSettings by
        viewModel.protocolCoreSettings.collectAsStateWithLifecycle()
    val subscriptionSourceUpdatedAt by
        viewModel.subscriptionSourceUpdatedAt.collectAsStateWithLifecycle()
    val testingLabel by viewModel.testingLabel.collectAsStateWithLifecycle()

    NodeListScreen(
        nodes = nodes,
        selectedNodeId = selectedNodeId,
        category = nodeListCategory,
        subscriptionGroups = subscriptionGroups,
        selectedSubscriptionSourceId = selectedSubscriptionSourceId,
        favoriteSourceNodeIds = favoriteSourceNodeIds,
        skipFavoriteRemovalConfirmation = skipFavoriteRemovalConfirmation,
        isTesting = isTesting,
        isRefreshing = isRefreshing,
        isRefreshingAllSubscriptions = isRefreshingAllSubscriptions,
        hideFavoriteNodeGroup = hideFavoriteNodeGroup,
        nodeDisplaySettings = nodeDisplaySettings,
        protocolCoreSettings = protocolCoreSettings,
        subscriptionSourceUpdatedAt = subscriptionSourceUpdatedAt,
        testingLabel = testingLabel,
        onNodeSelected = onNodeSelected,
        onCategoryChange = viewModel::setNodeListCategory,
        onSubscriptionSourceChange = viewModel::setSubscriptionSource,
        onToggleFavorite = viewModel::toggleFavoriteNode,
        onSkipFavoriteRemovalConfirmationForSession =
            viewModel::skipFavoriteRemovalConfirmationForSession,
        onImportFromText = onImportFromText,
        onScanQrCode = onScanQrCode,
        onTcpingTest = viewModel::testAllNodes,
        onUrlTest = viewModel::urlTestAllNodes,
        onRefresh = {
            if (nodeListCategory == NodeListCategory.PRIMARY) {
                selectedSubscriptionSourceId?.let(viewModel::refreshNodes)
            }
        },
        onRefreshAll = viewModel::refreshNodes,
        onSwitchToCountries = onSwitchToCountries,
        onBack = onBack,
        showBackNavigation = showBackNavigation,
        bottomContentPadding = bottomContentPadding
    )
}
