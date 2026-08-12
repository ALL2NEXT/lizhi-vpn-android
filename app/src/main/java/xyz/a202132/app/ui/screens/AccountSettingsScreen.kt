package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
    onBack: () -> Unit
) {
    val accountInfo by viewModel.accountInfo.collectAsStateWithLifecycle()
    val isUpdatingNickname by viewModel.isUpdatingNickname.collectAsStateWithLifecycle()
    val isBindingAccount by viewModel.isBindingAccount.collectAsStateWithLifecycle()
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

    LaunchedEffect(Unit) {
        viewModel.clearAccountMessage()
    }

    LaunchedEffect(accountInfo?.uid, accountInfo?.nickname) {
        nickname = accountInfo?.nickname.orEmpty()
    }

    LaunchedEffect(accountInfo?.uid, accountInfo?.email) {
        email = accountInfo?.email.orEmpty()
        if (!accountInfo?.email.isNullOrBlank()) {
            password = ""
            confirmPassword = ""
        }
    }

    val normalizedNickname = nickname.trim()
    val canSaveNickname = account != null &&
        normalizedNickname.isNotBlank() &&
        normalizedNickname.length <= 24 &&
        normalizedNickname != account.nickname &&
        account.level > 0 &&
        !isUpdatingNickname
    val normalizedEmail = email.trim().lowercase()
    val canBindAccount = account != null &&
        account.email.isBlank() &&
        account.level > 0 &&
        android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches() &&
        password.length in 8..128 &&
        password == confirmPassword &&
        !isBindingAccount

    AppScreenScaffold(
        title = "账户设置",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                AccountSettingsSection(title = "账户资料") {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { value ->
                            if (value.length <= 24) nickname = value
                        },
                        label = { Text("昵称") },
                        supportingText = { Text("${nickname.length}/24") },
                        singleLine = true,
                        enabled = account.level > 0 && !isUpdatingNickname,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.updateNickname(normalizedNickname) },
                        enabled = canSaveNickname,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        if (isUpdatingNickname) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        }
                        Text(if (isUpdatingNickname) "保存中…" else "保存昵称")
                    }
                }

                if (account.email.isBlank()) {
                    AccountSettingsSection(title = "绑定账户") {
                        Text(
                            text = "绑定邮箱可获得经验，经验达到后台设置的升级门槛后自动升级。Lv0 账户不能获取经验。密码只会在服务端加密保存。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Button(
                            onClick = {
                                viewModel.bindEmailPassword(normalizedEmail, password)
                            },
                            enabled = canBindAccount,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            if (isBindingAccount) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(if (isBindingAccount) "绑定中…" else "绑定账户")
                        }
                    }
                }

                accountMessage?.let { message ->
                    Text(
                        text = message,
                        color = if (
                            message.contains("成功") || message.contains("已更新")
                        ) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontSize = 13.sp
                    )
                }

                AccountSettingsSection(title = "账户信息") {
                    AccountInfoRow(label = "UID", value = account.uid)
                    AccountInfoRow(
                        label = "绑定邮箱",
                        value = account.email.displayOrUnknown()
                    )
                    AccountInfoRow(
                        label = "账户等级",
                        value = accountLevelText(account)
                    )
                    AccountInfoRow(
                        label = "当前经验",
                        value = accountExperienceText(account, backendSettings.experienceThresholds)
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
            }
        }
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

private fun accountLevelText(accountInfo: AccountInfo): String {
    return if (accountInfo.level == 0) "Lv0 · 已封禁" else "Lv${accountInfo.level.coerceIn(1, 6)}"
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

private fun String.displayOrUnknown(): String = trim().ifBlank { "未登记" }
