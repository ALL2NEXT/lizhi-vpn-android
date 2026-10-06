package io.github.HanJason233.chimera.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.HanJason233.chimera.data.model.Node
import io.github.HanJason233.chimera.data.model.NodeGroupKey
import io.github.HanJason233.chimera.feature.nodegrouping.model.NodeGroupingMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.viewmodel.MainViewModel

data class NodeGroupingDetailRequest(
    val groupKey: NodeGroupKey,
    val groupName: String
)

private sealed interface NodeGroupingDetail {
    data class Country(
        val countryCode: String,
        val countryName: String
    ) : NodeGroupingDetail

    data class Subscription(
        val sourceId: String,
        val sourceName: String
    ) : NodeGroupingDetail

    data class UserGroup(
        val groupId: String,
        val groupName: String
    ) : NodeGroupingDetail
}

/**
 * 节点分组的统一页面入口，根据底部导航显示地区组或仓库源。
 * 分组详情的页面状态由本模块维护。
 */
@Composable
fun NodeGroupingDestination(
    groupingMode: NodeGroupingMode,
    viewModel: MainViewModel,
    onCountrySelected: (String) -> Unit,
    onNodeSelected: (Node) -> Unit,
    onCreateSubscriptionGroup: (String?, (Boolean) -> Unit) -> Unit,
    onUpdateSubscriptionGroup: (String, String, String?, (Boolean) -> Unit) -> Unit,
    onDeleteSubscriptionGroup: (String, (Boolean) -> Unit) -> Unit,
    onScanQrCode: () -> Unit,
    scannedSubscriptionText: String?,
    onScannedSubscriptionTextConsumed: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 16.dp,
    detailRequest: NodeGroupingDetailRequest? = null,
    onDetailRequestConsumed: (NodeGroupingDetailRequest) -> Unit = {}
) {
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
    val countryGroupingNodes by viewModel.countryGroupingNodes.collectAsStateWithLifecycle()
    val warehouseGroupingCountryCodes by viewModel.warehouseGroupingCountryCodes
        .collectAsStateWithLifecycle()
    var detail by remember(groupingMode) { mutableStateOf<NodeGroupingDetail?>(null) }
    LaunchedEffect(detailRequest, groupingMode) {
        val request = detailRequest ?: return@LaunchedEffect
        detail = when (val groupKey = request.groupKey) {
            is NodeGroupKey.Country -> NodeGroupingDetail.Country(
                countryCode = groupKey.countryCode,
                countryName = request.groupName
            )
            is NodeGroupKey.Subscription -> NodeGroupingDetail.Subscription(
                sourceId = groupKey.sourceId,
                sourceName = request.groupName
            )
            is NodeGroupKey.Imported -> NodeGroupingDetail.UserGroup(
                groupId = groupKey.groupId,
                groupName = request.groupName
            )
        }
        onDetailRequestConsumed(request)
    }

    // 线路详情标题显示当前分组名称，测速阶段状态不再替换标题栏副标题。
    when (val currentDetail = detail) {
        is NodeGroupingDetail.Country -> {
            val countryNodes = countryGroupingNodes.filter {
                it.getCountryCode() == currentDetail.countryCode
            }
            SubscriptionGroupingDestination(
                viewModel = viewModel,
                nodesOverride = countryNodes,
                screenTitle = currentDetail.countryName,
                onNodeSelected = onNodeSelected,
                onBack = { detail = null },
                showBackNavigation = true,
                bottomContentPadding = bottomContentPadding
            )
        }

        is NodeGroupingDetail.Subscription -> {
            val subscriptionNodes = allSubscriptionNodes
                .filter { it.subscriptionSourceId == currentDetail.sourceId }
                .filter { node ->
                    warehouseGroupingCountryCodes == null ||
                        warehouseGroupingCountryCodes?.contains(node.getCountryCode()) == true
                }
            SubscriptionGroupingDestination(
                viewModel = viewModel,
                nodesOverride = subscriptionNodes,
                screenTitle = currentDetail.sourceName,
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
                screenTitle = currentDetail.groupName,
                importTargetGroupId = currentDetail.groupId,
                onNodeSelected = onNodeSelected,
                onBack = { detail = null },
                showBackNavigation = true,
                bottomContentPadding = bottomContentPadding
            )
        }

        null -> when (groupingMode) {
            NodeGroupingMode.REGION -> CountryGroupingDestination(
                viewModel = viewModel,
                onCountrySelected = onCountrySelected,
                onViewCountryDetails = { country ->
                    detail = NodeGroupingDetail.Country(country.code, country.name)
                },
                onBack = onBack,
                bottomContentPadding = bottomContentPadding
            )

            NodeGroupingMode.WAREHOUSE -> SubscriptionGroupSelectionDestination(
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
                onCreateSubscriptionGroup = onCreateSubscriptionGroup,
                onUpdateSubscriptionGroup = onUpdateSubscriptionGroup,
                onDeleteSubscriptionGroup = onDeleteSubscriptionGroup,
                onScanQrCode = onScanQrCode,
                scannedSubscriptionText = scannedSubscriptionText,
                onScannedSubscriptionTextConsumed = onScannedSubscriptionTextConsumed,
                onBack = onBack,
                bottomContentPadding = bottomContentPadding
            )
        }
    }
}
