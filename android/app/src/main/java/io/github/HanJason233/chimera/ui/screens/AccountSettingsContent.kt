package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.HanJason233.chimera.data.model.AccountInfo
import io.github.HanJason233.chimera.data.model.AccountTrafficInfo
import io.github.HanJason233.chimera.data.model.AccountTrafficPeriod
import io.github.HanJason233.chimera.util.TrafficUsageFormatter

@Composable
internal fun AccountSettingsOverviewContent(
    account: AccountInfo,
    isSwitchingAccount: Boolean,
    isDeletingAccount: Boolean,
    canStartDeleteAccount: Boolean,
    onOpenAccountInfo: () -> Unit,
    onOpenVip: () -> Unit,
    onOpenTraffic: () -> Unit,
    onOpenClientInfo: () -> Unit,
    onAbout: () -> Unit,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onSwitchAccount: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsGroupLabel(title = "账户", first = true)
        SettingsLinkRow(
            icon = Icons.Outlined.Person,
            title = accountDisplayName(account),
            subtitle = if (account.email.isBlank()) {
                accountIdentityText(account)
            } else {
                "${accountIdentityText(account)} · ${account.email}"
            },
            onClick = onOpenAccountInfo
        )

        SettingsGroupLabel(title = "会员与流量")
        SettingsLinkRow(
            icon = Icons.Outlined.WorkspacePremium,
            title = "VIP 会员",
            subtitle = vipSummary(account),
            iconTint = MaterialTheme.colorScheme.tertiary,
            onClick = onOpenVip
        )
        SettingsLinkRow(
            icon = Icons.Outlined.BarChart,
            title = "流量信息",
            subtitle = trafficSummary(account.traffic),
            onClick = onOpenTraffic
        )

        SettingsGroupLabel(title = "设备")
        SettingsLinkRow(
            icon = Icons.Outlined.PhoneAndroid,
            title = "客户端信息",
            subtitle = "${account.brand.displayOrUnknown()} · Android ${account.androidVersion.displayOrUnknown()}",
            onClick = onOpenClientInfo
        )

        SettingsGroupLabel(title = "帮助与支持")
        SettingsLinkRow(
            icon = Icons.Outlined.Info,
            title = "关于",
            subtitle = "应用版本、官网与反馈方式",
            onClick = onAbout
        )
        SettingsOperationRow(
            icon = Icons.Filled.Refresh,
            title = if (isCheckingUpdate) "正在检查更新…" else "检查更新",
            subtitle = "检查并打开最新版本下载链接",
            enabled = !isCheckingUpdate,
            loading = isCheckingUpdate,
            onClick = onCheckUpdate
        )

        SettingsGroupLabel(title = "账户操作")
        SettingsOperationRow(
            icon = Icons.Outlined.Sync,
            title = if (isSwitchingAccount) "正在切换账户…" else "切换账户",
            subtitle = "使用其他已绑定账户登录",
            enabled = !isSwitchingAccount && !isDeletingAccount,
            loading = isSwitchingAccount,
            onClick = onSwitchAccount
        )
        SettingsOperationRow(
            icon = Icons.Outlined.Delete,
            title = if (isDeletingAccount) "正在注销账户…" else "注销账户并清除数据后退出",
            subtitle = "此操作不可恢复",
            enabled = canStartDeleteAccount,
            loading = isDeletingAccount,
            danger = true,
            onClick = onDeleteAccount
        )
    }
}

@Composable
internal fun OfflineAccountSettingsOverviewContent(
    onSwitchToOnline: () -> Unit,
    onAbout: () -> Unit,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsGroupLabel(title = "账户", first = true)
        SettingsSummaryRow(
            icon = Icons.Outlined.Person,
            title = "离线用户",
            subtitle = "仅使用本地功能"
        )

        SettingsGroupLabel(title = "帮助与支持")
        SettingsLinkRow(
            icon = Icons.Outlined.Info,
            title = "关于",
            subtitle = "应用版本、官网与反馈方式",
            onClick = onAbout
        )
        SettingsOperationRow(
            icon = Icons.Filled.Refresh,
            title = if (isCheckingUpdate) "正在检查更新…" else "检查更新",
            subtitle = "检查并打开最新版本下载链接",
            enabled = !isCheckingUpdate,
            loading = isCheckingUpdate,
            onClick = onCheckUpdate
        )

        SettingsGroupLabel(title = "账户操作")
        SettingsOperationRow(
            icon = Icons.Outlined.Sync,
            title = "切换到在线登录",
            subtitle = "登录账户以同步账户和流量信息",
            enabled = true,
            loading = false,
            onClick = onSwitchToOnline
        )
    }
}

@Composable
internal fun AccountSettingsLoadingOverviewContent() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp
        )
        Column(
            modifier = Modifier.padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "账户信息同步中…",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "正在读取账户状态",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
internal fun AccountInfoDetailContent(
    account: AccountInfo,
    registrationEnabled: Boolean,
    canOpenBindDialog: Boolean,
    isUpdatingNickname: Boolean,
    onEditNickname: () -> Unit,
    onRegisterAccount: () -> Unit
) {
    AccountSettingsCard {
        AccountDetailRow(
            label = "昵称",
            value = account.nickname.displayOrUnknown(),
            trailing = {
                IconButton(
                    onClick = onEditNickname,
                    enabled = account.accountType != "banned" && !isUpdatingNickname
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "修改昵称",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
        SettingsDivider()
        AccountDetailRow(label = "账户身份", value = accountIdentityText(account))
        SettingsDivider()
        AccountDetailRow(label = "UID", value = account.uid)
        SettingsDivider()
        AccountDetailRow(
            label = "邮箱",
            value = account.email.displayOrUnknown(),
            trailing = if (account.email.isBlank()) {
                {
                    TextButton(
                        onClick = onRegisterAccount,
                        enabled = registrationEnabled && canOpenBindDialog
                    ) {
                        Text(
                            text = if (registrationEnabled) "注册正式用户" else "注册未开放",
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
            } else {
                null
            }
        )
        SettingsDivider()
        AccountDetailRow(label = "账户类型", value = accountTypeText(account))
    }
}

@Composable
internal fun VipDetailContent(
    account: AccountInfo,
    vipCode: String,
    isActivatingVip: Boolean,
    canActivateVip: Boolean,
    onVipCodeChange: (String) -> Unit,
    onActivate: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountSettingsCard {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.WorkspacePremium,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(27.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("会员状态", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (account.vipActive) "VIP 已开通" else "VIP 未开通",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
                SettingsDivider(startInset = 0.dp, endInset = 0.dp)
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("有效期至", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = formattedAccountTimestamp(account.vipExpiresAt) ?: "暂无有效期",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
                SettingsDivider(startInset = 0.dp, endInset = 0.dp)
                Text("VIP 卡密", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = vipCode,
                    onValueChange = onVipCodeChange,
                    placeholder = { Text("输入 VIP 卡密") },
                    singleLine = true,
                    enabled = account.accountType != "banned" && !isActivatingVip,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Button(
                    onClick = onActivate,
                    enabled = canActivateVip,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    if (isActivatingVip) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (isActivatingVip) "激活中…" else "激活", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            text = "兑换卡密会在当前有效期后继续增加 VIP 时长。",
            modifier = Modifier.padding(horizontal = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

@Composable
internal fun TrafficDetailContent(account: AccountInfo) {
    val traffic = account.traffic ?: AccountTrafficInfo()
    val hasReported = hasReportedTraffic(traffic)
    AccountSettingsCard {
        TrafficDetailRow("今日流量", traffic.today, hasReported)
        SettingsDivider()
        TrafficDetailRow("本月流量", traffic.month, hasReported)
        SettingsDivider()
        TrafficDetailRow("累计流量", traffic.total, hasReported)
        SettingsDivider()
        AccountDetailRow(
            label = "更新时间",
            value = TrafficUsageFormatter.formatTimestamp(traffic.lastReportAt)
        )
    }
}

@Composable
internal fun ClientInfoDetailContent(account: AccountInfo) {
    AccountSettingsCard {
        AccountDetailRow(label = "设备厂商", value = account.brand.displayOrUnknown())
        SettingsDivider()
        AccountDetailRow(label = "设备型号", value = account.model.displayOrUnknown())
        SettingsDivider()
        AccountDetailRow(label = "Android 版本", value = account.androidVersion.displayOrUnknown())
        SettingsDivider()
        AccountDetailRow(
            label = "Android SDK",
            value = account.androidSdk.takeIf { it > 0 }?.toString() ?: "未登记"
        )
        SettingsDivider()
        AccountDetailRow(label = "App 版本", value = account.appVersion.displayOrUnknown())
    }
}

@Composable
internal fun AccountSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsLinkRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = (iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant)
                .copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.55f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.65f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun SettingsSummaryRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SettingsGroupLabel(title: String, first: Boolean = false) {
    Text(
        text = title,
        modifier = Modifier.padding(
            start = 24.dp,
            top = if (first) 2.dp else 20.dp,
            bottom = 4.dp
        ),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SettingsOperationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    loading: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = tint.copy(alpha = if (enabled) 1f else 0.48f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
        } else {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun AccountDetailRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun TrafficDetailRow(
    label: String,
    period: AccountTrafficPeriod,
    hasReported: Boolean
) {
    val upload = period.uploadBytes.coerceAtLeast(0L)
    val download = period.downloadBytes.coerceAtLeast(0L)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            text = if (hasReported) TrafficUsageFormatter.formatBytes(period.displayTotalBytes()) else "暂无统计",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        if (hasReported) {
            Text(
                text = "上行 ${TrafficUsageFormatter.formatBytes(upload)} · 下行 ${TrafficUsageFormatter.formatBytes(download)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun SettingsDivider(startInset: Dp = 18.dp, endInset: Dp = 18.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startInset, end = endInset)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

private fun vipSummary(account: AccountInfo): String {
    val expiry = formattedAccountTimestamp(account.vipExpiresAt)
    return when {
        account.vipActive && expiry != null -> "VIP 已开通 · ${expiry.substringBefore(' ')} 到期"
        account.vipActive -> "VIP 已开通"
        expiry != null -> "VIP 已过期 · ${expiry.substringBefore(' ')}"
        else -> "未开通"
    }
}

private fun trafficSummary(traffic: AccountTrafficInfo?): String {
    if (!hasReportedTraffic(traffic)) return "暂无统计"
    val data = traffic ?: return "暂无统计"
    return "今日 ${TrafficUsageFormatter.formatBytes(data.today.displayTotalBytes())} · 本月 ${TrafficUsageFormatter.formatBytes(data.month.displayTotalBytes())}"
}

private fun hasReportedTraffic(traffic: AccountTrafficInfo?): Boolean =
    traffic?.let {
        !it.lastReportAt.isNullOrBlank() ||
            it.today.hasUsage() || it.month.hasUsage() || it.total.hasUsage()
    } == true

private fun AccountTrafficPeriod.hasUsage(): Boolean =
    uploadBytes > 0L || downloadBytes > 0L || totalBytes > 0L

private fun AccountTrafficPeriod.displayTotalBytes(): Long {
    val upload = uploadBytes.coerceAtLeast(0L)
    val download = downloadBytes.coerceAtLeast(0L)
    val calculatedTotal = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    return totalBytes.coerceAtLeast(calculatedTotal)
}

private fun accountDisplayName(account: AccountInfo): String =
    account.nickname.trim().takeIf { it.isNotBlank() && it != "用户" }
        ?: "用户${account.uid.takeLast(6).uppercase()}"

private fun accountIdentityText(account: AccountInfo): String = when {
    account.deviceBlocked || account.accountType == "banned" -> "被封禁用户"
    account.accountType == "formal" -> "正式用户"
    else -> "游客"
}

private fun accountTypeText(account: AccountInfo): String = when {
    account.deviceBlocked || account.accountType == "banned" -> "封禁账户"
    account.accountType == "formal" -> "正式账户"
    else -> "游客账户"
}

private fun formattedAccountTimestamp(value: String?): String? = value
    ?.replace('T', ' ')
    ?.substringBefore('+')
    ?.take(19)
    ?.takeIf { it.isNotBlank() }

private fun String.displayOrUnknown(): String = trim().ifBlank { "未登记" }
