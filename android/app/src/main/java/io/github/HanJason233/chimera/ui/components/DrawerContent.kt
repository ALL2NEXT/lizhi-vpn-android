package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.ui.dialogs.AboutDialog

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DrawerContent(
    onCheckUpdate: () -> Unit,
    onOpenOtherConfig: () -> Unit,
    onOpenRoutingAssets: () -> Unit = {},
    onOpenRegionLookupSettings: () -> Unit = {},
    onOpenSplitRoutingSettings: () -> Unit = {},
    onOpenInbound: () -> Unit = {},
    onOpenOutbound: () -> Unit = {},
    onOpenSpeedTestSchemes: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    onOpenBackendDialog: () -> Unit = {},
    websiteUrl: String = "",
    feedbackEmail: String = "",
    feedbackUrl: String = "",
    githubUrl: String = "",
    telegramUrl: String = "",
    appDisplayName: String = "Chimera",
    appIconResourceId: Int = R.mipmap.ic_launcher
) {
    var showAboutDialog by remember { mutableStateOf(false) }
    
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
                icon = Icons.Outlined.Tune,
                title = "设置",
                onClick = {
                    onOpenOtherConfig()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.FolderOpen,
                title = "资源文件",
                onClick = onOpenRoutingAssets
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Public,
                title = "地区组查询",
                onClick = onOpenRegionLookupSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Route,
                title = "分流设置",
                onClick = onOpenSplitRoutingSettings
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Login,
                title = "入站",
                onClick = onOpenInbound
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Logout,
                title = "出站",
                onClick = onOpenOutbound
            )

            DrawerMenuItem(
                icon = Icons.Outlined.FlashOn,
                title = "测试",
                onClick = onOpenSpeedTestSchemes
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Palette,
                title = "主题",
                onClick = onOpenSkin
            )

            if (AppConfig.BACKEND_URL.isNotBlank()) {
                DrawerMenuItem(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检查更新",
                    onClick = onCheckUpdate
                )
            }

            DrawerMenuItem(
                icon = Icons.Outlined.Info,
                title = "关于",
                onClick = { showAboutDialog = true }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
        }

    }
    
    // 关于弹窗
    if (showAboutDialog) {
        AboutDialog(
            githubUrl = githubUrl,
            websiteUrl = websiteUrl,
            feedbackEmail = feedbackEmail,
            feedbackUrl = feedbackUrl,
            telegramUrl = telegramUrl,
            appDisplayName = appDisplayName,
            appIconResourceId = appIconResourceId,
            onOpenBackendDialog = {
                showAboutDialog = false
                onOpenBackendDialog()
            },
            onDismiss = { showAboutDialog = false }
        )
    }

}

@Composable
private fun DrawerMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

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
        selected = isPressed,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        },
        shape = RoundedCornerShape(50),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onBackground
        ),
        interactionSource = interactionSource
    )
}
