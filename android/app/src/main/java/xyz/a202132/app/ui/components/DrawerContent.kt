package xyz.a202132.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.a202132.app.AppConfig
import xyz.a202132.app.R
import xyz.a202132.app.data.model.AccountInfo
import xyz.a202132.app.ui.dialogs.AboutDialog

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawerContent(
    onCheckUpdate: () -> Unit,
    onOpenOtherConfig: () -> Unit,
    onOpenInbound: () -> Unit = {},
    onOpenOutbound: () -> Unit = {},
    onOpenOutboundAfter: () -> Unit = {},
    onOpenCoreRouting: () -> Unit = {},
    onOpenAccountSettings: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    onOpenBackendDialog: () -> Unit = {},
    websiteUrl: String = "",
    feedbackEmail: String = "",
    feedbackUrl: String = "",
    githubUrl: String = "",
    telegramUrl: String = "",
    accountInfo: AccountInfo? = null,
    customImagePath: String? = null
) {
    var showAboutDialog by remember { mutableStateOf(false) }
    val customImage by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = customImagePath
    ) {
        value = withContext(Dispatchers.IO) {
            customImagePath
                ?.takeIf { it.isNotBlank() }
                ?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            // 全面屏模式下避开状态栏，避免顶部自定义图片被系统状态栏遮挡。
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(bottom = 24.dp)
    ) {
        val drawerImage = customImage
        if (drawerImage != null) {
            Image(
                bitmap = drawerImage,
                contentDescription = "自定义样式图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.drawer_wallpaper),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
            )
        }
        
        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 8.dp))
        
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
                onClick = onOpenOutboundAfter
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Palette,
                title = "样式",
                onClick = onOpenSkin
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Info,
                title = "关于",
                onClick = { showAboutDialog = true }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
        }

        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))

        UserAccountCard(
            accountInfo = accountInfo,
            onClick = onOpenAccountSettings
        )
        
    }
    
    // 关于弹窗
    if (showAboutDialog) {
        AboutDialog(
            githubUrl = githubUrl,
            websiteUrl = websiteUrl,
            feedbackEmail = feedbackEmail,
            feedbackUrl = feedbackUrl,
            telegramUrl = telegramUrl,
            showUpdateAction = AppConfig.BACKEND_URL.isNotBlank(),
            onCheckUpdate = {
                showAboutDialog = false
                onCheckUpdate()
            },
            onOpenBackendDialog = {
                showAboutDialog = false
                onOpenBackendDialog()
            },
            onDismiss = { showAboutDialog = false }
        )
    }

}

@Composable
private fun UserAccountCard(
    accountInfo: AccountInfo?,
    onClick: () -> Unit
) {
    val isBanned = accountInfo?.accountType == "banned" || accountInfo?.deviceBlocked == true
    val accountTypeText = when {
        accountInfo == null -> "账户同步中"
        isBanned -> "被封禁用户"
        accountInfo.accountType == "formal" -> "正式用户"
        else -> "游客"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isBanned) Icons.Outlined.Block else Icons.Outlined.AccountCircle,
                contentDescription = null,
                tint = if (isBanned) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = accountInfo?.nickname ?: "正在同步用户信息…",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = accountTypeText,
                    fontSize = 12.sp,
                    color = if (isBanned) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "账户设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
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
