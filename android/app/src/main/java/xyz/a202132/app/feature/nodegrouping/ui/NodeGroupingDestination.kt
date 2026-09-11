package xyz.a202132.app.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.data.model.NodeListCategory
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
    onImportFromText: (String) -> Unit,
    onScanQrCode: () -> Unit,
    onBack: () -> Unit,
    bottomContentPadding: Dp = 108.dp
) {
    val allSubscriptionNodes by viewModel.allSubscriptionNodes.collectAsStateWithLifecycle()
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
                categoryOverride = NodeListCategory.PRIMARY,
                screenTitle = "线路信息",
                screenSubtitle = currentDetail.countryName,
                showCategorySwitch = false,
                testNodes = countryNodes,
                onNodeSelected = onNodeSelected,
                onImportFromText = onImportFromText,
                onScanQrCode = onScanQrCode,
                onSwitchToCountries = null,
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
                categoryOverride = NodeListCategory.PRIMARY,
                screenTitle = "线路信息",
                screenSubtitle = currentDetail.sourceName,
                showCategorySwitch = false,
                testNodes = subscriptionNodes,
                onNodeSelected = onNodeSelected,
                onImportFromText = onImportFromText,
                onScanQrCode = onScanQrCode,
                onSwitchToCountries = switchToCountries,
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
                onSubscriptionSelected = { group ->
                    detail = NodeGroupingDetail.Subscription(group.sourceId, group.name)
                    viewModel.setSubscriptionSource(group.sourceId)
                },
                onSwitchToCountries = switchToCountries,
                onBack = onBack,
                bottomContentPadding = bottomContentPadding
            )
        }
    }
}
