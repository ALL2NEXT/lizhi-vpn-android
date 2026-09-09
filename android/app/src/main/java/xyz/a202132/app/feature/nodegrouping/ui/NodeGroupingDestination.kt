package xyz.a202132.app.feature.nodegrouping.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.a202132.app.data.model.Node
import xyz.a202132.app.feature.nodegrouping.model.NodeGroupingMode
import xyz.a202132.app.viewmodel.MainViewModel

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
    when (groupingMode) {
        NodeGroupingMode.COUNTRY -> CountryGroupingDestination(
            viewModel = viewModel,
            onCountrySelected = onCountrySelected,
            onSwitchToSubscriptions = viewModel::selectSubscriptionGrouping,
            onBack = onBack,
            bottomContentPadding = bottomContentPadding
        )

        NodeGroupingMode.SUBSCRIPTION -> SubscriptionGroupingDestination(
            viewModel = viewModel,
            onNodeSelected = onNodeSelected,
            onImportFromText = onImportFromText,
            onScanQrCode = onScanQrCode,
            onSwitchToCountries = viewModel::selectCountryGrouping,
            onBack = onBack,
            showBackNavigation = false,
            bottomContentPadding = bottomContentPadding
        )
    }
}
