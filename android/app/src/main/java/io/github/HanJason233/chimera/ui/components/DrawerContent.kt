package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.ui.dialogs.AboutDialog

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawerContent(
    onCheckUpdate: () -> Unit,
    onOpenOtherConfig: () -> Unit,
    onOpenPerAppInbound: () -> Unit = {},
    onOpenInbound: () -> Unit = {},
    onOpenOutbound: () -> Unit = {},
    onOpenSpeedTestSchemes: () -> Unit = {},
    onOpenCoreRouting: () -> Unit = {},
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

             DrawerMenuItem(
                icon = Icons.Outlined.Tune,
                title = "设置",
                onClick = {
                    onOpenOtherConfig()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Android,
                title = stringResource(R.string.per_app_inbound_settings),
                onClick = onOpenPerAppInbound
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Login,
                title = "入站",
                onClick = onOpenInbound
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Route,
                title = "路由",
                onClick = onOpenCoreRouting
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
