package xyz.a202132.app.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeGroupKey
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.viewmodel.MainViewModel

private sealed interface NodeGroupingDetail {
    data class Country(
        val countryCode: String,
        val countryName: String
    ) : NodeGroupingDetail

    data class Subscription(
        val sourceId: String,
        val sourceName: String
    ) : NodeGroupingDetail

    data object Favorites : NodeGroupingDetail

    data class UserGroup(
        val groupId: String,
        val groupName: String
    ) : NodeGroupingDetail
}

/**
 * 节点分组的统一页面入口。
 *
 * 导航层只需要打开该入口；地区与订阅页面的切换、状态绑定由模块内部维护。
 */
@Composable
fun NodeGroupingDestination(
    groupingMode: NodeGroupingMode,
    viewModel: MainViewModel,
    onCountrySelected: (String) -> Unit,
    onNodeSelected: (Node) -> Unit,
    onCreateSubscriptionGroup: (String, String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    scannedSubscriptionText: String?,
    onScannedSubscriptionTextConsumed: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 108.dp
) {
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val favoriteNodes by viewModel.favoriteNodes.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<NodeGroupingDetail?>(null) }

    val switchToCountries = {
        detail = null
        viewModel.selectCountryGrouping()
    }
    val switchToSubscriptions = {
        detail = null
        viewModel.selectSubscriptionGrouping()
    }

    when (val currentDetail = detail) {
        is NodeGroupingDetail.Country -> {
            val countryNodes = allSubscriptionNodes.filter {
                it.getCountryCode() == currentDetail.countryCode
            }
            SubscriptionGroupingDestination(
                viewModel = viewModel,
                nodesOverride = countryNodes,
                screenTitle = "线路信息",
                screenSubtitle = currentDetail.countryName,
                onNodeSelected = onNodeSelected,
                onBack = { detail = null },
                showBackNavigation = true,
                bottomContentPadding = bottomContentPadding
            )
        }

        is NodeGroupingDetail.Subscription -> {
            val subscriptionNodes = allSubscriptionNodes.filter {
                it.subscriptionSourceId == currentDetail.sourceId
            }
            SubscriptionGroupingDestination(
                viewModel = viewModel,
                nodesOverride = subscriptionNodes,
                screenTitle = "线路信息",
                screenSubtitle = currentDetail.sourceName,
                onNodeSelected = onNodeSelected,
                onBack = { detail = null },
                showBackNavigation = true,
                bottomContentPadding = bottomContentPadding
            )
        }

        NodeGroupingDetail.Favorites -> {
            SubscriptionGroupingDestination(
                viewModel = viewModel,
                nodesOverride = favoriteNodes,
                screenTitle = "收藏节点",
                screenSubtitle = "管理收藏的节点",
                onNodeSelected = onNodeSelected,
                onBack = { detail = null },
                showBackNavigation = true,
                bottomContentPadding = bottomContentPadding
            )
        }

        is NodeGroupingDetail.UserGroup -> {
            val groupNodes by remember(currentDetail.groupId) {
                viewModel.observeNodeGroupNodes(currentDetail.groupId)
            }.collectAsStateWithLifecycle(initialValue = emptyList())
            SubscriptionGroupingDestination(
                viewModel = viewModel,
                nodesOverride = groupNodes,
                screenTitle = "线路信息",
                screenSubtitle = currentDetail.groupName,
                importTargetGroupId = currentDetail.groupId,
                onNodeSelected = onNodeSelected,
                onBack = { detail = null },
                showBackNavigation = true,
                bottomContentPadding = bottomContentPadding
            )
        }

        null -> when (groupingMode) {
            NodeGroupingMode.COUNTRY -> CountryGroupingDestination(
                viewModel = viewModel,
                onCountrySelected = onCountrySelected,
                onViewCountryDetails = { country ->
                    detail = NodeGroupingDetail.Country(country.code, country.name)
                },
                onSwitchToSubscriptions = switchToSubscriptions,
                onBack = onBack,
                bottomContentPadding = bottomContentPadding
            )

            NodeGroupingMode.SUBSCRIPTION -> SubscriptionGroupSelectionDestination(
                viewModel = viewModel,
                // 卡片点击只更新主页筛选范围，右侧箭头才进入该订阅的线路详情。
                onSubscriptionSelected = { group ->
                    viewModel.setSubscriptionSource(group.sourceId)
                },
                onViewSubscriptionDetails = { group ->
                    detail = NodeGroupingDetail.Subscription(group.sourceId, group.name)
                },
                onUserGroupSelected = { group ->
                    viewModel.selectNodeGroup(NodeGroupKey.Imported(group.id.removePrefix("imported:")))
                },
                onViewUserGroupDetails = { group ->
                    detail = NodeGroupingDetail.UserGroup(
                        groupId = group.id.removePrefix("imported:"),
                        groupName = group.name
                    )
                },
                // 收藏卡片同样区分“选中”和“查看详情”两种操作。
                onFavoritesSelected = {
                    viewModel.selectFavoritesGroup()
                },
                onViewFavoritesDetails = {
                    detail = NodeGroupingDetail.Favorites
                },
                onCreateSubscriptionGroup = onCreateSubscriptionGroup,
                onUpdateSubscriptionGroup = onUpdateSubscriptionGroup,
                onDeleteSubscriptionGroup = onDeleteSubscriptionGroup,
                onScanQrCode = onScanQrCode,
                scannedSubscriptionText = scannedSubscriptionText,
                onScannedSubscriptionTextConsumed = onScannedSubscriptionTextConsumed,
                onSwitchToCountries = switchToCountries,
                onBack = onBack,
                bottomContentPadding = bottomContentPadding
            )
        }
    }
}
