package xyz.a202132.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    // 导航背景与主页一致，顶部用细线区分；底部系统手势区域单独保留。
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                HomeNavigationItem(
                    selected = selectedDestination == HomeDestination.HOME,
                    selectedIcon = Icons.Rounded.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    label = "首页",
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
            Spacer(
                modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
            )
        }
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
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                color = contentColor,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
