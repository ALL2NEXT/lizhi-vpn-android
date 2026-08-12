package xyz.a202132.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.a202132.app.AppConfig
import xyz.a202132.app.R
import xyz.a202132.app.data.model.AccountInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import xyz.a202132.app.ui.dialogs.AboutDialog

@Composable
fun DrawerContent(
    onCheckUpdate: () -> Unit,
    onOpenProxyScope: () -> Unit,
    onOpenOtherConfig: () -> Unit,
    onOpenFeatures: () -> Unit,
    onOpenTaskCenter: () -> Unit = {},
    onOpenAccountSettings: () -> Unit = {},
    onClose: () -> Unit,
    websiteUrl: String = "",
    feedbackEmail: String = "",
    feedbackUrl: String = "",
    githubUrl: String = "",
    accountInfo: AccountInfo? = null
) {
    val context = LocalContext.current
    var showAboutDialog by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(bottom = 24.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.drawer_wallpaper),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        )
        
        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 8.dp))
        
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp)
        ) {
            // 菜单项

            if (AppConfig.BACKEND_URL.isNotBlank()) {
                DrawerMenuItem(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检查更新",
                    onClick = {
                        onCheckUpdate()
                    }
                )
            }

             DrawerMenuItem(
                icon = Icons.Outlined.Tune,
                title = "其他配置",
                onClick = {
                    onOpenOtherConfig()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Apps,
                title = "代理作用域",
                onClick = {
                    onOpenProxyScope()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.Star,
                title = "特色功能",
                onClick = {
                    onOpenFeatures()
                }
            )

            DrawerMenuItem(
                icon = Icons.Outlined.CheckCircle,
                title = "任务中心",
                onClick = {
                    onOpenTaskCenter()
                }
            )

            if (websiteUrl.isNotBlank()) {
                DrawerMenuItem(
                    icon = Icons.Outlined.Language,
                    title = "官方网站",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl))
                        context.startActivity(intent)
                        onClose()
                    }
                )
            }
            
            // 问题反馈 - 支持邮箱复制 + 链接跳转
            val hasEmail = feedbackEmail.isNotBlank()
            val hasFeedbackUrl = feedbackUrl.isNotBlank()
            
            if (hasEmail || hasFeedbackUrl) {
                DrawerMenuItem(
                    icon = Icons.Outlined.Email,
                    title = "问题反馈",
                    onClick = {
                        if (hasEmail) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("email", feedbackEmail)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "邮箱已复制", Toast.LENGTH_SHORT).show()
                        }
                        if (hasFeedbackUrl) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(feedbackUrl))
                            context.startActivity(intent)
                            onClose()
                        }
                    }
                )
            }
            
            DrawerMenuItem(
                icon = Icons.Outlined.Info,
                title = "关于应用",
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
            onDismiss = { showAboutDialog = false }
        )
    }

}

@Composable
private fun UserAccountCard(
    accountInfo: AccountInfo?,
    onClick: () -> Unit
) {
    val isBanned = accountInfo?.level == 0
    val levelText = when {
        accountInfo == null -> "账户同步中"
        isBanned -> "Lv0 · 已封禁"
        else -> "Lv${accountInfo.level.coerceIn(1, 6)}"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(
                enabled = accountInfo != null,
                onClick = onClick
            ),
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
                    text = levelText,
                    fontSize = 12.sp,
                    color = if (isBanned) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            if (accountInfo != null) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "账户设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
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
