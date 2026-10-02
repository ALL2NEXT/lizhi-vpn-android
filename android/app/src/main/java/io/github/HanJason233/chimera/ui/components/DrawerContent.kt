package io.github.HanJason233.chimera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.AppConfig
import io.github.HanJason233.chimera.R
import io.github.HanJason233.chimera.data.model.AccountInfo
import io.github.HanJason233.chimera.data.model.AccountTrafficPeriod
import io.github.HanJason233.chimera.ui.dialogs.AboutDialog
import io.github.HanJason233.chimera.util.TrafficUsageFormatter

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
    onOpenAccountSettings: () -> Unit = {},
    onOpenSkin: () -> Unit = {},
    onOpenBackendDialog: () -> Unit = {},
    websiteUrl: String = "",
    feedbackEmail: String = "",
    feedbackUrl: String = "",
    githubUrl: String = "",
    telegramUrl: String = "",
    accountInfo: AccountInfo? = null,
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
        UserAccountSummary(
            accountInfo = accountInfo,
            onClick = onOpenAccountSettings
        )

        Divider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(vertical = 8.dp))
        
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
private fun UserAccountSummary(
    accountInfo: AccountInfo?,
    onClick: () -> Unit
) {
    val isBanned = accountInfo?.accountType == "banned" || accountInfo?.deviceBlocked == true
    val nickname = accountInfo?.nickname?.trim()?.ifBlank { null } ?: "用户"
    val accountTypeText = when {
        accountInfo == null -> "账户同步中"
        isBanned -> "被封禁用户"
        accountInfo.accountType == "formal" -> "正式用户"
        else -> "游客"
    }
    val accountTypeColor = if (isBanned) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary
    val todayTraffic = when {
        accountInfo == null -> "同步中…"
        accountInfo.traffic == null -> "暂无数据"
        else -> trafficTotalText(accountInfo.traffic.today)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (accountInfo == null) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(25.dp)
                    )
                } else {
                    Text(
                        text = nickname.take(1),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (accountInfo == null) "正在同步用户信息…" else nickname,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = accountTypeText,
                    fontSize = 12.sp,
                    color = accountTypeColor
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "账户设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 58.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "今日流量",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = todayTraffic,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun trafficTotalText(period: AccountTrafficPeriod): String {
    val upload = period.uploadBytes.coerceAtLeast(0L)
    val download = period.downloadBytes.coerceAtLeast(0L)
    val calculatedTotal = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    val total = maxOf(period.totalBytes.coerceAtLeast(0L), calculatedTotal)
    return TrafficUsageFormatter.formatBytes(total)
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
