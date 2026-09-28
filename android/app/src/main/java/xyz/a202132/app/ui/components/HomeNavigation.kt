package xyz.a202132.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

enum class HomeDestination {
    HOME,
    // 保留原枚举值，兼容 Activity 保存的旧导航状态；该入口现在固定显示地区分组。
    NODE_GROUPING,
    SUBSCRIPTION_GROUPING,
    NAVIGATION
}

@Composable
fun HomeNavigationLayout(
    selectedDestination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit,
    content: @Composable () -> Unit
) {
    // 内容区与底部导航分开排布，避免导航栏覆盖页面内容。
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            content()
        }
        HomeNavigationBar(
            selectedDestination = selectedDestination,
            onDestinationSelected = onDestinationSelected
        )
    }
}

@Composable
fun HomeNavigationBar(
    selectedDestination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(modifier = modifier) {
        HomeNavigationItem(
            selected = selectedDestination == HomeDestination.HOME,
            selectedIcon = Icons.Rounded.Home,
            unselectedIcon = Icons.Outlined.Home,
            label = "主页",
            onClick = { onDestinationSelected(HomeDestination.HOME) }
        )
        HomeNavigationItem(
            selected = selectedDestination == HomeDestination.NODE_GROUPING,
            selectedIcon = Icons.Rounded.Public,
            unselectedIcon = Icons.Outlined.Public,
            label = "分组",
            onClick = { onDestinationSelected(HomeDestination.NODE_GROUPING) }
        )
        HomeNavigationItem(
            selected = selectedDestination == HomeDestination.SUBSCRIPTION_GROUPING,
            selectedIcon = Icons.Rounded.Subscriptions,
            unselectedIcon = Icons.Outlined.Subscriptions,
            label = "仓库",
            onClick = { onDestinationSelected(HomeDestination.SUBSCRIPTION_GROUPING) }
        )
        HomeNavigationItem(
            selected = selectedDestination == HomeDestination.NAVIGATION,
            selectedIcon = Icons.Rounded.Explore,
            unselectedIcon = Icons.Outlined.Explore,
            label = "导航",
            onClick = { onDestinationSelected(HomeDestination.NAVIGATION) }
        )
    }
}

@Composable
private fun RowScope.HomeNavigationItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label
            )
        },
        label = { Text(label) },
        alwaysShowLabel = true
    )
}
