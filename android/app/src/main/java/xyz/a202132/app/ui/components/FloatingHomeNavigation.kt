package xyz.a202132.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Explore
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class HomeDestination {
    HOME,
    COUNTRIES,
    SUBSCRIPTIONS,
    NAVIGATION
}

@Composable
fun FloatingHomeNavigationLayout(
    selectedDestination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        FloatingHomeNavigationBar(
            selectedDestination = selectedDestination,
            onDestinationSelected = onDestinationSelected,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun FloatingHomeNavigationBar(
    selectedDestination: HomeDestination,
    onDestinationSelected: (HomeDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 12.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 400.dp)
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
                FloatingNavigationItem(
                    selected = selectedDestination == HomeDestination.HOME,
                    selectedIcon = Icons.Rounded.Home,
                    unselectedIcon = Icons.Outlined.Home,
                    label = "主页",
                    onClick = { onDestinationSelected(HomeDestination.HOME) },
                    modifier = Modifier.weight(1f)
                )
                FloatingNavigationItem(
                    selected = selectedDestination == HomeDestination.COUNTRIES,
                    selectedIcon = Icons.Rounded.Public,
                    unselectedIcon = Icons.Outlined.Public,
                    label = "国家",
                    onClick = { onDestinationSelected(HomeDestination.COUNTRIES) },
                    modifier = Modifier.weight(1f)
                )
                FloatingNavigationItem(
                    selected = selectedDestination == HomeDestination.SUBSCRIPTIONS,
                    selectedIcon = Icons.Rounded.Subscriptions,
                    unselectedIcon = Icons.Outlined.Subscriptions,
                    label = "订阅",
                    onClick = { onDestinationSelected(HomeDestination.SUBSCRIPTIONS) },
                    modifier = Modifier.weight(1f)
                )
                FloatingNavigationItem(
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
private fun FloatingNavigationItem(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
        label = "floating-navigation-container"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "floating-navigation-content"
    )

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
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
                fontSize = 12.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}
