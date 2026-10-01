package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.HomeNavigationStyle

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
    navigationStyle: HomeNavigationStyle = HomeNavigationStyle.CLASSIC,
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
        when (navigationStyle) {
            HomeNavigationStyle.CLASSIC -> HomeNavigationBar(
                selectedDestination = selectedDestination,
                onDestinationSelected = onDestinationSelected
            )
            HomeNavigationStyle.CAPSULE -> CapsuleHomeNavigationBar(
                selectedDestination = selectedDestination,
                onDestinationSelected = onDestinationSelected
            )
            HomeNavigationStyle.NATIVE -> NativeHomeNavigationBar(
                selectedDestination = selectedDestination,
                onDestinationSelected = onDestinationSelected
            )
        }
    }
}

@Composable
private fun CapsuleHomeNavigationBar(
    selectedDestination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
            tonalElevation = 10.dp,
            shadowElevation = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .height(64.dp)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CapsuleNavigationItem(
                    selected = selectedDestination == HomeDestination.HOME,
                    selectedIcon = Icons.Rounded.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    label = "首页",
                    onClick = { onDestinationSelected(HomeDestination.HOME) },
                    modifier = Modifier.weight(1f)
                )
                CapsuleNavigationItem(
                    selected = selectedDestination == HomeDestination.NODE_GROUPING,
                    selectedIcon = Icons.Rounded.Public,
                    unselectedIcon = Icons.Outlined.Public,
                    label = "分组",
                    onClick = { onDestinationSelected(HomeDestination.NODE_GROUPING) },
                    modifier = Modifier.weight(1f)
                )
                CapsuleNavigationItem(
                    selected = selectedDestination == HomeDestination.SUBSCRIPTION_GROUPING,
                    selectedIcon = Icons.Rounded.Subscriptions,
                    unselectedIcon = Icons.Outlined.Subscriptions,
                    label = "仓库",
                    onClick = { onDestinationSelected(HomeDestination.SUBSCRIPTION_GROUPING) },
                    modifier = Modifier.weight(1f)
                )
                CapsuleNavigationItem(
                    selected = selectedDestination == HomeDestination.NAVIGATION,
                    selectedIcon = Icons.Rounded.Explore,
                    unselectedIcon = Icons.Outlined.Explore,
                    label = "导航",
                    onClick = { onDestinationSelected(HomeDestination.NAVIGATION) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NativeHomeNavigationBar(
    selectedDestination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit
) {
    NavigationBar {
        NativeNavigationItem(
            selected = selectedDestination == HomeDestination.HOME,
            selectedIcon = Icons.Rounded.Home,
            unselectedIcon = Icons.Outlined.Home,
            label = "首页",
            onClick = { onDestinationSelected(HomeDestination.HOME) }
        )
        NativeNavigationItem(
            selected = selectedDestination == HomeDestination.NODE_GROUPING,
            selectedIcon = Icons.Rounded.Public,
            unselectedIcon = Icons.Outlined.Public,
            label = "分组",
            onClick = { onDestinationSelected(HomeDestination.NODE_GROUPING) }
        )
        NativeNavigationItem(
            selected = selectedDestination == HomeDestination.SUBSCRIPTION_GROUPING,
            selectedIcon = Icons.Rounded.Subscriptions,
            unselectedIcon = Icons.Outlined.Subscriptions,
            label = "仓库",
            onClick = { onDestinationSelected(HomeDestination.SUBSCRIPTION_GROUPING) }
        )
        NativeNavigationItem(
            selected = selectedDestination == HomeDestination.NAVIGATION,
            selectedIcon = Icons.Rounded.Explore,
            unselectedIcon = Icons.Outlined.Explore,
            label = "导航",
            onClick = { onDestinationSelected(HomeDestination.NAVIGATION) }
        )
    }
}

@Composable
private fun RowScope.CapsuleNavigationItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "capsule-navigation-container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "capsule-navigation-content"
    )
    val shape = RoundedCornerShape(28.dp)

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        shape = shape,
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                color = contentColor,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun RowScope.NativeNavigationItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val indicatorColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(indicatorColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (selected) selectedIcon else unselectedIcon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            color = contentColor,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
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
