package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.ui.theme.MonetThemeOverlay
import io.github.HanJason233.chimera.R

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DrawerContent(
    onOpenOtherConfig: () -> Unit,
    onOpenDnsSettings: () -> Unit = {},
    onOpenProtocolCoreSettings: () -> Unit = {},
    onOpenRoutingAssets: () -> Unit = {},
    onOpenRegionLookupSettings: () -> Unit = {},
    onOpenSplitRoutingSettings: () -> Unit = {},
    onOpenInboundOutbound: () -> Unit = {},
    onOpenSpeedTestSettings: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    appDisplayName: String = "荔枝VPN"
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(bottom = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp)
        ) {
            // 菜单项

            Text(
                text = appDisplayName,
                modifier = Modifier.padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = 12.dp,
                    bottom = 12.dp
                ),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Settings,
                iconResource = R.drawable.gmail_drawer_settings,
                title = "设置",
                onClick = {
                    onOpenOtherConfig()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Dns,
                title = "DNS 设置",
                onClick = onOpenDnsSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Memory,
                title = "协议核心",
                onClick = onOpenProtocolCoreSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.FolderOpen,
                title = "资源文件",
                onClick = onOpenRoutingAssets
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Search,
                title = "地区查询",
                onClick = onOpenRegionLookupSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Route,
                title = "分流设置",
                onClick = onOpenSplitRoutingSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.SwapHoriz,
                title = "出入站",
                onClick = onOpenInboundOutbound
            )

            DrawerMenuItem(
                icon = Icons.Outlined.FlashOn,
                title = "测试",
                onClick = onOpenSpeedTestSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Palette,
                title = "主题",
                onClick = onOpenSkin
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

    }
}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    iconResource: Int? = null,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        // 抽屉菜单项用于页面导航，按压反馈由组件自带的涟漪效果提供。
        selected = false,
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .height(48.dp),
        icon = {
            if (iconResource != null) {
                Icon(
                    painter = painterResource(iconResource),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        shape = RoundedCornerShape(24.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MonetThemeOverlay.selectedControl,
            selectedIconColor = MonetThemeOverlay.onSelectedControl,
            selectedTextColor = MonetThemeOverlay.onSelectedControl,
            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onBackground
        )
    )
}
