package io.github.HanJason233.chimera.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import io.github.HanJason233.chimera.ui.components.AppAlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.HanJason233.chimera.ui.components.AppScreenScaffold
import io.github.HanJason233.chimera.viewmodel.MainViewModel

enum class AccountSettingsPage(val routeKey: String, val title: String) {
    OVERVIEW("overview", "账户设置"),
    ACCOUNT_INFO("account", "账户信息"),
    VIP("vip", "VIP 会员"),
    TRAFFIC("traffic", "流量信息"),
    CLIENT_INFO("client", "客户端信息")
}

@Composable
fun AccountSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onAccountDeleted: () -> Unit,
    page: AccountSettingsPage = AccountSettingsPage.OVERVIEW,
    onOpenPage: (AccountSettingsPage) -> Unit = {}
) {
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val isUpdatingNickname by viewModel.isUpdatingNickname.collectAsStateWithLifecycle()
    val isSyncingAccount by viewModel.isSyncingAccount.collectAsStateWithLifecycle()
    val isActivatingVip by viewModel.isActivatingVip.collectAsStateWithLifecycle()
    val isBindingAccount by viewModel.isBindingAccount.collectAsStateWithLifecycle()
    val isSwitchingAccount by viewModel.isSwitchingAccount.collectAsStateWithLifecycle()
    val isDeletingAccount by viewModel.isDeletingAccount.collectAsStateWithLifecycle()
    val accountMessage by viewModel.accountMessage.collectAsStateWithLifecycle()
    val backendSettings by viewModel.backendSettings.collectAsStateWithLifecycle()
    val offlineModeEnabled by viewModel.offlineModeEnabled.collectAsStateWithLifecycle()
    val account = accountInfo
    var nickname by remember(accountInfo?.uid, accountInfo?.nickname) {
        mutableStateOf(accountInfo?.nickname.orEmpty())
    }
    var email by remember(accountInfo?.uid, accountInfo?.email) {
        mutableStateOf(accountInfo?.email.orEmpty())
    }
    var registrationCode by remember(accountInfo?.uid) { mutableStateOf("") }
    var vipCode by remember(accountInfo?.uid) { mutableStateOf("") }
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

    LaunchedEffect(accountInfo?.uid, accountInfo?.vipExpiresAt) {
        vipCode = ""
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
        !isDeletingAccount &&
        !isActivatingVip
    val canOpenBindDialog = account != null &&
        account.email.isBlank() &&
        account.accountType != "banned" &&
        backendSettings.registrationEnabled &&
        !isSyncingAccount &&
        !isActivatingVip &&
        !isBindingAccount &&
        !isSwitchingAccount &&
        !isDeletingAccount
    val normalizedEmail = email.trim().lowercase()
    val normalizedRegistrationCode = registrationCode.trim().uppercase()
        .replace("-", "").replace(" ", "")
    val normalizedVipCode = vipCode.trim().uppercase().replace("-", "").replace(" ", "")
    val canActivateVip = account != null &&
        account.accountType != "banned" &&
        normalizedVipCode.matches(Regex("^VIP[A-HJ-NP-Z2-9]{12}$")) &&
        !isActivatingVip &&
        !isSyncingAccount &&
        !isUpdatingNickname &&
        !isBindingAccount &&
        !isSwitchingAccount &&
        !isDeletingAccount
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
        title = page.title,
        onBack = onBack,
        showBackNavigation = page != AccountSettingsPage.OVERVIEW,
        containerColor = accountSettingsBackground,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        actions = {
            if (!offlineModeEnabled &&
                (page == AccountSettingsPage.OVERVIEW || page == AccountSettingsPage.TRAFFIC)
            ) {
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
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                AccountSettingsCard {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (offlineModeEnabled) {
                                "已开启离线模式，账户和后台同步功能已停用。"
                            } else {
                                "账户信息同步中…"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (offlineModeEnabled) {
                            TextButton(onClick = viewModel::exitOfflineMode) {
                                Text("切换到在线登录")
                            }
                        }
                    }
                }
            } else {
                when (page) {
                    AccountSettingsPage.OVERVIEW -> AccountSettingsOverviewContent(
                        account = account,
                        isSwitchingAccount = isSwitchingAccount,
                        isDeletingAccount = isDeletingAccount,
                        canStartDeleteAccount = canStartDeleteAccount,
                        onOpenAccountInfo = { onOpenPage(AccountSettingsPage.ACCOUNT_INFO) },
                        onOpenVip = { onOpenPage(AccountSettingsPage.VIP) },
                        onOpenTraffic = { onOpenPage(AccountSettingsPage.TRAFFIC) },
                        onOpenClientInfo = { onOpenPage(AccountSettingsPage.CLIENT_INFO) },
                        onSwitchAccount = {
                            switchEmail = ""
                            switchPassword = ""
                            showSwitchDialog = true
                        },
                        onDeleteAccount = { showDeleteConfirmation = true }
                    )
                    AccountSettingsPage.ACCOUNT_INFO -> AccountInfoDetailContent(
                        account = account,
                        registrationEnabled = backendSettings.registrationEnabled,
                        canOpenBindDialog = canOpenBindDialog,
                        isUpdatingNickname = isUpdatingNickname,
                        onEditNickname = {
                            nickname = account.nickname
                            showNicknameDialog = true
                        },
                        onRegisterAccount = {
                            email = ""
                            password = ""
                            confirmPassword = ""
                            showBindDialog = true
                        }
                    )
                    AccountSettingsPage.VIP -> VipDetailContent(
                        account = account,
                        vipCode = vipCode,
                        isActivatingVip = isActivatingVip,
                        canActivateVip = canActivateVip,
                        onVipCodeChange = { value ->
                            vipCode = value.uppercase().filterNot { it.isWhitespace() || it == '-' }.take(15)
                        },
                        onActivate = { viewModel.activateVipCard(normalizedVipCode) }
                    )
                    AccountSettingsPage.TRAFFIC -> TrafficDetailContent(account = account)
                    AccountSettingsPage.CLIENT_INFO -> ClientInfoDetailContent(account = account)
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
                    OutlinedTextField(
                        value = registrationCode,
                        onValueChange = { if (it.length <= 32) registrationCode = it },
                        label = { Text(if (backendSettings.requireAdminRegistrationCode) "注册码（必填）" else "注册码（可选）") },
                        singleLine = true,
                        enabled = !isBindingAccount,
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
