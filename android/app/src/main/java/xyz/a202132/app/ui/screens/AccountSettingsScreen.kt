package xyz.a202132.app.ui.screens
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import xyz.a202132.app.ui.components.AppAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.a202132.app.data.model.AccountInfo
import xyz.a202132.app.data.model.AccountTrafficInfo
import xyz.a202132.app.data.model.AccountTrafficPeriod
import xyz.a202132.app.ui.components.AppScreenScaffold
import xyz.a202132.app.util.TrafficUsageFormatter
import xyz.a202132.app.viewmodel.MainViewModel

@Composable
fun AccountSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit
) {
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val isUpdatingNickname by viewModel.isUpdatingNickname.collectAsStateWithLifecycle()
    val isSyncingAccount by viewModel.isSyncingAccount.collectAsStateWithLifecycle()
    val isBindingAccount by viewModel.isBindingAccount.collectAsStateWithLifecycle()
    val isSwitchingAccount by viewModel.isSwitchingAccount.collectAsStateWithLifecycle()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsStateWithLifecycle()
    val accountMessage by viewModel.accountMessage.collectAsStateWithLifecycle()
    val backendSettings by viewModel.backendSettings.collectAsStateWithLifecycle()
    val account = accountInfo
    var nickname by remember(accountInfo?.uid, accountInfo?.nickname) {
        mutableStateOf(accountInfo?.nickname.orEmpty())
    }
    var email by remember(accountInfo?.uid, accountInfo?.email) {
        mutableStateOf(accountInfo?.email.orEmpty())
    }
    var registrationCode by remember(accountInfo?.uid) { mutableStateOf("") }
    var password by remember(accountInfo?.uid) { mutableStateOf("") }
    var confirmPassword by remember(accountInfo?.uid) { mutableStateOf("") }
    var switchEmail by remember(accountInfo?.uid) { mutableStateOf("") }
    var switchPassword by remember(accountInfo?.uid) { mutableStateOf("") }
    var deletePassword by remember(accountInfo?.uid) { mutableStateOf("") }
    var showNicknameDialog by remember(accountInfo?.uid) { mutableStateOf(false) }
    var showBindDialog by remember(accountInfo?.uid) { mutableStateOf(false) }
    var showSwitchDialog by remember(accountInfo?.uid) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(accountInfo?.uid) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.clearAccountMessage()
    }

    LaunchedEffect(accountInfo?.uid, accountInfo?.nickname) {
        nickname = accountInfo?.nickname.orEmpty()
        showNicknameDialog = false
    }

    LaunchedEffect(accountInfo?.uid, accountInfo?.email) {
        email = accountInfo?.email.orEmpty()
        if (!accountInfo?.email.isNullOrBlank()) {
            password = ""
            confirmPassword = ""
        }
        switchEmail = ""
        switchPassword = ""
        deletePassword = ""
        registrationCode = ""
        showBindDialog = false
        showNicknameDialog = false
        showSwitchDialog = false
        showDeleteConfirmation = false
    }

    val normalizedNickname = nickname.trim()
    val canSaveNickname = account != null &&
        normalizedNickname.isNotBlank() &&
        normalizedNickname.length <= 24 &&
        normalizedNickname != account.nickname &&
        account.accountType != "banned" &&
        !isUpdatingNickname
    val canSyncAccount = !isSyncingAccount &&
        !isUpdatingNickname &&
        !isBindingAccount &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val canOpenBindDialog = account != null &&
        account.email.isBlank() &&
        account.accountType != "banned" &&
        backendSettings.registrationEnabled &&
        !isSyncingAccount &&
        !isBindingAccount &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val normalizedEmail = email.trim().lowercase()
    val normalizedRegistrationCode = registrationCode.trim().uppercase()
        .replace("-", "").replace(" ", "")
    val canBindAccount = account != null &&
        account.email.isBlank() &&
        account.accountType != "banned" &&
        backendSettings.registrationEnabled &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches() &&
        (normalizedRegistrationCode.isBlank() || normalizedRegistrationCode.matches(Regex("^[A-HJ-NP-Z2-9]{15}$"))) &&
        (!backendSettings.requireAdminRegistrationCode || normalizedRegistrationCode.isNotBlank()) &&
        password.length in 8..128 &&
        password == confirmPassword &&
        !isBindingAccount
    val canSwitchAccount = account != null &&
        account.accountType != "banned" &&
        !account.deviceBlocked &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(switchEmail.trim()).matches() &&
        switchPassword.length in 8..128 &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val canStartDeleteAccount = account != null &&
        account.accountType != "banned" &&
        !isDeletingAccount &&
        !isSwitchingAccount
    AppScreenScaffold(
        title = "账户设置",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { viewModel.syncCurrentAccount() },
                enabled = canSyncAccount
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新账户信息"
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            accountMessage?.let { message ->
                Text(
                    text = message,
                    color = if (
                        message.contains("成功") ||
                            message.contains("已更新") ||
                            message.contains("已同步")
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontSize = 13.sp
                )
            }
            if (account == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "账户信息同步中…",
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                AccountSettingsSection(title = "账户信息") {
                    AccountInfoRowWithIconAction(
                        label = "昵称",
                        value = account.nickname.displayOrUnknown(),
                        actionEnabled = account.accountType != "banned" && !isUpdatingNickname,
                        onAction = {
                            nickname = account.nickname
                            showNicknameDialog = true
                        }
                    )
                    AccountInfoRow(
                        label = "账户身份",
                        value = accountIdentityText(account)
                    )
                    AccountInfoRow(label = "UID", value = account.uid)
                    AccountInfoRowWithAction(
                        label = "邮箱",
                        value = account.email.displayOrUnknown(),
                        actionLabel = if (backendSettings.registrationEnabled) "注册正式用户" else "正式注册未开放",
                        showAction = account.email.isBlank(),
                        actionEnabled = canOpenBindDialog,
                        onAction = {
                            email = ""
                            password = ""
                            confirmPassword = ""
                            showBindDialog = true
                        }
                    )
                    AccountInfoRow(
                        label = "账户类型",
                        value = accountIdentityText(account)
                    )
                }
                AccountSettingsSection(title = "流量信息") {
                    val traffic = account.traffic ?: AccountTrafficInfo()
                    AccountInfoRow(
                        label = "今日流量",
                        value = trafficPeriodText(traffic.today)
                    )
                    AccountInfoRow(
                        label = "本月流量",
                        value = trafficPeriodText(traffic.month)
                    )
                    AccountInfoRow(
                        label = "累计流量",
                        value = trafficPeriodText(traffic.total)
                    )
                    AccountInfoRow(
                        label = "更新时间",
                        value = TrafficUsageFormatter.formatTimestamp(traffic.lastReportAt)
                    )
                    Text(
                        text = "这里显示后台已接收的 VPN 流量，点击右上角刷新可获取最新统计。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AccountSettingsSection(title = "客户端信息") {
                    AccountInfoRow(
                        label = "设备厂商",
                        value = account.brand.displayOrUnknown()
                    )
                    AccountInfoRow(
                        label = "设备型号",
                        value = account.model.displayOrUnknown()
                    )
                    AccountInfoRow(
                        label = "Android 版本",
                        value = account.androidVersion.displayOrUnknown()
                    )
                    AccountInfoRow(
                        label = "Android SDK",
                        value = account.androidSdk.takeIf { it > 0 }?.toString()
                            ?: "未登记"
                    )
                    AccountInfoRow(
                        label = "App 版本",
                        value = account.appVersion.displayOrUnknown()
                    )
                }

                AccountSettingsSection(title = "账户操作") {
                    Text(
                        text = "切换账户或注销账户时，点击对应按钮后再输入所需信息。操作前请先断开 VPN。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            switchEmail = ""
                            switchPassword = ""
                            showSwitchDialog = true
                        },
                        enabled = !isSwitchingAccount && !isDeletingAccount,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (isSwitchingAccount) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(if (isSwitchingAccount) "切换中…" else "切换账户")
                    }

                    Text(
                        text = if (account.deviceBlocked) {
                            "当前设备已被封禁，不能切换或注销账户。"
                        } else if (account.accountType == "banned") {
                            "当前账户已被封禁，不能注销账户。"
                        } else {
                            "注销账户会永久删除账户、邮箱绑定和全部流量记录，操作不可恢复。"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = { showDeleteConfirmation = true },
                        enabled = canStartDeleteAccount,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (isDeletingAccount) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onError,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(
                            when {
                                account.deviceBlocked -> "封禁设备不可注销"
                                account.accountType == "banned" -> "封禁账户不可注销"
                                isDeletingAccount -> "注销中…"
                                else -> "注销账户并清除数据后退出"
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNicknameDialog && account != null) {
        AppAlertDialog(
            onDismissRequest = { if (!isUpdatingNickname) showNicknameDialog = false },
            title = { Text("修改昵称") },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { value ->
                        if (value.length <= 24) nickname = value
                    },
                    label = { Text("昵称") },
                    supportingText = { Text("${nickname.length}/24") },
                    singleLine = true,
                    enabled = !isUpdatingNickname,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.updateNickname(normalizedNickname) },
                    enabled = canSaveNickname
                ) {
                    Text(if (isUpdatingNickname) "保存中…" else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNicknameDialog = false },
                    enabled = !isUpdatingNickname
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showBindDialog && account != null) {
        AppAlertDialog(
            onDismissRequest = { if (!isBindingAccount) showBindDialog = false },
            title = { Text("注册正式用户") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = registrationCode,
                        onValueChange = { if (it.length <= 32) registrationCode = it },
                        label = { Text(if (backendSettings.requireAdminRegistrationCode) "管理员一次性注册码（必填）" else "管理员一次性注册码（可选）") },
                        singleLine = true,
                        enabled = !isBindingAccount,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("邮箱") },
                        singleLine = true,
                        enabled = !isBindingAccount,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { if (it.length <= 128) password = it },
                        label = { Text("密码") },
                        supportingText = { Text("8～128 个字符") },
                        singleLine = true,
                        enabled = !isBindingAccount,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { if (it.length <= 128) confirmPassword = it },
                        label = { Text("确认密码") },
                        singleLine = true,
                        enabled = !isBindingAccount,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canBindAccount,
                    onClick = {
                        viewModel.bindEmailPassword(
                            normalizedEmail,
                            password,
                            normalizedRegistrationCode,
                        )
                    }
                ) {
                    if (isBindingAccount) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (isBindingAccount) "注册中…" else "确认注册")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBindingAccount,
                    onClick = { showBindDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showSwitchDialog && account != null) {
        AppAlertDialog(
            onDismissRequest = { if (!isSwitchingAccount) showSwitchDialog = false },
            title = { Text("切换账户") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入已绑定账户的邮箱和密码。")
                    OutlinedTextField(
                        value = switchEmail,
                        onValueChange = { switchEmail = it },
                        label = { Text("账户邮箱") },
                        singleLine = true,
                        enabled = !isSwitchingAccount,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = switchPassword,
                        onValueChange = { if (it.length <= 128) switchPassword = it },
                        label = { Text("账户密码") },
                        singleLine = true,
                        enabled = !isSwitchingAccount,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSwitchAccount,
                    onClick = {
                        showSwitchDialog = false
                        viewModel.switchAccount(switchEmail.trim(), switchPassword)
                    }
                ) {
                    Text(if (isSwitchingAccount) "切换中…" else "确认切换")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSwitchingAccount,
                    onClick = { showSwitchDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteConfirmation && account != null) {
        AppAlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteConfirmation = false },
            title = { Text("注销账户并清除数据后退出") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("账户数据、流量记录和本地数据将永久删除，注销成功后软件会立即退出。此操作不可恢复。")
                    if (account.email.isNotBlank()) {
                        OutlinedTextField(
                            value = deletePassword,
                            onValueChange = { if (it.length <= 128) deletePassword = it },
                            label = { Text("当前账户密码") },
                            supportingText = { Text("绑定账户注销时必须填写") },
                            singleLine = true,
                            enabled = !isDeletingAccount,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("当前为游客账户，无需输入密码。")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isDeletingAccount &&
                        (account.email.isBlank() || deletePassword.length in 8..128),
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteAccount(deletePassword, onAccountDeleted)
                    }
                ) {
                    Text(
                        if (isDeletingAccount) "注销中…" else "注销账户并清除数据后退出",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDeletingAccount,
                    onClick = { showDeleteConfirmation = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun AccountSettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.66f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccountInfoRowWithAction(
    label: String,
    value: String,
    actionLabel: String,
    showAction: Boolean,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Row(
            modifier = Modifier.weight(0.66f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (showAction) {
                TextButton(
                    onClick = onAction,
                    enabled = actionEnabled
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun AccountInfoRowWithIconAction(
    label: String,
    value: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.34f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Row(
            modifier = Modifier.weight(0.66f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(
                onClick = onAction,
                enabled = actionEnabled
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "修改昵称"
                )
            }
        }
    }
}

private fun accountIdentityText(accountInfo: AccountInfo): String {
    return when {
        accountInfo.deviceBlocked || accountInfo.accountType == "banned" -> "被封禁用户"
        accountInfo.accountType == "formal" -> "正式用户"
        else -> "游客"
    }
}

private fun trafficPeriodText(period: AccountTrafficPeriod): String {
    val upload = period.uploadBytes.coerceAtLeast(0L)
    val download = period.downloadBytes.coerceAtLeast(0L)
    val calculatedTotal = if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
    val total = period.totalBytes.coerceAtLeast(calculatedTotal)
    return "${TrafficUsageFormatter.formatBytes(total)}\n" +
        "上行 ${TrafficUsageFormatter.formatBytes(upload)} · " +
        "下行 ${TrafficUsageFormatter.formatBytes(download)}"
}

private fun String.displayOrUnknown(): String = trim().ifBlank { "未登记" }
