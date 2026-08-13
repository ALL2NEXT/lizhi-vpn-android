package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import xyz.a202132.app.ui.components.AppScreenScaffold
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
        account.level > 0 &&
        !isUpdatingNickname
    val canSyncAccount = !isSyncingAccount &&
        !isUpdatingNickname &&
        !isBindingAccount &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val canOpenBindDialog = account != null &&
        account.email.isBlank() &&
        account.level > 0 &&
        !isSyncingAccount &&
        !isBindingAccount &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val normalizedEmail = email.trim().lowercase()
    val canBindAccount = account != null &&
        account.email.isBlank() &&
        account.level > 0 &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches() &&
        password.length in 8..128 &&
        password == confirmPassword &&
        !isBindingAccount
    val canSwitchAccount = account != null &&
        account.level > 0 &&
        !account.deviceBlocked &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(switchEmail.trim()).matches() &&
        switchPassword.length in 8..128 &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val canStartDeleteAccount = account != null &&
        account.level > 0 &&
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
                        actionEnabled = account.level > 0 && !isUpdatingNickname,
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
                        actionLabel = "绑定邮箱",
                        showAction = account.email.isBlank(),
                        actionEnabled = canOpenBindDialog,
                        onAction = {
                            email = ""
                            password = ""
                            confirmPassword = ""
                            showBindDialog = true
                        }
                    )
                    AccountLevelRow(
                        accountInfo = account,
                        thresholds = backendSettings.experienceThresholds
                    )
                    AccountInfoRow(
                        label = "当前经验",
                        value = accountExperienceText(account, backendSettings.experienceThresholds)
                    )
                }

                AccountSettingsSection(title = "当前权益") {
                    AccountInfoRow(
                        label = "线路权益",
                        value = accountEntitlementText(account)
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
                        } else if (account.level == 0) {
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
                                account.level == 0 -> "封禁账户不可注销"
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
        AlertDialog(
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
        AlertDialog(
            onDismissRequest = { if (!isBindingAccount) showBindDialog = false },
            title = { Text("绑定邮箱") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("请输入要绑定的邮箱和密码。")
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
                        viewModel.bindEmailPassword(normalizedEmail, password)
                    }
                ) {
                    if (isBindingAccount) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (isBindingAccount) "绑定中…" else "确认绑定")
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
        AlertDialog(
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
        AlertDialog(
            onDismissRequest = { if (!isDeletingAccount) showDeleteConfirmation = false },
            title = { Text("注销账户并清除数据后退出") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("账户数据、流量记录和本地数据将永久删除，注销成功后软件会立即退出。下次进入软件需要重新同意用户协议。此操作不可恢复。")
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

@Composable
private fun AccountLevelRow(
    accountInfo: AccountInfo,
    thresholds: List<Long>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "账户等级",
            modifier = Modifier.weight(0.34f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
        Row(
            modifier = Modifier.weight(0.66f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = accountLevelText(accountInfo),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )
            LinearProgressIndicator(
                progress = accountExperienceProgress(accountInfo, thresholds),
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp),
                color = if (accountInfo.level == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

private fun accountLevelText(accountInfo: AccountInfo): String {
    return when {
        accountInfo.deviceBlocked -> "Lv0 · 设备已封禁"
        accountInfo.level == 0 -> "Lv0 · 账户已封禁"
        else -> "Lv${accountInfo.level.coerceIn(1, 6)}"
    }
}

private fun accountIdentityText(accountInfo: AccountInfo): String {
    return when {
        accountInfo.deviceBlocked -> "受限设备"
        accountInfo.level == 0 -> "受限账户"
        accountInfo.email.isBlank() -> "游客"
        else -> "正式用户"
    }
}

private fun accountEntitlementText(accountInfo: AccountInfo): String {
    return "Lv${accountInfo.level.coerceIn(0, 6)}尊享线路"
}

private fun accountExperienceText(
    accountInfo: AccountInfo,
    thresholds: List<Long>
): String {
    val experience = accountInfo.experience.coerceAtLeast(0L)
    return when {
        accountInfo.level == 0 -> "$experience · Lv0 不可获取经验"
        accountInfo.level >= 6 -> "$experience · 已达最高等级"
        else -> {
            val required = thresholds.getOrNull(accountInfo.level - 1)
            if (required == null) "$experience" else "$experience / $required"
        }
    }
}

private fun accountExperienceProgress(
    accountInfo: AccountInfo,
    thresholds: List<Long>
): Float {
    if (accountInfo.level >= 6) return 1f
    if (accountInfo.level <= 0) return 0f
    val required = thresholds.getOrNull(accountInfo.level - 1) ?: return 0f
    if (required <= 0L) return 0f
    return (accountInfo.experience.toFloat() / required.toFloat()).coerceIn(0f, 1f)
}

private fun String.displayOrUnknown(): String = trim().ifBlank { "未登记" }
