package xyz.a202132.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
    val account = accountInfo
    var nickname by remember(accountInfo?.uid, accountInfo?.nickname) {
        mutableStateOf(accountInfo?.nickname.orEmpty())
    }

    LaunchedEffect(accountInfo?.uid, accountInfo?.nickname) {
        nickname = accountInfo?.nickname.orEmpty()
    }

    val normalizedNickname = nickname.trim()
    val canSaveNickname = account != null &&
        normalizedNickname.isNotBlank() &&
        normalizedNickname.length <= 24 &&
        normalizedNickname != account.nickname &&
        !isUpdatingNickname

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
                        enabled = !isUpdatingNickname,
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

                AccountSettingsSection(title = "账户信息") {
                    AccountInfoRow(label = "UID", value = account.uid)
                    AccountInfoRow(
                        label = "账户等级",
                        value = accountLevelText(account)
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

private fun String.displayOrUnknown(): String = trim().ifBlank { "未登记" }
